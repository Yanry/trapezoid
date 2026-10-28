package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.core._
import spinal.lib._

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

  private class MFIURouteCheck extends Component {
    val io = new Bundle {
      val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
      val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
      val count = out UInt(cfg.matchCountWidth bits)
      val overflow = out Bool()
      val aRoutable = out Bool()
      val bRoutable = out Bool()
    }
    val mfiu = new MultiFiberIntersection(cfg)
    val aControl = new BenesRouteControlGenerator(cfg)
    val bControl = new BenesRouteControlGenerator(cfg)
    for (fiber <- 0 until cfg.maxFibers) {
      mfiu.io.aValid(fiber) := True
      mfiu.io.aMask(fiber) := io.aMask(fiber)
      mfiu.io.bValid(fiber) := True
      mfiu.io.bMask(fiber) := io.bMask(fiber)
      for (value <- 0 until cfg.fiberWidth) {
        mfiu.io.aValues(fiber)(value) := value + 1
        mfiu.io.bValues(fiber)(value) := value + 1
      }
    }
    for (lane <- 0 until cfg.macLanes) {
      aControl.io.routeValid(lane) := mfiu.io.routes(lane).valid
      bControl.io.routeValid(lane) := mfiu.io.routes(lane).valid
      aControl.io.sourceIndices(lane) := mfiu.io.routes(lane).aSourceIndex
      bControl.io.sourceIndices(lane) := mfiu.io.routes(lane).bSourceIndex
    }
    io.count := mfiu.io.count
    io.overflow := mfiu.io.overflow
    io.aRoutable := aControl.io.routable
    io.bRoutable := bControl.io.routable
  }

  private class PipelinedRoutedBenes extends Component {
    val io = new Bundle {
      val start = slave(Stream(BenesRouteMetadata(cfg)))
      val sources = in Vec(Bits((cfg.dataWidth + 1) bits), cfg.macLanes)
      val result = master(Stream(BenesRouteControlResult(cfg)))
      val outputs = out Vec(Bits((cfg.dataWidth + 1) bits), cfg.macLanes)
    }
    val control = new PipelinedBenesRouteControlGenerator(cfg)
    val fabric = new BenesDistributionFabric(cfg)
    control.io.start <> io.start
    io.result.valid := control.io.result.valid
    io.result.payload := control.io.result.payload
    control.io.result.ready := io.result.ready
    fabric.io.sources := io.sources
    fabric.io.controls := control.io.result.payload.controls
    io.outputs := fabric.io.outputs
  }

  private class RestrictedPipelinedRoutedBenes extends Component {
    val io = new Bundle {
      val start = slave(Stream(BenesRouteMetadata(cfg)))
      val startSupported = out Bool()
      val sources = in Vec(Bits((cfg.dataWidth + 1) bits), cfg.macLanes)
      val result = master(Stream(BenesRouteControlResult(cfg)))
      val outputs = out Vec(Bits((cfg.dataWidth + 1) bits), cfg.macLanes)
    }
    val control = new PipelinedRestrictedBenesRouteControlGenerator(cfg)
    val fabric = new BenesDistributionFabric(cfg)
    control.io.start <> io.start
    io.startSupported := control.io.startSupported
    io.result.valid := control.io.result.valid
    io.result.payload := control.io.result.payload
    control.io.result.ready := io.result.ready
    fabric.io.sources := io.sources
    fabric.io.controls := control.io.result.payload.controls
    io.outputs := fabric.io.outputs
  }

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

  test("runtime route generator covers every legal small MFIU mask pattern") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new MFIURouteCheck)
      .doSim { dut =>
        val maskLimit = 1 << cfg.fiberWidth
        for (a0 <- 0 until maskLimit; a1 <- 0 until maskLimit;
             b0 <- 0 until maskLimit; b1 <- 0 until maskLimit) {
          dut.io.aMask(0) #= a0
          dut.io.aMask(1) #= a1
          dut.io.bMask(0) #= b0
          dut.io.bMask(1) #= b1
          sleep(1)
          if (dut.io.count.toInt != 0) {
            assert(dut.io.aRoutable.toBoolean,
              s"A route failed masks=[$a0,$a1] x [$b0,$b1]")
            assert(dut.io.bRoutable.toBoolean,
              s"B route failed masks=[$a0,$a1] x [$b0,$b1]")
          }
        }
      }
  }

  test("pipelined route generator covers every four-lane multicast mapping") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new PipelinedRoutedBenes)
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.start.valid #= false
        dut.io.result.ready #= false
        for (index <- 0 until cfg.macLanes) {
          dut.io.sources(index) #= payload(60 + index)
        }

        for (mapping <- 0 until scala.math.pow(
            cfg.macLanes, cfg.macLanes).toInt) {
          var encoded = mapping
          dut.io.start.payload.routeValid #= (1 << cfg.macLanes) - 1
          val expected = Array.ofDim[Int](cfg.macLanes)
          for (output <- 0 until cfg.macLanes) {
            val source = encoded % cfg.macLanes
            encoded /= cfg.macLanes
            dut.io.start.payload.sourceIndices(output) #= source
            expected(output) = payload(60 + source)
          }
          dut.io.start.valid #= true
          while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
          dut.clockDomain.waitSampling()
          dut.io.start.valid #= false
          while (!dut.io.result.valid.toBoolean) dut.clockDomain.waitSampling()
          assert(dut.io.result.payload.routable.toBoolean,
            s"pipelined mapping $mapping was reported unroutable")
          for (output <- 0 until cfg.macLanes) {
            assert(dut.io.outputs(output).toInt == expected(output),
              s"pipelined mapping $mapping output $output")
          }
          dut.io.result.ready #= true
          dut.clockDomain.waitSampling()
          dut.io.result.ready #= false
        }
      }
  }

  test("restricted Benes metadata pipeline accepts one route per cycle") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new RestrictedPipelinedRoutedBenes)
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.start.valid #= false
        dut.io.result.ready #= true
        for (index <- 0 until cfg.macLanes) {
          dut.io.sources(index) #= payload(80 + index)
        }
        val spread = (0 until cfg.macLanes).map(index =>
          StaticBenesControls.spreadSourceIndex(cfg.macLanes, index))
        val mappings = Seq(
          Seq.fill(cfg.macLanes)(1),
          spread,
          Seq.fill(cfg.macLanes)(3))
        var nextInput = 0
        var nextOutput = 0
        var cycle = 0
        val acceptedCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
        val resultCycles = scala.collection.mutable.ArrayBuffer.empty[Int]

        while (nextOutput < mappings.size && cycle < 32) {
          if (nextInput < mappings.size) {
            dut.io.start.valid #= true
            dut.io.start.payload.routeValid #= (1 << cfg.macLanes) - 1
            for (output <- 0 until cfg.macLanes) {
              dut.io.start.payload.sourceIndices(output) #=
                mappings(nextInput)(output)
            }
          } else {
            dut.io.start.valid #= false
          }
          sleep(1)
          if (dut.io.start.valid.toBoolean && dut.io.start.ready.toBoolean) {
            assert(dut.io.startSupported.toBoolean)
            acceptedCycles += cycle
            nextInput += 1
          }
          if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
            assert(dut.io.result.payload.routable.toBoolean)
            for (output <- 0 until cfg.macLanes) {
              val expected = payload(80 + mappings(nextOutput)(output))
              assert(dut.io.outputs(output).toInt == expected,
                s"restricted mapping $nextOutput output $output")
            }
            resultCycles += cycle
            nextOutput += 1
          }
          dut.clockDomain.waitSampling()
          cycle += 1
        }
        assert(nextOutput == mappings.size)
        assert(acceptedCycles.sliding(2).forall(pair => pair(1) - pair(0) == 1))
        assert(resultCycles.sliding(2).forall(pair => pair(1) - pair(0) == 1))
        assert(resultCycles.head - acceptedCycles.head == cfg.mrnReductionLevels)
      }
  }

  test("runtime route generator covers the four-fiber MFIU pattern") {
    val wideCfg = cfg.copy(maxFibers = 4, macLanes = 16, outputLanes = 16)
    class WideRoutedBenes extends Component {
      val io = new Bundle {
        val sources = in Vec(Bits((wideCfg.dataWidth + 1) bits), wideCfg.macLanes)
        val valid = in Bits(wideCfg.macLanes bits)
        val sourceIndex = in Vec(UInt(wideCfg.macLaneIndexWidth bits),
          wideCfg.macLanes)
        val outputs = out Vec(Bits((wideCfg.dataWidth + 1) bits),
          wideCfg.macLanes)
        val routable = out Bool()
      }
      val control = new BenesRouteControlGenerator(wideCfg)
      val fabric = new BenesDistributionFabric(wideCfg)
      control.io.routeValid := io.valid
      control.io.sourceIndices := io.sourceIndex
      fabric.io.sources := io.sources
      fabric.io.controls := control.io.controls
      io.outputs := fabric.io.outputs
      io.routable := control.io.routable
    }
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new WideRoutedBenes)
      .doSim { dut =>
        for (index <- 0 until wideCfg.macLanes) {
          dut.io.sources(index) #= payload(40 + index)
          dut.io.sourceIndex(index) #= 0
        }
        val mappings = Seq(
          Seq(0, 0, 2, 4, 6, 6, 8, 10, 8, 10, 12),
          Seq(0, 6, 8, 2, 4, 10, 0, 4, 6, 10, 8))
        for ((mapping, mappingIndex) <- mappings.zipWithIndex) {
          dut.io.valid #= (BigInt(1) << mapping.size) - 1
          for ((source, output) <- mapping.zipWithIndex) {
            dut.io.sourceIndex(output) #= source
          }
          sleep(1)
          assert(dut.io.routable.toBoolean,
            s"MFIU mapping $mappingIndex was reported unroutable")
          for ((source, output) <- mapping.zipWithIndex) {
            assert(dut.io.outputs(output).toInt == payload(40 + source),
              s"MFIU mapping $mappingIndex output $output")
          }
        }
      }
  }
}
