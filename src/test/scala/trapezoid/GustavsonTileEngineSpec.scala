package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable
import scala.util.Random

class GustavsonTileEngineSpec extends AnyFunSuite {
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
  private val tileCfg = GustavsonTileConfig(cfg, accumulatorEntries = 4)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def clearPayload(dut: GustavsonTileEngine): Unit = {
    dut.io.command.valid #= false
    dut.io.command.payload.endOfTile #= false
    for (fiber <- 0 until cfg.maxFibers) {
      dut.io.command.payload.trip.a(fiber).valid #= false
      dut.io.command.payload.trip.a(fiber).tag #= 0
      dut.io.command.payload.trip.a(fiber).mask #= 0
      dut.io.command.payload.trip.b(fiber).valid #= false
      dut.io.command.payload.trip.b(fiber).tag #= 0
      dut.io.command.payload.trip.b(fiber).mask #= 0
      for (value <- 0 until cfg.fiberWidth) {
        dut.io.command.payload.trip.a(fiber).values(value) #= 0
        dut.io.command.payload.trip.b(fiber).values(value) #= 0
      }
    }
    for (entry <- 0 until cfg.macLanes) {
      dut.io.command.payload.gustavson.a(entry).valid #= false
      dut.io.command.payload.gustavson.a(entry).row #= 0
      dut.io.command.payload.gustavson.a(entry).k #= 0
      dut.io.command.payload.gustavson.a(entry).value #= 0
    }
    for (lane <- 0 until cfg.cacheLineWords) {
      dut.io.command.payload.gustavson.b(lane).valid #= false
      dut.io.command.payload.gustavson.b(lane).aIndex #= 0
      dut.io.command.payload.gustavson.b(lane).k #= 0
      dut.io.command.payload.gustavson.b(lane).column #= 0
      dut.io.command.payload.gustavson.b(lane).value #= 0
    }
  }

  private def issue(dut: GustavsonTileEngine, mode: TrapezoidDataflow.E,
                    endOfTile: Boolean,
                    a: Seq[(Int, Int, Int)],
                    b: Seq[(Int, Int, Int, Int)]): Unit = {
    clearPayload(dut)
    dut.io.command.payload.mode #= mode
    dut.io.command.payload.endOfTile #= endOfTile
    for (((row, k, value), index) <- a.zipWithIndex) {
      dut.io.command.payload.gustavson.a(index).valid #= true
      dut.io.command.payload.gustavson.a(index).row #= row
      dut.io.command.payload.gustavson.a(index).k #= k
      dut.io.command.payload.gustavson.a(index).value #= value
    }
    for (((aIndex, k, column, value), lane) <- b.zipWithIndex) {
      dut.io.command.payload.gustavson.b(lane).valid #= true
      dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
      dut.io.command.payload.gustavson.b(lane).k #= k
      dut.io.command.payload.gustavson.b(lane).column #= column
      dut.io.command.payload.gustavson.b(lane).value #= value
    }
    dut.io.command.valid #= true
    var accepted = false
    var timeout = 0
    while (!accepted && timeout < 500) {
      sleep(1)
      accepted = dut.io.command.ready.toBoolean
      dut.clockDomain.waitSampling()
      timeout += 1
    }
    sleep(1)
    dut.io.command.valid #= false
    sleep(1)
    assert(accepted, "Gustavson command timed out")
  }

  private def collect(dut: GustavsonTileEngine): Seq[(Int, Int, Long, Boolean)] = {
    val random = new Random(0x6712)
    val result = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
    var timeout = 0
    while (!dut.io.done.toBoolean && timeout < 500) {
      dut.io.result.ready #= random.nextBoolean()
      sleep(1)
      val resultFire = dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean
      val sampled = if (resultFire) Some((dut.io.result.payload.row.toInt,
        dut.io.result.payload.column.toInt,
        dut.io.result.payload.value.toLong,
        dut.io.result.payload.endOfTile.toBoolean)) else None
      dut.clockDomain.waitSampling()
      sleep(1)
      sampled.foreach(result += _)
      timeout += 1
    }
    assert(dut.io.done.toBoolean, "Gustavson tile engine timed out")
    result.toSeq
  }

  test("TrGT and TrGS accumulate across commands and flush in coordinate order") {
    simConfig.compile(new GustavsonTileEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.result.ready #= false
      clearPayload(dut)

      issue(dut, TrapezoidDataflow.TrGt, endOfTile = false,
        a = Seq((2, 0, 2), (1, 1, 3)),
        b = Seq((0, 0, 1, 5), (1, 1, 0, 7)))
      while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      assert(dut.io.busy.toBoolean)
      assert(!dut.io.result.valid.toBoolean)

      issue(dut, TrapezoidDataflow.TrGs, endOfTile = false,
        a = Seq((2, 2, 4)),
        b = Seq((0, 2, 0, 1), (0, 2, 1, 3)))
      issue(dut, TrapezoidDataflow.TrGs, endOfTile = true,
        a = Seq((0, 3, 6)),
        b = Seq((0, 3, 2, 2)))

      val observed = collect(dut)
      assert(observed.map(x => (x._1, x._2, x._3)) == Seq(
        (0, 2, 12L), (1, 0, 21L), (2, 0, 4L), (2, 1, 22L)))
      assert(observed.map(_._4) == Seq(false, false, false, true))
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
    }
  }

  test("Gustavson accumulator reports capacity overflow") {
    simConfig.compile(new GustavsonTileEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.result.ready #= true
      clearPayload(dut)

      issue(dut, TrapezoidDataflow.TrGt, endOfTile = false,
        a = Seq((0, 0, 1), (1, 1, 1), (2, 2, 1), (3, 3, 1)),
        b = Seq((0, 0, 0, 1), (1, 1, 0, 1), (2, 2, 0, 1), (3, 3, 0, 1)))
      while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
      issue(dut, TrapezoidDataflow.TrGs, endOfTile = true,
        a = Seq((4, 0, 1)), b = Seq((0, 0, 0, 1)))
      collect(dut)
      assert(dut.io.overflow.toBoolean)
    }
  }

  test("Gustavson engine rejects IP modes and safely aborts an active command") {
    simConfig.compile(new GustavsonTileEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.result.ready #= true
      clearPayload(dut)

      issue(dut, TrapezoidDataflow.TrIp, endOfTile = true,
        a = Seq.empty, b = Seq.empty)
      dut.clockDomain.waitSampling()
      assert(dut.io.done.toBoolean)
      assert(dut.io.unsupportedMode.toBoolean)

      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      issue(dut, TrapezoidDataflow.TrGt, endOfTile = false,
        a = Seq((0, 0, 2)), b = Seq((0, 0, 0, 3)))
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false

      var timeout = 0
      while (dut.io.busy.toBoolean && timeout < 20) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(!dut.io.busy.toBoolean)
      assert(!dut.io.done.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
      assert(dut.io.activeEntries.toInt == 0)
    }
  }
}
