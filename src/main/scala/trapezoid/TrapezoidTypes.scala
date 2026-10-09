package trapezoid

import spinal.core._
import spinal.lib._

/** Bitmask fiber with values packed in increasing coordinate order. */
case class CompressedFiber(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val tag = UInt(cfg.coordinateWidth bits)
  val mask = Bits(cfg.fiberWidth bits)
  val values = Vec(SInt(cfg.dataWidth bits), cfg.fiberWidth)
}

case class TrIpPayload(cfg: TrapezoidConfig) extends Bundle {
  val a = Vec(CompressedFiber(cfg), cfg.maxFibers)
  val b = Vec(CompressedFiber(cfg), cfg.maxFibers)
}

/** A nonzero retained in a PE-row A register for Gustavson modes. */
case class GustavsonA(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val row = UInt(cfg.coordinateWidth bits)
  val k = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.dataWidth bits)
}

/** A B-row element delivered by a local-buffer/cache scheduler.
  * `aIndex` is the routing metadata selecting the matching stationary A value.
  */
case class GustavsonB(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val aIndex = UInt(cfg.aRegisterIndexWidth bits)
  val k = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.dataWidth bits)
}

case class GustavsonPayload(cfg: TrapezoidConfig) extends Bundle {
  val a = Vec(GustavsonA(cfg), cfg.macLanes)
  val b = Vec(GustavsonB(cfg), cfg.cacheLineWords)
}

case class PERowCommand(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val trip = TrIpPayload(cfg)
  val gustavson = GustavsonPayload(cfg)
}

case class MatrixResult(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
  val endOfTile = Bool()
}

case class PERowResponse(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val overflow = Bool()
  val selectedColumns = UInt(log2Up(cfg.maxFibers + 1) bits)
  val effectualMacs = UInt(cfg.matchCountWidth bits)
  val count = UInt(cfg.outputCountWidth bits)
  val results = Vec(MatrixResult(cfg), cfg.outputLanes)
}

case class IntersectionRoute(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val aFiber = UInt(cfg.fiberIndexWidth bits)
  val bFiber = UInt(cfg.fiberIndexWidth bits)
  val coordinate = UInt(cfg.fiberValueIndexWidth bits)
  val aValueIndex = UInt(cfg.fiberValueIndexWidth bits)
  val bValueIndex = UInt(cfg.fiberValueIndexWidth bits)
}

case class LocalBufferWrite(cfg: TrapezoidConfig) extends Bundle {
  val address = UInt(cfg.localAddressWidth bits)
  val data = Bits(cfg.accumulatorWidth bits)
  val mask = Bits((cfg.accumulatorWidth / 8) bits)
}

/** Scheduler-facing port of one PE row's four-bank local SRAM. */
case class LocalBufferBus(cfg: TrapezoidConfig) extends Bundle with IMasterSlave {
  val writes = Vec(Flow(LocalBufferWrite(cfg)), cfg.localBanks)
  val readCommands = Vec(Flow(UInt(cfg.localAddressWidth bits)), cfg.localBanks)
  val readResponses = Vec(Flow(Bits(cfg.accumulatorWidth bits)), cfg.localBanks)

  override def asMaster(): Unit = {
    writes.foreach(master(_))
    readCommands.foreach(master(_))
    readResponses.foreach(slave(_))
  }
}

case class IndexedFiber(cfg: TrapezoidConfig, indexWidth: Int) extends Bundle {
  val index = UInt(indexWidth bits)
  val fiber = CompressedFiber(cfg)
}

case class TrIpTileStart(tileCfg: TrIpTileConfig) extends Bundle {
  /** DenseIp and TrIp share the bitmask/IP tile scheduler.  The caller chooses
    * the mode explicitly; operand density does not alter it.
    */
  val mode = TrapezoidDataflow()
  val rowCount = UInt(tileCfg.rowCountWidth bits)
  val columnCount = UInt(tileCfg.columnCountWidth bits)
  /** firstKTile resets C; lastKTile emits C.  Intermediate tiles only add
    * their partial sums, allowing K to exceed one fiber.
    */
  val firstKTile = Bool()
  val lastKTile = Bool()
}

case class TrIpMatrixDescriptor(matrixCfg: TrIpMatrixConfig) extends Bundle {
  /** Mode is selected explicitly for each K tile and reused across all M/N tiles. */
  val modes = Vec(TrapezoidDataflow(), matrixCfg.maxKTiles)
  val rowCount = UInt(matrixCfg.rowCountWidth bits)
  val columnCount = UInt(matrixCfg.columnCountWidth bits)
  val kTileCount = UInt(matrixCfg.kTileCountWidth bits)
}

case class MatrixDescriptorResult(cfg: TrapezoidConfig) extends Bundle {
  val element = MatrixResult(cfg)
  val endOfMatrix = Bool()
}

case class CacheReadRequest(cacheCfg: CacheConfig) extends Bundle {
  val address = UInt(cacheCfg.addressWidth bits)
  val requestId = UInt(cacheCfg.requestIdWidth bits)
}

case class CacheReadResponse(cacheCfg: CacheConfig) extends Bundle {
  val requestId = UInt(cacheCfg.requestIdWidth bits)
  val data = Bits(cacheCfg.lineWidth bits)
  /** False means this response required a refill before it could complete. */
  val wasHit = Bool()
  val error = Bool()
}

case class CacheRefillRequest(cacheCfg: CacheConfig) extends Bundle {
  val bank = UInt(cacheCfg.bankIndexWidth bits)
  val address = UInt(cacheCfg.addressWidth bits)
}

case class CacheRefillResponse(cacheCfg: CacheConfig) extends Bundle {
  val bank = UInt(cacheCfg.bankIndexWidth bits)
  val address = UInt(cacheCfg.addressWidth bits)
  val data = Bits(cacheCfg.lineWidth bits)
  val error = Bool()
}

case class FiberCacheReadCommand(
    fiberCacheCfg: FiberCacheConfig,
    indexWidth: Int
) extends Bundle {
  val address = UInt(fiberCacheCfg.cache.addressWidth bits)
  val index = UInt(indexWidth bits)
}

case class FiberCacheReadResult(
    fiberCacheCfg: FiberCacheConfig,
    indexWidth: Int
) extends Bundle {
  val index = UInt(indexWidth bits)
  val fiber = CompressedFiber(fiberCacheCfg.core)
  val error = Bool()
}

case class TrIpMatrixFiberLoad(
    matrixCfg: TrIpMatrixConfig,
    cacheCfg: CacheConfig
) extends Bundle {
  val aBaseAddress = UInt(cacheCfg.addressWidth bits)
  val bBaseAddress = UInt(cacheCfg.addressWidth bits)
  val rowBase = UInt(matrixCfg.rowCountWidth bits)
  val columnBase = UInt(matrixCfg.columnCountWidth bits)
  val kTileIndex = UInt(matrixCfg.kTileIndexWidth bits)
  val rowCount = UInt(matrixCfg.tile.rowCountWidth bits)
  val columnCount = UInt(matrixCfg.tile.columnCountWidth bits)
}

case class CachedTrIpMatrixDescriptor(
    matrixCfg: TrIpMatrixConfig,
    cacheCfg: CacheConfig
) extends Bundle {
  val matrix = TrIpMatrixDescriptor(matrixCfg)
  val aBaseAddress = UInt(cacheCfg.addressWidth bits)
  val bBaseAddress = UInt(cacheCfg.addressWidth bits)
}

case class IndexedGustavsonA(cfg: TrapezoidConfig) extends Bundle {
  val index = UInt(cfg.aRegisterIndexWidth bits)
  val element = GustavsonA(cfg)
}

case class GustavsonStart() extends Bundle {
  /** The caller selects the execution policy explicitly. */
  val mode = TrapezoidDataflow()
}

case class TrGsB(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val k = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.dataWidth bits)
}

case class TrGsCommand(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val a = GustavsonA(cfg)
  val b = Vec(TrGsB(cfg), cfg.cacheLineWords)
}
