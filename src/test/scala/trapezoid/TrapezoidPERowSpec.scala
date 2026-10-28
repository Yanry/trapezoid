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

  private def clearInputs(
      dut: TrapezoidPERow,
      config: TrapezoidConfig = cfg): Unit = {
    dut.io.command.valid #= false
    dut.io.command.payload.endOfTile #= false
    dut.io.response.ready #= true
    for (fiber <- 0 until config.maxFibers) {
      dut.io.command.payload.trip.a(fiber).valid #= false
      dut.io.command.payload.trip.a(fiber).tag #= 0
      dut.io.command.payload.trip.a(fiber).mask #= 0
      dut.io.command.payload.trip.b(fiber).valid #= false
      dut.io.command.payload.trip.b(fiber).tag #= 0
      dut.io.command.payload.trip.b(fiber).mask #= 0
      for (entry <- 0 until config.fiberWidth) {
        dut.io.command.payload.trip.a(fiber).values(entry) #= 0
        dut.io.command.payload.trip.b(fiber).values(entry) #= 0
      }
    }
    for (entry <- 0 until config.macLanes) {
      dut.io.command.payload.gustavson.a(entry).valid #= false
      dut.io.command.payload.gustavson.a(entry).row #= 0
      dut.io.command.payload.gustavson.a(entry).k #= 0
      dut.io.command.payload.gustavson.a(entry).value #= 0
    }
    for (entry <- 0 until config.cacheLineWords) {
      dut.io.command.payload.gustavson.b(entry).valid #= false
      dut.io.command.payload.gustavson.b(entry).aIndex #= 0
      dut.io.command.payload.gustavson.b(entry).k #= 0
      dut.io.command.payload.gustavson.b(entry).column #= 0
      dut.io.command.payload.gustavson.b(entry).value #= 0
    }
  }

  private def issue(dut: TrapezoidPERow): Unit = {
    dut.io.command.valid #= true
    while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    dut.io.command.valid #= false
    while (!dut.io.response.valid.toBoolean) dut.clockDomain.waitSampling()
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
      val destinations = (0 until 3).map { index =>
        (dut.io.response.payload.destinationBank(index).toInt,
          dut.io.response.payload.destinationWordOffset(index).toInt)
      }
      assert(destinations == Seq((0, 0), (0, 1), (1, 1)))
    }
  }

  test("DenseIp full fibers use fixed lanes from arbitrary fiber slots") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.DenseIp

      dut.io.command.payload.trip.a(1).valid #= true
      dut.io.command.payload.trip.a(1).tag #= 6
      dut.io.command.payload.trip.a(1).mask #= 0xf
      for ((value, index) <- Seq(1, 2, 3, 4).zipWithIndex) {
        dut.io.command.payload.trip.a(1).values(index) #= value
      }
      dut.io.command.payload.trip.b(0).valid #= true
      dut.io.command.payload.trip.b(0).tag #= 7
      dut.io.command.payload.trip.b(0).mask #= 0xf
      for ((value, index) <- Seq(5, 6, 7, 8).zipWithIndex) {
        dut.io.command.payload.trip.b(0).values(index) #= value
      }

      issue(dut)
      assert(!dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.selectedColumns.toInt == 1)
      assert(dut.io.response.payload.effectualMacs.toInt == cfg.fiberWidth)
      assert(dut.io.response.payload.count.toInt == 1)
      assert(dut.io.response.payload.results(0).row.toInt == 6)
      assert(dut.io.response.payload.results(0).column.toInt == 7)
      assert(dut.io.response.payload.results(0).value.toInt == 70)
    }
  }

  test("TrIP prefix routing and reduction scale across four by four fibers") {
    val wideCfg = cfg.copy(maxFibers = 4, macLanes = 16, outputLanes = 16)
    simConfig.compile(new TrapezoidPERow(wideCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut, wideCfg)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrIp

      val aFibers = Seq(
        (0x9, Seq(2, -1)),
        (0x6, Seq(3, 4)),
        (0x5, Seq(-2, 5)),
        (0x8, Seq(6))
      )
      val bFibers = Seq(
        (0x1, Seq(7)),
        (0x6, Seq(2, -3)),
        (0x9, Seq(-4, 5)),
        (0x4, Seq(6))
      )
      for (fiber <- 0 until wideCfg.maxFibers) {
        dut.io.command.payload.trip.a(fiber).valid #= true
        dut.io.command.payload.trip.a(fiber).tag #= 10 + fiber
        dut.io.command.payload.trip.a(fiber).mask #= aFibers(fiber)._1
        for ((value, index) <- aFibers(fiber)._2.zipWithIndex) {
          dut.io.command.payload.trip.a(fiber).values(index) #= value
        }

        dut.io.command.payload.trip.b(fiber).valid #= true
        dut.io.command.payload.trip.b(fiber).tag #= 20 + fiber
        dut.io.command.payload.trip.b(fiber).mask #= bFibers(fiber)._1
        for ((value, index) <- bFibers(fiber)._2.zipWithIndex) {
          dut.io.command.payload.trip.b(fiber).values(index) #= value
        }
      }

      issue(dut)
      assert(!dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.selectedColumns.toInt == 4)
      assert(dut.io.response.payload.effectualMacs.toInt == 11)
      assert(dut.io.response.payload.count.toInt == 9)
      val results = (0 until 9).map { index =>
        val result = dut.io.response.payload.results(index)
        (result.row.toInt, result.column.toInt, result.value.toInt)
      }
      assert(results == Seq(
        (10, 20, 14),
        (10, 22, -13),
        (11, 21, -6),
        (11, 23, 24),
        (12, 20, -14),
        (12, 21, -15),
        (12, 22, 8),
        (12, 23, 30),
        (13, 22, 30)
      ))
    }
  }

  test("paper-width Benes control cache amortizes repeated route preparation") {
    val routedCfg = cfg.copy(
      fiberWidth = 8,
      maxFibers = 4,
      macLanes = 32,
      outputLanes = 16,
      localDepthPerBank = 16)
    simConfig.compile(new TrapezoidPERow(routedCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut, routedCfg)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.command.payload.trip.a(0).valid #= true
      dut.io.command.payload.trip.a(0).tag #= 1
      dut.io.command.payload.trip.a(0).mask #= 0xff
      dut.io.command.payload.trip.b(0).valid #= true
      dut.io.command.payload.trip.b(0).tag #= 2
      dut.io.command.payload.trip.b(0).mask #= 0xff
      for (entry <- 0 until routedCfg.fiberWidth) {
        dut.io.command.payload.trip.a(0).values(entry) #= entry + 1
        dut.io.command.payload.trip.b(0).values(entry) #= entry + 2
      }

      issue(dut)
      val firstPrepareCycles = dut.io.controlPrepareCycles.toLong
      assert(firstPrepareCycles > 0,
        "the first unseen route pattern must run the exact Benes solver")
      dut.clockDomain.waitSampling()

      issue(dut)
      val repeatedPrepareCycles =
        dut.io.controlPrepareCycles.toLong - firstPrepareCycles
      assert(repeatedPrepareCycles <= 1,
        "a cached route pattern must bypass the multi-cycle Benes solver")

      dut.io.command.payload.endOfTile #= true
      issue(dut)
      val tileEndPrepareCycles = dut.io.controlPrepareCycles.toLong
      dut.io.command.payload.endOfTile #= false
      issue(dut)
      val nextTilePrepareCycles =
        dut.io.controlPrepareCycles.toLong - tileEndPrepareCycles
      assert(nextTilePrepareCycles > 0,
        "the first route pattern of a new tile must start from a cold cache")
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

  test("Gustavson modes sort coordinates and merge identical partial products") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGt

      dut.io.command.payload.gustavson.a(0).valid #= true
      dut.io.command.payload.gustavson.a(0).row #= 2
      dut.io.command.payload.gustavson.a(0).k #= 0
      dut.io.command.payload.gustavson.a(0).value #= 2
      dut.io.command.payload.gustavson.a(1).valid #= true
      dut.io.command.payload.gustavson.a(1).row #= 2
      dut.io.command.payload.gustavson.a(1).k #= 1
      dut.io.command.payload.gustavson.a(1).value #= 2
      dut.io.command.payload.gustavson.a(2).valid #= true
      dut.io.command.payload.gustavson.a(2).row #= 2
      dut.io.command.payload.gustavson.a(2).k #= 2
      dut.io.command.payload.gustavson.a(2).value #= 2
      dut.io.command.payload.gustavson.a(3).valid #= true
      dut.io.command.payload.gustavson.a(3).row #= 3
      dut.io.command.payload.gustavson.a(3).k #= 3
      dut.io.command.payload.gustavson.a(3).value #= 7

      val inputs = Seq(
        (0, 0, 1, 17),
        (1, 1, 1, 3),
        (2, 2, 0, 11),
        (3, 3, 1, 19)
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

  test("TrGS reports an unordered cache line instead of silently misrouting it") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.command.payload.gustavson.a(0).valid #= true
      dut.io.command.payload.gustavson.a(0).row #= 2
      dut.io.command.payload.gustavson.a(0).k #= 0
      dut.io.command.payload.gustavson.a(0).value #= 3
      for ((column, lane) <- Seq(3, 1).zipWithIndex) {
        dut.io.command.payload.gustavson.b(lane).valid #= true
        dut.io.command.payload.gustavson.b(lane).aIndex #= 0
        dut.io.command.payload.gustavson.b(lane).k #= 0
        dut.io.command.payload.gustavson.b(lane).column #= column
        dut.io.command.payload.gustavson.b(lane).value #= lane + 2
      }

      issue(dut)
      assert(dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.count.toInt == 0)
    }
  }

  test("TrGT rejects a B element routed through the wrong subrow") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.command.payload.gustavson.a(1).valid #= true
      dut.io.command.payload.gustavson.a(1).k #= 1
      dut.io.command.payload.gustavson.a(1).value #= 3
      dut.io.command.payload.gustavson.b(0).valid #= true
      dut.io.command.payload.gustavson.b(0).aIndex #= 1
      dut.io.command.payload.gustavson.b(0).k #= 1
      dut.io.command.payload.gustavson.b(0).value #= 5

      issue(dut)
      assert(dut.io.response.payload.overflow.toBoolean)
      assert(dut.io.response.payload.count.toInt == 0)
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
          val aIndex = if (mode == TrapezoidDataflow.TrGs) 0
            else lane % cfg.macLanes
          val column = lane
          val value = lane + 2
          dut.io.command.payload.gustavson.b(lane).valid #= valid
          dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
          dut.io.command.payload.gustavson.b(lane).k #= aIndex
          dut.io.command.payload.gustavson.b(lane).column #= column
          dut.io.command.payload.gustavson.b(lane).value #= value
          if (valid) {
            val key = aIndex -> column
            expected(key) = expected.getOrElse(key, 0) + (aIndex + 1) * value
          }
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

  test("pipeline accepts ordered commands and holds responses under backpressure") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.response.ready #= false
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.command.payload.gustavson.a(0).valid #= true
      dut.io.command.payload.gustavson.a(0).k #= 0
      dut.io.command.payload.gustavson.b(0).valid #= true
      dut.io.command.payload.gustavson.b(0).aIndex #= 0
      dut.io.command.payload.gustavson.b(0).k #= 0
      dut.io.command.payload.gustavson.b(0).column #= 3

      def send(row: Int, aValue: Int, bValue: Int): Unit = {
        dut.io.command.payload.gustavson.a(0).row #= row
        dut.io.command.payload.gustavson.a(0).value #= aValue
        dut.io.command.payload.gustavson.b(0).value #= bValue
        dut.io.command.valid #= true
        while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.command.valid #= false
      }

      send(row = 1, aValue = 2, bValue = 3)
      send(row = 2, aValue = 4, bValue = 5)

      while (!dut.io.response.valid.toBoolean) dut.clockDomain.waitSampling()
      assert(dut.io.response.payload.results(0).row.toInt == 1)
      assert(dut.io.response.payload.results(0).value.toInt == 6)
      for (_ <- 0 until 3) {
        dut.clockDomain.waitSampling()
        assert(dut.io.response.valid.toBoolean)
        assert(dut.io.response.payload.results(0).row.toInt == 1)
        assert(dut.io.response.payload.results(0).value.toInt == 6)
      }

      dut.io.response.ready #= true
      sleep(1)
      assert(dut.io.response.valid.toBoolean)
      assert(dut.io.response.payload.results(0).row.toInt == 1)
      dut.clockDomain.waitSampling()
      dut.io.response.ready #= false
      sleep(1)
      while (!dut.io.response.valid.toBoolean) dut.clockDomain.waitSampling()
      assert(dut.io.response.payload.results(0).row.toInt == 2)
      assert(dut.io.response.payload.results(0).value.toInt == 20)
    }
  }

  test("configured pipeline depth matches accepted-command response latency") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.command.payload.gustavson.a(0).valid #= true
      dut.io.command.payload.gustavson.a(0).row #= 1
      dut.io.command.payload.gustavson.a(0).k #= 0
      dut.io.command.payload.gustavson.a(0).value #= 2
      dut.io.command.payload.gustavson.b(0).valid #= true
      dut.io.command.payload.gustavson.b(0).aIndex #= 0
      dut.io.command.payload.gustavson.b(0).k #= 0
      dut.io.command.payload.gustavson.b(0).column #= 3
      dut.io.command.payload.gustavson.b(0).value #= 4

      dut.io.command.valid #= true
      while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.command.valid #= false

      var elapsedCycles = 0
      while (!dut.io.response.valid.toBoolean) {
        dut.clockDomain.waitSampling()
        elapsedCycles += 1
      }
      assert(elapsedCycles == cfg.pePipelineStages)
      assert(dut.io.response.payload.results(0).value.toInt == 8)
    }
  }
}
