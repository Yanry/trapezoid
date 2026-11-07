package trapezoid

import spinal.core._
import spinal.lib._

object MrnNodeMode extends SpinalEnum {
  val IpReduce, GustavsonMerge = newElement()
}

/** Spatial policy selected before a command enters the augmented MRN.
  *
  * The policy is control metadata, while coordinate equality remains a
  * data-dependent boundary event evaluated by the node comparator.
  */
object SpatialMrnNodeMode extends SpinalEnum {
  val IpReduce, OrderedMerge = newElement()
}

/** Mapper-selected MRN profile carried with each command. */
object SpatialMrnProfile extends SpinalEnum {
  val IpReduction, OrderedMerge = newElement()

  def fromDataflow(dataflow: TrapezoidDataflow.C): SpatialMrnProfile.C = {
    val profile = SpatialMrnProfile()
    profile := SpatialMrnProfile.IpReduction
    when(dataflow === TrapezoidDataflow.TrGt ||
        dataflow === TrapezoidDataflow.TrGs) {
      profile := SpatialMrnProfile.OrderedMerge
    }
    profile
  }
}

/** One independently addressed node word in the mapper control store. */
case class SpatialMrnNodeConfig(cfg: TrapezoidConfig) extends Bundle {
  val programmed = Bool()
  val mode = SpatialMrnNodeMode()
  val reduceOnEqual = Bool()
  val checkOrdered = Bool()
  val subtreeBase = UInt(log2Up(cfg.macLanes + 1) bits)
  val subtreeLength = UInt(log2Up(cfg.macLanes + 1) bits)
  val routeMode = Bits(2 bits)
  val routeShift = UInt(log2Up(cfg.macLanes + 1) bits)
  val temporalEnable = Bool()
  val temporalOwner = UInt(log2Up(cfg.localBanks + 1) bits)
}

/** One mapper/compiler write to the MRN control store. */
case class SpatialMrnControlWrite(cfg: TrapezoidConfig) extends Bundle {
  val profile = SpatialMrnProfile()
  val level = UInt(scala.math.max(1, log2Up(cfg.mrnReductionLevels)) bits)
  val node = UInt(scala.math.max(1, log2Up(cfg.macLanes)) bits)
  val mode = SpatialMrnNodeMode()
  val reduceOnEqual = Bool()
  val checkOrdered = Bool()
  val subtreeBase = UInt(log2Up(cfg.macLanes + 1) bits)
  val subtreeLength = UInt(log2Up(cfg.macLanes + 1) bits)
  val routeMode = Bits(2 bits)
  val routeShift = UInt(log2Up(cfg.macLanes + 1) bits)
  val temporalEnable = Bool()
  val temporalOwner = UInt(log2Up(cfg.localBanks + 1) bits)
}

/** Per-level view of the mapper-programmable MRN control store.
  *
  * Every physical node owns one word per profile. The words are distributed
  * registers because all nodes consume their settings in parallel; a single
  * serial programming port updates one addressed word at a time.
  */
class SpatialMrnControlMemory(
    cfg: TrapezoidConfig,
    level: Int,
    nodeCount: Int,
    subtreeCapacity: Int,
    temporalPortPositions: Map[Int, Int]
) extends Component {
  private val levelBase = cfg.macLanes - (cfg.macLanes >> level)
  private val profileCount = 2
  private val wordCount = profileCount * nodeCount
  private val addressWidth = scala.math.max(1, log2Up(wordCount))
  private val countWidth = log2Up(cfg.macLanes + 1)

  val io = new Bundle {
    val profile = in(SpatialMrnProfile())
    val program = slave(Flow(SpatialMrnControlWrite(cfg)))
    val nodeConfigs = out Vec(SpatialMrnNodeConfig(cfg), nodeCount)
  }

  private val configWidth = SpatialMrnNodeConfig(cfg).getBitsWidth
  val controlWords = Vec((0 until wordCount).map(_ =>
    Reg(Bits(configWidth bits)) init 0))
  val programmedValid = Vec((0 until wordCount).map(_ => RegInit(False)))
  val programAddress = UInt(addressWidth bits)
  programAddress := (io.program.payload.profile.asBits.asUInt *
    U(nodeCount, addressWidth bits) + io.program.payload.node).resized
  when(io.program.valid && io.program.payload.level === U(level,
      io.program.payload.level.getWidth bits) &&
      io.program.payload.node < U(nodeCount, io.program.payload.node.getWidth bits)) {
    val writeWord = SpatialMrnNodeConfig(cfg)
    writeWord.programmed := True
    writeWord.mode := io.program.payload.mode
    writeWord.reduceOnEqual := io.program.payload.reduceOnEqual
    writeWord.checkOrdered := io.program.payload.checkOrdered
    writeWord.subtreeBase := io.program.payload.subtreeBase
    writeWord.subtreeLength := io.program.payload.subtreeLength
    writeWord.routeMode := io.program.payload.routeMode
    writeWord.routeShift := io.program.payload.routeShift
    writeWord.temporalEnable := io.program.payload.temporalEnable
    writeWord.temporalOwner := io.program.payload.temporalOwner
    controlWords(programAddress) := writeWord.asBits
    programmedValid(programAddress) := True
  }

  for (node <- 0 until nodeCount) {
    val globalNodeId = levelBase + node
    val defaultConfig = SpatialMrnNodeConfig(cfg)
    val orderedProfile = io.profile === SpatialMrnProfile.OrderedMerge
    defaultConfig.programmed := False
    defaultConfig.mode := Mux(orderedProfile,
      SpatialMrnNodeMode.OrderedMerge, SpatialMrnNodeMode.IpReduce)
    defaultConfig.reduceOnEqual := True
    defaultConfig.checkOrdered := orderedProfile
    defaultConfig.subtreeBase := U(node * subtreeCapacity, countWidth bits)
    defaultConfig.subtreeLength := U(subtreeCapacity, countWidth bits)
    defaultConfig.routeMode := 0
    defaultConfig.routeShift := 0
    temporalPortPositions.collectFirst {
      case (port, mappedNode) if mappedNode == node => port
    } match {
      case Some(port) =>
        defaultConfig.temporalEnable := True
        defaultConfig.temporalOwner := U(port, defaultConfig.temporalOwner.getWidth bits)
      case None =>
        defaultConfig.temporalEnable := False
        defaultConfig.temporalOwner := U(cfg.localBanks,
          defaultConfig.temporalOwner.getWidth bits)
    }

    val storedBits = Mux(orderedProfile,
      controlWords(nodeCount + node), controlWords(node))
    val storedConfig = SpatialMrnNodeConfig(cfg)
    storedConfig.assignFromBits(storedBits)
    val storedValid = Mux(orderedProfile,
      programmedValid(nodeCount + node), programmedValid(node))
    val config = io.nodeConfigs(node)
    config := Mux(storedValid, storedConfig, defaultConfig)
    config.mode.setName(s"mrnNode${globalNodeId}Mode")
    config.reduceOnEqual.setName(s"mrnNode${globalNodeId}ReduceOnEqual")
    config.checkOrdered.setName(s"mrnNode${globalNodeId}CheckOrdered")
  }
}

case class MrnNodeEntry(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
}

case class DualModeMrnRequest(cfg: TrapezoidConfig) extends Bundle {
  val mode = MrnNodeMode()
  val left = MrnNodeEntry(cfg)
  val right = MrnNodeEntry(cfg)
}

case class DualModeMrnResponse(cfg: TrapezoidConfig) extends Bundle {
  val first = MrnNodeEntry(cfg)
  val second = MrnNodeEntry(cfg)
  val consumeLeft = Bool()
  val consumeRight = Bool()
}

object DualModeMrnPolicy {
  def sameCoordinate(
      leftValid: Bool,
      leftRow: UInt,
      leftColumn: UInt,
      rightValid: Bool,
      rightRow: UInt,
      rightColumn: UInt
  ): Bool = leftValid && rightValid && leftRow === rightRow &&
    leftColumn === rightColumn

  def leftBefore(
      leftRow: UInt,
      leftColumn: UInt,
      rightRow: UInt,
      rightColumn: UInt
  ): Bool = leftRow < rightRow ||
    (leftRow === rightRow && leftColumn < rightColumn)
}

case class DualModeMrnCellResult(cfg: TrapezoidConfig) extends Bundle {
  val sameCoordinate = Bool()
  val leftBeforeRight = Bool()
  val reducedValue = SInt(cfg.accumulatorWidth bits)
}

object DualModeMrnCell {
  def apply(
      cfg: TrapezoidConfig,
      leftValid: Bool,
      leftRow: UInt,
      leftColumn: UInt,
      leftValue: SInt,
      rightValid: Bool,
      rightRow: UInt,
      rightColumn: UInt,
      rightValue: SInt
  ): DualModeMrnCellResult = {
    val result = DualModeMrnCellResult(cfg)
    val bothValid = leftValid && rightValid
    result.sameCoordinate := DualModeMrnPolicy.sameCoordinate(
      leftValid, leftRow, leftColumn, rightValid, rightRow, rightColumn)
    result.leftBeforeRight := bothValid &&
      DualModeMrnPolicy.leftBefore(leftRow, leftColumn, rightRow, rightColumn)
    val gatedLeft = Mux(result.sameCoordinate, leftValue,
      S(0, cfg.accumulatorWidth bits))
    val gatedRight = Mux(result.sameCoordinate, rightValue,
      S(0, cfg.accumulatorWidth bits))
    result.reducedValue := (gatedLeft + gatedRight).resize(cfg.accumulatorWidth)
    result
  }

  def response(
      cfg: TrapezoidConfig,
      request: DualModeMrnRequest,
      cell: DualModeMrnCellResult
  ): DualModeMrnResponse = {
    val result = DualModeMrnResponse(cfg)
    result := DualModeMrnResponse(cfg).getZero
    when(request.mode === MrnNodeMode.IpReduce) {
      result.consumeLeft := request.left.valid
      result.consumeRight := request.right.valid
      when(cell.sameCoordinate) {
        result.first.valid := True
        result.first.row := request.left.row
        result.first.column := request.left.column
        result.first.value := cell.reducedValue
      } otherwise {
        result.first := request.left
        result.second := request.right
      }
    } otherwise {
      when(cell.sameCoordinate) {
        result.first.valid := True
        result.first.row := request.left.row
        result.first.column := request.left.column
        result.first.value := cell.reducedValue
        result.consumeLeft := True
        result.consumeRight := True
      } elsewhen(request.left.valid &&
          (!request.right.valid || cell.leftBeforeRight)) {
        result.first := request.left
        result.consumeLeft := True
      } otherwise {
        result.first := request.right
        result.consumeRight := request.right.valid
      }
    }
    result
  }
}

/** One MRN compare/reduce node shared by IP and Gustavson policies.
  *
  * `pipelineOutput` is used when the node owns its elastic stage.  A temporal
  * merge engine can disable it when its stream indices and SRAM write port are
  * already the stage boundary, allowing one merge decision per cycle.
  */
class DualModeMrnNode(
    cfg: TrapezoidConfig,
    pipelineOutput: Boolean = true
) extends Component {
  val io = new Bundle {
    val input = slave(Stream(DualModeMrnRequest(cfg)))
    val output = master(Stream(DualModeMrnResponse(cfg)))
  }

  val transformed = Stream(DualModeMrnResponse(cfg))
  transformed.valid := io.input.valid
  io.input.ready := transformed.ready
  val cell = DualModeMrnCell(
    cfg,
    io.input.payload.left.valid,
    io.input.payload.left.row,
    io.input.payload.left.column,
    io.input.payload.left.value,
    io.input.payload.right.valid,
    io.input.payload.right.row,
    io.input.payload.right.column,
    io.input.payload.right.value)
  transformed.payload := DualModeMrnCell.response(cfg, io.input.payload, cell)

  if (pipelineOutput) io.output << transformed.m2sPipe()
  else io.output << transformed
}
