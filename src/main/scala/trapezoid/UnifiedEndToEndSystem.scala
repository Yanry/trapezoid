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
        io.requests(client).payload.address
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
        clusters(cluster).io.refillRequest.payload.address
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
      io.refillResponse.payload.address
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
  io.command.payload.aIndex := aK.resized
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

object ResultWritebackState extends SpinalEnum {
  val Idle, Request, WaitResponse = newElement()
}

/** Writes one sparse C coordinate/value record per DMA cache line. */
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

  val state = RegInit(ResultWritebackState.Idle)
  val active = RegInit(False)
  val baseAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val requestAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val requestData = Reg(Bits(cacheCfg.lineWidth bits)) init 0
  val writtenResults = Reg(UInt(32 bits)) init 0
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)

  io.busy := active
  io.done := doneReg
  io.error := errorReg
  io.writtenResults := writtenResults
  io.start.ready := !io.clear && !active
  doneReg := False

  when(io.start.fire) {
    active := True
    baseAddress := io.start.payload
    writtenResults := 0
    errorReg := False
  }

  io.input.ready := active && state === ResultWritebackState.Idle &&
    writtenResults < systemCfg.maxCResults
  when(io.input.fire) {
    val packed = Bits(cacheCfg.lineWidth bits)
    packed := 0
    packed(0) := io.input.payload.valid
    packed(cfg.coordinateWidth downto 1) := io.input.payload.row.asBits
    packed(2 * cfg.coordinateWidth downto cfg.coordinateWidth + 1) :=
      io.input.payload.column.asBits
    packed(recordWidth - 1 downto 2 * cfg.coordinateWidth + 1) :=
      io.input.payload.value.asBits
    requestData := packed
    requestAddress := (baseAddress + writtenResults.resized).resized
    state := ResultWritebackState.Request
  }

  io.writeRequest.valid := active && state === ResultWritebackState.Request
  io.writeRequest.payload.address := requestAddress
  io.writeRequest.payload.data := requestData
  io.writeRequest.payload.mask.setAll()
  when(io.writeRequest.fire) {
    state := ResultWritebackState.WaitResponse
  }

  io.writeResponse.ready := active && state === ResultWritebackState.WaitResponse &&
    io.writeResponse.payload.address === requestAddress
  when(io.writeResponse.fire) {
    errorReg := errorReg || io.writeResponse.payload.error
    writtenResults := writtenResults + 1
    state := ResultWritebackState.Idle
  }

  when(active && io.close && state === ResultWritebackState.Idle &&
      !io.input.valid) {
    active := False
    doneReg := True
  }

  when(active && state === ResultWritebackState.Idle &&
      writtenResults === systemCfg.maxCResults && io.input.valid) {
    errorReg := True
  }

  when(io.clear) {
    state := ResultWritebackState.Idle
    active := False
    doneReg := False
    errorReg := False
    writtenResults := 0
  }
}

object UnifiedEndToEndState extends SpinalEnum {
  val Idle, StartIp, RunIp, StartGustavson, RunGustavson, CloseWriteback = newElement()
}

/** Descriptor-to-final-C-write-response scaled RTL system. */
class UnifiedEndToEndSystem(systemCfg: EndToEndSystemConfig) extends Component {
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
  }

  val state = RegInit(UnifiedEndToEndState.Idle)
  val descriptorReg = Reg(UnifiedMatrixDescriptor(systemCfg))
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)

  val ip = new TrIpCachedMatrixController(matrixCfg, cacheCfg)
  val csr = new CsrGustavsonDescriptorFrontend(systemCfg)
  val trgtAdapter = new GustavsonBRowCacheAdapter(gustCfg,
    allowTrGt = true, allowTrGs = false)
  val trgtScheduler = new GustavsonRowScheduler(gustCfg.tile)
  val trgsAdapter = new GustavsonBRowCacheAdapter(gustCfg,
    allowTrGt = false, allowTrGs = true, emitSpatialCommand = true)
  val trgsScheduler = new TrGsSpatialRowScheduler(gustCfg.tile, wideOutput = false)
  val cache = new ClusteredReadCache(systemCfg.clusters)
  val writeback = new ResultWritebackDma(systemCfg)

  ip.io.clear := io.clear
  csr.io.clear := io.clear
  trgtAdapter.io.clear := io.clear
  trgtScheduler.io.clear := io.clear
  trgsAdapter.io.clear := io.clear
  trgsScheduler.io.clear := io.clear
  cache.io.clear := io.clear
  writeback.io.clear := io.clear

  io.busy := state =/= UnifiedEndToEndState.Idle
  io.done := doneReg
  io.error := errorReg || writeback.io.error
  io.writtenResults := writeback.io.writtenResults
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount
  doneReg := False

  val descriptorModeIsIp = io.descriptor.payload.mode === TrapezoidDataflow.DenseIp ||
    io.descriptor.payload.mode === TrapezoidDataflow.TrIp
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
    errorReg := cAddressError
    when(cAddressError) {
      state := UnifiedEndToEndState.CloseWriteback
    } otherwise {
      state := Mux(descriptorModeIsIp,
        UnifiedEndToEndState.StartIp, UnifiedEndToEndState.StartGustavson)
    }
  }

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

  csr.io.descriptor.valid := state === UnifiedEndToEndState.StartGustavson
  csr.io.descriptor.payload := descriptorReg
  when(csr.io.descriptor.fire) { state := UnifiedEndToEndState.RunGustavson }

  val runTrGt = descriptorReg.mode === TrapezoidDataflow.TrGt
  trgtAdapter.io.rowCommand.valid := csr.io.command.valid && runTrGt
  trgtAdapter.io.rowCommand.payload := csr.io.command.payload
  trgsAdapter.io.rowCommand.valid := csr.io.command.valid && !runTrGt
  trgsAdapter.io.rowCommand.payload := csr.io.command.payload
  csr.io.command.ready := Mux(runTrGt,
    trgtAdapter.io.rowCommand.ready, trgsAdapter.io.rowCommand.ready)
  trgtScheduler.io.command <> trgtAdapter.io.peCommand
  trgsScheduler.io.command <> trgsAdapter.io.spatialCommand

  val selectedResult = MatrixResult(cfg)
  selectedResult := MatrixResult(cfg).getZero
  val selectedResultValid = Bool()
  selectedResultValid := False
  ip.io.result.ready := False
  trgtScheduler.io.result.ready := False
  trgsScheduler.io.result.ready := False
  trgsScheduler.io.resultLine.ready := False
  when(state === UnifiedEndToEndState.RunIp) {
    selectedResult := ip.io.result.payload.element
    selectedResultValid := ip.io.result.valid && ip.io.result.payload.element.valid
    ip.io.result.ready := Mux(ip.io.result.payload.element.valid,
      writeback.io.input.ready, True)
  } elsewhen(state === UnifiedEndToEndState.RunGustavson && runTrGt) {
    selectedResult := trgtScheduler.io.result.payload
    selectedResultValid := trgtScheduler.io.result.valid &&
      trgtScheduler.io.result.payload.valid
    trgtScheduler.io.result.ready := Mux(trgtScheduler.io.result.payload.valid,
      writeback.io.input.ready, True)
  } elsewhen(state === UnifiedEndToEndState.RunGustavson) {
    selectedResult := trgsScheduler.io.result.payload
    selectedResultValid := trgsScheduler.io.result.valid &&
      trgsScheduler.io.result.payload.valid
    trgsScheduler.io.result.ready := Mux(trgsScheduler.io.result.payload.valid,
      writeback.io.input.ready, True)
  }
  writeback.io.input.valid := selectedResultValid
  writeback.io.input.payload := selectedResult

  val gustavsonCompleted = csr.io.done &&
    trgtAdapter.io.busy === False && trgsAdapter.io.busy === False &&
    Mux(runTrGt,
      trgtScheduler.io.completedTiles === csr.io.issuedTiles,
      trgsScheduler.io.completedTiles === csr.io.issuedTiles)
  when(state === UnifiedEndToEndState.RunIp && ip.io.done) {
    errorReg := ip.io.descriptorError || ip.io.addressError ||
      ip.io.innerError || ip.io.memoryError
    state := UnifiedEndToEndState.CloseWriteback
  }
  when(state === UnifiedEndToEndState.RunGustavson && gustavsonCompleted) {
    errorReg := csr.io.error || trgtAdapter.io.memoryError ||
      trgtAdapter.io.addressError || trgtAdapter.io.unsupportedMode ||
      trgsAdapter.io.memoryError || trgsAdapter.io.addressError ||
      trgsAdapter.io.unsupportedMode || trgtScheduler.io.overflow.orR ||
      trgtScheduler.io.unsupportedMode.orR || trgsScheduler.io.overflow.orR ||
      trgsScheduler.io.unsupportedMode.orR
    state := UnifiedEndToEndState.CloseWriteback
  }

  writeback.io.close := state === UnifiedEndToEndState.CloseWriteback
  when(state === UnifiedEndToEndState.CloseWriteback && writeback.io.done) {
    doneReg := True
    state := UnifiedEndToEndState.Idle
  }

  cache.io.requests(0) <> ip.io.aCacheRequest
  ip.io.aCacheResponse <> cache.io.responses(0)
  cache.io.requests(1) <> ip.io.bCacheRequest
  ip.io.bCacheResponse <> cache.io.responses(1)
  cache.io.requests(2) <> csr.io.cacheRequest
  csr.io.cacheResponse <> cache.io.responses(2)
  cache.io.requests(3) <> trgtAdapter.io.cacheRequest
  trgtAdapter.io.cacheResponse <> cache.io.responses(3)
  cache.io.requests(4) <> trgsAdapter.io.cacheRequest
  trgsAdapter.io.cacheResponse <> cache.io.responses(4)
  for (client <- 5 until cacheCfg.clientCount) {
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
