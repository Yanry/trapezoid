package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class TrapezoidPERowSpec extends AnyFunSuite {
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

  // SpinalHDL 1.7 predates Verilator 5, whose runtime now requires C++14.
  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def clearInputs(dut: TrapezoidPERow): Unit = {
    dut.io.command.valid #= false
    dut.io.command.payload.endOfTile #= false
    dut.io.response.ready #= true
    for (fiber <- 0 until cfg.maxFibers) {
      dut.io.command.payload.trip.a(fiber).valid #= false
      dut.io.command.payload.trip.a(fiber).tag #= 0
      dut.io.command.payload.trip.a(fiber).mask #= 0
      dut.io.command.payload.trip.b(fiber).valid #= false
      dut.io.command.payload.trip.b(fiber).tag #= 0
      dut.io.command.payload.trip.b(fiber).mask #= 0
      for (entry <- 0 until cfg.fiberWidth) {
        dut.io.command.payload.trip.a(fiber).values(entry) #= 0
        dut.io.command.payload.trip.b(fiber).values(entry) #= 0
      }
    }
    for (entry <- 0 until cfg.macLanes) {
      dut.io.command.payload.gustavson.a(entry).valid #= false
      dut.io.command.payload.gustavson.a(entry).row #= 0
      dut.io.command.payload.gustavson.a(entry).k #= 0
      dut.io.command.payload.gustavson.a(entry).value #= 0
    }
    for (entry <- 0 until cfg.cacheLineWords) {
      dut.io.command.payload.gustavson.b(entry).valid #= false
      dut.io.command.payload.gustavson.b(entry).aIndex #= 0
      dut.io.command.payload.gustavson.b(entry).k #= 0
      dut.io.command.payload.gustavson.b(entry).column #= 0
      dut.io.command.payload.gustavson.b(entry).value #= 0
    }
  }

  private def issue(dut: TrapezoidPERow): Unit = {
    dut.io.command.valid #= true
    dut.clockDomain.waitSampling()
    dut.io.command.valid #= false
    dut.clockDomain.waitSampling()
    assert(dut.io.response.valid.toBoolean)
  }

  test("TrIP intersects packed fibers and reduces each output coordinate") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrIp

      // A[2] = [2, 0, 3, 0], A[3] = [0, 5, 7, 0]
      dut.io.command.payload.trip.a(0).valid #= true
      dut.io.command.payload.trip.a(0).tag #= 2
      dut.io.command.payload.trip.a(0).mask #= 0x5
      dut.io.command.payload.trip.a(0).values(0) #= 2
      dut.io.command.payload.trip.a(0).values(1) #= 3
      dut.io.command.payload.trip.a(1).valid #= true
      dut.io.command.payload.trip.a(1).tag #= 3
      dut.io.command.payload.trip.a(1).mask #= 0x6
      dut.io.command.payload.trip.a(1).values(0) #= 5
      dut.io.command.payload.trip.a(1).values(1) #= 7

      // B[:,0] = [11,0,0,13], B[:,1] = [17,0,19,0]
      dut.io.command.payload.trip.b(0).valid #= true
      dut.io.command.payload.trip.b(0).tag #= 0
      dut.io.command.payload.trip.b(0).mask #= 0x9
      dut.io.command.payload.trip.b(0).values(0) #= 11
      dut.io.command.payload.trip.b(0).values(1) #= 13
      dut.io.command.payload.trip.b(1).valid #= true
      dut.io.command.payload.trip.b(1).tag #= 1
      dut.io.command.payload.trip.b(1).mask #= 0x5
      dut.io.command.payload.trip.b(1).values(0) #= 17
      dut.io.command.payload.trip.b(1).values(1) #= 19

      issue(dut)
      assert(!dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.selectedColumns.toInt == 2)
      assert(dut.io.response.payload.effectualMacs.toInt == 4)
      assert(dut.io.response.payload.count.toInt == 3)
      val results = (0 until 3).map { index =>
        (dut.io.response.payload.results(index).row.toInt,
          dut.io.response.payload.results(index).column.toInt,
          dut.io.response.payload.results(index).value.toInt)
      }
      assert(results == Seq((2, 0, 22), (2, 1, 91), (3, 1, 133)))
    }
  }

  test("TrIP reports overflow instead of silently dropping products") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrIp
      for (fiber <- 0 until cfg.maxFibers) {
        dut.io.command.payload.trip.a(fiber).valid #= true
        dut.io.command.payload.trip.a(fiber).mask #= 0xf
        dut.io.command.payload.trip.b(fiber).valid #= true
        dut.io.command.payload.trip.b(fiber).mask #= 0xf
      }
      issue(dut)
      assert(dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.selectedColumns.toInt == 0)
      assert(dut.io.response.payload.effectualMacs.toInt == 16)
      assert(dut.io.response.payload.count.toInt == 0)
    }
  }

  test("Gustavson modes merge partial products with identical coordinates") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGt

      dut.io.command.payload.gustavson.a(0).valid #= true
      dut.io.command.payload.gustavson.a(0).row #= 2
      dut.io.command.payload.gustavson.a(0).k #= 0
      dut.io.command.payload.gustavson.a(0).value #= 2
      dut.io.command.payload.gustavson.a(1).valid #= true
      dut.io.command.payload.gustavson.a(1).row #= 3
      dut.io.command.payload.gustavson.a(1).k #= 2
      dut.io.command.payload.gustavson.a(1).value #= 7

      val inputs = Seq(
        (0, 0, 0, 11),
        (0, 0, 1, 17),
        (1, 2, 1, 19),
        (0, 0, 1, 3)
      )
      for (((aIndex, k, column, value), lane) <- inputs.zipWithIndex) {
        dut.io.command.payload.gustavson.b(lane).valid #= true
        dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
        dut.io.command.payload.gustavson.b(lane).k #= k
        dut.io.command.payload.gustavson.b(lane).column #= column
        dut.io.command.payload.gustavson.b(lane).value #= value
      }

      issue(dut)
      assert(dut.io.response.payload.count.toInt == 3)
      val results = (0 until 3).map { index =>
        (dut.io.response.payload.results(index).row.toInt,
          dut.io.response.payload.results(index).column.toInt,
          dut.io.response.payload.results(index).value.toInt)
      }
      assert(results == Seq((2, 0, 22), (2, 1, 40), (3, 1, 133)))
    }
  }

  test("TrGT and TrGS accept sparse or dense lane populations by explicit mode") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val random = new scala.util.Random(0x117a)

      for (mode <- Seq(TrapezoidDataflow.TrGt, TrapezoidDataflow.TrGs);
           density <- Seq(0.25, 0.5, 1.0)) {
        clearInputs(dut)
        dut.io.command.payload.mode #= mode
        for (index <- 0 until cfg.macLanes) {
          dut.io.command.payload.gustavson.a(index).valid #= true
          dut.io.command.payload.gustavson.a(index).row #= index
          dut.io.command.payload.gustavson.a(index).k #= index
          dut.io.command.payload.gustavson.a(index).value #= index + 1
        }

        val expected = scala.collection.mutable.Map.empty[(Int, Int), Int]
        for (lane <- 0 until cfg.cacheLineWords) {
          val valid = random.nextDouble() < density
          val aIndex = lane % cfg.macLanes
          val column = lane % 2
          val value = lane + 2
          dut.io.command.payload.gustavson.b(lane).valid #= valid
          dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
          dut.io.command.payload.gustavson.b(lane).k #= aIndex
          dut.io.command.payload.gustavson.b(lane).column #= column
          dut.io.command.payload.gustavson.b(lane).value #= value
          if (valid) expected((aIndex, column)) = (aIndex + 1) * value
        }

        issue(dut)
        val observed = (0 until dut.io.response.payload.count.toInt).map { index =>
          val result = dut.io.response.payload.results(index)
          (result.row.toInt, result.column.toInt) -> result.value.toInt
        }.toMap
        assert(observed == expected.toMap)
      }
    }
  }
}
