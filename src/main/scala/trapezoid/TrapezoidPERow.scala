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
  val tripValid = Vec(Bool(), cfg.macLanes)
  val tripAFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val tripBFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val tripAOperand = Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
  val tripBOperand = Vec(SInt(cfg.dataWidth bits), cfg.macLanes)

  val gustValid = Vec(Bool(), cfg.cacheLineWords)
  val gustRow = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustColumn = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustAOperand = Vec(SInt(cfg.dataWidth bits), cfg.cacheLineWords)
  val gustBOperand = Vec(SInt(cfg.dataWidth bits), cfg.cacheLineWords)
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
  val tripValid = Vec(Bool(), cfg.macLanes)
  val tripAFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val tripBFiber = Vec(UInt(cfg.fiberIndexWidth bits), cfg.macLanes)
  val tripProduct = Vec(SInt(cfg.accumulatorWidth bits), cfg.macLanes)

  val gustValid = Vec(Bool(), cfg.cacheLineWords)
  val gustRow = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustColumn = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustProduct = Vec(SInt(cfg.accumulatorWidth bits), cfg.cacheLineWords)
}

/** One packed TrIP product carried through the configurable reduction tree. */
case class PERowReducedProduct(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val aFiber = UInt(cfg.fiberIndexWidth bits)
  val bFiber = UInt(cfg.fiberIndexWidth bits)
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
}

case class TrapezoidPERowIo(cfg: TrapezoidConfig) extends Bundle {
  val command = slave(Stream(PERowCommand(cfg)))
  val response = master(Stream(PERowResponse(cfg)))
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

  private def balancedSum(terms: Seq[SInt]): SInt = {
    require(terms.nonEmpty)
    if (terms.length == 1) {
      terms.head.resize(cfg.accumulatorWidth)
    } else {
      val nextLevel = terms.grouped(2).map {
        case Seq(left, right) =>
          (left.resize(cfg.accumulatorWidth) + right.resize(cfg.accumulatorWidth))
            .resize(cfg.accumulatorWidth)
        case Seq(left) => left.resize(cfg.accumulatorWidth)
      }.toSeq
      balancedSum(nextLevel)
    }
  }

  private case class ReductionGroup(
      entries: Vec[PERowReducedProduct],
      count: UInt)

  /** Reduce adjacent products belonging to the same A/B fiber pair.
    *
    * MFIU packs products in pair-major order, so every output's products form
    * one contiguous cluster. Each tree node concatenates its child clusters
    * and needs only one boundary comparator/adder to join a cluster split
    * across the children. This is the reduction-mode behavior of the paper's
    * sliceable merge-reduction tree.
    */
  private def reduceContiguous(inputs: Seq[PERowReducedProduct]): ReductionGroup = {
    require(inputs.nonEmpty)
    if (inputs.length == 1) {
      val entries = Vec(PERowReducedProduct(cfg), 1)
      val count = UInt(1 bits)
      entries(0) := inputs.head
      count := inputs.head.valid.asUInt
      ReductionGroup(entries, count)
    } else {
      val split = inputs.length / 2
      val left = reduceContiguous(inputs.take(split))
      val right = reduceContiguous(inputs.drop(split))
      val entryCount = inputs.length
      val countWidth = log2Up(entryCount + 1)
      val entries = Vec(PERowReducedProduct(cfg), entryCount)
      val count = UInt(countWidth bits)
      val leftCount = left.count.resize(countWidth)
      val rightCount = right.count.resize(countWidth)

      val leftLast = PERowReducedProduct(cfg)
      leftLast := left.entries(0)
      for (index <- 1 until left.entries.length) {
        when(left.count === index + 1) {
          leftLast := left.entries(index)
        }
      }

      val joinBoundary = leftCount =/= 0 && rightCount =/= 0 &&
        leftLast.aFiber === right.entries(0).aFiber &&
        leftLast.bFiber === right.entries(0).bFiber
      count := (leftCount + rightCount - joinBoundary.asUInt).resize(countWidth)

      for (output <- 0 until entryCount) {
        if (output < left.entries.length) {
          entries(output) := left.entries(output)
        } else {
          entries(output) := PERowReducedProduct(cfg).getZero
        }

        for (source <- 0 until right.entries.length) {
          val appendSource = if (source == 0) {
            right.entries(source).valid && !joinBoundary
          } else {
            right.entries(source).valid
          }
          val target = (leftCount + U(source, countWidth bits) -
            joinBoundary.asUInt.resize(countWidth)).resize(countWidth)
          when(appendSource && target === output) {
            entries(output) := right.entries(source)
          }
        }

        when(joinBoundary && leftCount === output + 1) {
          entries(output).valid := True
          entries(output).aFiber := leftLast.aFiber
          entries(output).bFiber := leftLast.bFiber
          entries(output).row := leftLast.row
          entries(output).column := leftLast.column
          entries(output).value :=
            (leftLast.value + right.entries(0).value).resize(cfg.accumulatorWidth)
        }
      }

      ReductionGroup(entries, count)
    }
  }

  // Stage 0: capture one complete command and decouple the scheduler from MFIU.
  val inputStage = io.command.m2sPipe()

  // Stage 1: the MFIU/shift equivalent distributes operands to fixed lanes.
  val mfiu = new MultiFiberIntersection(cfg)
  val columnPacker = new DynamicColumnPacker(cfg)
  val input = inputStage.payload
  for (fiber <- 0 until cfg.maxFibers) {
    mfiu.io.aValid(fiber) := input.trip.a(fiber).valid
    mfiu.io.aMask(fiber) := input.trip.a(fiber).mask
    mfiu.io.bValid(fiber) := input.trip.b(fiber).valid
    mfiu.io.bMask(fiber) := input.trip.b(fiber).mask
    columnPacker.io.aValid(fiber) := input.trip.a(fiber).valid
    columnPacker.io.aMask(fiber) := input.trip.a(fiber).mask
    columnPacker.io.bValid(fiber) := input.trip.b(fiber).valid
    columnPacker.io.bMask(fiber) := input.trip.b(fiber).mask
  }

  val routed = PERowRoutePayload(cfg)
  routed.mode := input.mode
  routed.endOfTile := input.endOfTile
  routed.overflow := mfiu.io.overflow
  routed.selectedColumns := columnPacker.io.selectedColumns
  routed.effectualMacs := mfiu.io.count

  for (fiber <- 0 until cfg.maxFibers) {
    routed.tripATags(fiber) := input.trip.a(fiber).tag
    routed.tripBTags(fiber) := input.trip.b(fiber).tag
  }
  for (lane <- 0 until cfg.macLanes) {
    val laneRoute = mfiu.io.routes(lane)
    val aFiber = input.trip.a(laneRoute.aFiber)
    val bFiber = input.trip.b(laneRoute.bFiber)
    routed.tripValid(lane) := laneRoute.valid
    routed.tripAFiber(lane) := laneRoute.aFiber
    routed.tripBFiber(lane) := laneRoute.bFiber
    routed.tripAOperand(lane) := aFiber.values(laneRoute.aValueIndex)
    routed.tripBOperand(lane) := bFiber.values(laneRoute.bValueIndex)
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    val b = input.gustavson.b(lane)
    val a = input.gustavson.a(b.aIndex)
    routed.gustValid(lane) := b.valid && a.valid && a.k === b.k
    routed.gustRow(lane) := a.row
    routed.gustColumn(lane) := b.column
    routed.gustAOperand(lane) := a.value
    routed.gustBOperand(lane) := b.value
  }

  val routeInput = Stream(PERowRoutePayload(cfg))
  routeInput.valid := inputStage.valid
  routeInput.payload := routed
  inputStage.ready := routeInput.ready
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
    multiplied.tripValid(lane) := route.tripValid(lane)
    multiplied.tripAFiber(lane) := route.tripAFiber(lane)
    multiplied.tripBFiber(lane) := route.tripBFiber(lane)
    val product = route.tripAOperand(lane) * route.tripBOperand(lane)
    multiplied.tripProduct(lane) := Mux(route.tripValid(lane),
      product.resize(cfg.accumulatorWidth), S(0, cfg.accumulatorWidth bits))
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    multiplied.gustValid(lane) := route.gustValid(lane)
    multiplied.gustRow(lane) := route.gustRow(lane)
    multiplied.gustColumn(lane) := route.gustColumn(lane)
    val product = route.gustAOperand(lane) * route.gustBOperand(lane)
    multiplied.gustProduct(lane) := Mux(route.gustValid(lane),
      product.resize(cfg.accumulatorWidth), S(0, cfg.accumulatorWidth bits))
  }

  val macInput = Stream(PERowMacPayload(cfg))
  macInput.valid := routeStage.valid
  macInput.payload := multiplied
  routeStage.ready := macInput.ready
  val macStage = macInput.m2sPipe()

  // Stage 3: merge products by output coordinate with logarithmic-depth trees.
  val mac = macStage.payload
  val nextResponse = PERowResponse(cfg)
  nextResponse.mode := mac.mode
  nextResponse.overflow := False
  nextResponse.selectedColumns := 0
  nextResponse.effectualMacs := 0
  nextResponse.count := 0
  for (out <- 0 until cfg.outputLanes) {
    nextResponse.results(out).valid := False
    nextResponse.results(out).row := 0
    nextResponse.results(out).column := 0
    nextResponse.results(out).value := 0
    nextResponse.results(out).endOfTile := mac.endOfTile
  }

  val tripMode = mac.mode === TrapezoidDataflow.DenseIp ||
    mac.mode === TrapezoidDataflow.TrIp
  val tripLeaves = (0 until cfg.macLanes).map { lane =>
    val leaf = PERowReducedProduct(cfg)
    leaf.valid := mac.tripValid(lane)
    leaf.aFiber := mac.tripAFiber(lane)
    leaf.bFiber := mac.tripBFiber(lane)
    leaf.row := mac.tripATags(mac.tripAFiber(lane))
    leaf.column := mac.tripBTags(mac.tripBFiber(lane))
    leaf.value := mac.tripProduct(lane)
    leaf
  }
  private val tripReduction = reduceContiguous(tripLeaves)
  for (out <- 0 until cfg.outputLanes) {
    if (out < cfg.macLanes) {
      when(tripMode && !mac.overflow && tripReduction.entries(out).valid) {
        nextResponse.results(out).valid := True
        nextResponse.results(out).row := tripReduction.entries(out).row
        nextResponse.results(out).column := tripReduction.entries(out).column
        nextResponse.results(out).value := tripReduction.entries(out).value
      }
    }
  }

  when(tripMode) {
    nextResponse.overflow := mac.overflow
    nextResponse.selectedColumns := mac.selectedColumns
    nextResponse.effectualMacs := mac.effectualMacs
    nextResponse.count := Mux(mac.overflow, U(0), tripReduction.count).resized
  }

  val leader = Vec(Bool(), cfg.cacheLineWords)
  val leaderPrefix = Vec(UInt(cfg.outputCountWidth bits), cfg.cacheLineWords + 1)
  leaderPrefix(0) := 0
  for (lane <- 0 until cfg.cacheLineWords) {
    var seen = False
    for (previous <- 0 until lane) {
      seen = seen || (mac.gustValid(previous) &&
        mac.gustRow(previous) === mac.gustRow(lane) &&
        mac.gustColumn(previous) === mac.gustColumn(lane))
    }
    leader(lane) := mac.gustValid(lane) && !seen
    leaderPrefix(lane + 1) := leaderPrefix(lane) + leader(lane).asUInt

    val terms = (0 until cfg.cacheLineWords).map { other =>
      val sameOutput = mac.gustValid(other) &&
        mac.gustRow(other) === mac.gustRow(lane) &&
        mac.gustColumn(other) === mac.gustColumn(lane)
      Mux(sameOutput, mac.gustProduct(other), S(0, cfg.accumulatorWidth bits))
    }
    val sum = balancedSum(terms)
    for (out <- 0 until cfg.outputLanes) {
      when(!tripMode && leader(lane) && leaderPrefix(lane) === out) {
        nextResponse.results(out).valid := True
        nextResponse.results(out).row := mac.gustRow(lane)
        nextResponse.results(out).column := mac.gustColumn(lane)
        nextResponse.results(out).value := sum
      }
    }
  }

  when(!tripMode) {
    nextResponse.count := leaderPrefix.last.resized
  }

  // Stage 4: field-wise elastic registers preserve response ordering and make
  // backpressure independent of whether a target can infer a very-wide FIFO.
  val responseInput = Stream(PERowResponse(cfg))
  responseInput.valid := macStage.valid
  responseInput.payload := nextResponse
  macStage.ready := responseInput.ready
  val outputStage = (0 until cfg.responseFifoDepth).foldLeft(responseInput) {
    (stage, _) => stage.m2sPipe()
  }
  io.response.valid := outputStage.valid
  io.response.payload := outputStage.payload
  outputStage.ready := io.response.ready
}
