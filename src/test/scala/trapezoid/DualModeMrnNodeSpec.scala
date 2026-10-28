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
}
