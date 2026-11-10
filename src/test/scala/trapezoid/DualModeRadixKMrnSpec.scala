package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.core._
import spinal.lib._

private class SharedRadixKMrnPipeline(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val program = slave(Flow(SpatialMrnControlWrite(cfg)))
  }
  val radix = new DualModeRadixKMrnTree(cfg)
  radix.io.input << io.input
  io.output << radix.io.output
  radix.io.program << io.program
  for (port <- 0 until cfg.localBanks) {
    radix.io.temporalInputs(port).valid := False
    radix.io.temporalInputs(port).payload := DualModeMrnRequest(cfg).getZero
    radix.io.temporalOutputs(port).ready := True
  }
}

class DualModeRadixKMrnSpec extends AnyFunSuite {
  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("shared MRN emits one ordered TrGT head") {
    val cfg = TrapezoidConfig.paper.copy(peRows = 1)
    simConfig.compile(new SharedRadixKMrnPipeline(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.program.valid #= false
      dut.io.output.ready #= true
      dut.io.input.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.input.payload.mrnProfile #= SpatialMrnProfile.OrderedMerge
      dut.io.input.payload.endOfTile #= true
      dut.io.input.payload.responseTag #= 3
      dut.io.input.payload.overflow #= false
      dut.io.input.payload.selectedColumns #= 0
      dut.io.input.payload.effectualMacs #= 0
      for (lane <- 0 until cfg.macLanes) {
        val entry = dut.io.input.payload.lanes(lane)
        entry.valid #= lane == 0
        entry.segmentStart #= lane == 0
        entry.row #= 5
        entry.column #= 7
        entry.destinationBank #= 0
        entry.destinationWordOffset #= 0
        entry.value #= (if (lane == 0) 11 else 0)
      }

      dut.io.input.valid #= true
      while (!dut.io.input.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.input.valid #= false
      dut.io.program.valid #= false
      while (!dut.io.output.valid.toBoolean) dut.clockDomain.waitSampling()

      assert(!dut.io.output.payload.overflow.toBoolean)
      assert(dut.io.output.payload.lanes(0).valid.toBoolean)
      assert(dut.io.output.payload.lanes(0).row.toInt == 5)
      assert(dut.io.output.payload.lanes(0).column.toInt == 7)
      assert(dut.io.output.payload.lanes(0).value.toBigInt == 11)
      for (lane <- 1 until cfg.macLanes) {
        assert(!dut.io.output.payload.lanes(lane).valid.toBoolean)
      }
    }
  }

  test("shared MRN forwards non-TrGT modes through the same tree") {
    val cfg = TrapezoidConfig.paper.copy(peRows = 1)
    simConfig.compile(new SharedRadixKMrnPipeline(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.output.ready #= true
      dut.io.input.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.input.payload.mrnProfile #= SpatialMrnProfile.IpReduction
      dut.io.input.payload.endOfTile #= true
      dut.io.input.payload.responseTag #= 3
      dut.io.input.payload.overflow #= false
      dut.io.input.payload.selectedColumns #= 0
      dut.io.input.payload.effectualMacs #= 0
      for (lane <- 0 until cfg.macLanes) {
        val entry = dut.io.input.payload.lanes(lane)
        entry.valid #= lane == 0
        entry.segmentStart #= lane == 0
        entry.row #= 5
        entry.column #= 7
        entry.destinationBank #= 0
        entry.destinationWordOffset #= 0
        entry.value #= (if (lane == 0) 11 else 0)
      }

      dut.io.input.valid #= true
      while (!dut.io.input.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.input.valid #= false
      while (!dut.io.output.valid.toBoolean) dut.clockDomain.waitSampling()

      assert(!dut.io.output.payload.overflow.toBoolean)
      assert(dut.io.output.payload.lanes(0).valid.toBoolean)
      assert(dut.io.output.payload.lanes(0).row.toInt == 5)
      assert(dut.io.output.payload.lanes(0).column.toInt == 7)
      assert(dut.io.output.payload.lanes(0).value.toBigInt == 11)
      for (lane <- 1 until cfg.macLanes) {
        assert(!dut.io.output.payload.lanes(lane).valid.toBoolean)
      }
    }
  }

  test("mapper node mode controls the shared spatial MRN") {
    val cfg = TrapezoidConfig.small.copy(peRows = 1)
    simConfig.compile(new SharedRadixKMrnPipeline(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.output.ready #= true
      dut.io.input.valid #= false
      dut.io.program.valid #= false
      dut.io.input.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.input.payload.mrnProfile #= SpatialMrnProfile.IpReduction
      dut.io.input.payload.endOfTile #= true
      dut.io.input.payload.responseTag #= 0
      dut.io.input.payload.overflow #= false
      dut.io.input.payload.selectedColumns #= 0
      dut.io.input.payload.effectualMacs #= 2
      for (lane <- 0 until cfg.macLanes) {
        val entry = dut.io.input.payload.lanes(lane)
        entry.valid #= lane < 2
        entry.segmentStart #= lane == 0
        entry.row #= (if (lane == 0) 5 else 1)
        entry.column #= 0
        entry.destinationBank #= 0
        entry.destinationWordOffset #= 0
        entry.value #= lane + 1
      }

      dut.io.program.payload.profile #= SpatialMrnProfile.IpReduction
      dut.io.program.payload.level #= 0
      dut.io.program.payload.node #= 0
      dut.io.program.payload.mode #= SpatialMrnNodeMode.OrderedMerge
      dut.io.program.payload.reduceOnEqual #= true
      dut.io.program.payload.checkOrdered #= true
      dut.io.program.payload.subtreeBase #= 0
      dut.io.program.payload.subtreeLength #= 2
      dut.io.program.payload.routeMode #= 0
      dut.io.program.payload.routeShift #= 0
      dut.io.program.payload.temporalEnable #= false
      dut.io.program.payload.temporalOwner #= cfg.localBanks
      dut.io.program.valid #= true
      dut.clockDomain.waitSampling()
      dut.io.program.valid #= false

      dut.io.input.valid #= true
      while (!dut.io.input.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.input.valid #= false
      while (!dut.io.output.valid.toBoolean) dut.clockDomain.waitSampling()
      assert(dut.io.output.payload.overflow.toBoolean)
    }
  }
}
