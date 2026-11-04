package trapezoid

import spinal.core._
import spinal.lib._

/** One registered level of the PE-row merge-reduction network.
  *
  * Every active position contains the comparator, adder and mux structure used
  * by the paper's dual-mode MRN.  IP modes use the coordinate comparison to
  * form segmented reductions.  Gustavson modes additionally require the input
  * stream to remain coordinate ordered so the same levels can later be reused
  * as temporal merge nodes without changing their comparison policy.
  */
class DualModeMrnLevel(
    cfg: TrapezoidConfig,
    level: Int,
    temporalPortPositions: Map[Int, Int] = Map.empty
) extends Component {
  require(level >= 0 && level < cfg.mrnReductionLevels)

  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  private val reductionDistance = 1 << level
  private val temporalDemand = temporalPortPositions.keys.toSeq
    .map(port => io.temporalInputs(port).valid)
    .foldLeft(False)(_ || _)
  val transformed = Stream(PERowReductionPayload(cfg))
  // A blocked spatial response can keep every MRN level valid while the
  // accumulator waits for a temporal merge.  Give resident temporal requests
  // priority and hold the complete spatial payload at this level; otherwise
  // the two ready/valid paths form a deadlock around the response FIFO.
  transformed.valid := io.input.valid && !temporalDemand
  io.input.ready := transformed.ready && !temporalDemand
  transformed.payload.mode := io.input.payload.mode
  transformed.payload.endOfTile := io.input.payload.endOfTile
  transformed.payload.selectedColumns := io.input.payload.selectedColumns
  transformed.payload.effectualMacs := io.input.payload.effectualMacs

  for (port <- 0 until cfg.localBanks if !temporalPortPositions.contains(port)) {
    io.temporalInputs(port).ready := False
    io.temporalOutputs(port).valid := False
    io.temporalOutputs(port).payload := DualModeMrnResponse(cfg).getZero
  }

  val mergeMode = io.input.payload.mode === TrapezoidDataflow.TrGt ||
    io.input.payload.mode === TrapezoidDataflow.TrGs
  var orderViolation: Bool = False

  for (position <- 0 until cfg.macLanes) {
    val right = io.input.payload.lanes(position)
    val output = transformed.payload.lanes(position)
    output.valid := right.valid
    output.row := right.row
    output.column := right.column
    output.destinationBank := right.destinationBank
    output.destinationWordOffset := right.destinationWordOffset

    if (position >= reductionDistance) {
      val left = io.input.payload.lanes(position - reductionDistance)
      val temporalPort = temporalPortPositions.collectFirst {
        case (port, mappedPosition) if mappedPosition == position => port
      }
      val cell = temporalPort match {
        case Some(port) =>
          val useTemporal = io.temporalInputs(port).valid
          val shared = DualModeMrnCell(
            cfg,
            Mux(useTemporal, io.temporalInputs(port).payload.left.valid,
              left.valid),
            Mux(useTemporal, io.temporalInputs(port).payload.left.row,
              left.row),
            Mux(useTemporal, io.temporalInputs(port).payload.left.column,
              left.column),
            Mux(useTemporal, io.temporalInputs(port).payload.left.value,
              left.value),
            Mux(useTemporal, io.temporalInputs(port).payload.right.valid,
              right.valid),
            Mux(useTemporal, io.temporalInputs(port).payload.right.row,
              right.row),
            Mux(useTemporal, io.temporalInputs(port).payload.right.column,
              right.column),
            Mux(useTemporal, io.temporalInputs(port).payload.right.value,
              right.value))
          // Temporal traffic uses the same registered MRN boundary as the
          // spatial tree.  The old direct combinational response made the
          // SRAM read -> merge -> write path look like a zero-cycle merge and
          // prevented a real II=1 elastic stream from being formed.
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
            left.valid, left.row, left.column, left.value,
            right.valid, right.row, right.column, right.value)
      }

      output.value := Mux(cell.sameCoordinate, cell.reducedValue, right.value)
      output.segmentStart := right.valid &&
        (right.segmentStart || left.segmentStart)
      orderViolation = orderViolation ||
        (mergeMode && left.valid && right.valid && !cell.sameCoordinate &&
          !cell.leftBeforeRight)
    } else {
      output.value := right.value
      output.segmentStart := right.segmentStart
    }
  }

  transformed.payload.overflow := io.input.payload.overflow || orderViolation
  io.output << transformed.m2sPipe()
}

/** Paper-shaped PE-row MRN: log2(lanes) registered comparator/adder/mux levels.
  *
  * The current PE response format still emits every coordinate segment, so the
  * levels implement a segmented prefix reduction.  Keeping the levels as an
  * explicit dual-mode component removes the previous fixed inline tree.  Four
  * existing cells also serve as bank-parallel temporal merge resources while
  * their level holds its spatial payload under temporal arbitration.
  */
class DualModeMrnTree(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  private val temporalPlacements = (for {
    levelIndex <- 0 until cfg.mrnReductionLevels
    position <- (1 << levelIndex) until cfg.macLanes
  } yield (levelIndex, position)).take(cfg.localBanks).zipWithIndex.map {
    case ((levelIndex, position), port) => (port, levelIndex, position)
  }
  require(temporalPlacements.size == cfg.localBanks,
    "MRN must contain one reusable temporal cell per local bank")

  var stage = io.input
  for (levelIndex <- 0 until cfg.mrnReductionLevels) {
    val levelPorts = temporalPlacements.collect {
      case (port, mappedLevel, position) if mappedLevel == levelIndex =>
        port -> position
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
}
