package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class DualModeMrnNodeSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig.small
  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("dual-mode MRN reduces IP coordinates and merges Gustavson streams") {
    simConfig.compile(new DualModeMrnNode(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.output.ready #= true

      def drive(mode: MrnNodeMode.E,
                left: (Boolean, Int, Int, Int),
                right: (Boolean, Int, Int, Int)): Unit = {
        dut.io.input.payload.mode #= mode
        dut.io.input.payload.left.valid #= left._1
        dut.io.input.payload.left.row #= left._2
        dut.io.input.payload.left.column #= left._3
        dut.io.input.payload.left.value #= left._4
        dut.io.input.payload.right.valid #= right._1
        dut.io.input.payload.right.row #= right._2
        dut.io.input.payload.right.column #= right._3
        dut.io.input.payload.right.value #= right._4
        dut.io.input.valid #= true
        while (!dut.io.input.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.input.valid #= false
        while (!dut.io.output.valid.toBoolean) dut.clockDomain.waitSampling()
      }

      drive(MrnNodeMode.IpReduce,
        (true, 2, 3, 7), (true, 2, 3, 11))
      assert(dut.io.output.payload.first.valid.toBoolean)
      assert(dut.io.output.payload.first.value.toLong == 18)
      assert(!dut.io.output.payload.second.valid.toBoolean)
      assert(dut.io.output.payload.consumeLeft.toBoolean)
      assert(dut.io.output.payload.consumeRight.toBoolean)
      dut.clockDomain.waitSampling()

      drive(MrnNodeMode.GustavsonMerge,
        (true, 1, 7, 5), (true, 2, 0, 9))
      assert(dut.io.output.payload.first.row.toInt == 1)
      assert(dut.io.output.payload.first.column.toInt == 7)
      assert(dut.io.output.payload.first.value.toLong == 5)
      assert(dut.io.output.payload.consumeLeft.toBoolean)
      assert(!dut.io.output.payload.consumeRight.toBoolean)
    }
  }

  test("temporal merge can preempt a held spatial payload") {
    simConfig.compile(new DualModeMrnLevel(cfg, level = 0, Map(0 -> 1)))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.input.valid #= true
        dut.io.input.payload.mode #= TrapezoidDataflow.TrGs
        dut.io.input.payload.endOfTile #= false
        dut.io.input.payload.overflow #= false
        dut.io.input.payload.selectedColumns #= 0
        dut.io.input.payload.effectualMacs #= 0
        dut.io.output.ready #= false
        for (lane <- 0 until cfg.macLanes) {
          val entry = dut.io.input.payload.lanes(lane)
          entry.valid #= false
          entry.segmentStart #= false
          entry.row #= 0
          entry.column #= 0
          entry.destinationBank #= 0
          entry.destinationWordOffset #= 0
          entry.value #= 0
        }
        for (port <- 0 until cfg.localBanks) {
          dut.io.temporalInputs(port).valid #= false
          dut.io.temporalInputs(port).payload.mode #= MrnNodeMode.GustavsonMerge
          dut.io.temporalInputs(port).payload.left.valid #= false
          dut.io.temporalInputs(port).payload.left.row #= 0
          dut.io.temporalInputs(port).payload.left.column #= 0
          dut.io.temporalInputs(port).payload.left.value #= 0
          dut.io.temporalInputs(port).payload.right.valid #= false
          dut.io.temporalInputs(port).payload.right.row #= 0
          dut.io.temporalInputs(port).payload.right.column #= 0
          dut.io.temporalInputs(port).payload.right.value #= 0
          dut.io.temporalOutputs(port).ready #= true
        }

        val request = dut.io.temporalInputs(0)
        request.payload.left.valid #= true
        request.payload.left.row #= 3
        request.payload.left.column #= 5
        request.payload.left.value #= 7
        request.payload.right.valid #= true
        request.payload.right.row #= 3
        request.payload.right.column #= 5
        request.payload.right.value #= 11
        request.valid #= true
        sleep(1)
        assert(!dut.io.input.ready.toBoolean)
        assert(request.ready.toBoolean)
        dut.clockDomain.waitSampling()
        request.valid #= false
        while (!dut.io.temporalOutputs(0).valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        assert(dut.io.temporalOutputs(0).payload.first.value.toLong == 18)
      }
  }

  test("N-1 augmented tree reduces a six-lane segment across subtree boundaries") {
    val treeCfg = cfg.copy(maxFibers = 2, macLanes = 8, outputLanes = 4)
    simConfig.compile(new DualModeMrnTree(treeCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.output.ready #= true
      for (port <- 0 until treeCfg.localBanks) {
        dut.io.temporalInputs(port).valid #= false
        dut.io.temporalInputs(port).payload.mode #= MrnNodeMode.GustavsonMerge
        dut.io.temporalInputs(port).payload.left.valid #= false
        dut.io.temporalInputs(port).payload.left.row #= 0
        dut.io.temporalInputs(port).payload.left.column #= 0
        dut.io.temporalInputs(port).payload.left.value #= 0
        dut.io.temporalInputs(port).payload.right.valid #= false
        dut.io.temporalInputs(port).payload.right.row #= 0
        dut.io.temporalInputs(port).payload.right.column #= 0
        dut.io.temporalInputs(port).payload.right.value #= 0
        dut.io.temporalOutputs(port).ready #= true
      }

      dut.io.input.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.input.payload.endOfTile #= true
      dut.io.input.payload.overflow #= false
      dut.io.input.payload.responseTag #= 3
      dut.io.input.payload.selectedColumns #= 2
      dut.io.input.payload.effectualMacs #= 8
      for (lane <- 0 until treeCfg.macLanes) {
        val entry = dut.io.input.payload.lanes(lane)
        entry.valid #= true
        entry.segmentStart #= lane == 0 || lane == 6
        entry.row #= (if (lane < 6) 4 else 9)
        entry.column #= (if (lane < 6) 5 else 2)
        entry.destinationBank #= lane % treeCfg.localBanks
        entry.destinationWordOffset #= 0
        entry.value #= lane + 1
      }

      dut.io.input.valid #= true
      while (!dut.io.input.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.input.valid #= false
      while (!dut.io.output.valid.toBoolean) dut.clockDomain.waitSampling()

      assert(dut.io.output.payload.lanes(0).valid.toBoolean)
      assert(dut.io.output.payload.lanes(0).row.toInt == 4)
      assert(dut.io.output.payload.lanes(0).column.toInt == 5)
      assert(dut.io.output.payload.lanes(0).value.toLong == 21)
      assert(dut.io.output.payload.lanes(1).valid.toBoolean)
      assert(dut.io.output.payload.lanes(1).row.toInt == 9)
      assert(dut.io.output.payload.lanes(1).column.toInt == 2)
      assert(dut.io.output.payload.lanes(1).value.toLong == 15)
      for (lane <- 2 until treeCfg.macLanes) {
        assert(!dut.io.output.payload.lanes(lane).valid.toBoolean)
      }
      assert(!dut.io.output.payload.overflow.toBoolean)
    }
  }
}
