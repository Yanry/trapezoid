package trapezoid

import spinal.core._
import spinal.lib._

/** One registered level of the augmented PE-row merge-reduction tree.
  *
  * A level contains one arithmetic node per pair of child subtrees. Entries
  * inside each child are already compacted and coordinate ordered. The node
  * therefore only compares the left tail with the right head. Equal
  * coordinates are reduced once; level-local routing concatenates the two
  * child lists and removes the duplicate right head.
  *
  * Level `l` has `macLanes / 2^(l + 1)` nodes. Across all levels this is
  * exactly `macLanes - 1` spatial arithmetic nodes.
  */
class DualModeMrnLevel(
    cfg: TrapezoidConfig,
    level: Int,
    temporalPortPositions: Map[Int, Int] = Map.empty
) extends Component {
  require(level >= 0 && level < cfg.mrnReductionLevels)

  private val childCapacity = 1 << level
  private val subtreeCapacity = childCapacity * 2
  private val nodeCount = cfg.macLanes / subtreeCapacity
  private val childIndexWidth = scala.math.max(1, log2Up(childCapacity))
  private val childCountWidth = log2Up(childCapacity + 1)
  private val subtreeCountWidth = log2Up(subtreeCapacity + 1)
  require(nodeCount >= 1)
  require(temporalPortPositions.values.forall(position =>
    position >= 0 && position < nodeCount))

  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  private val temporalDemand = temporalPortPositions.keys.toSeq
    .map(port => io.temporalInputs(port).valid)
    .foldLeft(False)(_ || _)
  val transformed = Stream(PERowReductionPayload(cfg))
  // Temporal clients reuse physical tree nodes. A resident temporal request
  // holds this complete spatial level until the registered temporal response
  // accepts the request.
  transformed.valid := io.input.valid && !temporalDemand
  io.input.ready := transformed.ready && !temporalDemand
  transformed.payload.mode := io.input.payload.mode
  transformed.payload.endOfTile := io.input.payload.endOfTile
  transformed.payload.responseTag := io.input.payload.responseTag
  transformed.payload.selectedColumns := io.input.payload.selectedColumns
  transformed.payload.effectualMacs := io.input.payload.effectualMacs

  for (port <- 0 until cfg.localBanks if !temporalPortPositions.contains(port)) {
    io.temporalInputs(port).ready := False
    io.temporalOutputs(port).valid := False
    io.temporalOutputs(port).payload := DualModeMrnResponse(cfg).getZero
  }

  val mergeMode = io.input.payload.mode === TrapezoidDataflow.TrGt ||
    io.input.payload.mode === TrapezoidDataflow.TrGs
  val nodeOrderViolations = Vec(Bool(), nodeCount)

  for (node <- 0 until nodeCount) {
    val subtreeBase = node * subtreeCapacity
    val rightBase = subtreeBase + childCapacity
    val leftEntries = Vec((0 until childCapacity).map(offset =>
      io.input.payload.lanes(subtreeBase + offset)))
    val rightEntries = Vec((0 until childCapacity).map(offset =>
      io.input.payload.lanes(rightBase + offset)))
    val leftCount = CountOne(leftEntries.map(_.valid).asBits)
      .resize(childCountWidth)
    val rightCount = CountOne(rightEntries.map(_.valid).asBits)
      .resize(childCountWidth)
    val leftTailIndex = UInt(childIndexWidth bits)
    leftTailIndex := 0
    for (position <- 1 until childCapacity) {
      when(leftEntries(position).valid) {
        leftTailIndex := position
      }
    }
    val leftTail = if (childCapacity == 1) leftEntries(0)
      else leftEntries(leftTailIndex)
    val rightHead = rightEntries(0)

    val temporalPort = temporalPortPositions.collectFirst {
      case (port, mappedNode) if mappedNode == node => port
    }
    val cell = temporalPort match {
      case Some(port) =>
        val useTemporal = io.temporalInputs(port).valid
        val shared = DualModeMrnCell(
          cfg,
          Mux(useTemporal, io.temporalInputs(port).payload.left.valid,
            leftTail.valid),
          Mux(useTemporal, io.temporalInputs(port).payload.left.row,
            leftTail.row),
          Mux(useTemporal, io.temporalInputs(port).payload.left.column,
            leftTail.column),
          Mux(useTemporal, io.temporalInputs(port).payload.left.value,
            leftTail.value),
          Mux(useTemporal, io.temporalInputs(port).payload.right.valid,
            rightHead.valid),
          Mux(useTemporal, io.temporalInputs(port).payload.right.row,
            rightHead.row),
          Mux(useTemporal, io.temporalInputs(port).payload.right.column,
            rightHead.column),
          Mux(useTemporal, io.temporalInputs(port).payload.right.value,
            rightHead.value))
        val temporalResult = Stream(DualModeMrnResponse(cfg))
        temporalResult.valid := useTemporal
        temporalResult.payload := DualModeMrnCell.response(
          cfg, io.temporalInputs(port).payload, shared)
        io.temporalInputs(port).ready := temporalResult.ready
        io.temporalOutputs(port) << temporalResult.m2sPipe()
        shared
      case None =>
        DualModeMrnCell(
          cfg,
          leftTail.valid, leftTail.row, leftTail.column, leftTail.value,
          rightHead.valid, rightHead.row, rightHead.column, rightHead.value)
    }

    // Per-node runtime control: an equal boundary selects the adder and drops
    // one duplicate coordinate. A mismatch forwards both child segment lists.
    val reduceBoundary = cell.sameCoordinate
    nodeOrderViolations(node) := mergeMode && leftTail.valid &&
      rightHead.valid && !cell.sameCoordinate && !cell.leftBeforeRight
    val outputCount = (leftCount.resize(subtreeCountWidth) +
      rightCount.resize(subtreeCountWidth) - reduceBoundary.asUInt)
      .resize(subtreeCountWidth)

    for (position <- 0 until subtreeCapacity) {
      val output = transformed.payload.lanes(subtreeBase + position)
      output := PERowReducedProduct(cfg).getZero
      val positionValue = U(position, subtreeCountWidth bits)
      val fromLeft = positionValue < leftCount.resize(subtreeCountWidth)
      val isReducedBoundary = reduceBoundary && leftCount =/= 0 &&
        positionValue === (leftCount.resize(subtreeCountWidth) - 1)
      val rightIndexWide = (positionValue - leftCount.resize(subtreeCountWidth) +
        reduceBoundary.asUInt.resize(subtreeCountWidth)).resized
      val rightIndex = rightIndexWide.resize(childIndexWidth)
      val selectedLeft = PERowReducedProduct(cfg)
      val selectedRight = PERowReducedProduct(cfg)
      if (childCapacity == 1) {
        selectedLeft := leftEntries(0)
        selectedRight := rightEntries(0)
      } else {
        selectedLeft := leftEntries(positionValue.resize(childIndexWidth))
        selectedRight := rightEntries(rightIndex)
      }

      when(positionValue < outputCount) {
        when(fromLeft) {
          output.valid := selectedLeft.valid
          output.row := selectedLeft.row
          output.column := selectedLeft.column
          output.destinationBank := selectedLeft.destinationBank
          output.destinationWordOffset := selectedLeft.destinationWordOffset
          output.value := selectedLeft.value
          when(isReducedBoundary) {
            // Preserve the rightmost source identity after a TrGT coalesce.
            output.row := rightHead.row
            output.column := rightHead.column
            output.destinationBank := rightHead.destinationBank
            output.destinationWordOffset := rightHead.destinationWordOffset
            output.value := cell.reducedValue
          }
        } otherwise {
          output.valid := selectedRight.valid
          output.row := selectedRight.row
          output.column := selectedRight.column
          output.destinationBank := selectedRight.destinationBank
          output.destinationWordOffset := selectedRight.destinationWordOffset
          output.value := selectedRight.value
        }
        output.segmentStart := True
      }
    }
  }

  transformed.payload.overflow := io.input.payload.overflow ||
    nodeOrderViolations.asBits.orR
  io.output << transformed.m2sPipe()
}

/** `N-1`-node augmented MRN with one elastic register per tree level. */
class DualModeMrnTree(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  private val spatialPlacements = (for {
    levelIndex <- 0 until cfg.mrnReductionLevels
    node <- 0 until (cfg.macLanes / (1 << (levelIndex + 1)))
  } yield (levelIndex, node)).zipWithIndex.take(cfg.localBanks).map {
    case ((levelIndex, node), port) => (port, levelIndex, node)
  }
  private val placedPorts = spatialPlacements.map(_._1).toSet

  var stage = io.input
  for (levelIndex <- 0 until cfg.mrnReductionLevels) {
    val levelPorts = spatialPlacements.collect {
      case (port, mappedLevel, node) if mappedLevel == levelIndex =>
        port -> node
    }.toMap
    val level = new DualModeMrnLevel(cfg, levelIndex, levelPorts)
    level.io.input << stage
    for (port <- 0 until cfg.localBanks) {
      if (levelPorts.contains(port)) {
        level.io.temporalInputs(port) << io.temporalInputs(port)
        io.temporalOutputs(port) << level.io.temporalOutputs(port)
      } else {
        level.io.temporalInputs(port).valid := False
        level.io.temporalInputs(port).payload :=
          DualModeMrnRequest(cfg).getZero
        level.io.temporalOutputs(port).ready := True
      }
    }
    stage = level.io.output
  }
  io.output << stage

  // Four-lane unit-test configurations contain only three spatial nodes while
  // retaining four local banks. Production small/paper profiles reuse four of
  // their N-1 spatial nodes and instantiate no supplemental temporal cells.
  for (port <- 0 until cfg.localBanks if !placedPorts.contains(port)) {
    val temporal = new DualModeMrnNode(cfg)
    temporal.io.input << io.temporalInputs(port)
    io.temporalOutputs(port) << temporal.io.output
  }
}
