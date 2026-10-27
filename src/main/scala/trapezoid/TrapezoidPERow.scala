package trapezoid

import spinal.core._
import spinal.lib._

/** Routed operands captured between the distribution network and MAC lanes. */
case class PERowRoutePayload(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val overflow = Bool()
  val selectedColumns = UInt(log2Up(cfg.maxFibers + 1) bits)
  val effectualMacs = UInt(cfg.matchCountWidth bits)

  val tripATags = Vec(UInt(cfg.coordinateWidth bits), cfg.maxFibers)
  val tripBTags = Vec(UInt(cfg.coordinateWidth bits), cfg.maxFibers)
  val laneValid = Vec(Bool(), cfg.macLanes)
  val laneAFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val laneBFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val laneAOperand = Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
  val laneBOperand = Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
  val gustRow = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustColumn = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
}

/** Products and routing metadata captured before coordinate reduction. */
case class PERowMacPayload(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val overflow = Bool()
  val selectedColumns = UInt(log2Up(cfg.maxFibers + 1) bits)
  val effectualMacs = UInt(cfg.matchCountWidth bits)

  val tripATags = Vec(UInt(cfg.coordinateWidth bits), cfg.maxFibers)
  val tripBTags = Vec(UInt(cfg.coordinateWidth bits), cfg.maxFibers)
  val laneValid = Vec(Bool(), cfg.macLanes)
  val laneAFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val laneBFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val product = Vec(SInt(cfg.accumulatorWidth bits), cfg.macLanes)
  val gustRow = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustColumn = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
}

/** One packed TrIP product carried through the configurable reduction tree. */
case class PERowReducedProduct(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val segmentStart = Bool()
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val destinationBank = UInt(cfg.localBankIndexWidth bits)
  val destinationWordOffset = UInt(cfg.localWordIndexWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
}

/** Coordinate-ordered products carried through one registered MRN level. */
case class PERowReductionPayload(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val overflow = Bool()
  val selectedColumns = UInt(log2Up(cfg.maxFibers + 1) bits)
  val effectualMacs = UInt(cfg.matchCountWidth bits)
  val lanes = Vec(PERowReducedProduct(cfg), cfg.macLanes)
}

case class TrapezoidPERowIo(cfg: TrapezoidConfig) extends Bundle {
  val command = slave(Stream(PERowCommand(cfg)))
  val response = master(Stream(PERowResponse(cfg)))
  val routeCycles = out UInt(32 bits)
  val macCycles = out UInt(32 bits)
  val mrnCycles = out UInt(32 bits)
  val responseCycles = out UInt(32 bits)
}

trait TrapezoidPERowComponent {
  val io: TrapezoidPERowIo
}

/** A pipelined Trapezoid PE row.
  *
  * Input, route/distribution, MAC, reduction, and output buffering are
  * separated by elastic Stream stages. Every boundary propagates backpressure,
  * so several commands may be in flight without losing ordering.
  */
class TrapezoidPERow(cfg: TrapezoidConfig)
    extends Component with TrapezoidPERowComponent {
  val io = TrapezoidPERowIo(cfg)
  val routeCycleCounter = Reg(UInt(32 bits)) init 0
  val macCycleCounter = Reg(UInt(32 bits)) init 0
  val mrnCycleCounter = Reg(UInt(32 bits)) init 0
  val responseCycleCounter = Reg(UInt(32 bits)) init 0

  private case class ReductionGroup(
      entries: Vec[PERowReducedProduct],
      count: UInt)

  /** Stable hierarchical compaction for an already coordinate-ordered stream. */
  private def compactValid(inputs: Seq[PERowReducedProduct]): ReductionGroup = {
    require(inputs.nonEmpty)
    if (inputs.length == 1) {
      val entries = Vec(PERowReducedProduct(cfg), 1)
      val count = UInt(1 bits)
      entries(0) := inputs.head
      count := inputs.head.valid.asUInt
      ReductionGroup(entries, count)
    } else {
      val split = inputs.length / 2
      val left = compactValid(inputs.take(split))
      val right = compactValid(inputs.drop(split))
      val capacity = inputs.length
      val countWidth = log2Up(capacity + 1)
      val leftCount = left.count.resize(countWidth)
      val rightCount = right.count.resize(countWidth)
      val zero = PERowReducedProduct(cfg).getZero

      var shiftedRight = Vector.tabulate(capacity) { index =>
        if (index < right.entries.length) right.entries(index) else zero
      }
      var distance = 1
      var stage = 0
      while (distance < capacity) {
        val previous = shiftedRight
        shiftedRight = Vector.tabulate(capacity) { index =>
          val shifted = if (index >= distance) previous(index - distance) else zero
          Mux(leftCount(stage), shifted, previous(index))
        }
        distance *= 2
        stage += 1
      }

      val entries = Vec(PERowReducedProduct(cfg), capacity)
      for (index <- 0 until capacity) {
        val leftEntry = if (index < left.entries.length) left.entries(index) else zero
        entries(index) := Mux(U(index, countWidth bits) < leftCount,
          leftEntry, shiftedRight(index))
      }
      val count = UInt(countWidth bits)
      count := (leftCount + rightCount).resize(countWidth)
      ReductionGroup(entries, count)
    }
  }

  /** Inclusive logarithmic-depth prefix count. */
  private def prefixCount(values: Seq[Bool]): Seq[UInt] = {
    require(values.nonEmpty)
    var level = values.map(_.asUInt.resize(cfg.outputCountWidth))
    var distance = 1
    while (distance < values.length) {
      val previous = level
      level = previous.indices.map { index =>
        if (index >= distance) {
          (previous(index) + previous(index - distance))
            .resize(cfg.outputCountWidth)
        } else {
          previous(index)
        }
      }
      distance *= 2
    }
    level
  }

  // Stage 0: capture one complete command and decouple the scheduler from MFIU.
  val inputStage = io.command.m2sPipe()

  // Stage 1: the MFIU/shift equivalent distributes operands to fixed lanes.
  val mfiu = new MultiFiberIntersection(cfg)
  private val pipelinedBenesControls = cfg.macLanes > 16
  val aBenesControls = if (!pipelinedBenesControls)
    new BenesRouteControlGenerator(cfg) else null
  val bBenesControls = if (!pipelinedBenesControls)
    new BenesRouteControlGenerator(cfg) else null
  val aPipelinedBenesControls = if (pipelinedBenesControls)
    new PipelinedBenesRouteControlGenerator(cfg) else null
  val bPipelinedBenesControls = if (pipelinedBenesControls)
    new PipelinedBenesRouteControlGenerator(cfg) else null
  val aBenesFabric = new BenesDistributionFabric(cfg)
  val bBenesFabric = new BenesDistributionFabric(cfg)
  val input = inputStage.payload
  val fullFiberMask = B((BigInt(1) << cfg.fiberWidth) - 1,
    cfg.fiberWidth bits)
  val denseAChoices = Bits(cfg.maxFibers bits)
  val denseBChoices = Bits(cfg.maxFibers bits)
  for (fiber <- 0 until cfg.maxFibers) {
    denseAChoices(fiber) := input.trip.a(fiber).valid &&
      input.trip.a(fiber).mask === fullFiberMask
    denseBChoices(fiber) := input.trip.b(fiber).valid &&
      input.trip.b(fiber).mask === fullFiberMask
  }
  val denseFastPath = input.mode === TrapezoidDataflow.DenseIp &&
    CountOne(denseAChoices) === 1 && CountOne(denseBChoices) === 1
  val denseAFiber = UInt(cfg.fiberIndexWidth bits)
  val denseBFiber = UInt(cfg.fiberIndexWidth bits)
  denseAFiber := 0
  denseBFiber := 0
  for (fiber <- 0 until cfg.maxFibers) {
    when(denseAChoices(fiber)) { denseAFiber := fiber }
    when(denseBChoices(fiber)) { denseBFiber := fiber }
  }
  val denseA = input.trip.a(denseAFiber)
  val denseB = input.trip.b(denseBFiber)
  val useSparseTripRoute = input.mode === TrapezoidDataflow.TrIp ||
    (input.mode === TrapezoidDataflow.DenseIp && !denseFastPath)
  for (fiber <- 0 until cfg.maxFibers) {
    mfiu.io.aValid(fiber) := useSparseTripRoute && input.trip.a(fiber).valid
    mfiu.io.aMask(fiber) := Mux(useSparseTripRoute,
      input.trip.a(fiber).mask, B(0, cfg.fiberWidth bits))
    mfiu.io.bValid(fiber) := useSparseTripRoute && input.trip.b(fiber).valid
    mfiu.io.bMask(fiber) := Mux(useSparseTripRoute,
      input.trip.b(fiber).mask, B(0, cfg.fiberWidth bits))
    for (value <- 0 until cfg.fiberWidth) {
      mfiu.io.aValues(fiber)(value) := Mux(useSparseTripRoute,
        input.trip.a(fiber).values(value), S(0, cfg.dataWidth bits))
      mfiu.io.bValues(fiber)(value) := Mux(useSparseTripRoute,
        input.trip.b(fiber).values(value), S(0, cfg.dataWidth bits))
    }
  }
  val routeControlsReady = Bool()
  val aRouteRoutable = Bool()
  val bRouteRoutable = Bool()
  val sparseRouteNeedsControl = useSparseTripRoute && mfiu.io.count =/= 0
  val controlsStarted = if (pipelinedBenesControls) RegInit(False) else null
  for (lane <- 0 until cfg.macLanes) {
    aBenesFabric.io.sources(lane)(0) := mfiu.io.aSourceValid(lane)
    aBenesFabric.io.sources(lane)(cfg.dataWidth downto 1) :=
      mfiu.io.aSources(lane).asBits
    bBenesFabric.io.sources(lane)(0) := mfiu.io.bSourceValid(lane)
    bBenesFabric.io.sources(lane)(cfg.dataWidth downto 1) :=
      mfiu.io.bSources(lane).asBits
  }
  if (pipelinedBenesControls) {
    val startBoth = inputStage.valid && sparseRouteNeedsControl &&
      !controlsStarted && aPipelinedBenesControls.io.start.ready &&
      bPipelinedBenesControls.io.start.ready
    aPipelinedBenesControls.io.start.valid := startBoth
    bPipelinedBenesControls.io.start.valid := startBoth
    for (lane <- 0 until cfg.macLanes) {
      aPipelinedBenesControls.io.start.payload.routeValid(lane) :=
        mfiu.io.routes(lane).valid
      bPipelinedBenesControls.io.start.payload.routeValid(lane) :=
        mfiu.io.routes(lane).valid
      aPipelinedBenesControls.io.start.payload.sourceIndices(lane) :=
        mfiu.io.routes(lane).aSourceIndex
      bPipelinedBenesControls.io.start.payload.sourceIndices(lane) :=
        mfiu.io.routes(lane).bSourceIndex
    }
    when(aPipelinedBenesControls.io.start.fire &&
        bPipelinedBenesControls.io.start.fire) {
      controlsStarted := True
    }
    routeControlsReady := !sparseRouteNeedsControl ||
      (aPipelinedBenesControls.io.result.valid &&
        bPipelinedBenesControls.io.result.valid)
    aRouteRoutable := !sparseRouteNeedsControl ||
      (aPipelinedBenesControls.io.result.valid &&
        aPipelinedBenesControls.io.result.payload.routable)
    bRouteRoutable := !sparseRouteNeedsControl ||
      (bPipelinedBenesControls.io.result.valid &&
        bPipelinedBenesControls.io.result.payload.routable)
    aBenesFabric.io.controls :=
      aPipelinedBenesControls.io.result.payload.controls
    bBenesFabric.io.controls :=
      bPipelinedBenesControls.io.result.payload.controls
  } else {
    for (lane <- 0 until cfg.macLanes) {
      aBenesControls.io.routeValid(lane) := mfiu.io.routes(lane).valid
      bBenesControls.io.routeValid(lane) := mfiu.io.routes(lane).valid
      aBenesControls.io.sourceIndices(lane) := mfiu.io.routes(lane).aSourceIndex
      bBenesControls.io.sourceIndices(lane) := mfiu.io.routes(lane).bSourceIndex
    }
    routeControlsReady := True
    aRouteRoutable := aBenesControls.io.routable
    bRouteRoutable := bBenesControls.io.routable
    aBenesFabric.io.controls := aBenesControls.io.controls
    bBenesFabric.io.controls := bBenesControls.io.controls
  }

  val routed = PERowRoutePayload(cfg)
  routed.mode := input.mode
  routed.endOfTile := input.endOfTile
  routed.selectedColumns := Mux(denseFastPath,
    U(1, routed.selectedColumns.getWidth bits),
    Mux(mfiu.io.overflow,
      U(0, routed.selectedColumns.getWidth bits),
      CountOne(input.trip.b.map(_.valid).asBits).resized))
  routed.effectualMacs := Mux(denseFastPath,
    U(cfg.fiberWidth, cfg.matchCountWidth bits), mfiu.io.count)

  val inputTripMode = input.mode === TrapezoidDataflow.DenseIp ||
    input.mode === TrapezoidDataflow.TrIp
  val gustRouteErrors = Bits(cfg.cacheLineWords bits)
  for (fiber <- 0 until cfg.maxFibers) {
    routed.tripATags(fiber) := Mux(inputTripMode,
      input.trip.a(fiber).tag, U(0, cfg.coordinateWidth bits))
    routed.tripBTags(fiber) := Mux(inputTripMode,
      input.trip.b(fiber).tag, U(0, cfg.coordinateWidth bits))
  }
  for (lane <- 0 until cfg.macLanes) {
    val laneRoute = mfiu.io.routes(lane)
    val tripAFiber = UInt(cfg.fiberIndexWidth bits)
    val tripBFiber = UInt(cfg.fiberIndexWidth bits)
    val tripValid = Bool()
    val tripAOperand = SInt(cfg.dataWidth bits)
    val tripBOperand = SInt(cfg.dataWidth bits)
    val benesA = aBenesFabric.io.outputs(lane)
    val benesB = bBenesFabric.io.outputs(lane)
    val benesAOperand = benesA(cfg.dataWidth downto 1).asSInt
    val benesBOperand = benesB(cfg.dataWidth downto 1).asSInt
    if (lane < cfg.fiberWidth) {
      tripValid := Mux(denseFastPath, True, laneRoute.valid)
      tripAFiber := Mux(denseFastPath, denseAFiber, laneRoute.aFiber)
      tripBFiber := Mux(denseFastPath, denseBFiber, laneRoute.bFiber)
      tripAOperand := Mux(denseFastPath, denseA.values(lane),
        benesAOperand)
      tripBOperand := Mux(denseFastPath, denseB.values(lane),
        benesBOperand)
    } else {
      tripValid := laneRoute.valid
      tripAFiber := laneRoute.aFiber
      tripBFiber := laneRoute.bFiber
      tripAOperand := benesAOperand
      tripBOperand := benesBOperand
    }

    val gustValid = Bool()
    val gustAOperand = SInt(cfg.dataWidth bits)
    val gustBOperand = SInt(cfg.dataWidth bits)

    if (lane < cfg.cacheLineWords) {
      val b = input.gustavson.b(lane)
      val trGtA = GustavsonA(cfg)
      trGtA := GustavsonA(cfg).getZero
      val trGtRouteValid = Bool()
      trGtRouteValid := False
      if (lane < cfg.localBanks) {
        val firstA = lane * cfg.macLanesPerSubrow
        val lastA = firstA + cfg.macLanesPerSubrow
        for (index <- firstA until lastA) {
          when(b.aIndex === index) {
            trGtA := input.gustavson.a(index)
            trGtRouteValid := True
          }
        }
      }
      val trGsA = input.gustavson.a(0)
      val trGsMode = input.mode === TrapezoidDataflow.TrGs
      val selectedA = Mux(trGsMode, trGsA, trGtA)
      val routeValid = Mux(trGsMode, b.aIndex === 0, trGtRouteValid)
      gustRouteErrors(lane) := b.valid && !routeValid
      gustValid := b.valid && routeValid && selectedA.valid &&
        selectedA.k === b.k
      gustAOperand := selectedA.value
      gustBOperand := b.value
      routed.gustRow(lane) := Mux(inputTripMode,
        U(0, cfg.coordinateWidth bits), selectedA.row)
      routed.gustColumn(lane) := Mux(inputTripMode,
        U(0, cfg.coordinateWidth bits), b.column)
    } else {
      gustValid := False
      gustAOperand := 0
      gustBOperand := 0
    }

    routed.laneValid(lane) := Mux(inputTripMode, tripValid, gustValid)
    routed.laneAFiber(lane) := Mux(inputTripMode, tripAFiber,
      U(0, cfg.fiberIndexWidth bits))
    routed.laneBFiber(lane) := Mux(inputTripMode, tripBFiber,
      U(0, cfg.fiberIndexWidth bits))
    routed.laneAOperand(lane) := Mux(inputTripMode,
      tripAOperand, gustAOperand)
    routed.laneBOperand(lane) := Mux(inputTripMode,
      tripBOperand, gustBOperand)
  }
  routed.overflow := Mux(denseFastPath, False,
    Mux(inputTripMode, mfiu.io.overflow ||
      (mfiu.io.count =/= 0 && (!aRouteRoutable ||
        !bRouteRoutable)), gustRouteErrors.orR))

  val routeInput = Stream(PERowRoutePayload(cfg))
  routeInput.valid := inputStage.valid && routeControlsReady
  routeInput.payload := routed
  inputStage.ready := routeInput.ready && routeControlsReady
  if (pipelinedBenesControls) {
    val consumeControls = routeInput.fire && sparseRouteNeedsControl
    aPipelinedBenesControls.io.result.ready := consumeControls
    bPipelinedBenesControls.io.result.ready := consumeControls
    when(consumeControls) {
      controlsStarted := False
    }
  }
  when(routeInput.valid && useSparseTripRoute && mfiu.io.count =/= 0) {
    assert(aRouteRoutable && bRouteRoutable,
      "MFIU emitted a route pattern not supported by the Benes solver")
  }
  val routeStage = routeInput.m2sPipe()

  // Stage 2: signed multiplier lanes. Invalid lanes are explicitly zeroed.
  val route = routeStage.payload
  val multiplied = PERowMacPayload(cfg)
  multiplied.mode := route.mode
  multiplied.endOfTile := route.endOfTile
  multiplied.overflow := route.overflow
  multiplied.selectedColumns := route.selectedColumns
  multiplied.effectualMacs := route.effectualMacs
  for (fiber <- 0 until cfg.maxFibers) {
    multiplied.tripATags(fiber) := route.tripATags(fiber)
    multiplied.tripBTags(fiber) := route.tripBTags(fiber)
  }
  for (lane <- 0 until cfg.macLanes) {
    multiplied.laneValid(lane) := route.laneValid(lane)
    multiplied.laneAFiber(lane) := route.laneAFiber(lane)
    multiplied.laneBFiber(lane) := route.laneBFiber(lane)
    val laneProduct = route.laneAOperand(lane) * route.laneBOperand(lane)
    multiplied.product(lane) := Mux(route.laneValid(lane),
      laneProduct.resize(cfg.accumulatorWidth), S(0, cfg.accumulatorWidth bits))
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    multiplied.gustRow(lane) := route.gustRow(lane)
    multiplied.gustColumn(lane) := route.gustColumn(lane)
  }

  val macInput = Stream(PERowMacPayload(cfg))
  macInput.valid := routeStage.valid
  macInput.payload := multiplied
  routeStage.ready := macInput.ready
  val macStage = macInput.m2sPipe()

  // Stage 3: order products and mark segment boundaries at the MRN leaves.
  val mac = macStage.payload
  val tripMode = mac.mode === TrapezoidDataflow.DenseIp ||
    mac.mode === TrapezoidDataflow.TrIp
  val tripLeaves = (0 until cfg.macLanes).map { lane =>
    val leaf = PERowReducedProduct(cfg)
    leaf.valid := mac.laneValid(lane)
    leaf.segmentStart := False
    leaf.row := mac.tripATags(mac.laneAFiber(lane))
    leaf.column := mac.tripBTags(mac.laneBFiber(lane))
    leaf.destinationBank := mac.laneAFiber(lane).resized
    leaf.destinationWordOffset := mac.laneBFiber(lane).resized
    leaf.value := mac.product(lane)
    leaf
  }

  val gustLeaves = (0 until cfg.cacheLineWords).map { lane =>
    val leaf = PERowReducedProduct(cfg)
    leaf.valid := mac.laneValid(lane)
    leaf.segmentStart := False
    leaf.row := mac.gustRow(lane)
    leaf.column := mac.gustColumn(lane)
    leaf.destinationBank := 0
    leaf.destinationWordOffset := 0
    leaf.value := mac.product(lane)
    leaf
  }
  private val trGsCompacted = compactValid(gustLeaves)
  var trGsOrderError: Bool = False
  for (position <- 1 until cfg.cacheLineWords) {
    val previous = trGsCompacted.entries(position - 1)
    val current = trGsCompacted.entries(position)
    val coordinateDecreases = current.row < previous.row ||
      (current.row === previous.row && current.column < previous.column)
    trGsOrderError = trGsOrderError ||
      (previous.valid && current.valid && coordinateDecreases)
  }
  val gustOrderError = mac.overflow ||
    (mac.mode === TrapezoidDataflow.TrGs && trGsOrderError)

  val trGtRank = Vec(UInt(log2Up(cfg.localBanks + 1) bits), cfg.localBanks)
  for (lane <- 0 until cfg.localBanks) {
    val precedes = Bits(cfg.localBanks bits)
    for (other <- 0 until cfg.localBanks) {
      if (other == lane) {
        precedes(other) := False
      } else {
        val lowerCoordinate = mac.gustRow(other) < mac.gustRow(lane) ||
          (mac.gustRow(other) === mac.gustRow(lane) &&
            mac.gustColumn(other) < mac.gustColumn(lane))
        val sameCoordinateEarlier = if (other < lane) {
          mac.gustRow(other) === mac.gustRow(lane) &&
            mac.gustColumn(other) === mac.gustColumn(lane)
        } else {
          False
        }
        precedes(other) := mac.laneValid(other) &&
          (lowerCoordinate || sameCoordinateEarlier)
      }
    }
    trGtRank(lane) := CountOne(precedes).resized
  }

  val trGtSorted = Vec(PERowReducedProduct(cfg), cfg.cacheLineWords)
  for (position <- 0 until cfg.cacheLineWords) {
    trGtSorted(position) := PERowReducedProduct(cfg).getZero
    if (position < cfg.localBanks) {
      for (source <- 0 until cfg.localBanks) {
        when(mac.laneValid(source) && trGtRank(source) === position) {
          trGtSorted(position) := gustLeaves(source)
        }
      }
    }
  }

  val orderedProducts = Vec(PERowReducedProduct(cfg), cfg.macLanes)
  for (position <- 0 until cfg.macLanes) {
    val selected = if (position < cfg.cacheLineWords) {
      Mux(tripMode, tripLeaves(position),
        Mux(mac.mode === TrapezoidDataflow.TrGs,
          trGsCompacted.entries(position), trGtSorted(position)))
    } else {
      Mux(tripMode, tripLeaves(position),
        PERowReducedProduct(cfg).getZero)
    }
    orderedProducts(position).valid := selected.valid
    orderedProducts(position).row := selected.row
    orderedProducts(position).column := selected.column
    orderedProducts(position).destinationBank := selected.destinationBank
    orderedProducts(position).destinationWordOffset :=
      selected.destinationWordOffset
    orderedProducts(position).value := selected.value
    val startsNewSegment = if (position == 0) {
      True
    } else {
      !orderedProducts(position - 1).valid ||
        selected.row =/= orderedProducts(position - 1).row ||
        selected.column =/= orderedProducts(position - 1).column
    }
    orderedProducts(position).segmentStart := selected.valid && startsNewSegment
  }

  val reductionInput = Stream(PERowReductionPayload(cfg))
  reductionInput.valid := macStage.valid
  reductionInput.payload.mode := mac.mode
  reductionInput.payload.endOfTile := mac.endOfTile
  reductionInput.payload.overflow := Mux(tripMode, mac.overflow, gustOrderError)
  reductionInput.payload.selectedColumns := Mux(tripMode,
    mac.selectedColumns, U(0))
  reductionInput.payload.effectualMacs := Mux(tripMode,
    mac.effectualMacs, U(0))
  reductionInput.payload.lanes := orderedProducts
  macStage.ready := reductionInput.ready

  // The paper/SIGMA timing model assigns one cycle to each MRN level. The
  // segmented prefix operator carries a head flag so additions never cross
  // an output-coordinate boundary.
  var reductionStage = reductionInput.m2sPipe()
  for (level <- 0 until cfg.mrnReductionLevels) {
    val reductionDistance = 1 << level
    val levelInput = reductionStage
    val levelOutput = Stream(PERowReductionPayload(cfg))
    levelOutput.valid := levelInput.valid
    levelOutput.payload.mode := levelInput.payload.mode
    levelOutput.payload.endOfTile := levelInput.payload.endOfTile
    levelOutput.payload.overflow := levelInput.payload.overflow
    levelOutput.payload.selectedColumns := levelInput.payload.selectedColumns
    levelOutput.payload.effectualMacs := levelInput.payload.effectualMacs
    for (position <- 0 until cfg.macLanes) {
      val right = levelInput.payload.lanes(position)
      levelOutput.payload.lanes(position).valid := right.valid
      levelOutput.payload.lanes(position).row := right.row
      levelOutput.payload.lanes(position).column := right.column
      levelOutput.payload.lanes(position).destinationBank :=
        right.destinationBank
      levelOutput.payload.lanes(position).destinationWordOffset :=
        right.destinationWordOffset
      if (position >= reductionDistance) {
        val left = levelInput.payload.lanes(position - reductionDistance)
        val combine = right.valid && left.valid && !right.segmentStart
        levelOutput.payload.lanes(position).value := Mux(combine,
          (left.value + right.value).resize(cfg.accumulatorWidth), right.value)
        levelOutput.payload.lanes(position).segmentStart := right.valid &&
          (right.segmentStart || left.segmentStart)
      } else {
        levelOutput.payload.lanes(position).value := right.value
        levelOutput.payload.lanes(position).segmentStart := right.segmentStart
      }
    }
    levelInput.ready := levelOutput.ready
    reductionStage = levelOutput.m2sPipe()
  }

  // Final MRN stage: emit only segment tails and compact them to output lanes.
  val reduced = reductionStage.payload
  val segmentEnd = Vec(Bool(), cfg.macLanes)
  for (position <- 0 until cfg.macLanes) {
    val current = reduced.lanes(position)
    val nextDiffers = if (position == cfg.macLanes - 1) {
      True
    } else {
      val next = reduced.lanes(position + 1)
      !next.valid || current.row =/= next.row || current.column =/= next.column
    }
    segmentEnd(position) := current.valid && nextDiffers
  }
  // DenseIp deliberately carries zero-valued lanes.  Keep those lanes in the
  // reduction, but do not turn a zero sum into a sparse C entry.
  val nonZeroSegmentEnd = (0 until cfg.macLanes).map { position =>
    segmentEnd(position) && reduced.lanes(position).value =/= 0
  }
  val segmentPrefix = prefixCount(nonZeroSegmentEnd)

  val nextResponse = PERowResponse(cfg)
  nextResponse.mode := reduced.mode
  nextResponse.overflow := reduced.overflow
  nextResponse.selectedColumns := reduced.selectedColumns
  nextResponse.effectualMacs := reduced.effectualMacs
  nextResponse.count := Mux(reduced.overflow, U(0), segmentPrefix.last).resized
  for (out <- 0 until cfg.outputLanes) {
    nextResponse.results(out).valid := False
    nextResponse.results(out).row := 0
    nextResponse.results(out).column := 0
    nextResponse.results(out).value := 0
    nextResponse.results(out).endOfTile := reduced.endOfTile
    nextResponse.destinationBank(out) := 0
    nextResponse.destinationWordOffset(out) := 0
    for (position <- 0 until cfg.macLanes) {
      val outputIndex = segmentPrefix(position) - 1
      when(!reduced.overflow && nonZeroSegmentEnd(position) &&
          outputIndex === out) {
        nextResponse.results(out).valid := True
        nextResponse.results(out).row := reduced.lanes(position).row
        nextResponse.results(out).column := reduced.lanes(position).column
        nextResponse.results(out).value := reduced.lanes(position).value
        nextResponse.destinationBank(out) :=
          reduced.lanes(position).destinationBank
        nextResponse.destinationWordOffset(out) :=
          reduced.lanes(position).destinationWordOffset
      }
    }
  }

  // Final elastic response FIFO preserves order under downstream backpressure.
  val responseInput = Stream(PERowResponse(cfg))
  responseInput.valid := reductionStage.valid
  responseInput.payload := nextResponse
  reductionStage.ready := responseInput.ready
  val outputStage = (0 until cfg.responseFifoDepth).foldLeft(responseInput) {
    (stage, _) => stage.m2sPipe()
  }
  io.response.valid := outputStage.valid
  io.response.payload := outputStage.payload
  outputStage.ready := io.response.ready

  when(routeStage.valid) { routeCycleCounter := routeCycleCounter + 1 }
  when(macStage.valid) { macCycleCounter := macCycleCounter + 1 }
  when(reductionStage.valid) { mrnCycleCounter := mrnCycleCounter + 1 }
  when(outputStage.valid) { responseCycleCounter := responseCycleCounter + 1 }
  io.routeCycles := routeCycleCounter
  io.macCycles := macCycleCounter
  io.mrnCycles := mrnCycleCounter
  io.responseCycles := responseCycleCounter
}
