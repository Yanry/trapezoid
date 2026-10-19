package trapezoid

import spinal.core._
import spinal.lib._

object GustavsonBRowCacheState extends SpinalEnum {
  val Idle, Run, AbortTile = newElement()
}

/** Converts a cached, fixed-width B-row region into Gustavson PE-row commands.
  *
  * The memory format packs each element from low to high as valid, signed
  * value, and column.  K and aIndex are supplied by the command because all B
  * elements in the region belong to the stationary A value.  TrGT and TrGS use
  * exactly the same cache path; mode is forwarded without density heuristics.
  */
class GustavsonBRowCacheAdapter(
    gustCfg: GustavsonCacheConfig,
    allowTrGt: Boolean = true,
    allowTrGs: Boolean = true,
    emitSpatialCommand: Boolean = false
) extends Component {
  private val tileCfg = gustCfg.tile
  private val cfg = tileCfg.core
  private val cacheCfg = gustCfg.cache
  private val extendedAddressWidth = cacheCfg.addressWidth + 1
  require(allowTrGt || allowTrGs, "the adapter must accept at least one mode")
  require(!emitSpatialCommand || (!allowTrGt && allowTrGs),
    "the narrow spatial output only supports a TrGS-only adapter")

  val io = new Bundle {
    val clear = in Bool()
    val rowCommand = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val cacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val cacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val peCommand = if (!emitSpatialCommand)
      master(Stream(PERowCommand(cfg))) else null
    val spatialCommand = if (emitSpatialCommand)
      master(Stream(TrGsCommand(cfg))) else null
    val busy = out Bool()
    val done = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val unsupportedMode = out Bool()
    val requestedLines = out UInt(32 bits)
    val emittedLines = out UInt(32 bits)
  }

  val state = RegInit(GustavsonBRowCacheState.Idle)
  val commandReg = Reg(CachedGustavsonBRowCommand(gustCfg))
  val lineData = Reg(Bits(cacheCfg.lineWidth bits)) init 0
  val lineBufferIndex = Reg(UInt(gustCfg.lineCountWidth bits)) init 0
  val lineBufferValid = RegInit(False)
  val requestOutstanding = RegInit(False)
  val outstandingIndex = Reg(UInt(gustCfg.lineCountWidth bits)) init 0
  val tileOpen = RegInit(False)
  val doneReg = RegInit(False)
  val memoryErrorReg = RegInit(False)
  val addressErrorReg = RegInit(False)
  val unsupportedModeReg = RegInit(False)
  val requestedLines = Reg(UInt(32 bits)) init 0
  val emittedLines = Reg(UInt(32 bits)) init 0

  io.busy := state =/= GustavsonBRowCacheState.Idle
  io.done := doneReg
  io.memoryError := memoryErrorReg
  io.addressError := addressErrorReg
  io.unsupportedMode := unsupportedModeReg
  io.requestedLines := requestedLines
  io.emittedLines := emittedLines

  val supportedInputMode =
    (if (allowTrGt) io.rowCommand.payload.mode === TrapezoidDataflow.TrGt else False) ||
    (if (allowTrGs) io.rowCommand.payload.mode === TrapezoidDataflow.TrGs else False)
  val nonEmptyInput = io.rowCommand.payload.lineCount =/= 0 &&
    io.rowCommand.payload.lineCount <= gustCfg.maxLinesPerRow
  val exclusiveEnd = io.rowCommand.payload.bBaseAddress.resize(extendedAddressWidth) +
    io.rowCommand.payload.lineCount.resize(extendedAddressWidth)
  val addressLimit = U(BigInt(1) << cacheCfg.addressWidth, extendedAddressWidth bits)
  val inputAddressError = nonEmptyInput && exclusiveEnd > addressLimit

  io.rowCommand.ready := !io.clear && state === GustavsonBRowCacheState.Idle
  when(io.rowCommand.fire) {
    commandReg := io.rowCommand.payload
    lineBufferValid := False
    requestOutstanding := False
    doneReg := False
    memoryErrorReg := False
    addressErrorReg := !nonEmptyInput || inputAddressError
    unsupportedModeReg := !supportedInputMode
    requestedLines := 0
    emittedLines := 0

    when(supportedInputMode && nonEmptyInput && !inputAddressError) {
      state := GustavsonBRowCacheState.Run
    } otherwise {
      when(tileOpen) {
        state := GustavsonBRowCacheState.AbortTile
      } otherwise {
        doneReg := True
        state := GustavsonBRowCacheState.Idle
      }
    }
  }

  val allRequestsIssued = requestedLines >= commandReg.lineCount.resize(32)
  io.cacheRequest.valid := !io.clear && state === GustavsonBRowCacheState.Run &&
    !requestOutstanding && !allRequestsIssued
  io.cacheRequest.payload.address :=
    (commandReg.bBaseAddress + requestedLines.resized).resized
  io.cacheRequest.payload.requestId := requestedLines.resized
  when(io.cacheRequest.fire) {
    outstandingIndex := requestedLines.resized
    requestedLines := requestedLines + 1
    requestOutstanding := True
  }

  io.cacheResponse.ready := !io.clear && state === GustavsonBRowCacheState.Run &&
    requestOutstanding && !lineBufferValid
  when(io.cacheResponse.fire) {
    requestOutstanding := False
    when(io.cacheResponse.payload.error ||
        io.cacheResponse.payload.requestId =/= outstandingIndex.resized) {
      memoryErrorReg := True
      when(tileOpen) {
        state := GustavsonBRowCacheState.AbortTile
      } otherwise {
        doneReg := True
        state := GustavsonBRowCacheState.Idle
      }
    } otherwise {
      lineData := io.cacheResponse.payload.data
      lineBufferIndex := outstandingIndex
      lineBufferValid := True
    }
  }

  val lastLine = lineBufferIndex === (commandReg.lineCount - 1).resized
  val abortingTile = state === GustavsonBRowCacheState.AbortTile
  val outputValid = !io.clear &&
    ((state === GustavsonBRowCacheState.Run && lineBufferValid) ||
      state === GustavsonBRowCacheState.AbortTile)
  if (!emitSpatialCommand) {
    val adapted = PERowCommand(cfg)
    adapted.mode := commandReg.mode
    adapted.endOfTile := abortingTile || (lastLine && commandReg.endOfTile)
    adapted.trip := TrIpPayload(cfg).getZero
    for (index <- 0 until cfg.macLanes) {
      adapted.gustavson.a(index).valid := !abortingTile && commandReg.a.valid &&
        commandReg.aIndex === index
      adapted.gustavson.a(index).row := commandReg.a.row
      adapted.gustavson.a(index).k := commandReg.a.k
      adapted.gustavson.a(index).value := commandReg.a.value
    }
    for (lane <- 0 until cfg.cacheLineWords) {
      if (lane < gustCfg.elementsPerLine) {
        val offset = lane * gustCfg.elementWidth
        adapted.gustavson.b(lane).valid := !abortingTile && lineData(offset)
        adapted.gustavson.b(lane).aIndex := commandReg.aIndex
        adapted.gustavson.b(lane).k := commandReg.a.k
        adapted.gustavson.b(lane).value :=
          lineData(offset + cfg.dataWidth downto offset + 1).asSInt
        adapted.gustavson.b(lane).column :=
          lineData(offset + gustCfg.elementWidth - 1 downto
            offset + 1 + cfg.dataWidth).asUInt
      } else {
        adapted.gustavson.b(lane) := GustavsonB(cfg).getZero
      }
    }
    io.peCommand.valid := outputValid
    io.peCommand.payload := adapted
  } else {
    val spatialAdapted = TrGsCommand(cfg)
    spatialAdapted.mode := commandReg.mode
    spatialAdapted.endOfTile := abortingTile ||
      (lastLine && commandReg.endOfTile)
    spatialAdapted.a.valid := !abortingTile && commandReg.a.valid
    spatialAdapted.a.row := commandReg.a.row
    spatialAdapted.a.k := commandReg.a.k
    spatialAdapted.a.value := commandReg.a.value
    for (lane <- 0 until cfg.cacheLineWords) {
      if (lane < gustCfg.elementsPerLine) {
        val offset = lane * gustCfg.elementWidth
        spatialAdapted.b(lane).valid := !abortingTile && lineData(offset)
        spatialAdapted.b(lane).k := commandReg.a.k
        spatialAdapted.b(lane).value :=
          lineData(offset + cfg.dataWidth downto offset + 1).asSInt
        spatialAdapted.b(lane).column :=
          lineData(offset + gustCfg.elementWidth - 1 downto
            offset + 1 + cfg.dataWidth).asUInt
      } else {
        spatialAdapted.b(lane) := TrGsB(cfg).getZero
      }
    }
    io.spatialCommand.valid := outputValid
    io.spatialCommand.payload := spatialAdapted
  }

  val outputFire = if (emitSpatialCommand) io.spatialCommand.fire
    else io.peCommand.fire
  when(outputFire) {
    when(state === GustavsonBRowCacheState.AbortTile) {
      tileOpen := False
      doneReg := True
      state := GustavsonBRowCacheState.Idle
    } otherwise {
      lineBufferValid := False
      emittedLines := emittedLines + 1
      when(lastLine) {
        tileOpen := !commandReg.endOfTile
        doneReg := True
        state := GustavsonBRowCacheState.Idle
      } otherwise {
        tileOpen := True
      }
    }
  }

  when(io.clear) {
    state := GustavsonBRowCacheState.Idle
    lineBufferIndex := 0
    lineBufferValid := False
    requestOutstanding := False
    outstandingIndex := 0
    tileOpen := False
    doneReg := False
    memoryErrorReg := False
    addressErrorReg := False
    unsupportedModeReg := False
    requestedLines := 0
    emittedLines := 0
  }
}

/** Integrated Gustavson read system with one cache client and shared refill. */
class CachedGustavsonSystem(gustCfg: GustavsonCacheConfig) extends Component {
  private val cfg = gustCfg.tile.core
  private val cacheCfg = gustCfg.cache

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val adapterBusy = out Bool()
    val commandDone = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val adapterUnsupportedMode = out Bool()
    val requestedLines = out UInt(32 bits)
    val emittedLines = out UInt(32 bits)
    val allocated = out Bits(cfg.peRows bits)
    val schedulerBusy = out Bits(cfg.peRows bits)
    val schedulerOverflow = out Bits(cfg.peRows bits)
    val schedulerUnsupportedMode = out Bits(cfg.peRows bits)
    val assignedTiles = out UInt(32 bits)
    val completedTiles = out UInt(32 bits)
    val cacheBankBusy = out Bits(cacheCfg.bankCount bits)
    val cacheClientOutstanding = out Bits(cacheCfg.clientCount bits)
    val cacheHitCount = out UInt(32 bits)
    val cacheMissCount = out UInt(32 bits)
    val cacheRefillCount = out UInt(32 bits)
  }

  val adapter = new GustavsonBRowCacheAdapter(gustCfg)
  val scheduler = new GustavsonRowScheduler(gustCfg.tile)
  val cache = new BankedReadCache(cacheCfg)

  adapter.io.clear := io.clear
  scheduler.io.clear := io.clear
  cache.io.clear := io.clear
  adapter.io.rowCommand <> io.command
  scheduler.io.command <> adapter.io.peCommand
  io.result <> scheduler.io.result

  cache.io.requests(0) <> adapter.io.cacheRequest
  adapter.io.cacheResponse <> cache.io.responses(0)
  for (client <- 1 until cacheCfg.clientCount) {
    cache.io.requests(client).valid := False
    cache.io.requests(client).payload := CacheReadRequest(cacheCfg).getZero
    cache.io.responses(client).ready := True
  }
  io.refillRequest <> cache.io.refillRequest
  cache.io.refillResponse <> io.refillResponse

  io.adapterBusy := adapter.io.busy
  io.commandDone := adapter.io.done
  io.memoryError := adapter.io.memoryError
  io.addressError := adapter.io.addressError
  io.adapterUnsupportedMode := adapter.io.unsupportedMode
  io.requestedLines := adapter.io.requestedLines
  io.emittedLines := adapter.io.emittedLines
  io.allocated := scheduler.io.allocated
  io.schedulerBusy := scheduler.io.busy
  io.schedulerOverflow := scheduler.io.overflow
  io.schedulerUnsupportedMode := scheduler.io.unsupportedMode
  io.assignedTiles := scheduler.io.assignedTiles
  io.completedTiles := scheduler.io.completedTiles
  io.cacheBankBusy := cache.io.bankBusy
  io.cacheClientOutstanding := cache.io.clientOutstanding
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount
}

/** TrGS-only cache system using the paper-style spatial accumulator array. */
class CachedTrGsSpatialSystem(gustCfg: GustavsonCacheConfig) extends Component {
  private val cfg = gustCfg.tile.core
  private val cacheCfg = gustCfg.cache

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val adapterBusy = out Bool()
    val commandDone = out Bool()
    val memoryError = out Bool()
    val addressError = out Bool()
    val adapterUnsupportedMode = out Bool()
    val requestedLines = out UInt(32 bits)
    val emittedLines = out UInt(32 bits)
    val allocated = out Bits(cfg.peRows bits)
    val schedulerBusy = out Bits(cfg.peRows bits)
    val schedulerOverflow = out Bits(cfg.peRows bits)
    val schedulerUnsupportedMode = out Bits(cfg.peRows bits)
    val assignedTiles = out UInt(32 bits)
    val completedTiles = out UInt(32 bits)
    val cacheBankBusy = out Bits(cacheCfg.bankCount bits)
    val cacheClientOutstanding = out Bits(cacheCfg.clientCount bits)
    val cacheHitCount = out UInt(32 bits)
    val cacheMissCount = out UInt(32 bits)
    val cacheRefillCount = out UInt(32 bits)
  }

  val adapter = new GustavsonBRowCacheAdapter(gustCfg,
    allowTrGt = false, allowTrGs = true, emitSpatialCommand = true)
  val scheduler = new TrGsSpatialRowScheduler(gustCfg.tile)
  val cache = new BankedReadCache(cacheCfg)

  adapter.io.clear := io.clear
  scheduler.io.clear := io.clear
  cache.io.clear := io.clear
  adapter.io.rowCommand <> io.command
  scheduler.io.command <> adapter.io.spatialCommand
  io.result <> scheduler.io.result

  cache.io.requests(0) <> adapter.io.cacheRequest
  adapter.io.cacheResponse <> cache.io.responses(0)
  for (client <- 1 until cacheCfg.clientCount) {
    cache.io.requests(client).valid := False
    cache.io.requests(client).payload := CacheReadRequest(cacheCfg).getZero
    cache.io.responses(client).ready := True
  }
  io.refillRequest <> cache.io.refillRequest
  cache.io.refillResponse <> io.refillResponse

  io.adapterBusy := adapter.io.busy
  io.commandDone := adapter.io.done
  io.memoryError := adapter.io.memoryError
  io.addressError := adapter.io.addressError
  io.adapterUnsupportedMode := adapter.io.unsupportedMode
  io.requestedLines := adapter.io.requestedLines
  io.emittedLines := adapter.io.emittedLines
  io.allocated := scheduler.io.allocated
  io.schedulerBusy := scheduler.io.busy
  io.schedulerOverflow := scheduler.io.overflow
  io.schedulerUnsupportedMode := scheduler.io.unsupportedMode
  io.assignedTiles := scheduler.io.assignedTiles
  io.completedTiles := scheduler.io.completedTiles
  io.cacheBankBusy := cache.io.bankBusy
  io.cacheClientOutstanding := cache.io.clientOutstanding
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount
}

object GenerateCachedGustavsonSystem extends App {
  private val core = TrapezoidConfig.small
  private val tile = GustavsonTileConfig(core)
  private val cache = CacheConfig(
    wordWidth = 32,
    wordsPerLine = 16,
    addressWidth = 24,
    bankCount = 32,
    setsPerBank = 64,
    clientCount = 1
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cached-gustavson",
    oneFilePerComponent = true
  ).generate(new CachedGustavsonSystem(GustavsonCacheConfig(tile, cache)))
}

object GenerateCachedTrGsSpatialSystem extends App {
  private val core = TrapezoidConfig.small
  private val tile = GustavsonTileConfig(core)
  private val cache = CacheConfig(
    wordWidth = 32,
    wordsPerLine = 16,
    addressWidth = 24,
    bankCount = 32,
    setsPerBank = 64,
    clientCount = 1
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cached-trgs",
    oneFilePerComponent = true
  ).generate(new CachedTrGsSpatialSystem(GustavsonCacheConfig(tile, cache)))
}
