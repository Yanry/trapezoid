package trapezoid

import spinal.core._

/** Hardware sizing parameters for a Trapezoid compute array.
  *
  * The paper configuration is exposed as [[TrapezoidConfig.paper]].  The
  * default is deliberately smaller so that elaboration and simulation remain
  * practical on a workstation.
  */
case class TrapezoidConfig(
    dataWidth: Int = 16,
    accumulatorWidth: Int = 40,
    coordinateWidth: Int = 16,
    fiberWidth: Int = 16,
    maxFibers: Int = 4,
    macLanes: Int = 16,
    cacheLineWords: Int = 4,
    outputLanes: Int = 16,
    peRows: Int = 4,
    localBanks: Int = 4,
    localWordsPerBank: Int = 4,
    localDepthPerBank: Int = 128,
    responseFifoDepth: Int = 2
) {
  require(dataWidth > 0)
  require(fiberWidth >= 2)
  require(maxFibers >= 1 && maxFibers <= 4,
    "TrIP limits both A rows and B columns to four fibers")
  require(macLanes >= maxFibers * maxFibers,
    "one response must be able to represent every TrIP output fiber pair")
  require(cacheLineWords > 0 && cacheLineWords <= macLanes)
  require(cacheLineWords >= localBanks,
    "TrGT needs one command lane for each local-buffer subrow")
  require(outputLanes >= math.max(maxFibers * maxFibers, cacheLineWords))
  require(peRows > 0)
  require(localBanks == 4, "the paper's PE row has four local-buffer banks")
  require(macLanes % localBanks == 0,
    "MAC lanes must divide evenly across the four TrGT subrows")
  require(localWordsPerBank >= maxFibers &&
    (localWordsPerBank & (localWordsPerBank - 1)) == 0,
    "local buffer width must be a power of two and cover all TrIP fibers")
  require(localDepthPerBank >= 2)
  require(responseFifoDepth >= 1)
  require(accumulatorWidth >= 2 * dataWidth + log2Up(macLanes),
    "accumulator is too narrow for a full-lane dot product")

  val fiberIndexWidth: Int = math.max(1, log2Up(maxFibers))
  val fiberValueIndexWidth: Int = math.max(1, log2Up(fiberWidth))
  val macLaneIndexWidth: Int = math.max(1, log2Up(macLanes))
  val distributionWidth: Int = {
    var width = 1
    while (width < macLanes) width *= 2
    width
  }
  val benesStageCount: Int = {
    var levels = 0
    var width = distributionWidth
    while (width > 1) {
      levels += 1
      width /= 2
    }
    math.max(1, levels * 2 - 1)
  }
  val benesControlWidth: Int = distributionWidth * benesStageCount
  val mrnReductionLevels: Int = {
    var levels = 0
    var width = distributionWidth
    while (width > 1) {
      levels += 1
      width /= 2
    }
    levels
  }
  val pePipelineStages: Int = 4 + mrnReductionLevels + responseFifoDepth
  val aRegisterIndexWidth: Int = math.max(1, log2Up(macLanes))
  val macLanesPerSubrow: Int = macLanes / localBanks
  val matchCountWidth: Int = log2Up(maxFibers * maxFibers * fiberWidth + 1)
  val outputCountWidth: Int = log2Up(outputLanes + 1)
  val localBankIndexWidth: Int = log2Up(localBanks)
  val localWordIndexWidth: Int = log2Up(localWordsPerBank)
  val localLineWidth: Int = accumulatorWidth * localWordsPerBank
  val localLineBytes: Int = localLineWidth / 8
  val localAddressWidth: Int = log2Up(localDepthPerBank)
}

object TrapezoidConfig {
  val small: TrapezoidConfig = TrapezoidConfig()

  /** Dimensions reported in Table I.  Elaborating this configuration creates
    * 128 PE rows with 128 signed MAC lanes each.
    */
  val paper: TrapezoidConfig = TrapezoidConfig(
    dataWidth = 32,
    accumulatorWidth = 72,
    fiberWidth = 128,
    macLanes = 128,
    cacheLineWords = 16,
    outputLanes = 16,
    peRows = 128,
    localWordsPerBank = 4,
    localDepthPerBank = 128
  )
}

object TrapezoidDataflow extends SpinalEnum {
  // These are explicit execution policies, not operand-density classifications.
  val DenseIp, TrIp, TrGt, TrGs = newElement()
}

/** Storage bounds for the bitmask/IP tile scheduler.
  *
  * Dataflow selection remains a runtime input.  This configuration describes
  * capacity only and never infers a mode from operand density.
  */
case class TrIpTileConfig(
    core: TrapezoidConfig,
    maxColumns: Int = 16,
    maxRowsOverride: Int = 0
) {
  val maxRows: Int = if (maxRowsOverride == 0) core.peRows * core.maxFibers
    else maxRowsOverride

  require(maxColumns >= core.maxFibers)
  require((maxColumns & (maxColumns - 1)) == 0,
    "maxColumns must be a power of two for compact circular indexing")
  require(core.macLanes >= core.fiberWidth,
    "one A fiber and one B fiber must always fit without overflow")
  require(maxRowsOverride >= 0 && maxRows <= core.peRows * core.maxFibers,
    "tile rows must fit the configured PE-row fiber capacity")
  require(core.localDepthPerBank * core.localWordsPerBank >= maxColumns,
    "each PE-row buffer bank must hold one output row")

  val rowIndexWidth: Int = math.max(1, log2Up(maxRows))
  val columnIndexWidth: Int = math.max(1, log2Up(maxColumns))
  val rowCountWidth: Int = log2Up(maxRows + 1)
  val columnCountWidth: Int = log2Up(maxColumns + 1)
}

/** Bounds for the descriptor-driven outer M/N/K tile loops. */
case class TrIpMatrixConfig(
    tile: TrIpTileConfig,
    maxMatrixRows: Int,
    maxMatrixColumns: Int,
    maxKTiles: Int
) {
  require(maxMatrixRows >= tile.maxRows)
  require(maxMatrixColumns >= tile.maxColumns)
  require(maxKTiles >= 1)
  require(BigInt(maxMatrixRows) <= (BigInt(1) << tile.core.coordinateWidth),
    "matrix row coordinates must fit coordinateWidth")
  require(BigInt(maxMatrixColumns) <= (BigInt(1) << tile.core.coordinateWidth),
    "matrix column coordinates must fit coordinateWidth")

  val rowCountWidth: Int = log2Up(maxMatrixRows + 1)
  val columnCountWidth: Int = log2Up(maxMatrixColumns + 1)
  val kTileCountWidth: Int = log2Up(maxKTiles + 1)
  val kTileIndexWidth: Int = math.max(1, log2Up(maxKTiles))
}

/** Set-associative banked read-cache sizing.
  * Addresses identify complete cache lines rather than bytes.
  */
case class CacheConfig(
    wordWidth: Int,
    wordsPerLine: Int,
    addressWidth: Int,
    bankCount: Int,
    setsPerBank: Int,
    clientCount: Int,
    requestIdWidth: Int = 8,
    ways: Int = 1
) {
  require(wordWidth > 0 && wordsPerLine > 0)
  require(addressWidth > 0 && requestIdWidth > 0)
  require(bankCount > 0 && (bankCount & (bankCount - 1)) == 0,
    "cache bankCount must be a power of two")
  require(setsPerBank >= 2 && (setsPerBank & (setsPerBank - 1)) == 0,
    "cache setsPerBank must be a power of two and at least two")
  require(ways >= 1 && (ways & (ways - 1)) == 0,
    "cache ways must be a power of two")
  require(clientCount > 0)

  val lineWidth: Int = wordWidth * wordsPerLine
  val bankIndexWidth: Int = math.max(1, log2Up(bankCount))
  val setIndexWidth: Int = math.max(1, log2Up(setsPerBank))
  val wayIndexWidth: Int = math.max(1, log2Up(ways))
  val clientIndexWidth: Int = math.max(1, log2Up(clientCount))
  val tagWidth: Int = addressWidth - log2Up(bankCount) - log2Up(setsPerBank)
  require(tagWidth >= 1, "cache addressWidth must leave at least one tag bit")
  val capacityBytes: BigInt = BigInt(bankCount) * setsPerBank * ways *
    wordsPerLine * wordWidth / 8
}

object CacheConfig {
  def paper(clientCount: Int, requestIdWidth: Int = 12): CacheConfig =
    CacheConfig(
      wordWidth = 32,
      wordsPerLine = 16,
      addressWidth = 24,
      bankCount = 128,
      setsPerBank = 128,
      clientCount = clientCount,
      requestIdWidth = requestIdWidth,
      ways = 16)
}

/** Fixed-width representation used to move one bitmask fiber through cache.
  * A record may span several cache lines; lines are ordered least-significant
  * first so the format is independent of the external memory word width.
  */
case class FiberCacheConfig(core: TrapezoidConfig, cache: CacheConfig) {
  val recordWidth: Int = 1 + core.fiberWidth +
    core.fiberWidth * core.dataWidth + core.coordinateWidth
  val linesPerFiber: Int = (recordWidth + cache.lineWidth - 1) / cache.lineWidth
  val lineIndexWidth: Int = math.max(1, log2Up(linesPerFiber))

  require(linesPerFiber <= (BigInt(1) << cache.requestIdWidth),
    "cache requestIdWidth must identify every line in a fiber record")
}

/** Capacity of the coordinate accumulator shared by TrGT and TrGS.
  * Mode remains a runtime policy and is never inferred from lane density.
  */
case class GustavsonTileConfig(
    core: TrapezoidConfig,
    accumulatorEntries: Int,
    fifoDepthPerSubrow: Int = 8
) {
  require(accumulatorEntries >= core.outputLanes,
    "the accumulator must hold at least one complete PE response")
  require(fifoDepthPerSubrow >= 2)
  require(fifoDepthPerSubrow <= core.localDepthPerBank,
    "each TrGT FIFO must fit in one local-buffer bank")
  require(core.cacheLineWords >= core.localBanks,
    "TrGT needs one cache-line lane per PE subrow")

  val entryIndexWidth: Int = math.max(1, log2Up(accumulatorEntries))
  val entryCountWidth: Int = log2Up(accumulatorEntries + 1)
  val fifoPointerWidth: Int = math.max(1, log2Up(fifoDepthPerSubrow))
  val fifoCountWidth: Int = log2Up(fifoDepthPerSubrow + 1)
}

object GustavsonTileConfig {
  def apply(core: TrapezoidConfig): GustavsonTileConfig =
    new GustavsonTileConfig(core, math.max(core.outputLanes, core.macLanes))
}

/** Fixed-width B-row storage consumed by the Gustavson cache adapter.
  *
  * Each packed element contains valid, value, then column in low-to-high bit
  * order.  A cache line may contain fewer elements than the PE row can consume;
  * unused PE lanes are marked invalid by the adapter.
  */
case class GustavsonCacheConfig(
    tile: GustavsonTileConfig,
    cache: CacheConfig,
    maxLinesPerRow: Int = 256
) {
  require(maxLinesPerRow >= 1)

  val elementWidth: Int = 1 + tile.core.dataWidth + tile.core.coordinateWidth
  val elementsPerLine: Int = math.min(tile.core.cacheLineWords,
    cache.lineWidth / elementWidth)
  require(elementsPerLine >= 1,
    "a cache line must hold at least one packed Gustavson B element")
  require(maxLinesPerRow <= (BigInt(1) << cache.requestIdWidth),
    "cache requestIdWidth must identify every B-row line")

  val lineCountWidth: Int = log2Up(maxLinesPerRow + 1)
}

/** Physical organization of the shared global read cache. */
case class CacheClusterConfig(cache: CacheConfig, clusterCount: Int) {
  require(clusterCount >= 1 && (clusterCount & (clusterCount - 1)) == 0,
    "cache clusterCount must be a power of two")
  require(cache.bankCount % clusterCount == 0,
    "global cache banks must divide evenly across clusters")

  val banksPerCluster: Int = cache.bankCount / clusterCount
  require(banksPerCluster >= 2 &&
    (banksPerCluster & (banksPerCluster - 1)) == 0,
    "each cache cluster needs at least two power-of-two banks")

  val clusterAddressBits: Int = log2Up(clusterCount)
  val clusterIndexWidth: Int = math.max(1, clusterAddressBits)
  val localCache: CacheConfig = cache.copy(
    addressWidth = cache.addressWidth - clusterAddressBits,
    bankCount = banksPerCluster)
}

/** Bounds for the descriptor-to-C-write-response RTL system.
  * Write responses use one ordered completion stream, matching a single AXI ID.
  */
case class EndToEndSystemConfig(
    matrix: TrIpMatrixConfig,
    gustavson: GustavsonCacheConfig,
    clusters: CacheClusterConfig,
    maxCResults: Int,
    writeOutstandingDepth: Int = 16
) {
  require(matrix.tile.core == gustavson.tile.core,
    "all dataflows must share one core configuration")
  require(gustavson.cache == clusters.cache,
    "all descriptor frontends must share the clustered cache")
  require(clusters.cache.clientCount >= 2,
    "an end-to-end system needs at least two cache clients")
  require(maxCResults >= matrix.maxMatrixRows * matrix.maxMatrixColumns,
    "C DMA capacity must cover the largest configured output matrix")
  require(writeOutstandingDepth >= 1,
    "C DMA must allow at least one outstanding write")
  require(BigInt(maxCResults) <= (BigInt(1) << clusters.cache.addressWidth),
    "the C result region must fit the DMA line address space")

  val core: TrapezoidConfig = matrix.tile.core
  val cache: CacheConfig = clusters.cache
}

sealed trait EndToEndHardwareProfile {
  def includeIp: Boolean
  def includeTrGt: Boolean
  def includeTrGs: Boolean
}

object EndToEndHardwareProfile {
  case object Unified extends EndToEndHardwareProfile {
    val includeIp = true
    val includeTrGt = true
    val includeTrGs = true
  }
  case object IpOnly extends EndToEndHardwareProfile {
    val includeIp = true
    val includeTrGt = false
    val includeTrGs = false
  }
  case object TrGtOnly extends EndToEndHardwareProfile {
    val includeIp = false
    val includeTrGt = true
    val includeTrGs = false
  }
  case object TrGsOnly extends EndToEndHardwareProfile {
    val includeIp = false
    val includeTrGt = false
    val includeTrGs = true
  }
}
