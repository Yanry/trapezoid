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
    val extraRefillRequests = if (cacheCfg.refillChannelCount > 1)
      Vec(master(Stream(CacheRefillRequest(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
    val extraRefillResponses = if (cacheCfg.refillChannelCount > 1)
      Vec(slave(Stream(CacheRefillResponse(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
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
  private val refillRequestPorts = (0 until cacheCfg.refillChannelCount).map {
    channel => if (channel == 0) io.refillRequest
      else io.extraRefillRequests(channel - 1)
  }
  private val refillResponsePorts = (0 until cacheCfg.refillChannelCount).map {
    channel => if (channel == 0) io.refillResponse
      else io.extraRefillResponses(channel - 1)
  }
  private def clusterRefillRequest(cluster: Int, channel: Int) =
    if (channel == 0) clusters(cluster).io.refillRequest
    else clusters(cluster).io.extraRefillRequests(channel - 1)
  private def clusterRefillResponse(cluster: Int, channel: Int) =
    if (channel == 0) clusters(cluster).io.refillResponse
    else clusters(cluster).io.extraRefillResponses(channel - 1)
  val clientClusterOrder = Array.fill(cacheCfg.clientCount)(
    StreamFifo(UInt(clusterCfg.clusterIndexWidth bits),
      cacheCfg.maxOutstandingPerClient))
  for (cluster <- 0 until clusterCount) {
    clusters(cluster).io.clear := io.clear
  }

  for (client <- 0 until cacheCfg.clientCount) {
    clientClusterOrder(client).io.flush := io.clear
    clientClusterOrder(client).io.push.payload :=
      clusterOf(io.requests(client).payload.address)
    io.requests(client).ready := False
    for (cluster <- 0 until clusterCount) {
      val selected = clusterOf(io.requests(client).payload.address) === cluster
      clusters(cluster).io.requests(client).valid :=
        io.requests(client).valid && selected &&
          clientClusterOrder(client).io.push.ready
      clusters(cluster).io.requests(client).payload.address :=
        localAddressOf(io.requests(client).payload.address)
      clusters(cluster).io.requests(client).payload.requestId :=
        io.requests(client).payload.requestId
      when(selected) {
        io.requests(client).ready := clusters(cluster).io.requests(client).ready &&
          clientClusterOrder(client).io.push.ready
      }
    }
    clientClusterOrder(client).io.push.valid := io.requests(client).fire

    val expectedCluster = clientClusterOrder(client).io.pop.payload
    for (cluster <- 0 until clusterCount) {
      clusters(cluster).io.responses(client).ready := False
    }
    io.responses(client).valid := False
    io.responses(client).payload := CacheReadResponse(cacheCfg).getZero
    for (cluster <- 0 until clusterCount) {
      val selected = clientClusterOrder(client).io.pop.valid &&
        expectedCluster === cluster
      when(selected) {
        clusters(cluster).io.responses(client).ready := io.responses(client).ready
        io.responses(client).valid := clusters(cluster).io.responses(client).valid
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
    clientClusterOrder(client).io.pop.ready := io.responses(client).fire
  }

  for (channel <- 0 until cacheCfg.refillChannelCount) {
    val refillChoices = Bits(clusterCount bits)
    var earlierRefill: Bool = False
    for (cluster <- 0 until clusterCount) {
      val clusterRequest = clusterRefillRequest(cluster, channel)
      refillChoices(cluster) := clusterRequest.valid && !earlierRefill
      earlierRefill = earlierRefill || clusterRequest.valid
    }
    val externalRequest = refillRequestPorts(channel)
    externalRequest.valid := refillChoices.orR
    externalRequest.payload := CacheRefillRequest(cacheCfg).getZero
    for (cluster <- 0 until clusterCount) {
      val clusterRequest = clusterRefillRequest(cluster, channel)
      clusterRequest.ready := externalRequest.ready && refillChoices(cluster)
      when(refillChoices(cluster)) {
        externalRequest.payload.address :=
          globalAddressOf(clusterRequest.payload.address, cluster)
        if (clusterCount == 1) {
          externalRequest.payload.bank := clusterRequest.payload.bank.resized
        } else {
          externalRequest.payload.bank :=
            (U(cluster, clusterCfg.clusterIndexWidth bits) ##
              clusterRequest.payload.bank).asUInt
        }
      }
    }

    val externalResponse = refillResponsePorts(channel)
    val responseCluster = if (clusterCount == 1) {
      U(0, clusterCfg.clusterIndexWidth bits)
    } else {
      externalResponse.payload.bank(cacheCfg.bankIndexWidth - 1 downto
        localBankWidth)
    }
    externalResponse.ready := False
    for (cluster <- 0 until clusterCount) {
      val clusterResponse = clusterRefillResponse(cluster, channel)
      val selected = responseCluster === cluster
      clusterResponse.valid := externalResponse.valid && selected
      clusterResponse.payload.bank :=
        externalResponse.payload.bank(localBankWidth - 1 downto 0).resized
      clusterResponse.payload.address :=
        localAddressOf(externalResponse.payload.address)
      clusterResponse.payload.data := externalResponse.payload.data
      clusterResponse.payload.error := externalResponse.payload.error
      when(selected) {
        externalResponse.ready := clusterResponse.ready
      }
    }
  }

  for (cluster <- 0 until clusterCount; bank <- 0 until clusterCfg.banksPerCluster) {
    io.bankBusy(cluster * clusterCfg.banksPerCluster + bank) :=
      clusters(cluster).io.bankBusy(bank)
  }
  for (client <- 0 until cacheCfg.clientCount) {
    io.clientOutstanding(client) := clientClusterOrder(client).io.pop.valid
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

  def finishOrAdvanceRow(nextRowStart: UInt): Unit = {
    when(rowIndex.resize(matrixCfg.rowCountWidth + 1) + 1 >=
        descriptorReg.rowCount.resize(matrixCfg.rowCountWidth + 1)) {
      doneReg := True
      state := CsrGustavsonState.Idle
    } otherwise {
      rowIndex := rowIndex + 1
      rowStart := nextRowStart
      state := CsrGustavsonState.RequestRowEnd
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
            finishOrAdvanceRow(nextRowEnd)
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
  io.command.payload.aRowLength := (rowEnd - rowStart).resized
  io.command.payload.aIndex := rowEntryOrdinal.resized
  io.command.payload.a.valid := True
  io.command.payload.a.row := rowIndex.resized
  io.command.payload.a.k := aK
  io.command.payload.a.value := aValue
  io.command.payload.bBaseAddress := bBaseAddress
  io.command.payload.lineCount := bLineCount
  when(io.command.fire) {
    when(entryIndex + 1 >= rowEnd) {
      issuedTiles := issuedTiles + 1
      finishOrAdvanceRow(rowEnd)
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

/** Aggregates one packed group of CSR A rows for the four temporal TrGT subrows.
  *
  * The row scheduler maps short A rows into disjoint subrows. This adapter
  * stores each stationary A entry in one of two group contexts. The selected
  * group loads A into the scheduler before its referenced B lines are streamed
  * into the FIFO selected by the packed A index.
  */
class CachedTrGtSubrowAdapter(
    systemCfg: EndToEndSystemConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    externalScheduler: Boolean = false
) extends Component {
  private val cfg = systemCfg.core
  private val gustCfg = systemCfg.gustavson
  private val tileCfg = gustCfg.tile
  private val cacheCfg = systemCfg.cache
  private val scanIndexWidth = scala.math.max(1, log2Up(cfg.macLanes + 1))
  private val addressIndexWidth = scala.math.max(1, log2Up(cfg.macLanes))
  private val elementIndexWidth = scala.math.max(1, log2Up(gustCfg.elementsPerLine))
  private val subrowIndexWidth = scala.math.max(1, log2Up(cfg.localBanks))
  private val prefetchCapacity = cfg.localBanks * cfg.localDepthPerBank
  private val prefetchSlotWidth = scala.math.max(1, log2Up(prefetchCapacity))
  private val prefetchCountWidth = log2Up(prefetchCapacity + 1)
  private val prefetchBankAddressWidth = cfg.localAddressWidth
  private val groupCount = 2
  private val lineIdWidth = scala.math.max(1, log2Up(gustCfg.maxLinesPerRow))
  require(cacheCfg.requestIdWidth >= lineIdWidth + 1,
    "TrGT request IDs must carry groupId and lineId")

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
    val groupReadyState = out Bits(2 bits)
    val aLoadCompleteState = out Bits(2 bits)
    val prefetchActiveState = out Bits(2 bits)
    val executeGroupState = out Bool()
    val fillGroupState = out Bool()
    val schedulerALoad = if (externalScheduler)
      master(Stream(IndexedGustavsonA(cfg))) else null
    val schedulerStart = if (externalScheduler)
      master(Stream(GustavsonStart())) else null
    val schedulerBPush = if (externalScheduler)
      Vec(master(Stream(GustavsonB(cfg))), cfg.localBanks) else null
    val schedulerFinish = if (externalScheduler) out Bool() else null
    val schedulerResult = if (externalScheduler)
      slave(Stream(MatrixResult(cfg))) else null
    val schedulerBusyIn = if (externalScheduler) in Bool() else null
    val schedulerDoneIn = if (externalScheduler) in Bool() else null
    val schedulerOverflowIn = if (externalScheduler) in Bool() else null
    val schedulerUnsupportedModeIn = if (externalScheduler) in Bool() else null
    val schedulerFifoOccupancyIn = if (externalScheduler)
      in Vec(UInt(tileCfg.fifoCountWidth bits), cfg.localBanks) else null
  }

  val scheduler = if (externalScheduler) null else new TrGtSubrowScheduler(
    gustCfg.tile, peFactory = peFactory)
  val schedulerALoad = Stream(IndexedGustavsonA(cfg))
  val schedulerStart = Stream(GustavsonStart())
  val schedulerBPush = Vec(Stream(GustavsonB(cfg)), cfg.localBanks)
  val schedulerFinish = Bool()
  val schedulerResult = Stream(MatrixResult(cfg))
  val schedulerBusySignal = Bool()
  val schedulerDoneSignal = Bool()
  val schedulerOverflowSignal = Bool()
  val schedulerUnsupportedModeSignal = Bool()
  val schedulerFifoOccupancy = Vec(
    UInt(tileCfg.fifoCountWidth bits), cfg.localBanks)
  if (externalScheduler) {
    io.schedulerALoad << schedulerALoad
    io.schedulerStart << schedulerStart
    for (subrow <- 0 until cfg.localBanks) {
      io.schedulerBPush(subrow) << schedulerBPush(subrow)
      schedulerFifoOccupancy(subrow) := io.schedulerFifoOccupancyIn(subrow)
    }
    io.schedulerFinish := schedulerFinish
    schedulerResult << io.schedulerResult
    schedulerBusySignal := io.schedulerBusyIn
    schedulerDoneSignal := io.schedulerDoneIn
    schedulerOverflowSignal := io.schedulerOverflowIn
    schedulerUnsupportedModeSignal := io.schedulerUnsupportedModeIn
  } else {
    scheduler.io.aLoad << schedulerALoad
    scheduler.io.start << schedulerStart
    for (subrow <- 0 until cfg.localBanks) {
      scheduler.io.bPush(subrow) << schedulerBPush(subrow)
      schedulerFifoOccupancy(subrow) := scheduler.io.fifoOccupancy(subrow)
    }
    scheduler.io.finish := schedulerFinish
    schedulerResult << scheduler.io.result
    schedulerBusySignal := scheduler.io.busy
    schedulerDoneSignal := scheduler.io.done
    schedulerOverflowSignal := scheduler.io.overflow
    schedulerUnsupportedModeSignal := scheduler.io.unsupportedMode
  }
  io.result <> schedulerResult

  val state = RegInit(CachedTrGtState.Collect)
  val schedulerTileReset = state === CachedTrGtState.WaitDone &&
    schedulerDoneSignal && !schedulerBusySignal
  if (!externalScheduler) scheduler.io.clear := io.clear || schedulerTileReset
  val groupReady = Vec((0 until groupCount).map(_ => RegInit(False)))
  val groupCollectOpen = Vec((0 until groupCount).map(_ => RegInit(False)))
  val fillGroup = Reg(Bool()) init False
  val executeGroup = Reg(Bool()) init False
  val nextExecuteGroup = Reg(Bool()) init False
  val aValid = Vec((0 until groupCount).map(_ =>
    Vec((0 until cfg.macLanes).map(_ => RegInit(False)))))
  val aStorage = Vec((0 until groupCount).map(_ =>
    Vec((0 until cfg.macLanes).map(_ => Reg(GustavsonA(cfg))))))
  val bBaseStorage = Vec((0 until groupCount).map(_ =>
    Vec((0 until cfg.macLanes).map(_ =>
      Reg(UInt(cacheCfg.addressWidth bits)) init 0))))
  val lineCountStorage = Vec((0 until groupCount).map(_ =>
    Vec((0 until cfg.macLanes).map(_ =>
      Reg(UInt(gustCfg.lineCountWidth bits)) init 0))))
  val lineBaseStorage = Vec((0 until groupCount).map(_ =>
    Vec((0 until cfg.macLanes).map(_ =>
      Reg(UInt(prefetchSlotWidth bits)) init 0))))
  val groupLineAllocation = Vec((0 until groupCount).map(_ =>
    Reg(UInt(prefetchCountWidth bits)) init 0))
  val currentIndex = Reg(UInt(scanIndexWidth bits)) init 0
  val aLoadIndex = Vec((0 until groupCount).map(_ =>
    Reg(UInt(scanIndexWidth bits)) init 0))
  val aLoadComplete = Vec((0 until groupCount).map(_ => RegInit(False)))
  val requestIndex = Reg(UInt(gustCfg.lineCountWidth bits)) init 0
  val prefetchActive = Vec((0 until groupCount).map(_ => RegInit(False)))
  val prefetchAIndex = Vec((0 until groupCount).map(_ =>
    Reg(UInt(addressIndexWidth bits)) init 0))
  val prefetchLineCount = Vec((0 until groupCount).map(_ =>
    Reg(UInt(gustCfg.lineCountWidth bits)) init 0))
  val nextRequestIndex = Vec((0 until groupCount).map(_ =>
    Reg(UInt(gustCfg.lineCountWidth bits)) init 0))
  val prefetchOccupancy = Vec((0 until groupCount).map(_ =>
    Reg(UInt(prefetchCountWidth bits)) init 0))
  val requestOutstanding = Vec((0 until groupCount).map(_ =>
    Reg(UInt(prefetchCountWidth bits)) init 0))
  val prefetchValid = Vec((0 until groupCount).map(_ =>
    Vec((0 until prefetchCapacity).map(_ => RegInit(False)))))
  // B lines are physically banked by the local-buffer bank index. The valid
  // bitmap remains register state because a group reset invalidates all slots
  // without spending a cycle writing the data SRAM; data itself is inferred as
  // one memory per group and per bank.
  val prefetchDataMem = Array.fill(groupCount, cfg.localBanks) {
    Mem(Bits(cacheCfg.lineWidth bits), cfg.localDepthPerBank)
  }

  private def prefetchDataAt(group: UInt, slot: UInt): Bits = {
    val selected = Bits(cacheCfg.lineWidth bits)
    selected := 0
    val bank = slot(prefetchSlotWidth - 1 downto prefetchBankAddressWidth)
    val address = slot(prefetchBankAddressWidth - 1 downto 0)
    for (groupIndex <- 0 until groupCount; bankIndex <- 0 until cfg.localBanks) {
      when(group === groupIndex && bank === bankIndex) {
        selected := prefetchDataMem(groupIndex)(bankIndex).readAsync(address)
      }
    }
    selected
  }
  val elementCursor = Reg(UInt(elementIndexWidth bits)) init 0
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val memoryErrorReg = RegInit(False)
  val addressErrorReg = RegInit(False)
  val unsupportedModeReg = RegInit(False)
  val schedulerStarted = RegInit(False)

  io.busy := state =/= CachedTrGtState.Collect || groupReady.asBits.orR ||
    groupCollectOpen.asBits.orR
  io.done := doneReg
  io.overflow := overflowReg
  io.memoryError := memoryErrorReg
  io.addressError := addressErrorReg
  io.unsupportedMode := unsupportedModeReg
  io.phase := state.asBits.asUInt.resize(io.phase.getWidth)
  io.schedulerBusy := schedulerBusySignal
  io.fifoOccupancy := schedulerFifoOccupancy
    .map(_.resize(16)).reduce(_ + _).resize(16)
  io.groupReadyState := groupReady.asBits
  io.aLoadCompleteState := aLoadComplete.asBits
  io.prefetchActiveState := prefetchActive.asBits
  io.executeGroupState := executeGroup
  io.fillGroupState := fillGroup

  val currentAValid = Bool()
  val currentA = GustavsonA(cfg)
  val currentBBase = UInt(cacheCfg.addressWidth bits)
  val currentLineCount = UInt(gustCfg.lineCountWidth bits)
  currentAValid := False
  currentA := GustavsonA(cfg).getZero
  currentBBase := 0
  currentLineCount := 0
  for (index <- 0 until cfg.macLanes) {
    when(executeGroup === False && currentIndex === index) {
      currentAValid := aValid(0)(index)
      currentA := aStorage(0)(index)
      currentBBase := bBaseStorage(0)(index)
      currentLineCount := lineCountStorage(0)(index)
    }
    when(executeGroup === True && currentIndex === index) {
      currentAValid := aValid(1)(index)
      currentA := aStorage(1)(index)
      currentBBase := bBaseStorage(1)(index)
      currentLineCount := lineCountStorage(1)(index)
    }
  }

  val lineElements = Vec(TrGsB(cfg), gustCfg.elementsPerLine)
  val currentLineBase = UInt(prefetchSlotWidth bits)
  currentLineBase := 0
  for (index <- 0 until cfg.macLanes) {
    when(currentIndex === index) {
      currentLineBase := lineBaseStorage(executeGroup.asUInt)(index)
    }
  }
  val currentPrefetchSlot =
    (currentLineBase + requestIndex.resized).resize(prefetchSlotWidth)
  val currentLineValid = prefetchValid(executeGroup.asUInt)(currentPrefetchSlot)
  val currentLineData = prefetchDataAt(executeGroup.asUInt,
    currentPrefetchSlot)
  for (element <- 0 until gustCfg.elementsPerLine) {
    val offset = element * gustCfg.elementWidth
    lineElements(element).valid := currentLineValid &&
      currentLineData(offset)
    lineElements(element).k := currentA.k
    lineElements(element).value :=
      currentLineData(offset + cfg.dataWidth downto offset + 1).asSInt
    lineElements(element).column :=
      currentLineData(offset + gustCfg.elementWidth - 1 downto
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

  val backgroundGroup = !executeGroup
  val executeNeedsALoad = groupReady(executeGroup.asUInt) &&
    !aLoadComplete(executeGroup.asUInt)
  val backgroundNeedsALoad = groupReady(backgroundGroup.asUInt) &&
    !aLoadComplete(backgroundGroup.asUInt)
  val aLoadGroup = Mux(executeNeedsALoad, executeGroup, backgroundGroup)
  val aLoadContext = aLoadGroup.asUInt
  val loadAChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    loadAChoices(index) := U(index, scanIndexWidth bits) >=
      aLoadIndex(aLoadContext) &&
      Mux(aLoadGroup, aValid(1)(index), aValid(0)(index))
  }
  val loadAIndex = UInt(addressIndexWidth bits)
  loadAIndex := 0
  for (index <- (0 until cfg.macLanes).reverse) {
    when(loadAChoices(index)) { loadAIndex := index }
  }
  val loadAFound = loadAChoices.orR
  val loadAElement = GustavsonA(cfg)
  loadAElement := GustavsonA(cfg).getZero
  for (index <- 0 until cfg.macLanes) {
    when(loadAIndex === index) {
      loadAElement := Mux(aLoadGroup,
        aStorage(1)(index), aStorage(0)(index))
    }
  }
  schedulerALoad.valid := !io.clear &&
    (executeNeedsALoad || backgroundNeedsALoad) && loadAFound
  schedulerALoad.payload.groupId := aLoadGroup
  schedulerALoad.payload.index := loadAIndex
  schedulerALoad.payload.element := loadAElement
  schedulerStart.payload.mode := TrapezoidDataflow.TrGt
  schedulerStart.payload.groupId := executeGroup
  schedulerFinish := False
  for (subrow <- 0 until cfg.localBanks) {
    schedulerBPush(subrow).valid := False
    schedulerBPush(subrow).payload := GustavsonB(cfg).getZero
  }

  val nextAChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    nextAChoices(index) := U(index, scanIndexWidth bits) >= currentIndex &&
      Mux(executeGroup, aValid(1)(index), aValid(0)(index))
  }
  val nextAIndex = UInt(addressIndexWidth bits)
  nextAIndex := 0
  for (index <- (0 until cfg.macLanes).reverse) {
    when(nextAChoices(index)) {
      nextAIndex := index
    }
  }
  val nextAFound = nextAChoices.orR

  val backgroundAChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    backgroundAChoices(index) := Mux(backgroundGroup,
      aValid(1)(index), aValid(0)(index))
  }
  val backgroundAIndex = UInt(addressIndexWidth bits)
  backgroundAIndex := 0
  for (index <- (0 until cfg.macLanes).reverse) {
    when(backgroundAChoices(index)) { backgroundAIndex := index }
  }
  val backgroundAFound = backgroundAChoices.orR
  val backgroundNextChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    backgroundNextChoices(index) :=
      U(index, scanIndexWidth bits) >
        prefetchAIndex(backgroundGroup.asUInt).resize(scanIndexWidth) &&
        Mux(backgroundGroup, aValid(1)(index), aValid(0)(index))
  }
  val backgroundNextAIndex = UInt(addressIndexWidth bits)
  backgroundNextAIndex := 0
  for (index <- (0 until cfg.macLanes).reverse) {
    when(backgroundNextChoices(index)) {
      backgroundNextAIndex := index
    }
  }
  val backgroundNextAFound = backgroundNextChoices.orR
  val backgroundLineCount = UInt(gustCfg.lineCountWidth bits)
  backgroundLineCount := 0
  for (index <- 0 until cfg.macLanes) {
    when(backgroundAIndex === index) {
      backgroundLineCount := Mux(backgroundGroup,
        lineCountStorage(1)(index), lineCountStorage(0)(index))
    }
  }
  val backgroundPrefetchInitialize = state =/= CachedTrGtState.Collect &&
    groupReady(backgroundGroup.asUInt) && backgroundAFound &&
    !prefetchActive(backgroundGroup.asUInt)

  when(backgroundPrefetchInitialize) {
    prefetchActive(backgroundGroup.asUInt) := True
    prefetchAIndex(backgroundGroup.asUInt) := backgroundAIndex
    prefetchLineCount(backgroundGroup.asUInt) := backgroundLineCount
    nextRequestIndex(backgroundGroup.asUInt) :=
      U(0, gustCfg.lineCountWidth bits)
    prefetchOccupancy(backgroundGroup.asUInt) :=
      U(0, prefetchCountWidth bits)
    requestOutstanding(backgroundGroup.asUInt) :=
      U(0, prefetchCountWidth bits)
    for (slot <- 0 until prefetchCapacity) {
      prefetchValid(backgroundGroup.asUInt)(slot) := False
    }
  }

  val backgroundWindowComplete = groupReady(backgroundGroup.asUInt) &&
    prefetchActive(backgroundGroup.asUInt) &&
    nextRequestIndex(backgroundGroup.asUInt) >=
      prefetchLineCount(backgroundGroup.asUInt) &&
    requestOutstanding(backgroundGroup.asUInt) === 0 &&
    backgroundNextAFound &&
    prefetchOccupancy(backgroundGroup.asUInt) < prefetchCapacity
  when(backgroundWindowComplete) {
    prefetchAIndex(backgroundGroup.asUInt) := backgroundNextAIndex
    for (index <- 0 until cfg.macLanes) {
      when(backgroundNextAIndex === index) {
        prefetchLineCount(backgroundGroup.asUInt) :=
          Mux(backgroundGroup, lineCountStorage(1)(index),
            lineCountStorage(0)(index))
      }
    }
    nextRequestIndex(backgroundGroup.asUInt) :=
      U(0, gustCfg.lineCountWidth bits)
  }

  val currentSubrow = UInt(subrowIndexWidth bits)
  currentSubrow := (currentIndex / cfg.macLanesPerSubrow).resized
  when(state === CachedTrGtState.EmitLine && selectedElementValid) {
    for (subrow <- 0 until cfg.localBanks) {
      when(currentSubrow === subrow) {
        schedulerBPush(subrow).valid := True
        schedulerBPush(subrow).payload.valid := True
        schedulerBPush(subrow).payload.aIndex := currentIndex.resized
        schedulerBPush(subrow).payload.k := selectedElement.k
        schedulerBPush(subrow).payload.column := selectedElement.column
        schedulerBPush(subrow).payload.value := selectedElement.value
      }
    }
  }

  val startWithFirstElement = state === CachedTrGtState.EmitLine &&
    selectedElementValid && !schedulerStarted
  schedulerStart.valid := state === CachedTrGtState.Start ||
    startWithFirstElement
  when(state === CachedTrGtState.Finish) {
    schedulerFinish := True
  }

  val exclusiveEnd = currentBBase.resize(cacheCfg.addressWidth + 1) +
    currentLineCount.resize(cacheCfg.addressWidth + 1)
  val addressLimit = U(BigInt(1) << cacheCfg.addressWidth,
    cacheCfg.addressWidth + 1 bits)
  val currentAddressError = currentAValid &&
    (currentLineCount === 0 || exclusiveEnd > addressLimit)
  val fillAllocationBase = Mux(groupCollectOpen(fillGroup.asUInt),
    groupLineAllocation(fillGroup.asUInt), U(0, prefetchCountWidth bits))
  val fillAllocationEnd = fillAllocationBase +
    io.rowCommand.payload.lineCount.resize(prefetchCountWidth)
  val groupStorageOverflow = io.rowCommand.payload.a.valid &&
    fillAllocationEnd > prefetchCapacity

  io.rowCommand.ready := !io.clear && !groupReady(fillGroup.asUInt)
  when(io.rowCommand.fire) {
    doneReg := False
    when(!groupCollectOpen(fillGroup.asUInt)) {
      for (group <- 0 until groupCount; index <- 0 until cfg.macLanes) {
        when(fillGroup === (if (group == 1) True else False)) {
          aValid(group)(index) := False
        }
      }
      for (slot <- 0 until prefetchCapacity) {
        prefetchValid(fillGroup.asUInt)(slot) := False
      }
      groupCollectOpen(fillGroup.asUInt) := True
      groupLineAllocation(fillGroup.asUInt) :=
        U(0, prefetchCountWidth bits)
      aLoadIndex(fillGroup.asUInt) := U(0, scanIndexWidth bits)
      aLoadComplete(fillGroup.asUInt) := False
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
    } elsewhen(groupStorageOverflow) {
      addressErrorReg := True
      state := CachedTrGtState.Collect
      doneReg := True
    } otherwise {
      when(io.rowCommand.payload.a.valid) {
        for (group <- 0 until groupCount) {
          when(fillGroup === (if (group == 1) True else False)) {
            aValid(group)(io.rowCommand.payload.aIndex) := True
            aStorage(group)(io.rowCommand.payload.aIndex) :=
              io.rowCommand.payload.a
            bBaseStorage(group)(io.rowCommand.payload.aIndex) :=
              io.rowCommand.payload.bBaseAddress
            lineCountStorage(group)(io.rowCommand.payload.aIndex) :=
              io.rowCommand.payload.lineCount
            lineBaseStorage(group)(io.rowCommand.payload.aIndex) :=
              fillAllocationBase.resized
          }
        }
        groupLineAllocation(fillGroup.asUInt) := fillAllocationEnd
      }
      when(io.rowCommand.payload.endOfTile) {
        groupReady(fillGroup.asUInt) := True
        groupCollectOpen(fillGroup.asUInt) := False
        fillGroup := !fillGroup
      }
    }
  }
  when(state === CachedTrGtState.Collect && groupReady.asBits.orR) {
    executeGroup := Mux(groupReady(nextExecuteGroup.asUInt),
      nextExecuteGroup, !nextExecuteGroup)
    currentIndex := 0
    schedulerStarted := False
    state := CachedTrGtState.LoadA
  }
  when((executeNeedsALoad || backgroundNeedsALoad) && !loadAFound) {
    aLoadComplete(aLoadContext) := True
  }
  when(schedulerALoad.fire) {
    aLoadIndex(aLoadContext) := loadAIndex.resize(scanIndexWidth) + 1
  }
  when(state === CachedTrGtState.LoadA) {
    when(aLoadComplete(executeGroup.asUInt)) {
      currentIndex := 0
      state := CachedTrGtState.FindA
    }
  }
  when(state === CachedTrGtState.Start && schedulerStart.fire) {
    schedulerStarted := True
    state := CachedTrGtState.Finish
  }
  when(startWithFirstElement && schedulerStart.fire) {
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
      val currentWindowReady = prefetchValid(executeGroup.asUInt)(
        currentLineBase)
      val executeWindowMatches = prefetchActive(executeGroup.asUInt) &&
        prefetchAIndex(executeGroup.asUInt) === currentIndex.resized
      when(!currentWindowReady && !executeWindowMatches) {
        prefetchActive(executeGroup.asUInt) := True
        prefetchAIndex(executeGroup.asUInt) := currentIndex.resized
        prefetchLineCount(executeGroup.asUInt) := currentLineCount
        nextRequestIndex(executeGroup.asUInt) :=
          U(0, gustCfg.lineCountWidth bits)
        prefetchOccupancy(executeGroup.asUInt) :=
          U(0, prefetchCountWidth bits)
        requestOutstanding(executeGroup.asUInt) :=
          U(0, prefetchCountWidth bits)
      }
      state := Mux(currentWindowReady,
        CachedTrGtState.EmitLine, CachedTrGtState.RequestLine)
    }
  }

  val executeContext = executeGroup.asUInt
  val backgroundContext = backgroundGroup.asUInt
  val executePrefetchHasCapacity =
    prefetchOccupancy(executeContext) < prefetchCapacity
  val backgroundPrefetchHasCapacity =
    prefetchOccupancy(backgroundContext) < prefetchCapacity
  val executeWindowMatches = prefetchActive(executeContext) &&
    prefetchAIndex(executeContext) === currentIndex.resized
  val foregroundCanIssue = (state === CachedTrGtState.RequestLine ||
    state === CachedTrGtState.EmitLine) && executeWindowMatches &&
    nextRequestIndex(executeContext) < prefetchLineCount(executeContext) &&
    executePrefetchHasCapacity
  val backgroundCanIssue = groupReady(backgroundContext) &&
    prefetchActive(backgroundContext) &&
    prefetchActive(executeContext) &&
    state =/= CachedTrGtState.LoadA &&
    nextRequestIndex(backgroundContext) < prefetchLineCount(backgroundContext) &&
    backgroundPrefetchHasCapacity
  val requestGroup = Mux(foregroundCanIssue, executeGroup, backgroundGroup)
  val requestContext = requestGroup.asUInt
  val requestLineIndex = nextRequestIndex(requestContext)
  val requestAIndex = prefetchAIndex(requestContext)
  val requestBaseAddress = UInt(cacheCfg.addressWidth bits)
  requestBaseAddress := 0
  for (index <- 0 until cfg.macLanes) {
    when(requestAIndex === index) {
      requestBaseAddress := Mux(requestGroup,
        bBaseStorage(1)(index), bBaseStorage(0)(index))
    }
  }
  io.cacheRequest.valid := !io.clear &&
    (foregroundCanIssue || backgroundCanIssue)
  io.cacheRequest.payload.address :=
    (requestBaseAddress + requestLineIndex.resized).resized
  io.cacheRequest.payload.requestId :=
    (requestGroup.asBits ## requestLineIndex(lineIdWidth - 1 downto 0)
      .asBits).asUInt.resized
  when(io.cacheRequest.fire) {
    nextRequestIndex(requestContext) := requestLineIndex + 1
  }
  val responseLineIndex = io.cacheResponse.payload.requestId(
    lineIdWidth - 1 downto 0).resize(gustCfg.lineCountWidth)
  val responseGroup = io.cacheResponse.payload.requestId(lineIdWidth)
  val responseContext = responseGroup.asUInt
  val responseAIndex = prefetchAIndex(responseContext)
  val responseLineBase = UInt(prefetchSlotWidth bits)
  responseLineBase := 0
  for (index <- 0 until cfg.macLanes) {
    when(responseAIndex === index) {
      responseLineBase := lineBaseStorage(responseContext)(index)
    }
  }
  val responseStorageSlot = (responseLineBase +
    responseLineIndex.resized).resize(prefetchSlotWidth)
  val responseStorageBank = responseStorageSlot(
    prefetchSlotWidth - 1 downto prefetchBankAddressWidth)
  val responseStorageAddress = responseStorageSlot(
    prefetchBankAddressWidth - 1 downto 0)
  io.cacheResponse.ready := !io.clear && requestOutstanding.asBits.orR
  when(io.cacheResponse.fire) {
    val responseLineCount = UInt(gustCfg.lineCountWidth bits)
    responseLineCount := 0
    for (index <- 0 until cfg.macLanes) {
      when(responseAIndex === index) {
        responseLineCount := lineCountStorage(responseContext)(index)
      }
    }
    when(io.cacheResponse.payload.error ||
        !prefetchActive(responseContext) ||
        responseLineIndex >= responseLineCount) {
      memoryErrorReg := True
      state := CachedTrGtState.Finish
    } otherwise {
      for (group <- 0 until groupCount; bank <- 0 until cfg.localBanks) {
        when(responseGroup === (if (group == 1) True else False) &&
            responseStorageBank === bank) {
          prefetchDataMem(group)(bank).write(responseStorageAddress,
            io.cacheResponse.payload.data)
        }
      }
      prefetchValid(responseContext)(responseStorageSlot) := True
      when(responseGroup === executeGroup &&
          responseAIndex === currentIndex.resized &&
          state === CachedTrGtState.RequestLine &&
          responseLineIndex === requestIndex) {
        elementCursor := 0
        state := CachedTrGtState.EmitLine
      }
    }
  }
  for (group <- 0 until groupCount) {
    val groupValue = if (group == 1) True else False
    val requestFire = io.cacheRequest.fire && requestGroup === groupValue
    val responseFire = io.cacheResponse.fire && responseGroup === groupValue
    switch(requestFire ## responseFire) {
      is(B"10") {
        requestOutstanding(group) := requestOutstanding(group) + 1
      }
      is(B"01") {
        requestOutstanding(group) := requestOutstanding(group) - 1
      }
    }
  }
  when(state === CachedTrGtState.RequestLine &&
      currentLineValid) {
    elementCursor := 0
    state := CachedTrGtState.EmitLine
  }

  val laterElement = Bool()
  laterElement := False
  for (element <- 0 until gustCfg.elementsPerLine) {
    when(selectedElementValid && element > selectedElementIndex &&
        lineElements(element).valid) { laterElement := True }
  }
  val laterAChoices = Bits(cfg.macLanes bits)
  for (index <- 0 until cfg.macLanes) {
    laterAChoices(index) := U(index, scanIndexWidth bits) > currentIndex &&
      Mux(executeGroup, aValid(1)(index), aValid(0)(index))
  }
  val terminalElement = state === CachedTrGtState.EmitLine &&
    selectedElementValid && !laterElement &&
    requestIndex + 1 >= currentLineCount && !laterAChoices.orR
  val terminalElementFire = terminalElement &&
    schedulerBPush.map(_.fire).reduce(_ || _)
  when(terminalElementFire) {
    schedulerFinish := True
  }
  val bPushFire = schedulerBPush.map(_.fire).reduce(_ || _)
  val consumeCurrentLine = state === CachedTrGtState.EmitLine &&
    ((!selectedElementValid) || (selectedElementValid &&
      !laterElement && bPushFire))
  val nextLineIndex = (requestIndex + 1).resized
  when(state === CachedTrGtState.EmitLine && selectedElementValid &&
      bPushFire && laterElement) {
    elementCursor := selectedElementIndex + 1
  }
  when(consumeCurrentLine) {
    prefetchValid(executeContext)(currentPrefetchSlot) := False
    when(terminalElement) {
      currentIndex := (currentIndex + 1).resized
      state := CachedTrGtState.WaitDone
    } otherwise {
      when(requestIndex + 1 < currentLineCount) {
        requestIndex := requestIndex + 1
        elementCursor := 0
        state := Mux(prefetchValid(executeContext)(
          (currentLineBase + nextLineIndex.resized).resize(prefetchSlotWidth)),
          CachedTrGtState.EmitLine, CachedTrGtState.RequestLine)
      } otherwise {
        currentIndex := (currentIndex + 1).resized
        state := CachedTrGtState.FindA
      }
    }
  }
  for (group <- 0 until groupCount) {
    val groupValue = if (group == 1) True else False
    val requestFire = io.cacheRequest.fire && requestGroup === groupValue
    val consumeFire = consumeCurrentLine && executeGroup === groupValue
    switch(requestFire ## consumeFire) {
      is(B"10") {
        prefetchOccupancy(group) := prefetchOccupancy(group) + 1
      }
      is(B"01") {
        prefetchOccupancy(group) := prefetchOccupancy(group) - 1
      }
    }
  }

  when(state === CachedTrGtState.Finish) {
    state := CachedTrGtState.WaitDone
  }
  when(state === CachedTrGtState.WaitDone && schedulerDoneSignal &&
      !schedulerBusySignal) {
    overflowReg := schedulerOverflowSignal
    unsupportedModeReg := schedulerUnsupportedModeSignal
    doneReg := True
    groupReady(executeGroup.asUInt) := False
    prefetchActive(executeGroup.asUInt) := False
    prefetchOccupancy(executeGroup.asUInt) :=
      U(0, prefetchCountWidth bits)
    for (slot <- 0 until prefetchCapacity) {
      prefetchValid(executeGroup.asUInt)(slot) := False
    }
    nextExecuteGroup := !executeGroup
    state := CachedTrGtState.Collect
  }

  when(io.clear) {
    state := CachedTrGtState.Collect
    currentIndex := 0
    requestIndex := 0
    doneReg := False
    overflowReg := False
    memoryErrorReg := False
    addressErrorReg := False
    unsupportedModeReg := False
    schedulerStarted := False
    fillGroup := False
    executeGroup := False
    nextExecuteGroup := False
    for (group <- 0 until groupCount) {
      groupReady(group) := False
      groupCollectOpen(group) := False
      aLoadIndex(group) := 0
      aLoadComplete(group) := False
      prefetchActive(group) := False
      prefetchAIndex(group) := 0
      prefetchLineCount(group) := 0
      nextRequestIndex(group) := 0
      prefetchOccupancy(group) := 0
      requestOutstanding(group) := 0
      groupLineAllocation(group) := U(0, prefetchCountWidth bits)
      for (slot <- 0 until prefetchCapacity) {
        prefetchValid(group)(slot) := False
      }
      for (index <- 0 until cfg.macLanes) aValid(group)(index) := False
    }
  }
}

/** Packs CSR rows into subrows and allocates groups across temporal TrGT PE rows. */
class CachedTrGtRowScheduler(
    systemCfg: EndToEndSystemConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    externalSchedulers: Boolean = false
) extends Component {
  private val cfg = systemCfg.core
  private val gustCfg = systemCfg.gustavson
  private val cacheCfg = systemCfg.cache
  private val rowIndexWidth = scala.math.max(1, log2Up(cfg.peRows))

  val io = new Bundle {
    val clear = in Bool()
    val rowCommand = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val inputDone = in Bool()
    val cacheRequests = Vec(master(Stream(CacheReadRequest(cacheCfg))), cfg.peRows)
    val cacheResponses = Vec(slave(Stream(CacheReadResponse(cacheCfg))), cfg.peRows)
    val result = master(Stream(MatrixResult(cfg)))
    val allocated = out Bits(cfg.peRows bits)
    val completedTiles = out UInt(32 bits)
    val assignedTiles = out UInt(32 bits)
    val overflow = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val unsupportedMode = out Bool()
    val phase = out UInt(4 bits)
    val schedulerBusy = out Bool()
    val fifoOccupancy = out UInt(16 bits)
    val schedulerALoad = if (externalSchedulers)
      Vec(master(Stream(IndexedGustavsonA(cfg))), cfg.peRows) else null
    val schedulerStart = if (externalSchedulers)
      Vec(master(Stream(GustavsonStart())), cfg.peRows) else null
    val schedulerBPush = if (externalSchedulers)
      Vec(Vec(master(Stream(GustavsonB(cfg))), cfg.localBanks), cfg.peRows)
      else null
    val schedulerFinish = if (externalSchedulers) out Bits(cfg.peRows bits)
      else null
    val schedulerResult = if (externalSchedulers)
      Vec(slave(Stream(MatrixResult(cfg))), cfg.peRows) else null
    val schedulerBusyIn = if (externalSchedulers) in Bits(cfg.peRows bits)
      else null
    val schedulerDoneIn = if (externalSchedulers) in Bits(cfg.peRows bits)
      else null
    val schedulerOverflowIn = if (externalSchedulers) in Bits(cfg.peRows bits)
      else null
    val schedulerUnsupportedModeIn = if (externalSchedulers)
      in Bits(cfg.peRows bits) else null
    val schedulerFifoOccupancyIn = if (externalSchedulers)
      in Vec(Vec(UInt(gustCfg.tile.fifoCountWidth bits), cfg.localBanks),
        cfg.peRows) else null
  }

  val adapters = Array.fill(cfg.peRows)(
    new CachedTrGtSubrowAdapter(systemCfg, peFactory, externalSchedulers))
  // Keep one complete packed group behind the group currently executing.
  // This is the control-side half of the paper's double-buffered temporal
  // schedule; B-line data remains in the adapter's reorder window.
  private val groupFifoDepth = 2 * cfg.localBanks
  val commandFifos = Array.fill(cfg.peRows)(
    StreamFifo(CachedGustavsonBRowCommand(gustCfg), groupFifoDepth))
  val allocated = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val groupStarted = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val inputActive = RegInit(False)
  val inputRow = Reg(UInt(rowIndexWidth bits)) init 0
  val usedSubrows = Reg(UInt(log2Up(cfg.localBanks + 1) bits)) init 0
  val assignedTiles = Reg(UInt(32 bits)) init 0
  val completedTiles = Reg(UInt(32 bits)) init 0

  for (row <- 0 until cfg.peRows) {
    adapters(row).io.clear := io.clear
    commandFifos(row).io.flush := io.clear
    adapters(row).io.rowCommand << commandFifos(row).io.pop
    io.cacheRequests(row) <> adapters(row).io.cacheRequest
    adapters(row).io.cacheResponse <> io.cacheResponses(row)
    if (externalSchedulers) {
      io.schedulerALoad(row) << adapters(row).io.schedulerALoad
      io.schedulerStart(row) << adapters(row).io.schedulerStart
      for (subrow <- 0 until cfg.localBanks) {
        io.schedulerBPush(row)(subrow) <<
          adapters(row).io.schedulerBPush(subrow)
        adapters(row).io.schedulerFifoOccupancyIn(subrow) :=
          io.schedulerFifoOccupancyIn(row)(subrow)
      }
      io.schedulerFinish(row) := adapters(row).io.schedulerFinish
      adapters(row).io.schedulerResult <> io.schedulerResult(row)
      adapters(row).io.schedulerBusyIn := io.schedulerBusyIn(row)
      adapters(row).io.schedulerDoneIn := io.schedulerDoneIn(row)
      adapters(row).io.schedulerOverflowIn := io.schedulerOverflowIn(row)
      adapters(row).io.schedulerUnsupportedModeIn :=
        io.schedulerUnsupportedModeIn(row)
    }
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
  val rowLength = io.rowCommand.payload.aRowLength
  val requiredSubrows = UInt(log2Up(cfg.localBanks + 1) bits)
  requiredSubrows := ((rowLength + cfg.macLanesPerSubrow - 1) /
    cfg.macLanesPerSubrow).resized
  val startsRow = io.rowCommand.payload.aIndex === 0
  val rowFits = usedSubrows.resize(log2Up(cfg.localBanks + 2)) +
    requiredSubrows.resize(log2Up(cfg.localBanks + 2)) <= cfg.localBanks
  val flushBeforeRow = inputActive && startsRow && !rowFits
  val flushAtEnd = inputActive && io.inputDone && !io.rowCommand.valid
  val flushGroup = flushBeforeRow || flushAtEnd

  val packedCommand = CachedGustavsonBRowCommand(gustCfg)
  packedCommand.mode := io.rowCommand.payload.mode
  packedCommand.aRowLength := io.rowCommand.payload.aRowLength
  packedCommand.aIndex := (usedSubrows * cfg.macLanesPerSubrow +
    io.rowCommand.payload.aIndex).resized
  packedCommand.a := io.rowCommand.payload.a
  packedCommand.bBaseAddress := io.rowCommand.payload.bBaseAddress
  packedCommand.lineCount := io.rowCommand.payload.lineCount
  val closesFullGroup = io.rowCommand.payload.endOfTile &&
    usedSubrows.resize(log2Up(cfg.localBanks + 2)) +
      requiredSubrows.resize(log2Up(cfg.localBanks + 2)) >= cfg.localBanks
  packedCommand.endOfTile := closesFullGroup

  val flushCommand = CachedGustavsonBRowCommand(gustCfg)
  flushCommand.mode := TrapezoidDataflow.TrGt
  flushCommand.endOfTile := True
  flushCommand.aRowLength := 0
  flushCommand.aIndex := 0
  flushCommand.a := GustavsonA(cfg).getZero
  flushCommand.bBaseAddress := 0
  flushCommand.lineCount := 0
  val routedValid = inputRouteValid &&
    (flushGroup || (io.rowCommand.valid && !flushBeforeRow))
  val routedPayload = CachedGustavsonBRowCommand(gustCfg)
  routedPayload := Mux(flushGroup, flushCommand, packedCommand)
  io.rowCommand.ready := False
  for (row <- 0 until cfg.peRows) {
    commandFifos(row).io.push.valid := routedValid && selectedInputRow === row
    commandFifos(row).io.push.payload := routedPayload
    when(inputRouteValid && selectedInputRow === row) {
      io.rowCommand.ready := commandFifos(row).io.push.ready && !flushGroup
    }
  }

  val routedFire = commandFifos.zipWithIndex.map { case (fifo, row) =>
    fifo.io.push.fire && selectedInputRow === row
  }.reduce(_ || _)
  when(routedFire && flushGroup) {
    inputActive := False
    usedSubrows := 0
    assignedTiles := assignedTiles + 1
  }
  when(io.rowCommand.fire && !flushGroup) {
    when(!inputActive) {
      for (row <- 0 until cfg.peRows) {
        when(selectedInputRow === row) {
          allocated(row) := True
          groupStarted(row) := False
        }
      }
      inputRow := selectedInputRow
    }
    when(closesFullGroup) {
      inputActive := False
      usedSubrows := 0
      assignedTiles := assignedTiles + 1
    } elsewhen(io.rowCommand.payload.endOfTile) {
      inputActive := True
      usedSubrows := usedSubrows + requiredSubrows
    } otherwise {
      inputActive := True
    }
  }

  val completionBits = Bits(cfg.peRows bits)
  for (row <- 0 until cfg.peRows) {
    when(allocated(row) && adapters(row).io.busy) {
      groupStarted(row) := True
    }
    completionBits(row) := allocated(row) && groupStarted(row) &&
      adapters(row).io.done && !adapters(row).io.busy &&
      !commandFifos(row).io.pop.valid
    when(completionBits(row)) {
      allocated(row) := False
      groupStarted(row) := False
    }
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
  io.assignedTiles := assignedTiles
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
    for (row <- 0 until cfg.peRows) {
      allocated(row) := False
      groupStarted(row) := False
    }
    inputActive := False
    outputLocked := False
    usedSubrows := 0
    assignedTiles := 0
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
    val extraDmaReadRequests = if (cacheCfg.refillChannelCount > 1)
      Vec(master(Stream(CacheRefillRequest(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
    val extraDmaReadResponses = if (cacheCfg.refillChannelCount > 1)
      Vec(slave(Stream(CacheRefillResponse(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
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
    val trgtAssignedTiles = out UInt(32 bits)
    val trgtCompletedTiles = out UInt(32 bits)
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
  private val useSharedArray = hardwareProfile == EndToEndHardwareProfile.Unified
  private val requiredCacheClients =
    (if (includeIp) 2 * ipReaderLanes else 0) +
      (if (includeGustavson) 1 else 0) +
      (if (includeTrGt) cfg.peRows else 0) +
      (if (includeTrGs) 1 else 0)
  require(cacheCfg.clientCount >= requiredCacheClients,
    s"$hardwareProfile needs at least $requiredCacheClients cache clients")

  val sharedTile = if (useSharedArray) new ModeSharedTileController(
    matrixCfg.tile, gustCfg.tile, wideTrGsOutput = false) else null
  val ip = if (includeIp) new TrIpCachedMatrixController(
    matrixCfg, cacheCfg, ipReaderLanes, peFactory,
    externalTileController = useSharedArray) else null
  val csr = if (includeGustavson)
    new CsrGustavsonDescriptorFrontend(systemCfg) else null
  val trgtScheduler = if (includeTrGt)
    new CachedTrGtRowScheduler(systemCfg, peFactory,
      externalSchedulers = useSharedArray) else null
  val trgsAdapter = if (includeTrGs) new GustavsonBRowCacheAdapter(gustCfg,
    allowTrGt = false, allowTrGs = true, emitSpatialCommand = true) else null
  val trgsScheduler = if (includeTrGs) new TrGsSpatialRowScheduler(
    gustCfg.tile, peFactory, wideOutput = false,
    externalArray = useSharedArray) else null
  val cache = new ClusteredReadCache(systemCfg.clusters)
  val writeback = new ResultWritebackDma(systemCfg)

  if (includeIp) ip.io.clear := io.clear
  if (includeGustavson) csr.io.clear := io.clear
  if (includeTrGt) {
    trgtScheduler.io.clear := io.clear
    trgtScheduler.io.inputDone := csr.io.done
  }
  if (includeTrGs) {
    trgsAdapter.io.clear := io.clear
    trgsScheduler.io.clear := io.clear
  }
  cache.io.clear := io.clear
  writeback.io.clear := io.clear
  if (useSharedArray) {
    sharedTile.io.clear := io.clear
    sharedTile.io.tripALoad <> ip.io.tileALoad
    sharedTile.io.tripBLoad <> ip.io.tileBLoad
    sharedTile.io.tripStart <> ip.io.tileStart
    ip.io.tileResult <> sharedTile.io.tripResult
    ip.io.tileDone := sharedTile.io.tripDone
    ip.io.tileUnsupportedMode := sharedTile.io.tripUnsupportedMode

    for (row <- 0 until cfg.peRows) {
      sharedTile.io.trgtALoad(row) <> trgtScheduler.io.schedulerALoad(row)
      sharedTile.io.trgtStart(row) <> trgtScheduler.io.schedulerStart(row)
      for (subrow <- 0 until cfg.localBanks) {
        sharedTile.io.trgtBPush(row)(subrow) <>
          trgtScheduler.io.schedulerBPush(row)(subrow)
        trgtScheduler.io.schedulerFifoOccupancyIn(row)(subrow) :=
          sharedTile.io.trgtFifoOccupancy(row)(subrow)
      }
      sharedTile.io.trgtFinish(row) := trgtScheduler.io.schedulerFinish(row)
      trgtScheduler.io.schedulerResult(row) <>
        sharedTile.io.trgtResult(row)
      trgtScheduler.io.schedulerBusyIn(row) := sharedTile.io.trgtBusy(row)
      trgtScheduler.io.schedulerDoneIn(row) := sharedTile.io.trgtDone(row)
      trgtScheduler.io.schedulerOverflowIn(row) :=
        sharedTile.io.trgtOverflow(row)
      trgtScheduler.io.schedulerUnsupportedModeIn(row) :=
        sharedTile.io.trgtUnsupportedMode(row)

      sharedTile.io.trgsCommand(row) <>
        trgsScheduler.io.rowCommands(row)
      trgsScheduler.io.rowResults(row) <>
        sharedTile.io.trgsResult(row)
      trgsScheduler.io.rowResultLines(row) <>
        sharedTile.io.trgsResultLine(row)
    }
    trgsScheduler.io.rowBusy := sharedTile.io.trgsBusy
    trgsScheduler.io.rowDone := sharedTile.io.trgsDone
    trgsScheduler.io.rowOverflow := sharedTile.io.trgsOverflow
    trgsScheduler.io.rowUnsupportedMode :=
      sharedTile.io.trgsUnsupportedMode
  }

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
    io.trgtAssignedTiles := trgtScheduler.io.assignedTiles
    io.trgtCompletedTiles := trgtScheduler.io.completedTiles
  } else {
    io.trgtPhase := 0
    io.trgtSchedulerBusy := False
    io.trgtFifoOccupancy := 0
    io.trgtAssignedTiles := 0
    io.trgtCompletedTiles := 0
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
      trgtScheduler.io.completedTiles === trgtScheduler.io.assignedTiles &&
        !trgtScheduler.io.allocated.orR,
      trgsAdapter.io.busy === False &&
        trgsScheduler.io.completedTiles === csr.io.issuedTiles)
  } else if (includeTrGt) {
    csr.io.done &&
      trgtScheduler.io.completedTiles === trgtScheduler.io.assignedTiles &&
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
  if (cacheCfg.refillChannelCount > 1) {
    for (channel <- 0 until cacheCfg.refillChannelCount - 1) {
      io.extraDmaReadRequests(channel) <> cache.io.extraRefillRequests(channel)
      cache.io.extraRefillResponses(channel) <>
        io.extraDmaReadResponses(channel)
    }
  }
  io.dmaWriteRequest <> writeback.io.writeRequest
  writeback.io.writeResponse <> io.dmaWriteResponse

  when(io.clear) {
    state := UnifiedEndToEndState.Idle
    doneReg := False
    errorReg := False
  }
}
