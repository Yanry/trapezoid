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
  private def mappingFromControls(inputs: Vector[Int], controls: BigInt): Vector[Int] = {
    val width = inputs.size
    if (width == 2) {
      Vector(inputs(if (controls.testBit(0)) 1 else 0),
        inputs(if (controls.testBit(1)) 1 else 0))
    } else {
      val half = width / 2
      val childBits = half * (2 * Integer.numberOfTrailingZeros(half) - 1)
      val upperInputs = Vector.tabulate(half)(pair =>
        inputs(2 * pair + (if (controls.testBit(2 * pair)) 1 else 0)))
      val lowerInputs = Vector.tabulate(half)(pair =>
        inputs(2 * pair + (if (controls.testBit(2 * pair + 1)) 1 else 0)))
      val upper = mappingFromControls(upperInputs, controls >> width)
      val lower = mappingFromControls(lowerInputs, controls >> (width + childBits))
      Vector.tabulate(width)(output =>
        if (controls.testBit(width + 2 * childBits + output)) lower(output / 2)
        else upper(output / 2))
    }
  }

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private class RoutedBenes(val config: TrapezoidConfig) extends Component {
    val io = new Bundle {
      val start = slave(Stream(BenesRouteMetadata(config)))
      val sources = in Vec(Bits((config.dataWidth + 1) bits), config.macLanes)
      val result = master(Stream(BenesRouteControlResult(config)))
      val outputs = out Vec(Bits((config.dataWidth + 1) bits), config.macLanes)
      val backtrackCount = out UInt(32 bits)
    }
    val control = new BenesRouteControlGenerator(config)
    val fabric = new BenesDistributionFabric(config)
    control.io.start <> io.start
    io.result << control.io.result
    fabric.io.sources := io.sources
    fabric.io.controls := control.io.result.payload.controls
    io.outputs := fabric.io.outputs
    io.backtrackCount := control.io.backtrackCount
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

  private def initialize(dut: RoutedBenes): Unit = {
    dut.clockDomain.forkStimulus(10)
    dut.io.start.valid #= false
    dut.io.start.payload.routeValid #= 0
    dut.io.result.ready #= false
    for (source <- 0 until dut.config.macLanes) {
      dut.io.start.payload.sourceIndices(source) #= 0
      dut.io.sources(source) #= payload(source)
    }
    dut.clockDomain.waitSampling(10)
  }

  private def route(
      dut: RoutedBenes,
      mapping: Seq[Int],
      expectedRoutable: Boolean,
      holdCycles: Int = 0): (Int, BigInt) = {
    require(mapping.size == dut.config.macLanes)
    val valid = mapping.zipWithIndex.foldLeft(BigInt(0)) {
      case (mask, (source, output)) =>
        if (source >= 0) mask.setBit(output) else mask
    }
    dut.io.start.payload.routeValid #= valid
    for (output <- mapping.indices) {
      dut.io.start.payload.sourceIndices(output) #= scala.math.max(0, mapping(output))
    }
    dut.io.start.valid #= true
    dut.io.result.ready #= false
    sleep(1)
    assert(dut.io.start.ready.toBoolean)
    dut.clockDomain.waitSampling()
    dut.io.start.valid #= false
    dut.io.start.payload.routeValid #= 0
    for (output <- mapping.indices) {
      dut.io.start.payload.sourceIndices(output) #= dut.config.macLanes - 1
    }
    var cycles = 0
    sleep(1)
    while (!dut.io.result.valid.toBoolean && cycles < 100000) {
      assert(!dut.io.start.ready.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)
      cycles += 1
    }
    assert(dut.io.result.valid.toBoolean, s"solver timed out for $mapping")
    assert(dut.io.result.payload.routable.toBoolean == expectedRoutable,
      s"incorrect routability for $mapping")
    val controls = dut.io.result.payload.controls.toBigInt
    if (expectedRoutable) {
      for ((source, output) <- mapping.zipWithIndex if source >= 0) {
        assert(dut.io.outputs(output).toInt == payload(source),
          s"mapping=$mapping output=$output controls=$controls")
      }
    }
    for (_ <- 0 until holdCycles) {
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.result.valid.toBoolean)
      assert(!dut.io.start.ready.toBoolean)
      assert(dut.io.result.payload.controls.toBigInt == controls)
      assert(dut.io.result.payload.routable.toBoolean == expectedRoutable)
    }
    dut.io.result.ready #= true
    dut.clockDomain.waitSampling()
    sleep(1)
    dut.io.result.ready #= false
    assert(!dut.io.result.valid.toBoolean)
    assert(dut.io.start.ready.toBoolean)
    (cycles, controls)
  }

  private class MFIURouteCheck extends Component {
    val io = new Bundle {
      val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
      val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
      val start = in Bool()
      val ready = out Bool()
      val resultReady = in Bool()
      val resultValid = out Bool()
      val count = out UInt(cfg.matchCountWidth bits)
      val overflow = out Bool()
      val matches = out Bool()
    }
    val mfiu = new MultiFiberIntersection(cfg)
    val aControl = new BenesRouteControlGenerator(cfg)
    val bControl = new BenesRouteControlGenerator(cfg)
    val aFabric = new BenesDistributionFabric(cfg)
    val bFabric = new BenesDistributionFabric(cfg)
    for (fiber <- 0 until cfg.maxFibers) {
      mfiu.io.aValid(fiber) := True
      mfiu.io.aMask(fiber) := io.aMask(fiber)
      mfiu.io.bValid(fiber) := True
      mfiu.io.bMask(fiber) := io.bMask(fiber)
      for (value <- 0 until cfg.fiberWidth) {
        mfiu.io.aValues(fiber)(value) := fiber * cfg.fiberWidth + value + 1
        mfiu.io.bValues(fiber)(value) := 20 + fiber * cfg.fiberWidth + value
      }
    }
    io.ready := aControl.io.start.ready && bControl.io.start.ready
    aControl.io.start.valid := io.start && io.ready
    bControl.io.start.valid := io.start && io.ready
    io.resultValid := aControl.io.result.valid && bControl.io.result.valid
    aControl.io.result.ready := io.resultReady && io.resultValid
    bControl.io.result.ready := io.resultReady && io.resultValid
    aFabric.io.controls := aControl.io.result.payload.controls
    bFabric.io.controls := bControl.io.result.payload.controls
    val matches = Bits(cfg.macLanes bits)
    for (lane <- 0 until cfg.macLanes) {
      aControl.io.start.payload.routeValid(lane) := mfiu.io.routes(lane).valid
      bControl.io.start.payload.routeValid(lane) := mfiu.io.routes(lane).valid
      aControl.io.start.payload.sourceIndices(lane) := mfiu.io.routes(lane).aSourceIndex
      bControl.io.start.payload.sourceIndices(lane) := mfiu.io.routes(lane).bSourceIndex
      aFabric.io.sources(lane) := mfiu.io.aSources(lane).asBits ## mfiu.io.aSourceValid(lane)
      bFabric.io.sources(lane) := mfiu.io.bSources(lane).asBits ## mfiu.io.bSourceValid(lane)
      matches(lane) := !mfiu.io.routes(lane).valid ||
        (aFabric.io.outputs(lane) === aFabric.io.sources(mfiu.io.routes(lane).aSourceIndex) &&
         bFabric.io.outputs(lane) === bFabric.io.sources(mfiu.io.routes(lane).bSourceIndex))
    }
    io.count := mfiu.io.count
    io.overflow := mfiu.io.overflow
    io.matches := matches.andR && aControl.io.result.payload.routable &&
      bControl.io.result.payload.routable
  }

  test("Benes fabric routes identity and multicast controls") {
    simConfig.compile(new BenesDistributionFabric(cfg)).doSim { dut =>
      for (index <- 0 until cfg.macLanes) dut.io.sources(index) #= payload(10 + index)
      dut.io.controls #= ((BigInt(1) << 1) | (BigInt(1) << 3) |
        (BigInt(1) << 5) | (BigInt(1) << 7) |
        (BigInt(1) << 9) | (BigInt(1) << 11))
      sleep(1)
      for (index <- 0 until cfg.macLanes) {
        assert(dut.io.outputs(index).toInt == payload(10 + index))
      }
      dut.io.controls #= 0
      sleep(1)
      for (index <- 0 until cfg.macLanes) assert(dut.io.outputs(index).toInt == payload(10))
    }
  }

  test("runtime broadcast generator selects every requested source") {
    simConfig.compile(new BenesBroadcastNetwork(cfg)).doSim { dut =>
      for (index <- 0 until cfg.macLanes) dut.io.sources(index) #= payload(20 + index)
      for (source <- 0 until cfg.macLanes) {
        dut.io.sourceIndex #= source
        sleep(1)
        for (output <- 0 until cfg.macLanes) {
          assert(dut.io.outputs(output).toInt == payload(20 + source))
        }
      }
    }
  }

  test("backtracking solver covers every four-lane multicast mapping and valid mask") {
    simConfig.compile(new RoutedBenes(cfg)).doSim { dut =>
      initialize(dut)
      for (valid <- 0 until 16; encoded <- 0 until 256) {
        val mapping = Vector.tabulate(4) { output =>
          if ((valid & (1 << output)) != 0) (encoded >> (2 * output)) & 3 else -1
        }
        route(dut, mapping, expectedRoutable = true, holdCycles = if (encoded == 0) 3 else 0)
      }
    }
  }

  test("runtime route generator covers every legal small MFIU mask pattern") {
    simConfig.compile(new MFIURouteCheck).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.start #= false
      dut.io.resultReady #= false
      dut.clockDomain.waitSampling(10)
      val maskLimit = 1 << cfg.fiberWidth
      for (aFirst <- 0 until maskLimit; aSecond <- 0 until maskLimit;
           bFirst <- 0 until maskLimit; bSecond <- 0 until maskLimit) {
        dut.io.aMask(0) #= aFirst
        dut.io.aMask(1) #= aSecond
        dut.io.bMask(0) #= bFirst
        dut.io.bMask(1) #= bSecond
        sleep(1)
        if (dut.io.count.toInt != 0 && !dut.io.overflow.toBoolean) {
          assert(dut.io.ready.toBoolean)
          dut.io.start #= true
          dut.clockDomain.waitSampling()
          dut.io.start #= false
          sleep(1)
          var cycles = 0
          while (!dut.io.resultValid.toBoolean && cycles < 1000) {
            dut.clockDomain.waitSampling()
            sleep(1)
            cycles += 1
          }
          assert(dut.io.resultValid.toBoolean)
          assert(dut.io.matches.toBoolean,
            s"MFIU masks=[$aFirst,$aSecond] x [$bFirst,$bSecond]")
          dut.io.resultReady #= true
          dut.clockDomain.waitSampling()
          sleep(1)
          dut.io.resultReady #= false
        }
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

  test("sixteen-lane solver backtracks child failures and returns stable failure responses") {
    val wideCfg = cfg.copy(maxFibers = 4, macLanes = 16, outputLanes = 16)
    simConfig.compile(new RoutedBenes(wideCfg)).doSim { dut =>
      initialize(dut)
      val mappings = Seq(
        Seq(0, 0, 2, 4, 6, 6, 8, 10, 8, 10, 12, -1, -1, -1, -1, -1),
        Seq(0, 6, 8, 2, 4, 10, 0, 4, 6, 10, 8, -1, -1, -1, -1, -1),
        Seq(3, 7, 14, 0, -1, 14, 7, 1, 5, 8, 12, -1, 14, 13, 4, -1))
      for (mapping <- mappings) route(dut, mapping, expectedRoutable = true, holdCycles = 5)
      assert(dut.io.backtrackCount.toLong > 0,
        "the directed multicast mappings must exercise parent orientation backtracking")
      route(dut, Seq(15, 12, 12, 7, 10, 9, 14, 4, 12, 13, 8, 6, 11, 14, 14, 6),
        expectedRoutable = false, holdCycles = 7)
      val random = new scala.util.Random(615)
      for (_ <- 0 until 100) {
        route(dut, random.shuffle((0 until 16).toVector), expectedRoutable = true)
      }
      for (_ <- 0 until 100) {
        val controls = BigInt(wideCfg.benesControlWidth, random)
        val mapping = mappingFromControls((0 until 16).toVector, controls)
          .map(source => if (random.nextBoolean()) source else -1)
        route(dut, mapping, expectedRoutable = true)
      }
      route(dut, Seq.fill(16)(7), expectedRoutable = true)
      route(dut, Seq.fill(16)(-1), expectedRoutable = true)
      dut.io.start.payload.routeValid #= 65535
      for (output <- 0 until 16) dut.io.start.payload.sourceIndices(output) #= output
      dut.io.start.valid #= true
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false
      dut.clockDomain.waitSampling(3)
      dut.clockDomain.assertReset()
      sleep(25)
      dut.clockDomain.deassertReset()
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(!dut.io.result.valid.toBoolean)
      route(dut, (0 until 16).reverse, expectedRoutable = true)
    }
  }

  test("paper-width solver generates full controls with variable latency") {
    val paperCfg = TrapezoidConfig.paper.copy(dataWidth = 8, peRows = 1)
    simConfig.compile(new RoutedBenes(paperCfg)).doSim { dut =>
      initialize(dut)
      route(dut, (0 until 128).toVector, expectedRoutable = true, holdCycles = 3)
      route(dut, Vector.tabulate(128)(lane => (lane * 17) & 127), expectedRoutable = true)
      route(dut, Vector.tabulate(128)(lane => if (lane < 16) lane / 2 else -1),
        expectedRoutable = true)
      route(dut, Vector.fill(128)(127), expectedRoutable = true)
    }
  }

  test("independent fast controls route broadcast and masked spread through the actual fabric") {
    for (laneCount <- Seq(16, 128)) {
      val config = cfg.copy(macLanes = laneCount, outputLanes = 16)
      class FastFabric extends Component {
        val io = new Bundle {
          val metadata = in(BenesRouteMetadata(config))
          val supported = out Bool()
          val outputs = out Vec(Bits((config.dataWidth + 1) bits), laneCount)
        }
        val control = new RestrictedBenesRouteControlGenerator(config)
        val fabric = new BenesDistributionFabric(config)
        control.io.metadata := io.metadata
        fabric.io.controls := control.io.controls
        for (source <- 0 until laneCount) {
          fabric.io.sources(source) := B(payload(source), config.dataWidth + 1 bits)
        }
        io.outputs := fabric.io.outputs
        io.supported := control.io.supported
      }
      simConfig.compile(new FastFabric).doSim { dut =>
        val spread = Vector.tabulate(laneCount)(source =>
          StaticBenesControls.spreadSourceIndex(laneCount, source))
        val mappings = (0 until laneCount).map(source => Vector.fill(laneCount)(source)) ++
          Seq(spread, Vector.tabulate(laneCount)(identity))
        for (mapping <- mappings; mask <- Seq(BigInt(0),
          (BigInt(1) << laneCount) - 1, ((BigInt(1) << laneCount) - 1) / 3)) {
          dut.io.metadata.routeValid #= mask
          for (lane <- 0 until laneCount) dut.io.metadata.sourceIndices(lane) #= mapping(lane)
          sleep(1)
          val active = mapping.indices.filter(mask.testBit)
          val expectedSupported = active.nonEmpty &&
            (active.map(mapping).distinct.size == 1 || active.forall(lane => mapping(lane) == spread(lane)))
          assert(dut.io.supported.toBoolean == expectedSupported)
          if (expectedSupported) {
            for (lane <- active) assert(dut.io.outputs(lane).toInt == payload(mapping(lane)))
          }
        }
      }
    }
  }
}
