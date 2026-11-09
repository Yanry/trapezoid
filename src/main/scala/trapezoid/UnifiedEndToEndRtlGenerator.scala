package trapezoid

import spinal.core._

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

object UnifiedEndToEndRtlConfig {
  private def core(scale: String): (TrapezoidConfig, Int) = scale match {
    case "small" =>
      TrapezoidConfig(
        dataWidth = 8,
        accumulatorWidth = 32,
        coordinateWidth = 8,
        fiberWidth = 8,
        maxFibers = 2,
        macLanes = 16,
        cacheLineWords = 4,
        outputLanes = 4,
        peRows = 4,
        localBanks = 4,
        localWordsPerBank = 4,
        localDepthPerBank = 32,
        responseFifoDepth = 2) -> 16
    case "scaled-paper" => TrapezoidConfig.paper.copy(peRows = 1) -> 128
    case "paper" => TrapezoidConfig.paper -> 128
    case other => throw new IllegalArgumentException(
      s"unknown scale $other; expected small, scaled-paper, or paper")
  }

  def apply(scale: String): EndToEndSystemConfig = {
    val (cfg, dimension) = core(scale)
    val paperScale = scale == "paper"
    val scaledPaper = scale == "scaled-paper"
    val tileCfg = TrIpTileConfig(
      cfg,
      maxColumns = dimension,
      maxRowsOverride = if (paperScale) dimension
        else cfg.peRows * cfg.maxFibers)
    val matrixCfg = TrIpMatrixConfig(
      tileCfg,
      maxMatrixRows = dimension,
      maxMatrixColumns = dimension,
      maxKTiles = dimension / cfg.fiberWidth)
    val cacheClientCount = 2 * cfg.localBanks + cfg.peRows + 2
    val cacheCfg = if (paperScale) {
      CacheConfig.paper(cacheClientCount)
    } else if (scaledPaper) {
      CacheConfig(
        wordWidth = 32,
        wordsPerLine = 16,
        addressWidth = 18,
        bankCount = 8,
        setsPerBank = 64,
        clientCount = cacheClientCount,
        requestIdWidth = 12,
        ways = 1)
    } else {
      CacheConfig(
        wordWidth = 8,
        wordsPerLine = 16,
        addressWidth = 12,
        bankCount = 8,
        setsPerBank = 64,
        clientCount = cacheClientCount,
        requestIdWidth = 8,
        mshrsPerBank = 2,
        refillChannelCount = 2)
    }
    val gustCfg = GustavsonCacheConfig(
      GustavsonTileConfig(
        cfg,
        accumulatorEntries = if (scaledPaper) dimension
          else dimension * cfg.localBanks,
        fifoDepthPerSubrow = if (paperScale || scaledPaper) 32 else 8,
        bPrefetchDepth = if (paperScale || scaledPaper) 4 else 2),
      cacheCfg,
      maxLinesPerRow = if (paperScale) 256 else if (scaledPaper) 32 else 8)

    EndToEndSystemConfig(
      matrixCfg,
      gustCfg,
      CacheClusterConfig(cacheCfg, clusterCount = 4),
      maxCResults = dimension * dimension)
  }
}

/** Generates one RTL top containing all four dataflows and one shared array. */
object GenerateUnifiedEndToEndSystem extends App {
  private val options = args.map { argument =>
    val parts = argument.stripPrefix("--").split("=", 2)
    require(parts.length == 2, s"expected --name=value, got $argument")
    parts(0) -> parts(1)
  }.toMap
  private val scale = options.getOrElse("scale", "small")
  private val outputDirectory = Paths.get(options.getOrElse(
    "output", s"generated-unified-$scale")).toAbsolutePath.normalize()
  private val systemCfg = UnifiedEndToEndRtlConfig(scale)

  Files.createDirectories(outputDirectory)
  SpinalConfig(
    mode = Verilog,
    targetDirectory = outputDirectory.toString,
    oneFilePerComponent = false
  ).generate(new UnifiedEndToEndSystem(
    systemCfg,
    hardwareProfile = EndToEndHardwareProfile.Unified))

  private val resourceDirectory = Paths.get(
    "src/main/resources/rtl").toAbsolutePath.normalize()
  private def copyResource(name: String): Unit = {
    val source: Path = resourceDirectory.resolve(name)
    if (Files.isRegularFile(source)) {
      Files.copy(source, outputDirectory.resolve(name),
        StandardCopyOption.REPLACE_EXISTING)
    }
  }
  copyResource("TrapezoidSram.v")
  copyResource("TrapezoidBenesRouteControl.sv")

  println(s"Generated unified four-mode RTL ($scale) in $outputDirectory")
  println("Top module: UnifiedEndToEndSystem")
  println(s"Shared array: ${systemCfg.core.peRows} PE rows x " +
    s"${systemCfg.core.macLanes} MAC lanes")
}
