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

  test("four-bank local buffer elaborates") {
    elaborate(new BankedLocalBuffer(cfg))
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
}
