package trapezoid

import spinal.core._
import spinal.lib._

/** Routed operands captured between the distribution network and MAC lanes. */
case class PERowRoutePayload(cfg: TrapezoidConfig) extends Bundle {
  val mode = TrapezoidDataflow()
  val endOfTile = Bool()
  val responseTag = UInt(cfg.responseTagWidth bits)
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
  val responseTag = UInt(cfg.responseTagWidth bits)
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
  /** Mapper-selected spatial MRN profile carried with the command. */
  val mrnProfile = SpatialMrnProfile()
  val endOfTile = Bool()
  val responseTag = UInt(cfg.responseTagWidth bits)
  val overflow = Bool()
  val selectedColumns = UInt(log2Up(cfg.maxFibers + 1) bits)
  val effectualMacs = UInt(cfg.matchCountWidth bits)
  val lanes = Vec(PERowReducedProduct(cfg), cfg.macLanes)
}

case class BenesPerformanceCounters() extends Bundle {
  val commands = UInt(32 bits)
  val aCacheHits = UInt(32 bits)
  val bCacheHits = UInt(32 bits)
  val aFastRoutes = UInt(32 bits)
  val bFastRoutes = UInt(32 bits)
  val aSearches = UInt(32 bits)
  val bSearches = UInt(32 bits)
  val prepareCycles = UInt(32 bits)
  val aSearchCycles = UInt(32 bits)
  val bSearchCycles = UInt(32 bits)
  val aBacktracks = UInt(32 bits)
  val bBacktracks = UInt(32 bits)
}

case class TrapezoidPERowIo(cfg: TrapezoidConfig) extends Bundle {
  val command = slave(Stream(PERowCommand(cfg)))
  val response = master(Stream(PERowResponse(cfg)))
  /** Mapper/compiler programming port for the per-level MRN control store. */
  val mrnProgram = slave(Flow(SpatialMrnControlWrite(cfg)))
  val temporalMrnInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
    cfg.localBanks)
  val temporalMrnOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
    cfg.localBanks)
  val controlPrepareCycles = out UInt(32 bits)
  val routeCycles = out UInt(32 bits)
  val macCycles = out UInt(32 bits)
  val mrnCycles = out UInt(32 bits)
  val responseCycles = out UInt(32 bits)
  val benesPerformance = out(BenesPerformanceCounters())
}

trait TrapezoidPERowComponent {
  val io: TrapezoidPERowIo
}

object TrapezoidPERowComponent {
  def tieOffMrnProgramming(
      pe: TrapezoidPERowComponent,
      cfg: TrapezoidConfig): Unit = {
    pe.io.mrnProgram.valid := False
    pe.io.mrnProgram.payload := SpatialMrnControlWrite(cfg).getZero
  }

  def tieOffTemporalMrn(
      pe: TrapezoidPERowComponent,
      cfg: TrapezoidConfig): Unit = {
    tieOffMrnProgramming(pe, cfg)
    for (port <- 0 until cfg.localBanks) {
      pe.io.temporalMrnInputs(port).valid := False
      pe.io.temporalMrnInputs(port).payload := DualModeMrnRequest(cfg).getZero
      pe.io.temporalMrnOutputs(port).ready := True
    }
  }
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
  val controlPrepareCycleCounter = Reg(UInt(32 bits)) init 0
  controlPrepareCycleCounter := controlPrepareCycleCounter
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

  // Stage 0: descriptor FIFO.  The command payload remains wide, but it is
  // now decoupled from route-control preparation by two elastic entries.
  val inputStage = if (cfg.macLanes > 64) {
    // Paper commands are tens of kilobits wide. Two elastic register stages
    // avoid flattening that payload into a single oversized FIFO RAM word.
    io.command.m2sPipe().m2sPipe()
  } else {
    val fifo = new StreamFifoLowLatency(
      PERowCommand(cfg), depth = 4, latency = 1, useVec = false)
    fifo.io.push <> io.command
    fifo.io.pop
  }

  // Stage 1: the MFIU/shift equivalent distributes operands to fixed lanes.
  val mfiu = new MultiFiberIntersection(cfg)
  val aBenesControls: BenesRouteControlGenerator =
    if (cfg.macLanes > 16) new PipelinedBenesRouteControlGenerator(cfg)
    else new BenesRouteControlGenerator(cfg)
  val bBenesControls: BenesRouteControlGenerator =
    if (cfg.macLanes > 16) new PipelinedBenesRouteControlGenerator(cfg)
    else new BenesRouteControlGenerator(cfg)
  val aFastControls = new RestrictedBenesRouteControlGenerator(cfg)
  val bFastControls = new RestrictedBenesRouteControlGenerator(cfg)
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
  val sparseRouteNeedsControl = useSparseTripRoute &&
    mfiu.io.count =/= 0 && !mfiu.io.overflow
  val aSent = RegInit(False)
  val bSent = RegInit(False)
  val aResultValid = RegInit(False)
  val bResultValid = RegInit(False)
  val aResult = Reg(BenesRouteControlResult(cfg))
  val bResult = Reg(BenesRouteControlResult(cfg))
  private val controlCacheEntries = 4
  val aCacheValid = Vec((0 until controlCacheEntries).map(_ => RegInit(False)))
  val bCacheValid = Vec((0 until controlCacheEntries).map(_ => RegInit(False)))
  val aCacheMetadata = Vec((0 until controlCacheEntries).map(_ =>
    Reg(BenesRouteMetadata(cfg))))
  val bCacheMetadata = Vec((0 until controlCacheEntries).map(_ =>
    Reg(BenesRouteMetadata(cfg))))
  val aCacheControls = Vec((0 until controlCacheEntries).map(_ =>
    Reg(Bits(cfg.benesControlWidth bits))))
  val bCacheControls = Vec((0 until controlCacheEntries).map(_ =>
    Reg(Bits(cfg.benesControlWidth bits))))
  val aCacheReplace = Reg(UInt(log2Up(controlCacheEntries) bits)) init 0
  val bCacheReplace = Reg(UInt(log2Up(controlCacheEntries) bits)) init 0
  val currentMetadata = PERowBenesMetadata(cfg)
  currentMetadata.routeValid := mfiu.io.routes.map(_.valid).asBits
  for (lane <- 0 until cfg.macLanes) {
    currentMetadata.aSourceIndices(lane) := mfiu.io.routes(lane).aSourceIndex
    currentMetadata.bSourceIndices(lane) := mfiu.io.routes(lane).bSourceIndex
    aBenesFabric.io.sources(lane)(0) := mfiu.io.aSourceValid(lane)
    aBenesFabric.io.sources(lane)(cfg.dataWidth downto 1) :=
      mfiu.io.aSources(lane).asBits
    bBenesFabric.io.sources(lane)(0) := mfiu.io.bSourceValid(lane)
    bBenesFabric.io.sources(lane)(cfg.dataWidth downto 1) :=
      mfiu.io.bSources(lane).asBits
  }
  aBenesControls.io.start.payload.routeValid := currentMetadata.routeValid
  aBenesControls.io.start.payload.sourceIndices := currentMetadata.aSourceIndices
  bBenesControls.io.start.payload.routeValid := currentMetadata.routeValid
  bBenesControls.io.start.payload.sourceIndices := currentMetadata.bSourceIndices
  aFastControls.io.metadata := aBenesControls.io.start.payload
  bFastControls.io.metadata := bBenesControls.io.start.payload
  val aFastRoute = aFastControls.io.supported
  val bFastRoute = bFastControls.io.supported
  val aCacheHits = Bits(controlCacheEntries bits)
  val bCacheHits = Bits(controlCacheEntries bits)
  for (entry <- 0 until controlCacheEntries) {
    var aMatches: Bool = (aCacheMetadata(entry).routeValid & currentMetadata.routeValid) ===
      currentMetadata.routeValid
    var bMatches: Bool = (bCacheMetadata(entry).routeValid & currentMetadata.routeValid) ===
      currentMetadata.routeValid
    for (lane <- 0 until cfg.macLanes) {
      aMatches = aMatches && (!currentMetadata.routeValid(lane) ||
        aCacheMetadata(entry).sourceIndices(lane) === currentMetadata.aSourceIndices(lane))
      bMatches = bMatches && (!currentMetadata.routeValid(lane) ||
        bCacheMetadata(entry).sourceIndices(lane) === currentMetadata.bSourceIndices(lane))
    }
    aCacheHits(entry) := aCacheValid(entry) && aMatches
    bCacheHits(entry) := bCacheValid(entry) && bMatches
  }
  val aCacheHit = aCacheHits.orR
  val bCacheHit = bCacheHits.orR
  val aCacheHitIndex = OHToUInt(OHMasking.first(aCacheHits))
  val bCacheHitIndex = OHToUInt(OHMasking.first(bCacheHits))
  val requestNeeded = inputStage.valid && sparseRouteNeedsControl
  aBenesControls.io.start.valid := requestNeeded && !aFastRoute && !aCacheHit && !aSent
  bBenesControls.io.start.valid := requestNeeded && !bFastRoute && !bCacheHit && !bSent
  aBenesControls.io.result.ready := !aResultValid
  bBenesControls.io.result.ready := !bResultValid
  when(aBenesControls.io.start.fire) { aSent := True }
  when(bBenesControls.io.start.fire) { bSent := True }
  when(aBenesControls.io.result.fire) {
    aResult := aBenesControls.io.result.payload
    aResultValid := True
  }
  when(bBenesControls.io.result.fire) {
    bResult := bBenesControls.io.result.payload
    bResultValid := True
  }
  val aControlsAvailable = aFastRoute || aCacheHit || aResultValid
  val bControlsAvailable = bFastRoute || bCacheHit || bResultValid
  routeControlsReady := !sparseRouteNeedsControl ||
    (aControlsAvailable && bControlsAvailable)
  aRouteRoutable := !sparseRouteNeedsControl || aFastRoute ||
    aCacheHit || (aResultValid && aResult.routable)
  bRouteRoutable := !sparseRouteNeedsControl || bFastRoute ||
    bCacheHit || (bResultValid && bResult.routable)
  aBenesFabric.io.controls := Mux(aFastRoute, aFastControls.io.controls,
    Mux(aCacheHit, aCacheControls(aCacheHitIndex), aResult.controls))
  bBenesFabric.io.controls := Mux(bFastRoute, bFastControls.io.controls,
    Mux(bCacheHit, bCacheControls(bCacheHitIndex), bResult.controls))
  when(inputStage.valid && sparseRouteNeedsControl && !routeControlsReady) {
    controlPrepareCycleCounter := controlPrepareCycleCounter + 1
  }

  val routed = PERowRoutePayload(cfg)
  routed.mode := input.mode
  routed.endOfTile := input.endOfTile
  routed.responseTag := input.responseTag
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
      tripAOperand := Mux(denseFastPath, denseA.values(lane), benesAOperand)
      tripBOperand := Mux(denseFastPath, denseB.values(lane), benesBOperand)
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

    routed.laneValid(lane) := Mux(inputTripMode,
      tripValid && aRouteRoutable && bRouteRoutable && !mfiu.io.overflow,
      gustValid)
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
      (mfiu.io.count =/= 0 &&
        (!aRouteRoutable || !bRouteRoutable)), gustRouteErrors.orR))

  val routeInput = Stream(PERowRoutePayload(cfg))
  routeInput.valid := inputStage.valid && routeControlsReady
  routeInput.payload := routed
  inputStage.ready := routeInput.ready && routeControlsReady
  when(routeInput.fire) {
    aSent := False
    bSent := False
    aResultValid := False
    bResultValid := False
    when(sparseRouteNeedsControl && !routed.overflow) {
      for (entry <- 0 until controlCacheEntries) {
        when(aCacheReplace === entry && !aFastRoute && !aCacheHit && aResultValid) {
          aCacheValid(entry) := True
          aCacheMetadata(entry) := aBenesControls.io.start.payload
          aCacheControls(entry) := aResult.controls
        }
        when(bCacheReplace === entry && !bFastRoute && !bCacheHit && bResultValid) {
          bCacheValid(entry) := True
          bCacheMetadata(entry) := bBenesControls.io.start.payload
          bCacheControls(entry) := bResult.controls
        }
      }
      when(!aFastRoute && !aCacheHit && aResultValid) { aCacheReplace := aCacheReplace + 1 }
      when(!bFastRoute && !bCacheHit && bResultValid) { bCacheReplace := bCacheReplace + 1 }
    }
    when(input.endOfTile) {
      for (entry <- 0 until controlCacheEntries) {
        aCacheValid(entry) := False
        bCacheValid(entry) := False
      }
      aCacheReplace := 0
      bCacheReplace := 0
    }
  }
  when(routeInput.fire && useSparseTripRoute && !routed.overflow) {
    assert(aRouteRoutable && bRouteRoutable,
      "A successful MFIU route must have both Benes controls")
  }
  val routeStage = if (cfg.macLanes > 64) {
    routeInput.m2sPipe()
  } else {
    val fifo = new StreamFifoLowLatency(
      PERowRoutePayload(cfg), depth = 2, latency = 1, useVec = false)
    fifo.io.push <> routeInput
    fifo.io.pop
  }

  // Stage 2: signed multiplier lanes. Invalid lanes are explicitly zeroed.
  val route = routeStage.payload
  val multiplied = PERowMacPayload(cfg)
  multiplied.mode := route.mode
  multiplied.endOfTile := route.endOfTile
  multiplied.responseTag := route.responseTag
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

  // Stage 3: prepare source-ordered products and segment metadata for the MRN leaves.
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
    if (lane < cfg.localBanks) {
      // In TrGT each input lane is the head of one temporal subrow. Preserve
      // that identity through sorting and spatial reduction so the temporal
      // radix-K merge can maintain four independent stream heads.
      leaf.destinationBank := Mux(mac.mode === TrapezoidDataflow.TrGt,
        U(lane, cfg.localBankIndexWidth bits),
        U(0, cfg.localBankIndexWidth bits))
    } else {
      leaf.destinationBank := 0
    }
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

  // TrGT deliberately keeps the source-head order here.  Its spatial MRN
  // below performs the radix-K head selection and equal-coordinate coalesce;
  // there is no independent rank-sort network between MAC and MRN.
  val orderedProducts = Vec(PERowReducedProduct(cfg), cfg.macLanes)
  for (position <- 0 until cfg.macLanes) {
    val selected = if (position < cfg.cacheLineWords) {
      Mux(tripMode, tripLeaves(position),
        Mux(mac.mode === TrapezoidDataflow.TrGs,
          trGsCompacted.entries(position), gustLeaves(position)))
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
  reductionInput.payload.mrnProfile := SpatialMrnProfile.fromDataflow(mac.mode)
  reductionInput.payload.endOfTile := mac.endOfTile
  reductionInput.payload.responseTag := mac.responseTag
  reductionInput.payload.overflow := Mux(tripMode, mac.overflow, gustOrderError)
  reductionInput.payload.selectedColumns := Mux(tripMode,
    mac.selectedColumns, U(0))
  reductionInput.payload.effectualMacs := Mux(tripMode,
    mac.effectualMacs, U(0))
  reductionInput.payload.lanes := orderedProducts
  macStage.ready := reductionInput.ready

  // All modes use one physical augmented MRN.  The shared ingress selects a
  // mode policy: DenseIp/TrIP/TrGS enter directly, while TrGT performs its
  // radix-K source-head merge before entering the same tree.
  val mrnTree = new DualModeRadixKMrnTree(cfg)
  mrnTree.io.input << reductionInput
  mrnTree.io.program.valid := io.mrnProgram.valid
  mrnTree.io.program.payload := io.mrnProgram.payload
  for (port <- 0 until cfg.localBanks) {
    mrnTree.io.temporalInputs(port) << io.temporalMrnInputs(port)
    io.temporalMrnOutputs(port) << mrnTree.io.temporalOutputs(port)
  }
  val reductionStage = mrnTree.io.output

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
  nextResponse.responseTag := reduced.responseTag
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
  io.controlPrepareCycles := controlPrepareCycleCounter
  io.routeCycles := routeCycleCounter
  io.macCycles := macCycleCounter
  io.mrnCycles := mrnCycleCounter
  io.responseCycles := responseCycleCounter
  val benesCommandCounter = Reg(UInt(32 bits)) init 0
  val aCacheHitCounter = Reg(UInt(32 bits)) init 0
  val bCacheHitCounter = Reg(UInt(32 bits)) init 0
  val aFastCounter = Reg(UInt(32 bits)) init 0
  val bFastCounter = Reg(UInt(32 bits)) init 0
  val aSearchCounter = Reg(UInt(32 bits)) init 0
  val bSearchCounter = Reg(UInt(32 bits)) init 0
  when(routeInput.fire && sparseRouteNeedsControl) {
    benesCommandCounter := benesCommandCounter + 1
    when(aFastRoute) { aFastCounter := aFastCounter + 1 }
    when(bFastRoute) { bFastCounter := bFastCounter + 1 }
    when(aCacheHit && !aFastRoute) { aCacheHitCounter := aCacheHitCounter + 1 }
    when(bCacheHit && !bFastRoute) { bCacheHitCounter := bCacheHitCounter + 1 }
  }
  when(aBenesControls.io.start.fire) { aSearchCounter := aSearchCounter + 1 }
  when(bBenesControls.io.start.fire) { bSearchCounter := bSearchCounter + 1 }
  io.benesPerformance.commands := benesCommandCounter
  io.benesPerformance.aCacheHits := aCacheHitCounter
  io.benesPerformance.bCacheHits := bCacheHitCounter
  io.benesPerformance.aFastRoutes := aFastCounter
  io.benesPerformance.bFastRoutes := bFastCounter
  io.benesPerformance.aSearches := aSearchCounter
  io.benesPerformance.bSearches := bSearchCounter
  io.benesPerformance.prepareCycles := controlPrepareCycleCounter
  io.benesPerformance.aSearchCycles := aBenesControls.io.searchCycles
  io.benesPerformance.bSearchCycles := bBenesControls.io.searchCycles
  io.benesPerformance.aBacktracks := aBenesControls.io.backtrackCount
  io.benesPerformance.bBacktracks := bBenesControls.io.backtrackCount
}
