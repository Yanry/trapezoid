package trapezoid

import spinal.core._
import spinal.lib._

/** Control and boundary state carried on one regular subtree link. */
case class PERowMrnSubtreeMetadata(cfg: TrapezoidConfig) extends Bundle {
  val count = UInt(log2Up(cfg.macLanes + 1) bits)
  val head = PERowReducedProduct(cfg)
  val tail = PERowReducedProduct(cfg)
}

/** Internal sideband carried between registered MRN levels.
  *
  * Only subtree-base entries are meaningful at a given level. Every node gets
  * its child count/head/tail directly from the previous level's regular links,
  * so the datapath does not search either compacted child list.
  */
case class PERowMrnLevelPayload(
    cfg: TrapezoidConfig,
    subtreeCount: Int
) extends Bundle {
  require(subtreeCount >= 1 && subtreeCount <= cfg.macLanes)

  val reduction = PERowReductionPayload(cfg)
  val profile = SpatialMrnProfile()
  val subtrees = Vec(PERowMrnSubtreeMetadata(cfg), subtreeCount)
}

/** Runtime control for one augmented-tree node and its local switch fabric. */
case class PERowMrnNodeControl(
    cfg: TrapezoidConfig,
    subtreeCapacity: Int
) extends Bundle {
  val leftCount = UInt(log2Up(cfg.macLanes + 1) bits)
  val rightCount = UInt(log2Up(cfg.macLanes + 1) bits)
  val outputCount = UInt(log2Up(cfg.macLanes + 1) bits)
  val rightShift = UInt(log2Up(subtreeCapacity) bits)
  val reduceBoundary = Bool()
  val nodeMode = SpatialMrnNodeMode()
}

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
  private val countWidth = log2Up(cfg.macLanes + 1)
  private val routeStageCount = log2Up(subtreeCapacity)
  require(nodeCount >= 1)
  require(temporalPortPositions.values.forall(position =>
    position >= 0 && position < nodeCount))

  val io = new Bundle {
    val input = slave(Stream(PERowMrnLevelPayload(cfg, nodeCount * 2)))
    val output = master(Stream(PERowMrnLevelPayload(cfg, nodeCount)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  private val temporalDemand = temporalPortPositions.keys.toSeq
    .map(port => io.temporalInputs(port).valid)
    .foldLeft(False)(_ || _)
  val transformed = Stream(PERowMrnLevelPayload(cfg, nodeCount))
  // Temporal clients reuse physical tree nodes. A resident temporal request
  // holds this complete spatial level until the registered temporal response
  // accepts the request.
  transformed.valid := io.input.valid && !temporalDemand
  io.input.ready := transformed.ready && !temporalDemand
  transformed.payload.reduction.mode := io.input.payload.reduction.mode
  transformed.payload.reduction.endOfTile :=
    io.input.payload.reduction.endOfTile
  transformed.payload.reduction.responseTag :=
    io.input.payload.reduction.responseTag
  transformed.payload.reduction.selectedColumns :=
    io.input.payload.reduction.selectedColumns
  transformed.payload.reduction.effectualMacs :=
    io.input.payload.reduction.effectualMacs
  transformed.payload.profile := io.input.payload.profile

  for (port <- 0 until cfg.localBanks if !temporalPortPositions.contains(port)) {
    io.temporalInputs(port).ready := False
    io.temporalOutputs(port).valid := False
    io.temporalOutputs(port).payload := DualModeMrnResponse(cfg).getZero
  }

  val nodeOrderViolations = Vec(Bool(), nodeCount)
  val controlMemory = new SpatialMrnControlMemory(cfg, level, nodeCount)
  controlMemory.io.profile := io.input.payload.profile

  for (node <- 0 until nodeCount) {
    val subtreeBase = node * subtreeCapacity
    val rightBase = subtreeBase + childCapacity
    val leftEntries = Vec((0 until childCapacity).map(offset =>
      io.input.payload.reduction.lanes(subtreeBase + offset)))
    val rightEntries = Vec((0 until childCapacity).map(offset =>
      io.input.payload.reduction.lanes(rightBase + offset)))
    val control = PERowMrnNodeControl(cfg, subtreeCapacity)
    val nodeConfig = controlMemory.io.nodeConfigs(node)
    val leftMetadata = io.input.payload.subtrees(node * 2)
    val rightMetadata = io.input.payload.subtrees(node * 2 + 1)
    control.leftCount := leftMetadata.count
    control.rightCount := rightMetadata.count
    control.nodeMode := nodeConfig.mode
    val leftTail = leftMetadata.tail
    val rightHead = rightMetadata.head

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
    control.reduceBoundary := nodeConfig.reduceOnEqual && cell.sameCoordinate
    control.outputCount := (control.leftCount + control.rightCount -
      control.reduceBoundary.asUInt).resize(countWidth)
    val rightShiftWide = UInt(countWidth bits)
    rightShiftWide := U(childCapacity, countWidth bits) - control.leftCount
    when(control.reduceBoundary) {
      rightShiftWide := U(childCapacity + 1, countWidth bits) -
        control.leftCount
    }
    control.rightShift := rightShiftWide.resized
    nodeOrderViolations(node) := nodeConfig.checkOrdered && leftTail.valid &&
      rightHead.valid && !cell.sameCoordinate && !cell.leftBeforeRight

    val reducedBoundary = PERowReducedProduct(cfg)
    reducedBoundary.valid := control.reduceBoundary
    reducedBoundary.segmentStart := control.reduceBoundary
    reducedBoundary.row := rightHead.row
    reducedBoundary.column := rightHead.column
    reducedBoundary.destinationBank := rightHead.destinationBank
    reducedBoundary.destinationWordOffset := rightHead.destinationWordOffset
    reducedBoundary.value := cell.reducedValue

    val outputMetadata = transformed.payload.subtrees(node)
    outputMetadata.count := control.outputCount
    outputMetadata.head := PERowReducedProduct(cfg).getZero
    outputMetadata.tail := PERowReducedProduct(cfg).getZero
    when(control.leftCount =/= 0) {
      outputMetadata.head := leftMetadata.head
      when(control.reduceBoundary && control.leftCount === 1) {
        outputMetadata.head := reducedBoundary
      }
    } elsewhen(control.rightCount =/= 0) {
      outputMetadata.head := rightMetadata.head
    }
    when(control.rightCount =/= 0) {
      outputMetadata.tail := rightMetadata.tail
      when(control.reduceBoundary && control.rightCount === 1) {
        outputMetadata.tail := reducedBoundary
      }
    } elsewhen(control.leftCount =/= 0) {
      outputMetadata.tail := leftMetadata.tail
    }

    // Put the right child in the upper half, suppress a coalesced head, then
    // route it through log2(subtreeCapacity) fixed-distance 2:1 switch stages.
    // The selected shift is childCapacity-leftCount (+1 when rightHead was
    // reduced), so the surviving right entries land immediately after left.
    val rightRouteStages = Array.fill(routeStageCount + 1)(
      Vec(PERowReducedProduct(cfg), subtreeCapacity))
    for (position <- 0 until subtreeCapacity) {
      if (position < childCapacity) {
        rightRouteStages(0)(position) := PERowReducedProduct(cfg).getZero
      } else {
        val rightPosition = position - childCapacity
        rightRouteStages(0)(position) := rightEntries(rightPosition)
        if (rightPosition == 0) {
          when(control.reduceBoundary) {
            rightRouteStages(0)(position).valid := False
          }
        }
      }
    }
    for (routeStage <- 0 until routeStageCount) {
      val distance = 1 << routeStage
      for (position <- 0 until subtreeCapacity) {
        rightRouteStages(routeStage + 1)(position) :=
          rightRouteStages(routeStage)(position)
        when(control.rightShift(routeStage)) {
          if (position + distance < subtreeCapacity) {
            rightRouteStages(routeStage + 1)(position) :=
              rightRouteStages(routeStage)(position + distance)
          } else {
            rightRouteStages(routeStage + 1)(position) :=
              PERowReducedProduct(cfg).getZero
          }
        }
      }
    }
    val routedRight = rightRouteStages.last

    for (position <- 0 until subtreeCapacity) {
      val output = transformed.payload.reduction.lanes(subtreeBase + position)
      output := PERowReducedProduct(cfg).getZero
      val positionValue = U(position, countWidth bits)
      val fromLeft = positionValue < control.leftCount
      val isReducedBoundary = control.reduceBoundary &&
        control.leftCount =/= 0 &&
        positionValue === (control.leftCount - 1)
      val selectedLeft = PERowReducedProduct(cfg)
      if (position < childCapacity) {
        selectedLeft := leftEntries(position)
      } else {
        selectedLeft := PERowReducedProduct(cfg).getZero
      }

      when(positionValue < control.outputCount) {
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
          output.valid := routedRight(position).valid
          output.row := routedRight(position).row
          output.column := routedRight(position).column
          output.destinationBank := routedRight(position).destinationBank
          output.destinationWordOffset :=
            routedRight(position).destinationWordOffset
          output.value := routedRight(position).value
        }
        output.segmentStart := True
      }
    }
  }

  transformed.payload.reduction.overflow :=
    io.input.payload.reduction.overflow ||
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

  val treeInput = Stream(PERowMrnLevelPayload(cfg, cfg.macLanes))
  treeInput.valid := io.input.valid
  io.input.ready := treeInput.ready
  treeInput.payload.reduction := io.input.payload
  treeInput.payload.profile :=
    SpatialMrnProfile.fromDataflow(io.input.payload.mode)
  for (lane <- 0 until cfg.macLanes) {
    treeInput.payload.subtrees(lane).count :=
      io.input.payload.lanes(lane).valid.asUInt.resized
    treeInput.payload.subtrees(lane).head := io.input.payload.lanes(lane)
    treeInput.payload.subtrees(lane).tail := io.input.payload.lanes(lane)
  }

  var stage = treeInput
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
  io.output.valid := stage.valid
  io.output.payload := stage.payload.reduction
  stage.ready := io.output.ready

  // Four-lane unit-test configurations contain only three spatial nodes while
  // retaining four local banks. Production small/paper profiles reuse four of
  // their N-1 spatial nodes and instantiate no supplemental temporal cells.
  for (port <- 0 until cfg.localBanks if !placedPorts.contains(port)) {
    val temporal = new DualModeMrnNode(cfg)
    temporal.io.input << io.temporalInputs(port)
    io.temporalOutputs(port) << temporal.io.output
  }
}
