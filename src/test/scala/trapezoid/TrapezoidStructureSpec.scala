package trapezoid

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class TrapezoidStructureSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 1,
    localDepthPerBank = 8
  )

  private def elaborate(component: => Component): Unit = {
    val target = Files.createTempDirectory("trapezoid-elaboration-").toFile.getAbsolutePath
    SpinalConfig(targetDirectory = target).generateVerilog(component)
  }

  test("dynamic column packer elaborates") {
    elaborate(new DynamicColumnPacker(cfg))
  }

  test("operand distribution tree elaborates") {
    elaborate(new OperandDistributionNetwork(cfg))
  }

  test("programmable Benes fabric elaborates") {
    elaborate(new BenesDistributionFabric(cfg))
  }

  test("Benes broadcast controller elaborates") {
    elaborate(new BenesBroadcastNetwork(cfg))
  }

  test("four-bank local buffer elaborates") {
    elaborate(new BankedLocalBuffer(cfg))
  }

  test("mode-shared four-bank local buffer elaborates") {
    elaborate(new ModeSharedLocalBuffer(cfg))
  }

  test("paper configuration uses sixteen result lanes and per-row buffer capacity") {
    assert(TrapezoidConfig.paper.outputLanes == 16)
    assert(TrapezoidConfig.paper.mrnReductionLevels == 7)
    assert(TrapezoidConfig.paper.pePipelineStages == 13)
    assert(TrapezoidConfig.paper.localWordsPerBank == 4)
    assert(TrapezoidConfig.paper.localDepthPerBank == 128)
    val paperTile = TrIpTileConfig(TrapezoidConfig.paper)
    assert(TrapezoidConfig.paper.localDepthPerBank *
      TrapezoidConfig.paper.localWordsPerBank >= paperTile.maxColumns)
    assert(GustavsonTileConfig(TrapezoidConfig.paper).accumulatorEntries == 128)
  }

  test("multi-row Gustavson array elaborates") {
    elaborate(new GustavsonArray(GustavsonTileConfig(cfg, accumulatorEntries = 4)))
  }

  test("shared Gustavson row scheduler elaborates") {
    elaborate(new GustavsonRowScheduler(GustavsonTileConfig(
      cfg.copy(peRows = 2), accumulatorEntries = 4)))
  }

  test("descriptor-driven TrIP matrix controller elaborates") {
    val tileCfg = TrIpTileConfig(cfg, maxColumns = 4)
    elaborate(new TrIpMatrixController(TrIpMatrixConfig(
      tileCfg, maxMatrixRows = 8, maxMatrixColumns = 8, maxKTiles = 1)))
  }

  test("TrIP tile controller elaborates with mode-shared local buffers") {
    elaborate(new TrIpTileController(
      TrIpTileConfig(cfg, maxColumns = 4),
      modeSharedLocalBuffers = true))
  }

  test("multi-row TrIP, TrGT, and TrGS shared-PE tile controller elaborates") {
    val sharedCfg = cfg.copy(peRows = 2)
    elaborate(new ModeSharedTileController(
      TrIpTileConfig(sharedCfg, maxColumns = 4),
      GustavsonTileConfig(sharedCfg, accumulatorEntries = 4)))
  }

  test("banked read cache elaborates") {
    elaborate(new BankedReadCache(CacheConfig(
      wordWidth = 8, wordsPerLine = 4, addressWidth = 6,
      bankCount = 2, setsPerBank = 2, clientCount = 2)))
  }
}
