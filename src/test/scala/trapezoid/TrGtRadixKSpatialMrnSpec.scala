package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.core._
import spinal.lib._

private class TrGtRadixKMrnPipeline(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
  }
  val radix = new TrGtRadixKSpatialMrn(cfg)
  val tree = new DualModeMrnTree(cfg)
  radix.io.input << io.input
  tree.io.input << radix.io.output
  io.output << tree.io.output
  tree.io.program.valid := False
  tree.io.program.payload := SpatialMrnControlWrite(cfg).getZero
  for (port <- 0 until cfg.localBanks) {
    tree.io.temporalInputs(port).valid := False
    tree.io.temporalInputs(port).payload := DualModeMrnRequest(cfg).getZero
    tree.io.temporalOutputs(port).ready := True
  }
}

class TrGtRadixKSpatialMrnSpec extends AnyFunSuite {
  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("paper-width radix-K ingress emits one ordered TrGT head") {
    val cfg = TrapezoidConfig.paper.copy(peRows = 1)
    simConfig.compile(new TrGtRadixKSpatialMrn(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.output.ready #= true
      dut.io.input.payload.mode #= TrapezoidDataflow.TrGt
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

  test("paper-width radix-K ingress remains legal through the augmented tree") {
    val cfg = TrapezoidConfig.paper.copy(peRows = 1)
    simConfig.compile(new TrGtRadixKMrnPipeline(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.input.valid #= false
      dut.io.output.ready #= true
      dut.io.input.payload.mode #= TrapezoidDataflow.TrGt
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
}
