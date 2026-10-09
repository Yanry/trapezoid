package trapezoid

import spinal.core._
import spinal.lib._

object FiberCacheReaderState extends SpinalEnum {
  val Idle, Request, WaitResponse, Emit = newElement()
}

/** Reassembles a fixed-width compressed-fiber record from cache lines.
  *
  * Record bit layout, from least to most significant, is: valid, mask,
  * `fiberWidth` signed value slots, then tag.  Cache lines are fetched least
  * significant first.  The value slots remain fixed-width even when the mask
  * contains fewer nonzeros, which keeps address generation deterministic.
  */
class FiberCacheReader(fiberCacheCfg: FiberCacheConfig, indexWidth: Int)
    extends Component {
  private val cfg = fiberCacheCfg.core
  private val cacheCfg = fiberCacheCfg.cache
  private val maskOffset = 1
  private val valuesOffset = maskOffset + cfg.fiberWidth
  private val tagOffset = valuesOffset + cfg.fiberWidth * cfg.dataWidth

  require(indexWidth > 0)

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(FiberCacheReadCommand(fiberCacheCfg, indexWidth)))
    val cacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val cacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val result = master(Stream(FiberCacheReadResult(fiberCacheCfg, indexWidth)))
    val busy = out Bool()
  }

  val state = RegInit(FiberCacheReaderState.Idle)
  val commandReg = Reg(FiberCacheReadCommand(fiberCacheCfg, indexWidth))
  val lineIndex = Reg(UInt(fiberCacheCfg.lineIndexWidth bits)) init 0
  val record = Reg(Bits(fiberCacheCfg.recordWidth bits)) init 0
  val errorReg = RegInit(False)

  io.busy := state =/= FiberCacheReaderState.Idle
  io.command.ready := !io.clear && state === FiberCacheReaderState.Idle

  io.cacheRequest.valid := !io.clear && state === FiberCacheReaderState.Request
  io.cacheRequest.payload.address :=
    (commandReg.address + lineIndex.resize(cacheCfg.addressWidth)).resized
  io.cacheRequest.payload.requestId := lineIndex.resize(cacheCfg.requestIdWidth)

  io.cacheResponse.ready := !io.clear &&
    state === FiberCacheReaderState.WaitResponse &&
    io.cacheResponse.payload.requestId === lineIndex.resize(cacheCfg.requestIdWidth)

  io.result.valid := !io.clear && state === FiberCacheReaderState.Emit
  io.result.payload.index := commandReg.index
  io.result.payload.error := errorReg
  io.result.payload.fiber.valid := record(0) && !errorReg
  io.result.payload.fiber.mask :=
    record(maskOffset + cfg.fiberWidth - 1 downto maskOffset)
  for (entry <- 0 until cfg.fiberWidth) {
    val low = valuesOffset + entry * cfg.dataWidth
    io.result.payload.fiber.values(entry) :=
      record(low + cfg.dataWidth - 1 downto low).asSInt
  }
  io.result.payload.fiber.tag :=
    record(tagOffset + cfg.coordinateWidth - 1 downto tagOffset).asUInt

  when(io.command.fire) {
    commandReg := io.command.payload
    lineIndex := 0
    record := 0
    errorReg := False
    state := FiberCacheReaderState.Request
  }

  when(io.cacheRequest.fire) {
    state := FiberCacheReaderState.WaitResponse
  }

  for (line <- 0 until fiberCacheCfg.linesPerFiber) {
    val low = line * cacheCfg.lineWidth
    val width = scala.math.min(cacheCfg.lineWidth, fiberCacheCfg.recordWidth - low)
    when(io.cacheResponse.fire && lineIndex === line) {
      record(low + width - 1 downto low) :=
        io.cacheResponse.payload.data(width - 1 downto 0)
    }
  }

  when(io.cacheResponse.fire) {
    errorReg := errorReg || io.cacheResponse.payload.error
    when(lineIndex === fiberCacheCfg.linesPerFiber - 1) {
      state := FiberCacheReaderState.Emit
    } otherwise {
      lineIndex := lineIndex + 1
      state := FiberCacheReaderState.Request
    }
  }

  when(io.result.fire) {
    state := FiberCacheReaderState.Idle
  }

  when(io.clear) {
    state := FiberCacheReaderState.Idle
    lineIndex := 0
    record := 0
    errorReg := False
  }
}

/** Converts one matrix controller load phase into two cache request streams.
  *
  * A uses row-major `(globalRow, kTile)` records.  B uses transposed
  * `(kTile, globalColumn)` records.  Both layouts reserve their configured
  * maximum dimension, so addresses do not depend on runtime matrix sizes.
  */
class TrIpMatrixFiberLoader(matrixCfg: TrIpMatrixConfig, cacheCfg: CacheConfig)
    extends Component {
  private val tileCfg = matrixCfg.tile
  private val cfg = tileCfg.core
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)
  private val addressSpace = BigInt(1) << cacheCfg.addressWidth

  require(BigInt(matrixCfg.maxMatrixRows) * matrixCfg.maxKTiles *
    fiberCacheCfg.linesPerFiber <= addressSpace,
    "A fiber region must fit the cache line address space")
  require(BigInt(matrixCfg.maxKTiles) * matrixCfg.maxMatrixColumns *
    fiberCacheCfg.linesPerFiber <= addressSpace,
    "B fiber region must fit the cache line address space")

  val io = new Bundle {
    val clear = in Bool()
    val start = slave(Stream(TrIpMatrixFiberLoad(matrixCfg, cacheCfg)))
    val aCacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val aCacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val bCacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val bCacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val aLoad = master(Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth)))
    val bLoad = master(Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth)))
    val busy = out Bool()
    val done = out Bool()
    val error = out Bool()
  }

  val aReader = new FiberCacheReader(fiberCacheCfg, tileCfg.rowIndexWidth)
  val bReader = new FiberCacheReader(fiberCacheCfg, tileCfg.columnIndexWidth)
  aReader.io.clear := io.clear
  bReader.io.clear := io.clear

  val active = RegInit(False)
  val doneReg = RegInit(False)
  val errorReg = RegInit(False)
  val commandReg = Reg(TrIpMatrixFiberLoad(matrixCfg, cacheCfg))
  val aIssued = Reg(UInt(tileCfg.rowCountWidth bits)) init 0
  val bIssued = Reg(UInt(tileCfg.columnCountWidth bits)) init 0
  val aReceived = Reg(UInt(tileCfg.rowCountWidth bits)) init 0
  val bReceived = Reg(UInt(tileCfg.columnCountWidth bits)) init 0

  io.busy := active
  io.done := doneReg
  io.error := errorReg
  io.start.ready := !io.clear && !active

  val globalARow = UInt(cacheCfg.addressWidth bits)
  val aOrdinal = UInt(cacheCfg.addressWidth bits)
  val bOrdinal = UInt(cacheCfg.addressWidth bits)
  globalARow := (commandReg.rowBase.resize(cacheCfg.addressWidth) +
    aIssued.resize(cacheCfg.addressWidth)).resized
  aOrdinal := ((globalARow * U(matrixCfg.maxKTiles, cacheCfg.addressWidth bits)) +
    commandReg.kTileIndex.resize(cacheCfg.addressWidth)).resized
  bOrdinal := ((commandReg.kTileIndex.resize(cacheCfg.addressWidth) *
    U(matrixCfg.maxMatrixColumns, cacheCfg.addressWidth bits)) +
    commandReg.columnBase.resize(cacheCfg.addressWidth) +
    bIssued.resize(cacheCfg.addressWidth)).resized

  aReader.io.command.valid := active && aIssued < commandReg.rowCount
  aReader.io.command.payload.address := (commandReg.aBaseAddress +
    (aOrdinal * U(fiberCacheCfg.linesPerFiber,
      cacheCfg.addressWidth bits)).resize(cacheCfg.addressWidth)).resized
  aReader.io.command.payload.index := aIssued.resize(tileCfg.rowIndexWidth)

  bReader.io.command.valid := active && bIssued < commandReg.columnCount
  bReader.io.command.payload.address := (commandReg.bBaseAddress +
    (bOrdinal * U(fiberCacheCfg.linesPerFiber,
      cacheCfg.addressWidth bits)).resize(cacheCfg.addressWidth)).resized
  bReader.io.command.payload.index := bIssued.resize(tileCfg.columnIndexWidth)

  when(aReader.io.command.fire) { aIssued := aIssued + 1 }
  when(bReader.io.command.fire) { bIssued := bIssued + 1 }

  io.aLoad.valid := active && aReader.io.result.valid
  io.aLoad.payload.index := aReader.io.result.payload.index
  io.aLoad.payload.fiber := aReader.io.result.payload.fiber
  aReader.io.result.ready := active && io.aLoad.ready

  io.bLoad.valid := active && bReader.io.result.valid
  io.bLoad.payload.index := bReader.io.result.payload.index
  io.bLoad.payload.fiber := bReader.io.result.payload.fiber
  bReader.io.result.ready := active && io.bLoad.ready

  when(aReader.io.result.fire) {
    aReceived := aReceived + 1
  }
  when(bReader.io.result.fire) {
    bReceived := bReceived + 1
  }
  when(aReader.io.result.fire || bReader.io.result.fire) {
    errorReg := errorReg ||
      (aReader.io.result.fire && aReader.io.result.payload.error) ||
      (bReader.io.result.fire && bReader.io.result.payload.error)
  }

  val aCompletes = aReceived === commandReg.rowCount ||
    (aReader.io.result.fire &&
      (aReceived + 1).resized === commandReg.rowCount)
  val bCompletes = bReceived === commandReg.columnCount ||
    (bReader.io.result.fire &&
      (bReceived + 1).resized === commandReg.columnCount)

  doneReg := False
  when(io.start.fire) {
    commandReg := io.start.payload
    aIssued := 0
    bIssued := 0
    aReceived := 0
    bReceived := 0
    errorReg := False
    when(io.start.payload.rowCount === 0 ||
        io.start.payload.columnCount === 0 ||
        io.start.payload.rowCount > tileCfg.maxRows ||
        io.start.payload.columnCount > tileCfg.maxColumns) {
      active := False
      errorReg := True
      doneReg := True
    } otherwise {
      active := True
    }
  }

  when(active && aCompletes && bCompletes) {
    active := False
    doneReg := True
  }

  io.aCacheRequest.valid := aReader.io.cacheRequest.valid
  io.aCacheRequest.payload := aReader.io.cacheRequest.payload
  aReader.io.cacheRequest.ready := io.aCacheRequest.ready
  aReader.io.cacheResponse.valid := io.aCacheResponse.valid
  aReader.io.cacheResponse.payload := io.aCacheResponse.payload
  io.aCacheResponse.ready := aReader.io.cacheResponse.ready

  io.bCacheRequest.valid := bReader.io.cacheRequest.valid
  io.bCacheRequest.payload := bReader.io.cacheRequest.payload
  bReader.io.cacheRequest.ready := io.bCacheRequest.ready
  bReader.io.cacheResponse.valid := io.bCacheResponse.valid
  bReader.io.cacheResponse.payload := io.bCacheResponse.payload
  io.bCacheResponse.ready := bReader.io.cacheResponse.ready

  when(io.clear) {
    active := False
    doneReg := False
    errorReg := False
    aIssued := 0
    bIssued := 0
    aReceived := 0
    bReceived := 0
  }
}

/** Matrix-level TrIP controller with automatic cache-backed fiber loading. */
class TrIpCachedMatrixController(
    matrixCfg: TrIpMatrixConfig,
    cacheCfg: CacheConfig
) extends Component {
  private val tileCfg = matrixCfg.tile
  private val cfg = tileCfg.core
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)
  private val maximumRegionLines = Seq(
    BigInt(matrixCfg.maxMatrixRows) * matrixCfg.maxKTiles,
    BigInt(matrixCfg.maxKTiles) * matrixCfg.maxMatrixColumns
  ).max * fiberCacheCfg.linesPerFiber
  private val addressCalcWidth = cacheCfg.addressWidth +
    scala.math.max(1, log2Up(maximumRegionLines + 1)) + 1

  val io = new Bundle {
    val clear = in Bool()
    val descriptor = slave(Stream(CachedTrIpMatrixDescriptor(matrixCfg, cacheCfg)))
    val aCacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val aCacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val bCacheRequest = master(Stream(CacheReadRequest(cacheCfg)))
    val bCacheResponse = slave(Stream(CacheReadResponse(cacheCfg)))
    val result = master(Stream(MatrixDescriptorResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val descriptorError = out Bool()
    val addressError = out Bool()
    val innerError = out Bool()
    val memoryError = out Bool()
    val acceptingFibers = out Bool()
    val rowBase = out UInt(matrixCfg.rowCountWidth bits)
    val columnBase = out UInt(matrixCfg.columnCountWidth bits)
    val kTileIndex = out UInt(matrixCfg.kTileIndexWidth bits)
    val completedOutputTiles = out UInt(32 bits)
  }

  val controller = new TrIpMatrixController(matrixCfg)
  val loader = new TrIpMatrixFiberLoader(matrixCfg, cacheCfg)
  controller.io.clear := io.clear
  loader.io.clear := io.clear

  val aBaseAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val bBaseAddress = Reg(UInt(cacheCfg.addressWidth bits)) init 0
  val loadIssued = RegInit(False)
  val memoryError = RegInit(False)
  val addressError = RegInit(False)
  val addressDone = RegInit(False)

  val descriptorRows = io.descriptor.payload.matrix.rowCount.resize(addressCalcWidth)
  val descriptorColumns =
    io.descriptor.payload.matrix.columnCount.resize(addressCalcWidth)
  val descriptorKTiles =
    io.descriptor.payload.matrix.kTileCount.resize(addressCalcWidth)
  val nonEmptyDescriptor = descriptorRows =/= 0 &&
    descriptorColumns =/= 0 && descriptorKTiles =/= 0
  val aLastOrdinal = UInt(addressCalcWidth bits)
  val bLastOrdinal = UInt(addressCalcWidth bits)
  aLastOrdinal := (((descriptorRows - U(1, addressCalcWidth bits)) *
    U(matrixCfg.maxKTiles, addressCalcWidth bits)) +
    descriptorKTiles - U(1, addressCalcWidth bits)).resized
  bLastOrdinal := (((descriptorKTiles - U(1, addressCalcWidth bits)) *
    U(matrixCfg.maxMatrixColumns, addressCalcWidth bits)) +
    descriptorColumns - U(1, addressCalcWidth bits)).resized
  val aLastAddress = UInt(addressCalcWidth bits)
  val bLastAddress = UInt(addressCalcWidth bits)
  aLastAddress := (io.descriptor.payload.aBaseAddress.resize(addressCalcWidth) +
    aLastOrdinal * U(fiberCacheCfg.linesPerFiber, addressCalcWidth bits) +
    U(fiberCacheCfg.linesPerFiber - 1, addressCalcWidth bits)).resized
  bLastAddress := (io.descriptor.payload.bBaseAddress.resize(addressCalcWidth) +
    bLastOrdinal * U(fiberCacheCfg.linesPerFiber, addressCalcWidth bits) +
    U(fiberCacheCfg.linesPerFiber - 1, addressCalcWidth bits)).resized
  val descriptorAddressError = nonEmptyDescriptor &&
    (aLastAddress >= U(BigInt(1) << cacheCfg.addressWidth,
      addressCalcWidth bits) ||
      bLastAddress >= U(BigInt(1) << cacheCfg.addressWidth,
        addressCalcWidth bits))

  controller.io.descriptor.valid := io.descriptor.valid && !descriptorAddressError
  controller.io.descriptor.payload := io.descriptor.payload.matrix
  io.descriptor.ready := controller.io.descriptor.ready
  when(io.descriptor.fire) {
    aBaseAddress := io.descriptor.payload.aBaseAddress
    bBaseAddress := io.descriptor.payload.bBaseAddress
    memoryError := False
    addressError := descriptorAddressError
    addressDone := descriptorAddressError
  }

  when(!controller.io.acceptingFibers) { loadIssued := False }
  loader.io.start.valid := controller.io.acceptingFibers && !loadIssued
  loader.io.start.payload.aBaseAddress := aBaseAddress
  loader.io.start.payload.bBaseAddress := bBaseAddress
  loader.io.start.payload.rowBase := controller.io.rowBase
  loader.io.start.payload.columnBase := controller.io.columnBase
  loader.io.start.payload.kTileIndex := controller.io.kTileIndex
  loader.io.start.payload.rowCount := controller.io.tileRowCount
  loader.io.start.payload.columnCount := controller.io.tileColumnCount
  when(loader.io.start.fire) { loadIssued := True }
  when(loader.io.done && loader.io.error) { memoryError := True }

  controller.io.aLoad.valid := loader.io.aLoad.valid
  controller.io.aLoad.payload := loader.io.aLoad.payload
  loader.io.aLoad.ready := controller.io.aLoad.ready
  controller.io.bLoad.valid := loader.io.bLoad.valid
  controller.io.bLoad.payload := loader.io.bLoad.payload
  loader.io.bLoad.ready := controller.io.bLoad.ready

  io.result.valid := controller.io.result.valid
  io.result.payload := controller.io.result.payload
  controller.io.result.ready := io.result.ready

  io.aCacheRequest.valid := loader.io.aCacheRequest.valid
  io.aCacheRequest.payload := loader.io.aCacheRequest.payload
  loader.io.aCacheRequest.ready := io.aCacheRequest.ready
  loader.io.aCacheResponse.valid := io.aCacheResponse.valid
  loader.io.aCacheResponse.payload := io.aCacheResponse.payload
  io.aCacheResponse.ready := loader.io.aCacheResponse.ready

  io.bCacheRequest.valid := loader.io.bCacheRequest.valid
  io.bCacheRequest.payload := loader.io.bCacheRequest.payload
  loader.io.bCacheRequest.ready := io.bCacheRequest.ready
  loader.io.bCacheResponse.valid := io.bCacheResponse.valid
  loader.io.bCacheResponse.payload := io.bCacheResponse.payload
  io.bCacheResponse.ready := loader.io.bCacheResponse.ready

  io.busy := controller.io.busy
  io.done := controller.io.done || addressDone
  io.descriptorError := controller.io.descriptorError || addressError
  io.addressError := addressError
  io.innerError := controller.io.innerError
  io.memoryError := memoryError || (loader.io.busy && loader.io.error)
  io.acceptingFibers := controller.io.acceptingFibers
  io.rowBase := controller.io.rowBase
  io.columnBase := controller.io.columnBase
  io.kTileIndex := controller.io.kTileIndex
  io.completedOutputTiles := controller.io.completedOutputTiles

  when(io.clear) {
    loadIssued := False
    memoryError := False
    addressError := False
    addressDone := False
  }
}

/** Complete TrIP read path: matrix scheduler, fiber readers, and banked cache. */
class TrIpCachedMatrixSystem(
    matrixCfg: TrIpMatrixConfig,
    cacheCfg: CacheConfig
) extends Component {
  private val cfg = matrixCfg.tile.core
  require(cacheCfg.clientCount >= 2,
    "cached matrix system requires separate A and B cache clients")

  val io = new Bundle {
    val clear = in Bool()
    val descriptor = slave(Stream(CachedTrIpMatrixDescriptor(matrixCfg, cacheCfg)))
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val result = master(Stream(MatrixDescriptorResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val descriptorError = out Bool()
    val addressError = out Bool()
    val innerError = out Bool()
    val memoryError = out Bool()
    val completedOutputTiles = out UInt(32 bits)
    val cacheBankBusy = out Bits(cacheCfg.bankCount bits)
    val cacheClientOutstanding = out Bits(cacheCfg.clientCount bits)
    val cacheHitCount = out UInt(32 bits)
    val cacheMissCount = out UInt(32 bits)
    val cacheRefillCount = out UInt(32 bits)
  }

  val matrix = new TrIpCachedMatrixController(matrixCfg, cacheCfg)
  val cache = new BankedReadCache(cacheCfg)
  matrix.io.clear := io.clear
  cache.io.clear := io.clear

  matrix.io.descriptor.valid := io.descriptor.valid
  matrix.io.descriptor.payload := io.descriptor.payload
  io.descriptor.ready := matrix.io.descriptor.ready
  io.result.valid := matrix.io.result.valid
  io.result.payload := matrix.io.result.payload
  matrix.io.result.ready := io.result.ready

  cache.io.requests(0).valid := matrix.io.aCacheRequest.valid
  cache.io.requests(0).payload := matrix.io.aCacheRequest.payload
  matrix.io.aCacheRequest.ready := cache.io.requests(0).ready
  matrix.io.aCacheResponse.valid := cache.io.responses(0).valid
  matrix.io.aCacheResponse.payload := cache.io.responses(0).payload
  cache.io.responses(0).ready := matrix.io.aCacheResponse.ready

  cache.io.requests(1).valid := matrix.io.bCacheRequest.valid
  cache.io.requests(1).payload := matrix.io.bCacheRequest.payload
  matrix.io.bCacheRequest.ready := cache.io.requests(1).ready
  matrix.io.bCacheResponse.valid := cache.io.responses(1).valid
  matrix.io.bCacheResponse.payload := cache.io.responses(1).payload
  cache.io.responses(1).ready := matrix.io.bCacheResponse.ready

  for (client <- 2 until cacheCfg.clientCount) {
    cache.io.requests(client).valid := False
    cache.io.requests(client).payload := CacheReadRequest(cacheCfg).getZero
    cache.io.responses(client).ready := True
  }

  io.refillRequest.valid := cache.io.refillRequest.valid
  io.refillRequest.payload := cache.io.refillRequest.payload
  cache.io.refillRequest.ready := io.refillRequest.ready
  cache.io.refillResponse.valid := io.refillResponse.valid
  cache.io.refillResponse.payload := io.refillResponse.payload
  io.refillResponse.ready := cache.io.refillResponse.ready

  io.busy := matrix.io.busy
  io.done := matrix.io.done
  io.descriptorError := matrix.io.descriptorError
  io.addressError := matrix.io.addressError
  io.innerError := matrix.io.innerError
  io.memoryError := matrix.io.memoryError
  io.completedOutputTiles := matrix.io.completedOutputTiles
  io.cacheBankBusy := cache.io.bankBusy
  io.cacheClientOutstanding := cache.io.clientOutstanding
  io.cacheHitCount := cache.io.hitCount
  io.cacheMissCount := cache.io.missCount
  io.cacheRefillCount := cache.io.refillCount
}

object GenerateTrIpCachedMatrixController extends App {
  private val coreCfg = TrapezoidConfig.small
  private val matrixCfg = TrIpMatrixConfig(
    TrIpTileConfig(coreCfg), maxMatrixRows = 64,
    maxMatrixColumns = 64, maxKTiles = 16)
  private val cacheCfg = CacheConfig(
    wordWidth = 32, wordsPerLine = 16, addressWidth = 24,
    bankCount = 32, setsPerBank = 64, clientCount = 2)
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cached-matrix",
    oneFilePerComponent = true
  ).generate(new TrIpCachedMatrixController(matrixCfg, cacheCfg))
}

object GenerateTrIpCachedMatrixSystem extends App {
  private val coreCfg = TrapezoidConfig.small
  private val matrixCfg = TrIpMatrixConfig(
    TrIpTileConfig(coreCfg), maxMatrixRows = 64,
    maxMatrixColumns = 64, maxKTiles = 16)
  private val cacheCfg = CacheConfig(
    wordWidth = 32, wordsPerLine = 16, addressWidth = 24,
    bankCount = 32, setsPerBank = 64, clientCount = 2)
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cached-system",
    oneFilePerComponent = true
  ).generate(new TrIpCachedMatrixSystem(matrixCfg, cacheCfg))
}
