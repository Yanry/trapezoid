package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
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
    dut.io.command.payload.responseTag #= 0
    dut.io.mrnProgram.valid #= false
    dut.io.response.ready #= true
    for (port <- 0 until config.localBanks) {
      dut.io.temporalMrnInputs(port).valid #= false
      dut.io.temporalMrnInputs(port).payload.mode #= MrnNodeMode.GustavsonMerge
      dut.io.temporalMrnInputs(port).payload.left.valid #= false
      dut.io.temporalMrnInputs(port).payload.left.row #= 0
      dut.io.temporalMrnInputs(port).payload.left.column #= 0
      dut.io.temporalMrnInputs(port).payload.left.value #= 0
      dut.io.temporalMrnInputs(port).payload.right.valid #= false
      dut.io.temporalMrnInputs(port).payload.right.row #= 0
      dut.io.temporalMrnInputs(port).payload.right.column #= 0
      dut.io.temporalMrnInputs(port).payload.right.value #= 0
      dut.io.temporalMrnOutputs(port).ready #= true
    }
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

  test("PE-row level-zero MRN cell serves temporal Gustavson merges") {
    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut)

      def drive(
          left: (Boolean, Int, Int, Int),
          right: (Boolean, Int, Int, Int)): Unit = {
        dut.io.temporalMrnInputs(0).payload.left.valid #= left._1
        dut.io.temporalMrnInputs(0).payload.left.row #= left._2
        dut.io.temporalMrnInputs(0).payload.left.column #= left._3
        dut.io.temporalMrnInputs(0).payload.left.value #= left._4
        dut.io.temporalMrnInputs(0).payload.right.valid #= right._1
        dut.io.temporalMrnInputs(0).payload.right.row #= right._2
        dut.io.temporalMrnInputs(0).payload.right.column #= right._3
        dut.io.temporalMrnInputs(0).payload.right.value #= right._4
        dut.io.temporalMrnInputs(0).valid #= true
        while (!dut.io.temporalMrnInputs(0).ready.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()
        dut.io.temporalMrnInputs(0).valid #= false
        while (!dut.io.temporalMrnOutputs(0).valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
      }

      drive((true, 2, 3, 7), (true, 2, 3, 11))
      assert(dut.io.temporalMrnOutputs(0).payload.first.valid.toBoolean)
      assert(dut.io.temporalMrnOutputs(0).payload.first.value.toLong == 18)
      assert(dut.io.temporalMrnOutputs(0).payload.consumeLeft.toBoolean)
      assert(dut.io.temporalMrnOutputs(0).payload.consumeRight.toBoolean)
      dut.clockDomain.waitSampling()

      drive((true, 1, 7, 5), (true, 2, 0, 9))
      assert(dut.io.temporalMrnOutputs(0).payload.first.row.toInt == 1)
      assert(dut.io.temporalMrnOutputs(0).payload.first.column.toInt == 7)
      assert(dut.io.temporalMrnOutputs(0).payload.first.value.toLong == 5)
      assert(dut.io.temporalMrnOutputs(0).payload.consumeLeft.toBoolean)
      assert(!dut.io.temporalMrnOutputs(0).payload.consumeRight.toBoolean)
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

  test("backtracking TrIP commands retain operands and tags under response backpressure") {
    val routedCfg = cfg.copy(fiberWidth = 8, maxFibers = 4,
      macLanes = 16, outputLanes = 16)
    val backtrackingA = Vector(224, 206, 18, 34)
    val backtrackingB = Vector(160, 33, 198, 1)
    val commands = Vector(
      ("TrIp", backtrackingA, backtrackingB),
      ("TrIp", backtrackingA, backtrackingB),
      ("DenseIp", Vector(255, 0, 0, 0), Vector(255, 0, 0, 0)),
      ("TrIp", Vector(0, 0, 0, 0), Vector(1, 0, 0, 0)),
      ("TrIp", Vector.fill(4)(255), Vector.fill(4)(255)),
      ("TrIp", backtrackingA, backtrackingB))
    def aValue(command: Int, fiber: Int, coordinate: Int): Int =
      1 + command + 3 * fiber + coordinate
    def bValue(command: Int, fiber: Int, coordinate: Int): Int =
      2 + command + 5 * fiber + coordinate
    val expected = commands.zipWithIndex.map { case ((_, aMasks, bMasks), command) =>
      (for {
        aFiber <- 0 until 4
        bFiber <- 0 until 4
        sum = (0 until 8).filter(coordinate =>
          (aMasks(aFiber) & bMasks(bFiber) & (1 << coordinate)) != 0)
          .map(coordinate => aValue(command, aFiber, coordinate) *
            bValue(command, bFiber, coordinate)).sum
        if sum != 0
      } yield ((10 + aFiber, 20 + bFiber), sum)).toMap
    }
    simConfig.compile(new TrapezoidPERow(routedCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut, routedCfg)
      dut.clockDomain.waitSampling(10)
      var sent = 0
      var received = 0
      var cycle = 0
      var stalledResponse: Option[Vector[BigInt]] = None
      while (received < commands.size && cycle < 20000) {
        dut.io.command.valid #= sent < commands.size
        dut.io.response.ready #= cycle % 13 >= 7
        if (sent < commands.size) {
          val (mode, aMasks, bMasks) = commands(sent)
          dut.io.command.payload.mode #=
            (if (mode == "DenseIp") TrapezoidDataflow.DenseIp else TrapezoidDataflow.TrIp)
          dut.io.command.payload.responseTag #= sent
          dut.io.command.payload.endOfTile #= (sent == commands.size - 1)
          for (fiber <- 0 until 4) {
            dut.io.command.payload.trip.a(fiber).valid #= (aMasks(fiber) != 0)
            dut.io.command.payload.trip.a(fiber).mask #= aMasks(fiber)
            dut.io.command.payload.trip.a(fiber).tag #= 10 + fiber
            dut.io.command.payload.trip.b(fiber).valid #= (bMasks(fiber) != 0)
            dut.io.command.payload.trip.b(fiber).mask #= bMasks(fiber)
            dut.io.command.payload.trip.b(fiber).tag #= 20 + fiber
            val aCoordinates = (0 until 8).filter(coordinate =>
              (aMasks(fiber) & (1 << coordinate)) != 0)
            val bCoordinates = (0 until 8).filter(coordinate =>
              (bMasks(fiber) & (1 << coordinate)) != 0)
            for (entry <- 0 until 8) {
              dut.io.command.payload.trip.a(fiber).values(entry) #=
                (if (entry < aCoordinates.size) aValue(sent, fiber, aCoordinates(entry)) else 0)
              dut.io.command.payload.trip.b(fiber).values(entry) #=
                (if (entry < bCoordinates.size) bValue(sent, fiber, bCoordinates(entry)) else 0)
            }
          }
        }
        sleep(1)
        if (dut.io.command.valid.toBoolean && dut.io.command.ready.toBoolean) sent += 1
        if (dut.io.response.valid.toBoolean) {
          val response = dut.io.response.payload
          val snapshot = Vector(response.responseTag.toBigInt,
            response.count.toBigInt, response.selectedColumns.toBigInt,
            response.effectualMacs.toBigInt,
            BigInt(if (response.overflow.toBoolean) 1 else 0)) ++
            (0 until routedCfg.outputLanes).flatMap { index =>
              val result = response.results(index)
              Vector(result.row.toBigInt, result.column.toBigInt, result.value.toBigInt,
                BigInt(if (result.valid.toBoolean) 1 else 0),
                BigInt(if (result.endOfTile.toBoolean) 1 else 0),
                response.destinationBank(index).toBigInt,
                response.destinationWordOffset(index).toBigInt)
            }
          stalledResponse.foreach(previous => assert(previous == snapshot))
          if (dut.io.response.ready.toBoolean) {
            assert(response.responseTag.toInt == received)
            assert(response.overflow.toBoolean == (received == 4))
            if (received == 4) {
              assert(response.count.toInt == 0)
            } else {
              val observed = (0 until response.count.toInt).map { index =>
                val result = response.results(index)
                assert(result.endOfTile.toBoolean == (received == commands.size - 1))
                ((result.row.toInt, result.column.toInt), result.value.toInt)
              }.toMap
              assert(observed == expected(received))
            }
            received += 1
            stalledResponse = None
          } else {
            stalledResponse = Some(snapshot)
          }
        } else {
          assert(stalledResponse.isEmpty)
        }
        dut.clockDomain.waitSampling()
        cycle += 1
      }
      assert(sent == commands.size && received == commands.size)
      assert(dut.io.controlPrepareCycles.toLong > 0)
    }
  }

  test("a completed unroutable A control returns overflow and releases the next command") {
    val routedCfg = cfg.copy(fiberWidth = 8, maxFibers = 4,
      macLanes = 16, outputLanes = 16)
    class FaultInjectedPERow extends TrapezoidPERow(routedCfg) {
      val injectFailure = in Bool()
      aResult.routable.allowOverride
      when(injectFailure) { aResult.routable := False }
    }
    simConfig.compile(new FaultInjectedPERow).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      clearInputs(dut, routedCfg)
      dut.injectFailure #= true
      dut.io.command.payload.mode #= TrapezoidDataflow.TrIp
      val aMasks = Vector(224, 206, 18, 34)
      val bMasks = Vector(160, 33, 198, 1)
      for (fiber <- 0 until 4) {
        dut.io.command.payload.trip.a(fiber).valid #= true
        dut.io.command.payload.trip.a(fiber).tag #= fiber
        dut.io.command.payload.trip.a(fiber).mask #= aMasks(fiber)
        dut.io.command.payload.trip.b(fiber).valid #= true
        dut.io.command.payload.trip.b(fiber).tag #= fiber
        dut.io.command.payload.trip.b(fiber).mask #= bMasks(fiber)
        for (entry <- 0 until 8) {
          dut.io.command.payload.trip.a(fiber).values(entry) #= 1
          dut.io.command.payload.trip.b(fiber).values(entry) #= 1
        }
      }
      for (transaction <- 0 until 2) {
        dut.io.command.payload.responseTag #= transaction
        dut.io.command.valid #= true
        sleep(1)
        while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.command.valid #= false
        sleep(1)
        var cycles = 0
        while (!dut.io.response.valid.toBoolean && cycles < 10000) {
          dut.clockDomain.waitSampling()
          sleep(1)
          cycles += 1
        }
        assert(dut.io.response.valid.toBoolean)
        assert(dut.io.response.payload.responseTag.toInt == transaction)
        assert(dut.io.response.payload.overflow.toBoolean == (transaction == 0))
        assert(dut.io.response.payload.count.toInt == (if (transaction == 0) 0 else 9))
        if (transaction == 1) {
          assert((0 until 9).map(index =>
            dut.io.response.payload.results(index).value.toInt).sum == 14)
        }
        dut.clockDomain.waitSampling()
        dut.injectFailure #= false
      }
    }
  }

  test("Benes fast paths and per-operand caches work at small and paper widths") {
    for (laneCount <- Seq(16, 32)) {
      val routedCfg = cfg.copy(fiberWidth = 8, maxFibers = 4,
        macLanes = laneCount, outputLanes = 16, localDepthPerBank = 16)
      simConfig.compile(new TrapezoidPERow(routedCfg)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        clearInputs(dut, routedCfg)
        dut.io.command.payload.mode #= TrapezoidDataflow.TrIp
        dut.io.command.payload.trip.a(0).valid #= true
        dut.io.command.payload.trip.a(0).tag #= 1
        dut.io.command.payload.trip.a(0).mask #= 1
        dut.io.command.payload.trip.a(0).values(0) #= 3
        dut.io.command.payload.trip.b(0).valid #= true
        dut.io.command.payload.trip.b(0).tag #= 2
        dut.io.command.payload.trip.b(0).mask #= 1
        dut.io.command.payload.trip.b(0).values(0) #= 5
        issue(dut)
        assert(dut.io.controlPrepareCycles.toLong == 0)
        assert(dut.io.response.payload.results(0).value.toInt == 15)
        dut.clockDomain.waitSampling()

        dut.io.command.payload.trip.a(0).mask #= 255
        dut.io.command.payload.trip.b(0).mask #= 255
        for (entry <- 0 until 8) {
          dut.io.command.payload.trip.a(0).values(entry) #= entry + 1
          dut.io.command.payload.trip.b(0).values(entry) #= entry + 2
        }
        issue(dut)
        assert(dut.io.controlPrepareCycles.toLong == 0)
        assert(dut.io.benesPerformance.aSearches.toLong == 0)
        assert(dut.io.benesPerformance.bSearches.toLong == 0)
        dut.clockDomain.waitSampling()

        dut.io.command.payload.trip.a(1).valid #= true
        dut.io.command.payload.trip.a(1).tag #= 3
        dut.io.command.payload.trip.a(1).mask #= 255
        for (entry <- 0 until 8) {
          dut.io.command.payload.trip.a(1).values(entry) #= entry + 3
        }
        issue(dut)
        assert(!dut.io.response.payload.overflow.toBoolean)
        assert(dut.io.response.payload.count.toInt == 2)
        assert(dut.io.benesPerformance.aSearches.toLong == 0)
        assert(dut.io.benesPerformance.bSearches.toLong == 1)
        val firstPrepare = dut.io.controlPrepareCycles.toLong
        assert(firstPrepare > 0 && firstPrepare < 1000)
        dut.clockDomain.waitSampling()

        for (entry <- 0 until 8) {
          dut.io.command.payload.trip.b(0).values(entry) #= entry + 4
        }
        issue(dut)
        assert(dut.io.controlPrepareCycles.toLong == firstPrepare)
        assert(dut.io.benesPerformance.bSearches.toLong == 1)
        assert(dut.io.benesPerformance.bCacheHits.toLong == 1)
        assert(dut.io.response.payload.results(0).value.toInt ==
          (0 until 8).map(entry => (entry + 1) * (entry + 4)).sum)
        dut.clockDomain.waitSampling()

        dut.io.command.payload.trip.a(1).mask #= 15
        issue(dut)
        assert(dut.io.controlPrepareCycles.toLong == firstPrepare)
        assert(dut.io.benesPerformance.bSearches.toLong == 1)
        assert(dut.io.benesPerformance.bCacheHits.toLong == 2)
        assert(dut.io.response.payload.results(1).value.toInt ==
          (0 until 4).map(entry => (entry + 3) * (entry + 4)).sum)
        dut.clockDomain.waitSampling()
        dut.io.command.payload.trip.a(1).mask #= 255
        dut.io.command.payload.endOfTile #= true
        issue(dut)
        dut.clockDomain.waitSampling()
        dut.io.command.payload.endOfTile #= false
        issue(dut)
        assert(dut.io.benesPerformance.aSearches.toLong == 0)
        assert(dut.io.benesPerformance.bSearches.toLong == 2)
        assert(dut.io.controlPrepareCycles.toLong > firstPrepare)
      }
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
        dut.clockDomain.waitSampling()
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
      sleep(1)
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
