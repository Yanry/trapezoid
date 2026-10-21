package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

class BenesDistributionFabricSpec extends AnyFunSuite {
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

  private def payload(value: Int): Int = (value << 1) | 1

  test("Benes fabric routes identity and multicast controls") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new BenesDistributionFabric(cfg))
      .doSim { dut =>
        for (index <- 0 until cfg.macLanes) {
          dut.io.sources(index) #= payload(10 + index)
        }

        dut.io.controls #= ((BigInt(1) << 1) | (BigInt(1) << 3) |
          (BigInt(1) << 5) | (BigInt(1) << 7) |
          (BigInt(1) << 9) | (BigInt(1) << 11))
        sleep(1)
        for (index <- 0 until cfg.macLanes) {
          assert(dut.io.outputs(index).toInt == payload(10 + index))
        }

        dut.io.controls #= 0
        sleep(1)
        for (index <- 0 until cfg.macLanes) {
          assert(dut.io.outputs(index).toInt == payload(10))
        }
      }
  }

  test("runtime broadcast generator selects every requested source") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new BenesBroadcastNetwork(cfg))
      .doSim { dut =>
        for (index <- 0 until cfg.macLanes) {
          dut.io.sources(index) #= payload(20 + index)
        }

        for (source <- 0 until cfg.macLanes) {
          dut.io.sourceIndex #= source
          sleep(1)
          for (output <- 0 until cfg.macLanes) {
            assert(dut.io.outputs(output).toInt == payload(20 + source))
          }
        }
      }
  }
}
