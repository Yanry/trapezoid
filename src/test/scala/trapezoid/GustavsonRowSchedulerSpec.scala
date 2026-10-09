package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable

class GustavsonRowSchedulerSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 2,
    localDepthPerBank = 8
  )
  private val tileCfg = GustavsonTileConfig(cfg, accumulatorEntries = 8,
    fifoDepthPerSubrow = 2)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def clearPayload(dut: GustavsonRowScheduler): Unit = {
    for (fiber <- 0 until cfg.maxFibers) {
      val a = dut.io.command.payload.trip.a(fiber)
      val b = dut.io.command.payload.trip.b(fiber)
      a.valid #= false
      a.tag #= 0
      a.mask #= 0
      b.valid #= false
      b.tag #= 0
      b.mask #= 0
      for (value <- 0 until cfg.fiberWidth) {
        a.values(value) #= 0
        b.values(value) #= 0
      }
    }
    for (index <- 0 until cfg.macLanes) {
      val a = dut.io.command.payload.gustavson.a(index)
      a.valid #= false
      a.row #= 0
      a.k #= 0
      a.value #= 0
    }
    for (lane <- 0 until cfg.cacheLineWords) {
      val b = dut.io.command.payload.gustavson.b(lane)
      b.valid #= false
      b.aIndex #= 0
      b.k #= 0
      b.column #= 0
      b.value #= 0
    }
  }

  private def issueSingleResultTile(dut: GustavsonRowScheduler,
                                    mode: TrapezoidDataflow.E,
                                    row: Int, column: Int,
                                    aValue: Int, bValue: Int,
                                    endOfTile: Boolean): Unit = {
    clearPayload(dut)
    dut.io.command.payload.mode #= mode
    dut.io.command.payload.endOfTile #= endOfTile
    dut.io.command.payload.gustavson.a(0).valid #= true
    dut.io.command.payload.gustavson.a(0).row #= row
    dut.io.command.payload.gustavson.a(0).k #= 0
    dut.io.command.payload.gustavson.a(0).value #= aValue
    dut.io.command.payload.gustavson.b(0).valid #= true
    dut.io.command.payload.gustavson.b(0).aIndex #= 0
    dut.io.command.payload.gustavson.b(0).k #= 0
    dut.io.command.payload.gustavson.b(0).column #= column
    dut.io.command.payload.gustavson.b(0).value #= bValue
    dut.io.command.valid #= true
    var accepted = false
    var waitCycles = 0
    while (!accepted && waitCycles < 500) {
      sleep(1)
      accepted = dut.io.command.ready.toBoolean
      dut.clockDomain.waitSampling()
      waitCycles += 1
    }
    sleep(1)
    dut.io.command.valid #= false
    sleep(1)
    assert(accepted,
      s"command timeout: allocated=${dut.io.allocated.toBigInt}, " +
        s"busy=${dut.io.busy.toBigInt}, assigned=${dut.io.assignedTiles.toLong}, " +
        s"completed=${dut.io.completedTiles.toLong}")
  }

  test("shared scheduler overlaps tiles and releases rows after ordered completion") {
    simConfig.compile(new GustavsonRowScheduler(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= false
      clearPayload(dut)

      issueSingleResultTile(dut, TrapezoidDataflow.TrGt,
        row = 10, column = 1, aValue = 2, bValue = 3, endOfTile = false)
      assert(dut.io.assignedTiles.toLong == 1)
      assert(dut.io.allocated.toBigInt == 1,
        "an open tile must stay assigned to its original row")

      // A tile may explicitly switch between compatible Gustavson modes.
      issueSingleResultTile(dut, TrapezoidDataflow.TrGs,
        row = 10, column = 1, aValue = 2, bValue = 4, endOfTile = true)
      issueSingleResultTile(dut, TrapezoidDataflow.TrGs,
        row = 20, column = 2, aValue = 4, bValue = 5, endOfTile = true)

      assert(dut.io.assignedTiles.toLong == 2)
      assert(dut.io.allocated.toBigInt == 3,
        "both rows must remain allocated while output is backpressured")

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      dut.io.result.ready #= true
      var timeout = 0
      while (observed.size < 2 && timeout < 500) {
        sleep(1)
        if (dut.io.result.valid.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong,
            dut.io.result.payload.endOfTile.toBoolean))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(observed == Seq((10, 1, 14L, true), (20, 2, 20L, true)))
      while (dut.io.completedTiles.toLong != 2 && timeout < 520) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.completedTiles.toLong == 2)
      assert(dut.io.allocated.toBigInt == 0)
      assert(dut.io.overflow.toBigInt == 0)
      assert(dut.io.unsupportedMode.toBigInt == 0)
    }
  }
}
