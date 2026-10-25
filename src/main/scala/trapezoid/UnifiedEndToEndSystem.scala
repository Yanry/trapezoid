package trapezoid

import spinal.core._
import spinal.lib._

/** Several independently banked cache clusters behind one client/refill
  * boundary. Address bits above the local-bank index select the cluster.
  */
class ClusteredReadCache(clusterCfg: CacheClusterConfig) extends Component {
  private val cacheCfg = clusterCfg.cache
  private val localCfg = clusterCfg.localCache
  private val clusterCount = clusterCfg.clusterCount
  private val localBankWidth = log2Up(clusterCfg.banksPerCluster)

  private def localAddressOf(address: UInt): UInt = if (clusterCount == 1) {
    address.resized
  } else {
    (address(cacheCfg.addressWidth - 1 downto
      localBankWidth + clusterCfg.clusterIndexWidth) ##
      address(localBankWidth - 1 downto 0)).asUInt
  }

  private def globalAddressOf(address: UInt, cluster: Int): UInt =
    if (clusterCount == 1) {
      address.resized
    } else {
      (address(localCfg.addressWidth - 1 downto localBankWidth) ##
        U(cluster, clusterCfg.clusterIndexWidth bits) ##
        address(localBankWidth - 1 downto 0)).asUInt
    }

  val io = new Bundle {
    val clear = in Bool()
    val requests = Vec(slave(Stream(CacheReadRequest(cacheCfg))), cacheCfg.clientCount)
    val responses = Vec(master(Stream(CacheReadResponse(cacheCfg))), cacheCfg.clientCount)
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val bankBusy = out Bits(cacheCfg.bankCount bits)
    val clientOutstanding = out Bits(cacheCfg.clientCount bits)
    val hitCount = out UInt(32 bits)
    val missCount = out UInt(32 bits)
    val refillCount = out UInt(32 bits)
  }

  private def clusterOf(address: UInt): UInt = if (clusterCount == 1) {
    U(0, clusterCfg.clusterIndexWidth bits)
  } else {
    address(localBankWidth + clusterCfg.clusterIndexWidth - 1 downto
      localBankWidth)
  }

  val clusters = Array.fill(clusterCount)(new BankedReadCache(localCfg))
  for (cluster <- 0 until clusterCount) {
    clusters(cluster).io.clear := io.clear
  }

  for (client <- 0 until cacheCfg.clientCount) {
    io.requests(client).ready := False
    for (cluster <- 0 until clusterCount) {
      val selected = clusterOf(io.requests(client).payload.address) === cluster
      clusters(cluster).io.requests(client).valid :=
        io.requests(client).valid && selected
      clusters(cluster).io.requests(client).payload.address :=
        localAddressOf(io.requests(client).payload.address)
      clusters(cluster).io.requests(client).payload.requestId :=
        io.requests(client).payload.requestId
      when(selected) {
        io.requests(client).ready := clusters(cluster).io.requests(client).ready
      }
    }

    val responseChoices = Bits(clusterCount bits)
    var earlierResponse: Bool = False
    for (cluster <- 0 until clusterCount) {
      responseChoices(cluster) := clusters(cluster).io.responses(client).valid &&
        !earlierResponse
      earlierResponse = earlierResponse ||
        clusters(cluster).io.responses(client).valid
    }
    io.responses(client).valid := responseChoices.orR
    io.responses(client).payload := CacheReadResponse(cacheCfg).getZero
    for (cluster <- 0 until clusterCount) {
      clusters(cluster).io.responses(client).ready :=
        io.responses(client).ready && responseChoices(cluster)
      when(responseChoices(cluster)) {
        io.responses(client).payload.requestId :=
          clusters(cluster).io.responses(client).payload.requestId
        io.responses(client).payload.data :=
          clusters(cluster).io.responses(client).payload.data
        io.responses(client).payload.wasHit :=
          clusters(cluster).io.responses(client).payload.wasHit
        io.responses(client).payload.error :=
          clusters(cluster).io.responses(client).payload.error
      }
    }
  }

  val refillChoices = Bits(clusterCount bits)
  var earlierRefill: Bool = False
  for (cluster <- 0 until clusterCount) {
    refillChoices(cluster) := clusters(cluster).io.refillRequest.valid &&
      !earlierRefill
    earlierRefill = earlierRefill || clusters(cluster).io.refillRequest.valid
  }
  io.refillRequest.valid := refillChoices.orR
  io.refillRequest.payload := CacheRefillRequest(cacheCfg).getZero
  for (cluster <- 0 until clusterCount) {
    clusters(cluster).io.refillRequest.ready :=
      io.refillRequest.ready && refillChoices(cluster)
    when(refillChoices(cluster)) {
      io.refillRequest.payload.address :=
          globalAddressOf(clusters(cluster).io.refillRequest.payload.address,
            cluster)
      if (clusterCount == 1) {
        io.refillRequest.payload.bank :=
          clusters(cluster).io.refillRequest.payload.bank.resized
      } else {
        io.refillRequest.payload.bank :=
          (U(cluster, clusterCfg.clusterIndexWidth bits) ##
            clusters(cluster).io.refillRequest.payload.bank).asUInt
      }
    }
  }

  val responseCluster = if (clusterCount == 1) {
    U(0, clusterCfg.clusterIndexWidth bits)
  } else {
    io.refillResponse.payload.bank(cacheCfg.bankIndexWidth - 1 downto
      localBankWidth)
  }
  io.refillResponse.ready := False
  for (cluster <- 0 until clusterCount) {
    val selected = responseCluster === cluster
    clusters(cluster).io.refillResponse.valid :=
      io.refillResponse.valid && selected
    clusters(cluster).io.refillResponse.payload.bank :=
      io.refillResponse.payload.bank(localBankWidth - 1 downto 0).resized
    clusters(cluster).io.refillResponse.payload.address :=
      localAddressOf(io.refillResponse.payload.address)
    clusters(cluster).io.refillResponse.payload.data :=
      io.refillResponse.payload.data
    clusters(cluster).io.refillResponse.payload.error :=
      io.refillResponse.payload.error
    when(selected) {
      io.refillResponse.ready := clusters(cluster).io.refillResponse.ready
    }
  }

  for (cluster <- 0 until clusterCount; bank <- 0 until clusterCfg.banksPerCluster) {
    io.bankBusy(cluster * clusterCfg.banksPerCluster + bank) :=
      clusters(cluster).io.bankBusy(bank)
  }
  for (client <- 0 until cacheCfg.clientCount) {
    io.clientOutstanding(client) := clusters
      .map(_.io.clientOutstanding(client)).reduce(_ || _)
  }
  io.hitCount := clusters.map(_.io.hitCount).reduce(_ + _).resize(32)
  io.missCount := clusters.map(_.io.missCount).reduce(_ + _).resize(32)
  io.refillCount := clusters.map(_.io.refillCount).reduce(_ + _).resize(32)
}

object CsrGustavsonState extends SpinalEnum {
  val Idle, RequestRowStart, WaitRowStart, RequestRowEnd, WaitRowEnd,
      RequestAEntry, WaitAEntry, RequestBRow, WaitBRow, Emit = newElement()
}

/** Walks CSR A rows and B-row descriptors through the shared cache. */
class CsrGustavsonDescriptorFrontend(systemCfg: EndToEndSystemConfig)
    extends Component {
  private val cfg = systemCfg.core
  private val matrixCfg = systemCfg.matrix
  private val gustCfg = systemCfg.gustavson
  private val cacheCfg = systemCfg.cache
  private val pointerWidth = cacheCfg.addressWidth
  private val aEntryWidth = 1 + cfg.dataWidth + cfg.coordinateWidth
  private val bDescriptorWidth = cacheCfg.addressWidth + gustCfg.lineCountWidth
  require(cacheCfg.lineWidth >= pointerWidth)
  require(cacheCfg.lineWidth >= aEntryWidth)
  require(cacheCfg.lineWidth >= bDescriptorWidth)

  val io = new Bundle {
    val clear = in Bool()
    val descriptor = slave(Stream(UnifiedMatrixDescriptor(systemCfg)))
    val cacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val cacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val command = master(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
    val issuedTiles = out UInt(32 bits)
  }

  val state = RegInit(CsrGustavsonState.Idle)
  val descriptorReg = Reg(UnifiedMatrixDescriptor(systemCfg))
  val rowIndex = Reg(UInt(matrixCfg.rowCountWidth bits)) init 0
  val rowStart = Reg(UInt(pointerWidth bits)) init 0
  val rowEnd = Reg(UInt(pointerWidth bits)) init 0
  val entryIndex = Reg(UInt(pointerWidth bits)) init 0
  val rowEntryOrdinal = Reg(UInt(cfg.aRegisterIndexWidth bits)) init 0
  val aK = Reg(UInt(cfg.coordinateWidth bits)) init 0
  val aValue = Reg(SInt(cfg.dataWidth bits)) init 0
  val bBaseAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val bLineCount = Reg(UInt(gustCfg.lineCountWidth bits)) init 0
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)
  val issuedTiles = Reg(UInt(32 bits)) init 0

  io.busy := state =/= CsrGustavsonState.Idle
  io.done := doneReg
  io.error := errorReg
  io.issuedTiles := issuedTiles
  io.descriptor.ready := !io.clear && state === CsrGustavsonState.Idle

  val supportedMode = io.descriptor.payload.mode === TrapezoidDataflow.TrGt ||
    io.descriptor.payload.mode === TrapezoidDataflow.TrGs
  when(io.descriptor.fire) {
    descriptorReg := io.descriptor.payload
    rowIndex := 0
    issuedTiles := 0
    doneReg := False
    errorReg := False
    when(!supportedMode || io.descriptor.payload.rowCount === 0 ||
        io.descriptor.payload.rowCount > matrixCfg.maxMatrixRows) {
      doneReg := True
      errorReg := True
    } otherwise {
      state := CsrGustavsonState.RequestRowStart
    }
  }

  io.cacheRequest.valid := False
  io.cacheRequest.payload := CacheReadRequest(cacheCfg).getZero
  switch(state) {
    is(CsrGustavsonState.RequestRowStart) {
      io.cacheRequest.valid := !io.clear
      io.cacheRequest.payload.address :=
        (descriptorReg.csrRowPointerBase + rowIndex.resized).resized
      io.cacheRequest.payload.requestId := 0
    }
    is(CsrGustavsonState.RequestRowEnd) {
      io.cacheRequest.valid := !io.clear
      io.cacheRequest.payload.address :=
        (descriptorReg.csrRowPointerBase + rowIndex.resized + 1).resized
      io.cacheRequest.payload.requestId := 1
    }
    is(CsrGustavsonState.RequestAEntry) {
      io.cacheRequest.valid := !io.clear
      io.cacheRequest.payload.address :=
        (descriptorReg.csrAEntryBase + entryIndex).resized
      io.cacheRequest.payload.requestId := 2
    }
    is(CsrGustavsonState.RequestBRow) {
      io.cacheRequest.valid := !io.clear
      io.cacheRequest.payload.address :=
        (descriptorReg.csrBRowDescriptorBase + aK.resized).resized
      io.cacheRequest.payload.requestId := 3
    }
  }
  when(io.cacheRequest.fire) {
    switch(state) {
      is(CsrGustavsonState.RequestRowStart) {
        state := CsrGustavsonState.WaitRowStart
      }
      is(CsrGustavsonState.RequestRowEnd) {
        state := CsrGustavsonState.WaitRowEnd
      }
      is(CsrGustavsonState.RequestAEntry) {
        state := CsrGustavsonState.WaitAEntry
      }
      is(CsrGustavsonState.RequestBRow) {
        state := CsrGustavsonState.WaitBRow
      }
    }
  }

  val waitingForResponse = state === CsrGustavsonState.WaitRowStart ||
    state === CsrGustavsonState.WaitRowEnd ||
    state === CsrGustavsonState.WaitAEntry ||
    state === CsrGustavsonState.WaitBRow
  io.cacheResponse.ready := !io.clear && waitingForResponse

  def finishOrAdvanceRow(): Unit = {
    when(rowIndex.resize(matrixCfg.rowCountWidth + 1) + 1 >=
        descriptorReg.rowCount.resize(matrixCfg.rowCountWidth + 1)) {
      doneReg := True
      state := CsrGustavsonState.Idle
    } otherwise {
      rowIndex := rowIndex + 1
      state := CsrGustavsonState.RequestRowStart
    }
  }

  when(io.cacheResponse.fire) {
    when(io.cacheResponse.payload.error) {
      errorReg := True
      doneReg := True
      state := CsrGustavsonState.Idle
    } otherwise {
      switch(state) {
        is(CsrGustavsonState.WaitRowStart) {
          rowStart := io.cacheResponse.payload.data(pointerWidth - 1 downto 0).asUInt
          state := CsrGustavsonState.RequestRowEnd
        }
        is(CsrGustavsonState.WaitRowEnd) {
          val nextRowEnd = io.cacheResponse.payload.data(
            pointerWidth - 1 downto 0).asUInt
          rowEnd := nextRowEnd
          when(nextRowEnd < rowStart) {
            errorReg := True
            doneReg := True
            state := CsrGustavsonState.Idle
          } elsewhen(nextRowEnd === rowStart) {
            finishOrAdvanceRow()
          } otherwise {
            entryIndex := rowStart
            rowEntryOrdinal := 0
            state := CsrGustavsonState.RequestAEntry
          }
        }
        is(CsrGustavsonState.WaitAEntry) {
          val valid = io.cacheResponse.payload.data(0)
          aValue := io.cacheResponse.payload.data(
            cfg.dataWidth downto 1).asSInt
          aK := io.cacheResponse.payload.data(
            aEntryWidth - 1 downto 1 + cfg.dataWidth).asUInt
          when(!valid) {
            errorReg := True
            doneReg := True
            state := CsrGustavsonState.Idle
          } otherwise {
            state := CsrGustavsonState.RequestBRow
          }
        }
        is(CsrGustavsonState.WaitBRow) {
          bBaseAddress := io.cacheResponse.payload.data(
            cacheCfg.addressWidth - 1 downto 0).asUInt
          bLineCount := io.cacheResponse.payload.data(
            bDescriptorWidth - 1 downto cacheCfg.addressWidth).asUInt
          when(io.cacheResponse.payload.data(
              bDescriptorWidth - 1 downto cacheCfg.addressWidth).asUInt === 0) {
            errorReg := True
            doneReg := True
            state := CsrGustavsonState.Idle
          } otherwise {
            state := CsrGustavsonState.Emit
          }
        }
      }
    }
  }

  io.command.valid := !io.clear && state === CsrGustavsonState.Emit
  io.command.payload.mode := descriptorReg.mode
  io.command.payload.endOfTile := entryIndex + 1 >= rowEnd
  val packedSubrow = rowEntryOrdinal % cfg.localBanks
  val packedOffset = rowEntryOrdinal / cfg.localBanks
  io.command.payload.aIndex :=
    (packedSubrow * cfg.macLanesPerSubrow + packedOffset).resized
  io.command.payload.a.valid := True
  io.command.payload.a.row := rowIndex.resized
  io.command.payload.a.k := aK
  io.command.payload.a.value := aValue
  io.command.payload.bBaseAddress := bBaseAddress
  io.command.payload.lineCount := bLineCount
  when(io.command.fire) {
    when(entryIndex + 1 >= rowEnd) {
      issuedTiles := issuedTiles + 1
      finishOrAdvanceRow()
    } otherwise {
      entryIndex := entryIndex + 1
      rowEntryOrdinal := rowEntryOrdinal + 1
      state := CsrGustavsonState.RequestAEntry
    }
  }

  when(io.clear) {
    state := CsrGustavsonState.Idle
    doneReg := False
    errorReg := False
    issuedTiles := 0
  }
}

object CachedTrGtState extends SpinalEnum {
  val Collect, LoadA, Start, FindA, RequestLine, WaitLine, EmitLine,
      Finish, WaitDone = newElement()
}

/** Aggregates one CSR A row before starting the four temporal TrGT subrows.
  *
  * The CSR walker emits one B-row descriptor per A entry.  TrGT needs all A
  * entries resident before its four FIFO streams can run, so this adapter
  * buffers the row descriptors, loads the stationary A registers, and then
  * streams each packed B line into the FIFO selected by the A index.
  */
class CachedTrGtSubrowAdapter(
    systemCfg: EndToEndSystemConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg)
) extends Component {
  private val cfg = systemCfg.core
  private val gustCfg = systemCfg.gustavson
  private val cacheCfg = systemCfg.cache
  private val scanIndexWidth = scala.math.max(1, log2Up(cfg.macLanes + 1))
  private val addressIndexWidth = scala.math.max(1, log2Up(cfg.macLanes))
  private val elementIndexWidth = scala.math.max(1, log2Up(gustCfg.elementsPerLine))
  private val subrowIndexWidth = scala.math.max(1, log2Up(cfg.localBanks))

  val io = new Bundle {
    val clear = in Bool()
    val rowCommand = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val cacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val cacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val overflow = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val unsupportedMode = out Bool()
    val phase = out UInt(4 bits)
    val schedulerBusy = out Bool()
    val fifoOccupancy = out UInt(16 bits)
  }

  val scheduler = new TrGtSubrowScheduler(gustCfg.tile,
    peFactory = peFactory)
  scheduler.io.clear := io.clear
  io.result <> scheduler.io.result

  val state = RegInit(CachedTrGtState.Collect)
  val aValid = Vec((0 until cfg.macLanes).map(_ => RegInit(False)))
  val aStorage = Vec((0 until cfg.macLanes).map(_ => Reg(GustavsonA(cfg))))
  val bBaseStorage = Vec((0 until cfg.macLanes).map(_ =>
    Reg(UInt(cacheCfg.addressWidth bits)) init 0))
  val lineCountStorage = Vec((0 until cfg.macLanes).map(_ =>
    Reg(UInt(gustCfg.lineCountWidth bits)) init 0))
  val currentIndex = Reg(UInt(scanIndexWidth bits)) init 0
  val requestIndex = Reg(UInt(gustCfg.lineCountWidth bits)) init 0
  val lineData = Reg(Bits(cacheCfg.lineWidth bits)) init 0
  val lineValid = RegInit(False)
  val elementCursor = Reg(UInt(elementIndexWidth bits)) init 0
  val requestOutstanding = RegInit(False)
  val rowCollectOpen = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val memoryErrorReg = RegInit(False)
  val addressErrorReg = RegInit(False)
  val unsupportedModeReg = RegInit(False)
  val schedulerStarted = RegInit(False)

  io.busy := state =/= CachedTrGtState.Collect
  io.done := doneReg
  io.overflow := overflowReg
  io.memoryError := memoryErrorReg
  io.addressError := addressErrorReg
  io.unsupportedMode := unsupportedModeReg
  io.phase := state.asBits.asUInt
  io.schedulerBusy := scheduler.io.busy
  io.fifoOccupancy := scheduler.io.fifoOccupancy
    .map(_.resize(16)).reduce(_ + _).resize(16)

  val currentAValid = Bool()
  val currentA = GustavsonA(cfg)
  val currentBBase = UInt(cacheCfg.addressWidth bits)
  val currentLineCount = UInt(gustCfg.lineCountWidth bits)
  currentAValid := False
  currentA := GustavsonA(cfg).getZero
  currentBBase := 0
  currentLineCount := 0
  for (index <- 0 until cfg.macLanes) {
    when(currentIndex === index) {
      currentAValid := aValid(index)
      currentA := aStorage(index)
      currentBBase := bBaseStorage(index)
      currentLineCount := lineCountStorage(index)
    }
  }

  val lineElements = Vec(TrGsB(cfg), gustCfg.elementsPerLine)
  for (element <- 0 until gustCfg.elementsPerLine) {
    val offset = element * gustCfg.elementWidth
    lineElements(element).valid := lineData(offset)
    lineElements(element).k := currentA.k
    lineElements(element).value :=
      lineData(offset + cfg.dataWidth downto offset + 1).asSInt
    lineElements(element).column :=
      lineData(offset + gustCfg.elementWidth - 1 downto
        offset + 1 + cfg.dataWidth).asUInt
  }

  val elementChoices = Bits(gustCfg.elementsPerLine bits)
  for (element <- 0 until gustCfg.elementsPerLine) {
    elementChoices(element) := lineElements(element).valid &&
      elementCursor <= element
  }
  val selectedElementIndex = UInt(elementIndexWidth bits)
  selectedElementIndex := 0
  for (element <- (0 until gustCfg.elementsPerLine).reverse) {
    when(elementChoices(element)) {
      selectedElementIndex := element
    }
  }
  val selectedElementValid = elementChoices.orR
  val selectedElement = lineElements(selectedElementIndex)

  scheduler.io.aLoad.valid := False
  scheduler.io.aLoad.payload := IndexedGustavsonA(cfg).getZero
  scheduler.io.start.payload.mode := TrapezoidDataflow.TrGt
  scheduler.io.finish := False
  for (subrow <- 0 until cfg.localBanks) {
    scheduler.io.bPush(subrow).valid := False
    scheduler.io.bPush(subrow).payload := GustavsonB(cfg).getZero
  }

  val nextAChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    nextAChoices(index) := U(index, scanIndexWidth bits) >= currentIndex &&
      aValid(index)
  }
  val nextAIndex = UInt(addressIndexWidth bits)
  nextAIndex := 0
  for (index <- (0 until cfg.macLanes).reverse) {
    when(nextAChoices(index)) {
      nextAIndex := index
    }
  }
  val nextAFound = nextAChoices.orR

  when(state === CachedTrGtState.LoadA && nextAFound) {
    scheduler.io.aLoad.valid := True
    scheduler.io.aLoad.payload.index := nextAIndex.resized
    scheduler.io.aLoad.payload.element := aStorage(nextAIndex)
  }

  val currentSubrow = UInt(subrowIndexWidth bits)
  currentSubrow := (currentIndex / cfg.macLanesPerSubrow).resized
  when(state === CachedTrGtState.EmitLine && selectedElementValid) {
    for (subrow <- 0 until cfg.localBanks) {
      when(currentSubrow === subrow) {
        scheduler.io.bPush(subrow).valid := True
        scheduler.io.bPush(subrow).payload.valid := True
        scheduler.io.bPush(subrow).payload.aIndex := currentIndex.resized
        scheduler.io.bPush(subrow).payload.k := selectedElement.k
        scheduler.io.bPush(subrow).payload.column := selectedElement.column
        scheduler.io.bPush(subrow).payload.value := selectedElement.value
      }
    }
  }

  val startWithFirstElement = state === CachedTrGtState.EmitLine &&
    selectedElementValid && !schedulerStarted
  scheduler.io.start.valid := state === CachedTrGtState.Start ||
    startWithFirstElement
  when(state === CachedTrGtState.Finish) {
    scheduler.io.finish := True
  }

  val exclusiveEnd = currentBBase.resize(cacheCfg.addressWidth + 1) +
    currentLineCount.resize(cacheCfg.addressWidth + 1)
  val addressLimit = U(BigInt(1) << cacheCfg.addressWidth,
    cacheCfg.addressWidth + 1 bits)
  val currentAddressError = currentAValid &&
    (currentLineCount === 0 || exclusiveEnd > addressLimit)

  io.rowCommand.ready := !io.clear && state === CachedTrGtState.Collect
  when(io.rowCommand.fire) {
    doneReg := False
    when(!rowCollectOpen) {
      for (index <- 0 until cfg.macLanes) aValid(index) := False
      rowCollectOpen := True
    }
    when(io.rowCommand.payload.mode =/= TrapezoidDataflow.TrGt) {
      unsupportedModeReg := True
      state := CachedTrGtState.Collect
      doneReg := True
    } elsewhen(io.rowCommand.payload.aIndex.resize(scanIndexWidth) >=
        U(cfg.macLanes, scanIndexWidth bits)) {
      addressErrorReg := True
      state := CachedTrGtState.Collect
      doneReg := True
    } otherwise {
      aValid(io.rowCommand.payload.aIndex) := io.rowCommand.payload.a.valid
      aStorage(io.rowCommand.payload.aIndex) := io.rowCommand.payload.a
      bBaseStorage(io.rowCommand.payload.aIndex) :=
        io.rowCommand.payload.bBaseAddress
      lineCountStorage(io.rowCommand.payload.aIndex) :=
        io.rowCommand.payload.lineCount
      when(io.rowCommand.payload.endOfTile) {
        currentIndex := 0
        rowCollectOpen := False
        schedulerStarted := False
        state := CachedTrGtState.LoadA
      }
    }
  }

  when(state === CachedTrGtState.LoadA && !nextAFound) {
    currentIndex := 0
    state := CachedTrGtState.FindA
  }
  when(state === CachedTrGtState.LoadA && nextAFound &&
      scheduler.io.aLoad.fire) {
    currentIndex := nextAIndex.resize(scanIndexWidth) + 1
  }
  when(state === CachedTrGtState.Start && scheduler.io.start.fire) {
    schedulerStarted := True
    state := CachedTrGtState.Finish
  }
  when(startWithFirstElement && scheduler.io.start.fire) {
    schedulerStarted := True
  }
  when(state === CachedTrGtState.FindA) {
    when(!nextAFound) {
      when(schedulerStarted) {
        state := CachedTrGtState.Finish
      } otherwise {
        state := CachedTrGtState.Start
      }
    } elsewhen(currentIndex =/= nextAIndex.resize(scanIndexWidth)) {
      currentIndex := nextAIndex.resize(scanIndexWidth)
    } elsewhen(currentAddressError) {
      addressErrorReg := True
      state := CachedTrGtState.Finish
    } otherwise {
      requestIndex := 0
      state := CachedTrGtState.RequestLine
    }
  }

  io.cacheRequest.valid := !io.clear && state === CachedTrGtState.RequestLine &&
    !requestOutstanding && requestIndex < currentLineCount
  io.cacheRequest.payload.address := (currentBBase + requestIndex.resized).resized
  io.cacheRequest.payload.requestId := requestIndex.resized
  when(io.cacheRequest.fire) {
    requestOutstanding := True
    state := CachedTrGtState.WaitLine
  }
  io.cacheResponse.ready := !io.clear && state === CachedTrGtState.WaitLine &&
    requestOutstanding
  when(io.cacheResponse.fire) {
    requestOutstanding := False
    rowCollectOpen := False
    when(io.cacheResponse.payload.error ||
        io.cacheResponse.payload.requestId =/= requestIndex.resized) {
      memoryErrorReg := True
      state := CachedTrGtState.Finish
    } otherwise {
      lineData := io.cacheResponse.payload.data
      lineValid := True
      elementCursor := 0
      state := CachedTrGtState.EmitLine
    }
  }

  val laterElement = Bool()
  laterElement := False
  for (element <- 0 until gustCfg.elementsPerLine) {
    when(selectedElementValid && element > selectedElementIndex &&
        lineElements(element).valid) { laterElement := True }
  }
  when(state === CachedTrGtState.EmitLine && selectedElementValid &&
      scheduler.io.bPush.map(_.fire).reduce(_ || _)) {
    when(laterElement) {
      elementCursor := selectedElementIndex + 1
    } otherwise {
      lineValid := False
      when(requestIndex + 1 < currentLineCount) {
        requestIndex := requestIndex + 1
        state := CachedTrGtState.RequestLine
      } otherwise {
        currentIndex := (currentIndex + 1).resized
        state := CachedTrGtState.FindA
      }
    }
  }
  when(state === CachedTrGtState.EmitLine && !selectedElementValid) {
    lineValid := False
    when(requestIndex + 1 < currentLineCount) {
      requestIndex := requestIndex + 1
      state := CachedTrGtState.RequestLine
    } otherwise {
      currentIndex := (currentIndex + 1).resized
      state := CachedTrGtState.FindA
    }
  }

  when(state === CachedTrGtState.Finish) {
    state := CachedTrGtState.WaitDone
  }
  when(state === CachedTrGtState.WaitDone && scheduler.io.done &&
      !scheduler.io.busy) {
    overflowReg := scheduler.io.overflow
    doneReg := True
    state := CachedTrGtState.Collect
  }

  when(io.clear) {
    state := CachedTrGtState.Collect
    currentIndex := 0
    requestIndex := 0
    lineValid := False
    requestOutstanding := False
    doneReg := False
    overflowReg := False
    memoryErrorReg := False
    addressErrorReg := False
    unsupportedModeReg := False
    schedulerStarted := False
    for (index <- 0 until cfg.macLanes) aValid(index) := False
  }
}

/** Allocates complete CSR rows across independent temporal TrGT PE rows. */
class CachedTrGtRowScheduler(
    systemCfg: EndToEndSystemConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg)
) extends Component {
  private val cfg = systemCfg.core
  private val gustCfg = systemCfg.gustavson
  private val cacheCfg = systemCfg.cache
  private val rowIndexWidth = scala.math.max(1, log2Up(cfg.peRows))

  val io = new Bundle {
    val clear = in Bool()
    val rowCommand = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val cacheRequests = Vec(master(Stream(CacheReadRequest(cacheCfg))), cfg.peRows)
    val cacheResponses = Vec(slave(Stream(CacheReadResponse(cacheCfg))), cfg.peRows)
    val result = master(Stream(MatrixResult(cfg)))
    val allocated = out Bits(cfg.peRows bits)
    val completedTiles = out UInt(32 bits)
    val overflow = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val unsupportedMode = out Bool()
    val phase = out UInt(4 bits)
    val schedulerBusy = out Bool()
    val fifoOccupancy = out UInt(16 bits)
  }

  val adapters = Array.fill(cfg.peRows)(
    new CachedTrGtSubrowAdapter(systemCfg, peFactory))
  val allocated = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val inputActive = RegInit(False)
  val inputRow = Reg(UInt(rowIndexWidth bits)) init 0
  val completedTiles = Reg(UInt(32 bits)) init 0

  for (row <- 0 until cfg.peRows) {
    adapters(row).io.clear := io.clear
    io.cacheRequests(row) <> adapters(row).io.cacheRequest
    adapters(row).io.cacheResponse <> io.cacheResponses(row)
  }

  val freeChoices = Vec(Bool(), cfg.peRows)
  var earlierFree: Bool = False
  for (row <- 0 until cfg.peRows) {
    freeChoices(row) := !allocated(row) && !earlierFree
    earlierFree = earlierFree || !allocated(row)
  }
  val freeFound = freeChoices.asBits.orR
  val freeRow = UInt(rowIndexWidth bits)
  freeRow := 0
  for (row <- 0 until cfg.peRows) {
    when(freeChoices(row)) { freeRow := row }
  }

  val selectedInputRow = UInt(rowIndexWidth bits)
  selectedInputRow := Mux(inputActive, inputRow, freeRow)
  val inputRouteValid = inputActive || freeFound
  io.rowCommand.ready := False
  for (row <- 0 until cfg.peRows) {
    adapters(row).io.rowCommand.valid := io.rowCommand.valid &&
      inputRouteValid && selectedInputRow === row
    adapters(row).io.rowCommand.payload := io.rowCommand.payload
    when(inputRouteValid && selectedInputRow === row) {
      io.rowCommand.ready := adapters(row).io.rowCommand.ready
    }
  }

  when(io.rowCommand.fire) {
    when(!inputActive) {
      for (row <- 0 until cfg.peRows) {
        when(selectedInputRow === row) { allocated(row) := True }
      }
    }
    when(io.rowCommand.payload.endOfTile) {
      inputActive := False
    } otherwise {
      inputActive := True
      inputRow := selectedInputRow
    }
  }

  val completionBits = Bits(cfg.peRows bits)
  for (row <- 0 until cfg.peRows) {
    completionBits(row) := allocated(row) && adapters(row).io.done &&
      !adapters(row).io.busy
    when(completionBits(row)) { allocated(row) := False }
  }
  val completionsThisCycle = CountOne(completionBits).resize(32)
  when(completionsThisCycle =/= 0) {
    completedTiles := completedTiles + completionsThisCycle
  }

  val outputChoices = Vec(Bool(), cfg.peRows)
  var earlierOutput: Bool = False
  for (row <- 0 until cfg.peRows) {
    outputChoices(row) := adapters(row).io.result.valid && !earlierOutput
    earlierOutput = earlierOutput || adapters(row).io.result.valid
  }
  val outputFound = outputChoices.asBits.orR
  val chosenOutputRow = UInt(rowIndexWidth bits)
  chosenOutputRow := 0
  for (row <- 0 until cfg.peRows) {
    when(outputChoices(row)) { chosenOutputRow := row }
  }

  val outputLocked = RegInit(False)
  val outputRow = Reg(UInt(rowIndexWidth bits)) init 0
  val selectedOutputRow = Mux(outputLocked, outputRow, chosenOutputRow)
  val outputRouteValid = !io.clear && (outputLocked || outputFound)
  io.result.valid := False
  io.result.payload := MatrixResult(cfg).getZero
  for (row <- 0 until cfg.peRows) {
    adapters(row).io.result.ready := io.result.ready && outputRouteValid &&
      selectedOutputRow === row
    when(outputRouteValid && selectedOutputRow === row) {
      io.result.valid := adapters(row).io.result.valid
      io.result.payload := adapters(row).io.result.payload
    }
  }
  when(!outputLocked && outputFound) {
    outputLocked := True
    outputRow := chosenOutputRow
  }
  when(io.result.fire && io.result.payload.endOfTile) {
    outputLocked := False
  }

  io.allocated := allocated.asBits
  io.completedTiles := completedTiles
  io.overflow := adapters.map(_.io.overflow).reduce(_ || _)
  io.memoryError := adapters.map(_.io.memoryError).reduce(_ || _)
  io.addressError := adapters.map(_.io.addressError).reduce(_ || _)
  io.unsupportedMode := adapters.map(_.io.unsupportedMode).reduce(_ || _)
  io.schedulerBusy := adapters.map(_.io.schedulerBusy).reduce(_ || _)
  io.fifoOccupancy := adapters.map(_.io.fifoOccupancy)
    .reduce(_ + _).resize(16)
  io.phase := 0
  for (row <- 0 until cfg.peRows) {
    when(allocated(row)) { io.phase := adapters(row).io.phase }
  }

  when(io.clear) {
    for (row <- 0 until cfg.peRows) allocated(row) := False
    inputActive := False
    outputLocked := False
    completedTiles := 0
  }
}

/** Writes one sparse C coordinate/value record per DMA cache line.
  *
  * Requests are pipelined through a FIFO and their ordered completions are
  * tracked independently. This models a bandwidth-oriented DMA interface
  * instead of serializing every result behind its round-trip response.
  */
class ResultWritebackDma(systemCfg: EndToEndSystemConfig) extends Component {
  private val cfg = systemCfg.core
  private val cacheCfg = systemCfg.cache
  private val recordWidth = 1 + 2 * cfg.coordinateWidth + cfg.accumulatorWidth
  require(cacheCfg.lineWidth >= recordWidth,
    "one C result record must fit in a DMA line")
  require(cacheCfg.lineWidth % 8 == 0)

  val io = new Bundle {
    val clear = in Bool()
    val start = slave(Stream(UInt(cacheCfg.addressWidth bits)))
    val input = slave(Stream(MatrixResult(cfg)))
    val close = in Bool()
    val writeRequest = master(Stream(DmaWriteRequest(cacheCfg)))
    val writeResponse = slave(Stream(DmaWriteResponse(cacheCfg)))
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
    val writtenResults = out UInt(32 bits)
  }

  val active = RegInit(False)
  val baseAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val acceptedResults = Reg(UInt(32 bits)) init 0
  val writtenResults = Reg(UInt(32 bits)) init 0
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)
  val requestFifo = new StreamFifo(
    DmaWriteRequest(cacheCfg), systemCfg.writeOutstandingDepth)
  val completionFifo = new StreamFifo(
    UInt(cacheCfg.addressWidth bits), systemCfg.writeOutstandingDepth)
  requestFifo.io.flush := io.clear
  completionFifo.io.flush := io.clear

  io.busy := active
  io.done := doneReg
  io.error := errorReg
  io.writtenResults := writtenResults
  io.start.ready := !io.clear && !active
  doneReg := False

  when(io.start.fire) {
    active := True
    baseAddress := io.start.payload
    acceptedResults := 0
    writtenResults := 0
    errorReg := False
  }

  val packedResult = Bits(cacheCfg.lineWidth bits)
  packedResult := 0
  packedResult(0) := io.input.payload.valid
  packedResult(cfg.coordinateWidth downto 1) := io.input.payload.row.asBits
  packedResult(2 * cfg.coordinateWidth downto cfg.coordinateWidth + 1) :=
    io.input.payload.column.asBits
  packedResult(recordWidth - 1 downto 2 * cfg.coordinateWidth + 1) :=
    io.input.payload.value.asBits

  requestFifo.io.push.valid := active && io.input.valid &&
    acceptedResults < systemCfg.maxCResults
  requestFifo.io.push.payload.address :=
    (baseAddress + acceptedResults.resized).resized
  requestFifo.io.push.payload.data := packedResult
  requestFifo.io.push.payload.mask.setAll()
  io.input.ready := active && acceptedResults < systemCfg.maxCResults &&
    requestFifo.io.push.ready
  when(io.input.fire) {
    acceptedResults := acceptedResults + 1
  }

  io.writeRequest.valid := active && requestFifo.io.pop.valid &&
    completionFifo.io.push.ready
  io.writeRequest.payload := requestFifo.io.pop.payload
  requestFifo.io.pop.ready := active && io.writeRequest.ready &&
    completionFifo.io.push.ready
  completionFifo.io.push.valid := io.writeRequest.fire
  completionFifo.io.push.payload := requestFifo.io.pop.payload.address

  val responseMatchesHead = completionFifo.io.pop.valid &&
    io.writeResponse.payload.address === completionFifo.io.pop.payload
  io.writeResponse.ready := active && responseMatchesHead
  completionFifo.io.pop.ready := io.writeResponse.fire
  when(io.writeResponse.fire) {
    errorReg := errorReg || io.writeResponse.payload.error
    writtenResults := writtenResults + 1
  }

  val allWritesCompleted = requestFifo.io.occupancy === 0 &&
    completionFifo.io.occupancy === 0 && acceptedResults === writtenResults
  when(active && io.close && !io.input.valid && allWritesCompleted) {
    active := False
    doneReg := True
  }

  when(active && acceptedResults === systemCfg.maxCResults && io.input.valid) {
    errorReg := True
  }

  when(io.clear) {
    active := False
    doneReg := False
    errorReg := False
    acceptedResults := 0
    writtenResults := 0
  }
}

object UnifiedEndToEndState extends SpinalEnum {
  val Idle, StartIp, RunIp, StartGustavson, RunGustavson, CloseWriteback = newElement()
}

/** Descriptor-to-final-C-write-response scaled RTL system. */
class UnifiedEndToEndSystem(
    systemCfg: EndToEndSystemConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    hardwareProfile: EndToEndHardwareProfile =
      EndToEndHardwareProfile.Unified
) extends Component {
  private val cfg = systemCfg.core
  private val matrixCfg = systemCfg.matrix
  private val gustCfg = systemCfg.gustavson
  private val cacheCfg = systemCfg.cache

  val io = new Bundle {
    val clear = in Bool()
    val descriptor = slave(Stream(UnifiedMatrixDescriptor(systemCfg)))
    val dmaReadRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val dmaReadResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val dmaWriteRequest = master(Stream(DmaWriteRequest(cacheCfg)))
    val dmaWriteResponse = slave(Stream(DmaWriteResponse(cacheCfg)))
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
    val writtenResults = out UInt(32 bits)
    val cacheHitCount = out UInt(32 bits)
    val cacheMissCount = out UInt(32 bits)
    val cacheRefillCount = out UInt(32 bits)
    val trgtPhase = out UInt(4 bits)
    val trgtSchedulerBusy = out Bool()
    val trgtFifoOccupancy = out UInt(16 bits)
  }

  val state = RegInit(UnifiedEndToEndState.Idle)
  val descriptorReg = Reg(UnifiedMatrixDescriptor(systemCfg))
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)

  private val ipReaderLanes = cfg.localBanks
  private val includeIp = hardwareProfile.includeIp
  private val includeTrGt = hardwareProfile.includeTrGt
  private val includeTrGs = hardwareProfile.includeTrGs
  private val includeGustavson = includeTrGt || includeTrGs
  private val requiredCacheClients =
    (if (includeIp) 2 * ipReaderLanes else 0) +
      (if (includeGustavson) 1 else 0) +
      (if (includeTrGt) cfg.peRows else 0) +
      (if (includeTrGs) 1 else 0)
  require(cacheCfg.clientCount >= requiredCacheClients,
    s"$hardwareProfile needs at least $requiredCacheClients cache clients")

  val ip = if (includeIp) new TrIpCachedMatrixController(
    matrixCfg, cacheCfg, ipReaderLanes, peFactory) else null
  val csr = if (includeGustavson)
    new CsrGustavsonDescriptorFrontend(systemCfg) else null
  val trgtScheduler = if (includeTrGt)
    new CachedTrGtRowScheduler(systemCfg, peFactory) else null
  val trgsAdapter = if (includeTrGs) new GustavsonBRowCacheAdapter(gustCfg,
    allowTrGt = false, allowTrGs = true, emitSpatialCommand = true) else null
  val trgsScheduler = if (includeTrGs) new TrGsSpatialRowScheduler(
    gustCfg.tile, peFactory, wideOutput = false) else null
  val cache = new ClusteredReadCache(systemCfg.clusters)
  val writeback = new ResultWritebackDma(systemCfg)

  if (includeIp) ip.io.clear := io.clear
  if (includeGustavson) csr.io.clear := io.clear
  if (includeTrGt) trgtScheduler.io.clear := io.clear
  if (includeTrGs) {
    trgsAdapter.io.clear := io.clear
    trgsScheduler.io.clear := io.clear
  }
  cache.io.clear := io.clear
  writeback.io.clear := io.clear

  io.busy := state =/= UnifiedEndToEndState.Idle
  io.done := doneReg
  io.error := errorReg || writeback.io.error
  io.writtenResults := writeback.io.writtenResults
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount
  if (includeTrGt) {
    io.trgtPhase := trgtScheduler.io.phase
    io.trgtSchedulerBusy := trgtScheduler.io.schedulerBusy
    io.trgtFifoOccupancy := trgtScheduler.io.fifoOccupancy
  } else {
    io.trgtPhase := 0
    io.trgtSchedulerBusy := False
    io.trgtFifoOccupancy := 0
  }
  doneReg := False

  val descriptorModeIsIp = io.descriptor.payload.mode === TrapezoidDataflow.DenseIp ||
    io.descriptor.payload.mode === TrapezoidDataflow.TrIp
  val descriptorModeAllowed = Bool()
  descriptorModeAllowed := False
  if (includeIp) {
    when(descriptorModeIsIp) { descriptorModeAllowed := True }
  }
  if (includeTrGt) {
    when(io.descriptor.payload.mode === TrapezoidDataflow.TrGt) {
      descriptorModeAllowed := True
    }
  }
  if (includeTrGs) {
    when(io.descriptor.payload.mode === TrapezoidDataflow.TrGs) {
      descriptorModeAllowed := True
    }
  }
  val cAddressWidth = cacheCfg.addressWidth + 1
  val cRegionEnd = io.descriptor.payload.cBaseAddress.resize(cAddressWidth) +
    U(systemCfg.maxCResults, cAddressWidth bits)
  val cAddressError = cRegionEnd >
    U(BigInt(1) << cacheCfg.addressWidth, cAddressWidth bits)
  writeback.io.start.valid := io.descriptor.valid &&
    state === UnifiedEndToEndState.Idle
  writeback.io.start.payload := io.descriptor.payload.cBaseAddress
  io.descriptor.ready := state === UnifiedEndToEndState.Idle &&
    writeback.io.start.ready
  when(io.descriptor.fire) {
    descriptorReg := io.descriptor.payload
    errorReg := cAddressError || !descriptorModeAllowed
    when(cAddressError || !descriptorModeAllowed) {
      state := UnifiedEndToEndState.CloseWriteback
    } otherwise {
      state := Mux(descriptorModeIsIp,
        UnifiedEndToEndState.StartIp, UnifiedEndToEndState.StartGustavson)
    }
  }

  if (includeIp) {
    ip.io.descriptor.valid := state === UnifiedEndToEndState.StartIp
    ip.io.descriptor.payload.matrix.rowCount := descriptorReg.rowCount
    ip.io.descriptor.payload.matrix.columnCount := descriptorReg.columnCount
    ip.io.descriptor.payload.matrix.kTileCount := descriptorReg.kTileCount
    for (tile <- 0 until matrixCfg.maxKTiles) {
      ip.io.descriptor.payload.matrix.modes(tile) := descriptorReg.mode
    }
    ip.io.descriptor.payload.aBaseAddress := descriptorReg.ipABaseAddress
    ip.io.descriptor.payload.bBaseAddress := descriptorReg.ipBBaseAddress
    when(ip.io.descriptor.fire) { state := UnifiedEndToEndState.RunIp }
  }

  if (includeGustavson) {
    csr.io.descriptor.valid := state === UnifiedEndToEndState.StartGustavson
    csr.io.descriptor.payload := descriptorReg
    when(csr.io.descriptor.fire) { state := UnifiedEndToEndState.RunGustavson }
  }

  val runTrGt = descriptorReg.mode === TrapezoidDataflow.TrGt
  if (includeTrGt && includeTrGs) {
    trgtScheduler.io.rowCommand.valid := csr.io.command.valid && runTrGt
    trgtScheduler.io.rowCommand.payload := csr.io.command.payload
    trgsAdapter.io.rowCommand.valid := csr.io.command.valid && !runTrGt
    trgsAdapter.io.rowCommand.payload := csr.io.command.payload
    csr.io.command.ready := Mux(runTrGt,
      trgtScheduler.io.rowCommand.ready, trgsAdapter.io.rowCommand.ready)
  } else if (includeTrGt) {
    trgtScheduler.io.rowCommand <> csr.io.command
  } else if (includeTrGs) {
    trgsAdapter.io.rowCommand <> csr.io.command
  }
  if (includeTrGs) trgsScheduler.io.command <> trgsAdapter.io.spatialCommand

  val selectedResult = MatrixResult(cfg)
  selectedResult := MatrixResult(cfg).getZero
  val selectedResultValid = Bool()
  selectedResultValid := False
  if (includeIp) {
    ip.io.result.ready := False
    when(state === UnifiedEndToEndState.RunIp) {
      selectedResult := ip.io.result.payload.element
      selectedResultValid := ip.io.result.valid && ip.io.result.payload.element.valid
      ip.io.result.ready := Mux(ip.io.result.payload.element.valid,
        writeback.io.input.ready, True)
    }
  }
  if (includeTrGt) {
    trgtScheduler.io.result.ready := False
    when(state === UnifiedEndToEndState.RunGustavson && runTrGt) {
      selectedResult := trgtScheduler.io.result.payload
      selectedResultValid := trgtScheduler.io.result.valid &&
        trgtScheduler.io.result.payload.valid
      trgtScheduler.io.result.ready := Mux(trgtScheduler.io.result.payload.valid,
        writeback.io.input.ready, True)
    }
  }
  if (includeTrGs) {
    trgsScheduler.io.result.ready := False
    trgsScheduler.io.resultLine.ready := False
    when(state === UnifiedEndToEndState.RunGustavson && !runTrGt) {
      selectedResult := trgsScheduler.io.result.payload
      selectedResultValid := trgsScheduler.io.result.valid &&
        trgsScheduler.io.result.payload.valid
      trgsScheduler.io.result.ready := Mux(trgsScheduler.io.result.payload.valid,
        writeback.io.input.ready, True)
    }
  }
  writeback.io.input.valid := selectedResultValid
  writeback.io.input.payload := selectedResult

  val gustavsonCompleted: Bool = if (includeTrGt && includeTrGs) {
    csr.io.done && Mux(runTrGt,
      trgtScheduler.io.completedTiles === csr.io.issuedTiles &&
        !trgtScheduler.io.allocated.orR,
      trgsAdapter.io.busy === False &&
        trgsScheduler.io.completedTiles === csr.io.issuedTiles)
  } else if (includeTrGt) {
    csr.io.done &&
      trgtScheduler.io.completedTiles === csr.io.issuedTiles &&
      !trgtScheduler.io.allocated.orR
  } else if (includeTrGs) {
    csr.io.done && !trgsAdapter.io.busy &&
      trgsScheduler.io.completedTiles === csr.io.issuedTiles
  } else {
    False
  }
  if (includeIp) {
    when(state === UnifiedEndToEndState.RunIp && ip.io.done) {
      errorReg := ip.io.descriptorError || ip.io.addressError ||
        ip.io.innerError || ip.io.memoryError
      state := UnifiedEndToEndState.CloseWriteback
    }
  }
  if (includeGustavson) {
    var gustavsonError: Bool = csr.io.error
    if (includeTrGt) {
      gustavsonError = gustavsonError || trgtScheduler.io.memoryError ||
        trgtScheduler.io.addressError || trgtScheduler.io.unsupportedMode ||
        trgtScheduler.io.overflow
    }
    if (includeTrGs) {
      gustavsonError = gustavsonError || trgsAdapter.io.memoryError ||
        trgsAdapter.io.addressError || trgsAdapter.io.unsupportedMode ||
        trgsScheduler.io.overflow.orR || trgsScheduler.io.unsupportedMode.orR
    }
    when(state === UnifiedEndToEndState.RunGustavson && gustavsonCompleted) {
      errorReg := gustavsonError
      state := UnifiedEndToEndState.CloseWriteback
    }
  }

  writeback.io.close := state === UnifiedEndToEndState.CloseWriteback
  when(state === UnifiedEndToEndState.CloseWriteback && writeback.io.done) {
    doneReg := True
    state := UnifiedEndToEndState.Idle
  }

  var nextCacheClient = 0
  if (includeIp) {
    for (lane <- 0 until ipReaderLanes) {
      cache.io.requests(nextCacheClient + lane) <> ip.io.aCacheRequests(lane)
      ip.io.aCacheResponses(lane) <> cache.io.responses(nextCacheClient + lane)
      cache.io.requests(nextCacheClient + ipReaderLanes + lane) <>
        ip.io.bCacheRequests(lane)
      ip.io.bCacheResponses(lane) <>
        cache.io.responses(nextCacheClient + ipReaderLanes + lane)
    }
    nextCacheClient += 2 * ipReaderLanes
  }
  if (includeGustavson) {
    cache.io.requests(nextCacheClient) <> csr.io.cacheRequest
    csr.io.cacheResponse <> cache.io.responses(nextCacheClient)
    nextCacheClient += 1
  }
  if (includeTrGt) {
    for (row <- 0 until cfg.peRows) {
      cache.io.requests(nextCacheClient + row) <>
        trgtScheduler.io.cacheRequests(row)
      trgtScheduler.io.cacheResponses(row) <>
        cache.io.responses(nextCacheClient + row)
    }
    nextCacheClient += cfg.peRows
  }
  if (includeTrGs) {
    cache.io.requests(nextCacheClient) <> trgsAdapter.io.cacheRequest
    trgsAdapter.io.cacheResponse <> cache.io.responses(nextCacheClient)
    nextCacheClient += 1
  }
  for (client <- nextCacheClient until cacheCfg.clientCount) {
    cache.io.requests(client).valid := False
    cache.io.requests(client).payload := CacheReadRequest(cacheCfg).getZero
    cache.io.responses(client).ready := True
  }

  io.dmaReadRequest <> cache.io.refillRequest
  cache.io.refillResponse <> io.dmaReadResponse
  io.dmaWriteRequest <> writeback.io.writeRequest
  writeback.io.writeResponse <> io.dmaWriteResponse

  when(io.clear) {
    state := UnifiedEndToEndState.Idle
    doneReg := False
    errorReg := False
  }
}
