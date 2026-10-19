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
  * elements in the region belong to the stationary A value. TrGT serializes a
  * line into its owning temporal subrow, while TrGS broadcasts A and presents
  * the whole cache line spatially.
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
  private val elementIndexWidth = scala.math.max(1,
    log2Up(gustCfg.elementsPerLine))
  val lineElementCursor = Reg(UInt(elementIndexWidth bits)) init 0
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
    lineElementCursor := 0
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
      lineElementCursor := 0
    }
  }

  val lastLine = lineBufferIndex === (commandReg.lineCount - 1).resized
  val abortingTile = state === GustavsonBRowCacheState.AbortTile
  val trGtMode = commandReg.mode === TrapezoidDataflow.TrGt
  val lineElements = Vec(TrGsB(cfg), gustCfg.elementsPerLine)
  for (element <- 0 until gustCfg.elementsPerLine) {
    val offset = element * gustCfg.elementWidth
    lineElements(element).valid := lineData(offset)
    lineElements(element).k := commandReg.a.k
    lineElements(element).value :=
      lineData(offset + cfg.dataWidth downto offset + 1).asSInt
    lineElements(element).column :=
      lineData(offset + gustCfg.elementWidth - 1 downto
        offset + 1 + cfg.dataWidth).asUInt
  }

  val selectedElementIndex = UInt(elementIndexWidth bits)
  selectedElementIndex := 0
  var noSelectedElement: Bool = True
  for (element <- 0 until gustCfg.elementsPerLine) {
    val selectable = lineElements(element).valid &&
      lineElementCursor <= element
    when(noSelectedElement && selectable) {
      selectedElementIndex := element
    }
    noSelectedElement = noSelectedElement && !selectable
  }
  val selectedElementValid = !noSelectedElement
  val selectedElement = lineElements(selectedElementIndex)
  val laterElementBits = Bits(gustCfg.elementsPerLine bits)
  for (element <- 0 until gustCfg.elementsPerLine) {
    laterElementBits(element) := lineElements(element).valid &&
      selectedElementValid && element > selectedElementIndex
  }
  val trGtLineComplete = !selectedElementValid || !laterElementBits.orR
  val selectedSubrow = UInt(log2Up(cfg.localBanks) bits)
  selectedSubrow := 0
  for (subrow <- 0 until cfg.localBanks) {
    val firstA = subrow * cfg.macLanesPerSubrow
    val lastA = firstA + cfg.macLanesPerSubrow
    val inSubrow = if (subrow == cfg.localBanks - 1)
      commandReg.aIndex >= firstA
    else
      commandReg.aIndex >= firstA && commandReg.aIndex < lastA
    when(inSubrow) {
      selectedSubrow := subrow
    }
  }
  val outputValid = !io.clear &&
    ((state === GustavsonBRowCacheState.Run && lineBufferValid) ||
      state === GustavsonBRowCacheState.AbortTile)
  if (!emitSpatialCommand) {
    val adapted = PERowCommand(cfg)
    adapted.mode := commandReg.mode
    adapted.endOfTile := abortingTile ||
      (lastLine && commandReg.endOfTile && (!trGtMode || trGtLineComplete))
    adapted.trip := TrIpPayload(cfg).getZero
    for (index <- 0 until cfg.macLanes) {
      adapted.gustavson.a(index).valid := !abortingTile && commandReg.a.valid &&
        Mux(trGtMode, commandReg.aIndex === index,
          if (index == 0) True else False)
      adapted.gustavson.a(index).row := commandReg.a.row
      adapted.gustavson.a(index).k := commandReg.a.k
      adapted.gustavson.a(index).value := commandReg.a.value
    }
    for (lane <- 0 until cfg.cacheLineWords) {
      adapted.gustavson.b(lane) := GustavsonB(cfg).getZero
      if (lane < cfg.localBanks) {
        when(trGtMode && selectedElementValid && selectedSubrow === lane) {
          adapted.gustavson.b(lane).valid := !abortingTile
          adapted.gustavson.b(lane).aIndex := commandReg.aIndex
          adapted.gustavson.b(lane).k := selectedElement.k
          adapted.gustavson.b(lane).column := selectedElement.column
          adapted.gustavson.b(lane).value := selectedElement.value
        }
      }
      if (lane < gustCfg.elementsPerLine) {
        when(!trGtMode) {
          adapted.gustavson.b(lane).valid := !abortingTile &&
            lineElements(lane).valid
          adapted.gustavson.b(lane).aIndex := 0
          adapted.gustavson.b(lane).k := lineElements(lane).k
          adapted.gustavson.b(lane).column := lineElements(lane).column
          adapted.gustavson.b(lane).value := lineElements(lane).value
        }
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
      when(trGtMode && !trGtLineComplete) {
        lineElementCursor := selectedElementIndex + 1
      } otherwise {
        lineBufferValid := False
        lineElementCursor := 0
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
  }

  when(io.clear) {
    state := GustavsonBRowCacheState.Idle
    lineBufferIndex := 0
    lineBufferValid := False
    lineElementCursor := 0
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

/** Multi-client TrGS system partitioned into cache/PE-row clusters. */
class ClusteredCachedTrGsSpatialSystem(
    gustCfg: GustavsonCacheConfig,
    wideWriteback: Boolean = false
)
    extends Component {
  private val cfg = gustCfg.tile.core
  private val cacheCfg = gustCfg.cache
  private val clusterCount = cacheCfg.clientCount
  private val rowsPerCluster = cfg.peRows / clusterCount
  private val clusterIndexWidth = scala.math.max(1, log2Up(clusterCount))
  require(clusterCount <= cfg.peRows,
    "the TrGS cluster count cannot exceed the number of PE rows")
  require(cfg.peRows % clusterCount == 0,
    "PE rows must divide evenly across TrGS cache clusters")

  private val localCore = cfg.copy(peRows = rowsPerCluster)
  private val localTile = gustCfg.tile.copy(core = localCore)
  private val localGustCfg = gustCfg.copy(tile = localTile)

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val resultLine = master(Stream(MatrixResultLine(cfg)))
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val clusterOwned = out Bits(clusterCount bits)
    val adapterBusy = out Bits(clusterCount bits)
    val commandDone = out Bits(clusterCount bits)
    val memoryError = out Bits(clusterCount bits)
    val addressError = out Bits(clusterCount bits)
    val adapterUnsupportedMode = out Bits(clusterCount bits)
    val requestedLines = out Vec(UInt(32 bits), clusterCount)
    val emittedLines = out Vec(UInt(32 bits), clusterCount)
    val allocated = out Bits(cfg.peRows bits)
    val schedulerBusy = out Bits(cfg.peRows bits)
    val schedulerOverflow = out Bits(cfg.peRows bits)
    val schedulerUnsupportedMode = out Bits(cfg.peRows bits)
    val assignedTiles = out UInt(32 bits)
    val completedTiles = out UInt(32 bits)
    val cacheBankBusy = out Bits(cacheCfg.bankCount bits)
    val cacheClientOutstanding = out Bits(clusterCount bits)
    val cacheHitCount = out UInt(32 bits)
    val cacheMissCount = out UInt(32 bits)
    val cacheRefillCount = out UInt(32 bits)
  }

  val adapters = Array.fill(clusterCount)(new GustavsonBRowCacheAdapter(
    localGustCfg, allowTrGt = false, allowTrGs = true,
    emitSpatialCommand = true))
  val schedulers = Array.fill(clusterCount)(
    new TrGsSpatialRowScheduler(localTile, wideOutput = wideWriteback))
  val cache = new BankedReadCache(cacheCfg)
  cache.io.clear := io.clear
  io.refillRequest <> cache.io.refillRequest
  cache.io.refillResponse <> io.refillResponse

  for (cluster <- 0 until clusterCount) {
    adapters(cluster).io.clear := io.clear
    schedulers(cluster).io.clear := io.clear
    schedulers(cluster).io.command <> adapters(cluster).io.spatialCommand
    cache.io.requests(cluster) <> adapters(cluster).io.cacheRequest
    adapters(cluster).io.cacheResponse <> cache.io.responses(cluster)
    io.adapterBusy(cluster) := adapters(cluster).io.busy
    io.commandDone(cluster) := adapters(cluster).io.done
    io.memoryError(cluster) := adapters(cluster).io.memoryError
    io.addressError(cluster) := adapters(cluster).io.addressError
    io.adapterUnsupportedMode(cluster) := adapters(cluster).io.unsupportedMode
    io.requestedLines(cluster) := adapters(cluster).io.requestedLines
    io.emittedLines(cluster) := adapters(cluster).io.emittedLines
    for (row <- 0 until rowsPerCluster) {
      val globalRow = cluster * rowsPerCluster + row
      io.allocated(globalRow) := schedulers(cluster).io.allocated(row)
      io.schedulerBusy(globalRow) := schedulers(cluster).io.busy(row)
      io.schedulerOverflow(globalRow) := schedulers(cluster).io.overflow(row)
      io.schedulerUnsupportedMode(globalRow) :=
        schedulers(cluster).io.unsupportedMode(row)
    }
  }

  val clusterOwned = Vec((0 until clusterCount).map(_ => RegInit(False)))
  val clusterClosing = Vec((0 until clusterCount).map(_ => RegInit(False)))
  val completionBase = Vec((0 until clusterCount).map(_ =>
    Reg(UInt(32 bits)) init 0))
  val inputActive = RegInit(False)
  val inputCluster = Reg(UInt(clusterIndexWidth bits)) init 0
  io.clusterOwned := clusterOwned.asBits

  val freeChoices = Vec(Bool(), clusterCount)
  var earlierFree: Bool = False
  for (cluster <- 0 until clusterCount) {
    val available = !clusterOwned(cluster) &&
      adapters(cluster).io.rowCommand.ready
    freeChoices(cluster) := available && !earlierFree
    earlierFree = earlierFree || available
  }
  val freeFound = freeChoices.asBits.orR
  val freeCluster = UInt(clusterIndexWidth bits)
  freeCluster := 0
  for (cluster <- 0 until clusterCount) {
    when(freeChoices(cluster)) { freeCluster := cluster }
  }

  val selectedInputCluster = UInt(clusterIndexWidth bits)
  selectedInputCluster := Mux(inputActive, inputCluster, freeCluster)
  val inputRouteValid = inputActive || freeFound
  io.command.ready := False
  for (cluster <- 0 until clusterCount) {
    adapters(cluster).io.rowCommand.valid := io.command.valid &&
      inputRouteValid && selectedInputCluster === cluster
    adapters(cluster).io.rowCommand.payload := io.command.payload
    when(inputRouteValid && selectedInputCluster === cluster) {
      io.command.ready := adapters(cluster).io.rowCommand.ready
    }
  }

  when(io.command.fire) {
    when(!inputActive) {
      for (cluster <- 0 until clusterCount) {
        when(selectedInputCluster === cluster) {
          clusterOwned(cluster) := True
          completionBase(cluster) := schedulers(cluster).io.completedTiles
        }
      }
    }
    when(io.command.payload.endOfTile) {
      inputActive := False
      for (cluster <- 0 until clusterCount) {
        when(selectedInputCluster === cluster) {
          clusterClosing(cluster) := True
        }
      }
    } otherwise {
      inputActive := True
      inputCluster := selectedInputCluster
    }
  }

  for (cluster <- 0 until clusterCount) {
    val adapterFailed = adapters(cluster).io.memoryError ||
      adapters(cluster).io.addressError || adapters(cluster).io.unsupportedMode
    val rejectedBeforeAllocation = clusterOwned(cluster) &&
      adapters(cluster).io.done && adapterFailed &&
      !schedulers(cluster).io.allocated.orR
    val tileCompleted = clusterOwned(cluster) && clusterClosing(cluster) &&
      schedulers(cluster).io.completedTiles =/= completionBase(cluster)
    when(rejectedBeforeAllocation || tileCompleted) {
      clusterOwned(cluster) := False
      clusterClosing(cluster) := False
      when(inputActive && inputCluster === cluster) { inputActive := False }
    }
  }

  val assignedSum = schedulers.map(_.io.assignedTiles)
    .reduce(_ + _).resize(32)
  val completedSum = schedulers.map(_.io.completedTiles)
    .reduce(_ + _).resize(32)
  io.assignedTiles := assignedSum
  io.completedTiles := completedSum

  val outputChoices = Vec(Bool(), clusterCount)
  var earlierOutput: Bool = False
  for (cluster <- 0 until clusterCount) {
    val clusterValid = if (wideWriteback) schedulers(cluster).io.resultLine.valid
      else schedulers(cluster).io.result.valid
    outputChoices(cluster) := clusterValid && !earlierOutput
    earlierOutput = earlierOutput || clusterValid
  }
  val outputFound = outputChoices.asBits.orR
  val chosenOutputCluster = UInt(clusterIndexWidth bits)
  chosenOutputCluster := 0
  for (cluster <- 0 until clusterCount) {
    when(outputChoices(cluster)) { chosenOutputCluster := cluster }
  }

  val outputLocked = RegInit(False)
  val outputCluster = Reg(UInt(clusterIndexWidth bits)) init 0
  val selectedOutputCluster = UInt(clusterIndexWidth bits)
  selectedOutputCluster := Mux(outputLocked, outputCluster, chosenOutputCluster)
  val outputRouteValid = !io.clear && (outputLocked || outputFound)
  io.result.valid := False
  io.result.payload := MatrixResult(cfg).getZero
  io.resultLine.valid := False
  io.resultLine.payload := MatrixResultLine(cfg).getZero
  for (cluster <- 0 until clusterCount) {
    if (wideWriteback) {
      schedulers(cluster).io.result.ready := False
      schedulers(cluster).io.resultLine.ready := io.resultLine.ready &&
        outputRouteValid && selectedOutputCluster === cluster
      when(outputRouteValid && selectedOutputCluster === cluster) {
        io.resultLine.valid := schedulers(cluster).io.resultLine.valid
        io.resultLine.payload := schedulers(cluster).io.resultLine.payload
      }
    } else {
      schedulers(cluster).io.resultLine.ready := False
      schedulers(cluster).io.result.ready := io.result.ready &&
        outputRouteValid && selectedOutputCluster === cluster
      when(outputRouteValid && selectedOutputCluster === cluster) {
        io.result.valid := schedulers(cluster).io.result.valid
        io.result.payload := schedulers(cluster).io.result.payload
      }
    }
  }
  when(!outputLocked && outputFound) {
    outputLocked := True
    outputCluster := chosenOutputCluster
  }
  if (wideWriteback) {
    when(io.resultLine.fire && io.resultLine.payload.endOfTile) {
      outputLocked := False
    }
  } else {
    when(io.result.fire && io.result.payload.endOfTile) {
      outputLocked := False
    }
  }

  io.cacheBankBusy := cache.io.bankBusy
  io.cacheClientOutstanding := cache.io.clientOutstanding
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount

  when(io.clear) {
    for (cluster <- 0 until clusterCount) {
      clusterOwned(cluster) := False
      clusterClosing(cluster) := False
      completionBase(cluster) := 0
    }
    inputActive := False
    outputLocked := False
  }
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

object GenerateClusteredCachedTrGsSpatialSystem extends App {
  private val core = TrapezoidConfig.small
  private val tile = GustavsonTileConfig(core)
  private val cache = CacheConfig(
    wordWidth = 32,
    wordsPerLine = 16,
    addressWidth = 24,
    bankCount = 32,
    setsPerBank = 64,
    clientCount = 2
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-clustered-cached-trgs",
    oneFilePerComponent = true
  ).generate(new ClusteredCachedTrGsSpatialSystem(
    GustavsonCacheConfig(tile, cache), wideWriteback = true))
}
