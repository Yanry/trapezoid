package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

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
  private val headFifoTileCfg =
    GustavsonTileConfig(cfg, accumulatorEntries = 8)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private class HeadFifoHarness extends Component {
    val io = new Bundle {
      val allowMerge = in Bool()
      val clear = in Bool()
      val command = slave(Stream(PERowCommand(cfg)))
      val result = master(Stream(MatrixResult(cfg)))
      val peCommand = master(Stream(PERowCommand(cfg)))
      val peResponse = slave(Stream(PERowResponse(cfg)))
      val done = out Bool()
    }
    val engine = new GustavsonTileEngine(headFifoTileCfg, externalPe = true)
    val mrn = new DualModeMrnNode(cfg)

    engine.io.clear := io.clear
    engine.io.command << io.command
    io.result << engine.io.result
    io.peCommand << engine.io.peCommand
    engine.io.peResponse << io.peResponse
    io.done := engine.io.done

    mrn.io.input.valid := engine.io.peTemporalMrnInput.valid && io.allowMerge
    mrn.io.input.payload := engine.io.peTemporalMrnInput.payload
    engine.io.peTemporalMrnInput.ready := mrn.io.input.ready && io.allowMerge
    engine.io.peTemporalMrnOutput << mrn.io.output
  }

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

  test("TrGT temporal merge accepts one ordered decision per cycle") {
    simConfig.compile {
      val dut = new GustavsonTileEngine(tileCfg)
      dut.temporalMrnInput.valid.simPublic()
      dut.temporalMrnInput.ready.simPublic()
      dut.temporalMrnInput.payload.left.valid.simPublic()
      dut.temporalMrnInput.payload.right.valid.simPublic()
      dut.trGtResponseFire.simPublic()
      dut.trGtDequeue.simPublic()
      dut
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.result.ready #= true
      clearPayload(dut)

      val pairedRequestCycles = mutable.ArrayBuffer.empty[Long]
      val responseCycles = mutable.ArrayBuffer.empty[Long]
      val dequeueCycles = mutable.ArrayBuffer.empty[Long]
      var cycle = 0L
      val monitor = fork {
        while ((pairedRequestCycles.size < 4 || responseCycles.size < 2 ||
            dequeueCycles.size < 8) && cycle < 500) {
          sleep(1)
          if (dut.trGtResponseFire.toBoolean) responseCycles += cycle
          if (dut.trGtDequeue.toBoolean) dequeueCycles += cycle
          if (dut.temporalMrnInput.valid.toBoolean &&
              dut.temporalMrnInput.ready.toBoolean &&
              dut.temporalMrnInput.payload.left.valid.toBoolean &&
              dut.temporalMrnInput.payload.right.valid.toBoolean) {
            pairedRequestCycles += cycle
          }
          dut.clockDomain.waitSampling()
          cycle += 1
        }
      }

      issue(dut, TrapezoidDataflow.TrGt, endOfTile = false,
        a = (0 until 4).map(k => (0, k, 2)),
        b = (0 until 4).map(k => (k, k, k, k + 1)))
      while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
      issue(dut, TrapezoidDataflow.TrGt, endOfTile = true,
        a = (0 until 4).map(k => (0, k, 2)),
        b = (0 until 4).map(k => (k, k, k, k + 1)))
      val observed = collect(dut)
      monitor.join()

      assert(observed.map(x => (x._2, x._3)) ==
        Seq((0, 4L), (1, 8L), (2, 12L), (3, 16L)))
      assert(pairedRequestCycles.size == 4)
      assert(pairedRequestCycles.sliding(2).forall {
        case Seq(left, right) => right == left + 1
        case _ => true
      }, s"temporal merge was not II=1: ${pairedRequestCycles.mkString(",")}")
      assert(responseCycles.size == 2)
      assert(dequeueCycles.size == 8)
    }
  }

  test("TrGT head FIFOs retain packed-group streams across PE responses") {
    simConfig.compile {
      val dut = new HeadFifoHarness
      dut.engine.io.flatten.foreach(_.simPublic())
      dut.engine.trGtHeadFifoCounts.foreach(_.simPublic())
      dut
    }.doSim { dut =>
      val engine = dut.engine
      dut.clockDomain.forkStimulus(10)
      dut.io.allowMerge #= false
      dut.io.clear #= false
      dut.io.result.ready #= true
      dut.io.peCommand.ready #= true
      dut.io.peResponse.valid #= false
      dut.io.command.valid #= false

      def issueExternal(endOfTile: Boolean): Unit = {
        dut.io.command.payload.mode #= TrapezoidDataflow.TrGt
        dut.io.command.payload.endOfTile #= endOfTile
        dut.io.command.valid #= true
        var timeout = 0
        while (!dut.io.command.ready.toBoolean && timeout < 100) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(dut.io.command.ready.toBoolean,
          "external packed-group command timed out")
        dut.clockDomain.waitSampling()
        dut.io.command.valid #= false
      }

      def sendResponse(columns: Seq[Int]): Unit = {
        dut.io.peResponse.payload.mode #= TrapezoidDataflow.TrGt
        dut.io.peResponse.payload.overflow #= false
        dut.io.peResponse.payload.selectedColumns #= cfg.maxFibers
        dut.io.peResponse.payload.effectualMacs #= columns.size
        dut.io.peResponse.payload.count #= columns.size
        for (lane <- 0 until cfg.outputLanes) {
          val valid = lane < columns.size
          dut.io.peResponse.payload.results(lane).valid #= valid
          dut.io.peResponse.payload.results(lane).row #= 0
          dut.io.peResponse.payload.results(lane).column #=
            (if (valid) columns(lane) else 0)
          dut.io.peResponse.payload.results(lane).value #=
            (if (valid) columns(lane) else 0)
          dut.io.peResponse.payload.results(lane).endOfTile #= false
          dut.io.peResponse.payload.destinationBank(lane) #= lane
          dut.io.peResponse.payload.destinationWordOffset(lane) #= 0
        }
        dut.io.peResponse.valid #= true
        var timeout = 0
        while (!dut.io.peResponse.ready.toBoolean && timeout < 100) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(dut.io.peResponse.ready.toBoolean,
          "packed-group response was backpressured")
        dut.clockDomain.waitSampling()
        dut.io.peResponse.valid #= false
      }

      issueExternal(endOfTile = false)
      sendResponse(Seq(1, 3, 5, 7))
      issueExternal(endOfTile = true)
      sendResponse(Seq(2, 4, 6, 8))
      dut.clockDomain.waitSampling()

      assert(engine.trGtHeadFifoCounts.map(_.toInt).sum == 8,
        "both PE responses must reside in the four source FIFOs")
      dut.io.allowMerge #= true
      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 500) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong,
            dut.io.result.payload.endOfTile.toBoolean))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.done.toBoolean, "cross-response merge timed out")
      assert(observed.map(x => (x._2, x._3)) ==
        (1 to 8).map(value => (value, value.toLong)))
    }
  }

  test("TrGT radix-K merge consumes four independent subrow heads") {
    simConfig.compile {
      val dut = new GustavsonTileEngine(tileCfg)
      dut.mergeControl.io.request.valid.simPublic()
      dut.mergeControl.io.request.ready.simPublic()
      dut.mergeControl.io.request.payload.right.column.simPublic()
      dut.mergeControl.io.selectedRightIndex.simPublic()
      dut
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.result.ready #= true
      clearPayload(dut)

      val selectedSources = mutable.ArrayBuffer.empty[Int]
      val selectedColumns = mutable.ArrayBuffer.empty[Int]
      val monitor = fork {
        var timeout = 0
        while (selectedSources.size < cfg.localBanks && timeout < 500) {
          sleep(1)
          if (dut.mergeControl.io.request.valid.toBoolean &&
              dut.mergeControl.io.request.ready.toBoolean) {
            selectedSources += dut.mergeControl.io.selectedRightIndex.toInt
            selectedColumns +=
              dut.mergeControl.io.request.payload.right.column.toInt
          }
          dut.clockDomain.waitSampling()
          timeout += 1
        }
      }

      val columns = Seq(7, 3, 9, 4)
      issue(dut, TrapezoidDataflow.TrGt, endOfTile = true,
        a = (0 until cfg.localBanks).map(k => (0, k, 2)),
        b = columns.zipWithIndex.map { case (column, source) =>
          (source, source, column, source + 1)
        })
      val observed = collect(dut)
      monitor.join()

      assert(selectedSources == Seq(1, 3, 0, 2))
      assert(selectedColumns == Seq(3, 4, 7, 9))
      assert(observed.map(x => (x._2, x._3)) ==
        Seq((3, 4L), (4, 8L), (7, 2L), (9, 6L)))
    }
  }

  test("radix-K MRN control selects the minimum head and coalesces matches") {
    simConfig.compile(new GustavsonRadixKMrnControl(cfg, cfg.localBanks))
      .doSim { dut =>
        dut.io.request.ready #= true
        dut.io.oldHead.valid #= true
        dut.io.oldHead.row #= 0
        dut.io.oldHead.column #= 5
        dut.io.oldHead.value #= 11
        val columns = Seq(7, 3, 9, 4)
        for ((column, source) <- columns.zipWithIndex) {
          dut.io.rightHeads(source).valid #= true
          dut.io.rightHeads(source).row #= 0
          dut.io.rightHeads(source).column #= column
          dut.io.rightHeads(source).value #= column
        }

        sleep(1)
        assert(dut.io.selectedRightIndex.toInt == 1)
        assert(dut.io.request.payload.right.column.toInt == 3)
        assert(!dut.io.consumeLeft.toBoolean)
        assert(dut.io.consumeRight.toBoolean)

        dut.io.oldHead.column #= 3
        sleep(1)
        assert(dut.io.consumeLeft.toBoolean)
        assert(dut.io.consumeRight.toBoolean)
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
