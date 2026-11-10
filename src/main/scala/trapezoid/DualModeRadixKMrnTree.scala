package trapezoid

import spinal.core._
import spinal.lib._

object DualModeRadixKMrnState extends SpinalEnum {
  val Idle, Merge, Emit = newElement()
}

/**
  * Shared mode-configured MRN ingress and augmented reduction tree.
  *
  * The four TrGT source lanes are independent ordered streams.  They arrive
  * together at the PE row as source heads, but their coordinates are not
  * globally ordered.  The TrGT policy is the spatial MRN boundary for that
  * mode: it
  * captures those heads, selects the minimum through a balanced reduction
  * tree, coalesces all equal heads, and advances only the winning sources.
  *
  * It deliberately does not expose a rank-sort network or a pre-MRN sorted
  * vector.  The output vector is assembled by the MRN itself after the
  * source-head decisions. DenseIp, TrIP and TrGS use the same physical tree
  * through the direct ingress path, while TrGT only changes the ingress
  * policy. `localBanks` is the radix and is four in the paper architecture.
  */
class DualModeRadixKMrnTree(cfg: TrapezoidConfig) extends Component {
  private val sourceCount = cfg.localBanks
  private val countWidth = log2Up(cfg.macLanes + 1)
  private val radixLevels = log2Up(sourceCount)

  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val busy = out Bool()
    val program = slave(Flow(SpatialMrnControlWrite(cfg)))
    val temporalInputs = Vec(slave(Stream(DualModeMrnRequest(cfg))),
      cfg.localBanks)
    val temporalOutputs = Vec(master(Stream(DualModeMrnResponse(cfg))),
      cfg.localBanks)
  }

  val sharedTree = new DualModeMrnTree(cfg)
  sharedTree.io.program.valid := io.program.valid
  sharedTree.io.program.payload := io.program.payload
  for (port <- 0 until cfg.localBanks) {
    sharedTree.io.temporalInputs(port) << io.temporalInputs(port)
    io.temporalOutputs(port) << sharedTree.io.temporalOutputs(port)
  }

  val state = RegInit(DualModeRadixKMrnState.Idle)
  val command = Reg(PERowReductionPayload(cfg))
  val heads = Vec((0 until sourceCount).map(_ =>
    Reg(PERowReducedProduct(cfg))))
  val remaining = Reg(Bits(sourceCount bits)) init 0
  val outputCount = Reg(UInt(countWidth bits)) init 0

  val radixBusy = state =/= DualModeRadixKMrnState.Idle
  val inputIsTrGt = io.input.payload.mode === TrapezoidDataflow.TrGt
  sharedTree.io.input.valid := Mux(radixBusy,
    state === DualModeRadixKMrnState.Emit,
    io.input.valid && !inputIsTrGt)
  sharedTree.io.input.payload := Mux(radixBusy, command, io.input.payload)
  io.output << sharedTree.io.output
  io.input.ready := !radixBusy && Mux(inputIsTrGt,
    True, sharedTree.io.input.ready)
  io.busy := state =/= DualModeRadixKMrnState.Idle

  // A balanced radix-K minimum tree.  Invalid entries are treated as larger
  // than every valid coordinate.  Equal coordinates keep the earlier source
  // as the selected representative; the merge stage below still consumes all
  // equal heads in the same cycle.
  val selectLevels = Array.fill(radixLevels + 1)(
    Vec(PERowReducedProduct(cfg), sourceCount))
  for (source <- 0 until sourceCount) {
    selectLevels(0)(source).valid := heads(source).valid && remaining(source)
    selectLevels(0)(source).segmentStart := heads(source).segmentStart
    selectLevels(0)(source).row := heads(source).row
    selectLevels(0)(source).column := heads(source).column
    selectLevels(0)(source).destinationBank := heads(source).destinationBank
    selectLevels(0)(source).destinationWordOffset :=
      heads(source).destinationWordOffset
    selectLevels(0)(source).value := heads(source).value
  }
  for (level <- 0 until radixLevels) {
    val activeWidth = sourceCount >> level
    for (node <- 0 until (activeWidth / 2)) {
      val left = selectLevels(level)(node * 2)
      val right = selectLevels(level)(node * 2 + 1)
      val selected = selectLevels(level + 1)(node)
      val rightBeforeLeft = right.valid &&
        (!left.valid || DualModeMrnPolicy.leftBefore(
          right.row, right.column, left.row, left.column))
      selected.valid := left.valid
      selected.segmentStart := left.segmentStart
      selected.row := left.row
      selected.column := left.column
      selected.destinationBank := left.destinationBank
      selected.destinationWordOffset := left.destinationWordOffset
      selected.value := left.value
      when(rightBeforeLeft) {
        selected.valid := right.valid
        selected.segmentStart := right.segmentStart
        selected.row := right.row
        selected.column := right.column
        selected.destinationBank := right.destinationBank
        selected.destinationWordOffset := right.destinationWordOffset
        selected.value := right.value
      }
    }
    for (node <- activeWidth / 2 until sourceCount) {
      selectLevels(level + 1)(node) := PERowReducedProduct(cfg).getZero
    }
  }

  val selectedHead = selectLevels(radixLevels)(0)
  val equalHeads = Bits(sourceCount bits)
  equalHeads := 0
  for (source <- 0 until sourceCount) {
    equalHeads(source) := remaining(source) && selectedHead.valid &&
      heads(source).row === selectedHead.row &&
      heads(source).column === selectedHead.column
  }

  val mergedHead = PERowReducedProduct(cfg)
  mergedHead.valid := selectedHead.valid
  mergedHead.segmentStart := True
  mergedHead.row := selectedHead.row
  mergedHead.column := selectedHead.column
  var mergedValue: SInt = S(0, cfg.accumulatorWidth bits)
  var mergedDestinationBank: UInt = selectedHead.destinationBank
  var mergedDestinationWord: UInt = selectedHead.destinationWordOffset
  for (source <- 0 until sourceCount) {
    mergedValue = Mux(equalHeads(source),
      (mergedValue + heads(source).value).resize(cfg.accumulatorWidth),
      mergedValue)
    // Preserve the rightmost source identity, matching the ordinary MRN
    // boundary rule used by DualModeMrnTree for a coalesced coordinate.
    mergedDestinationBank = Mux(equalHeads(source),
      heads(source).destinationBank, mergedDestinationBank)
    mergedDestinationWord = Mux(equalHeads(source),
      heads(source).destinationWordOffset, mergedDestinationWord)
  }
  mergedHead.value := mergedValue
  mergedHead.destinationBank := mergedDestinationBank
  mergedHead.destinationWordOffset := mergedDestinationWord

  when(io.input.fire) {
    command := io.input.payload
    when(io.input.payload.mode === TrapezoidDataflow.TrGt) {
      for (position <- 0 until cfg.macLanes) {
        command.lanes(position).valid := False
        command.lanes(position).segmentStart := False
      }
      for (source <- 0 until sourceCount) {
        heads(source) := io.input.payload.lanes(source)
      }
      remaining := io.input.payload.lanes.take(sourceCount)
        .map(_.valid).asBits
      outputCount := 0
      state := DualModeRadixKMrnState.Merge
    }
  }

  when(state === DualModeRadixKMrnState.Merge) {
    when(!selectedHead.valid) {
      state := DualModeRadixKMrnState.Emit
    } otherwise {
      for (position <- 0 until cfg.macLanes) {
        when(outputCount === position) {
          command.lanes(position) := mergedHead
        }
      }
      for (source <- 0 until sourceCount) {
        when(equalHeads(source)) {
          remaining(source) := False
        }
      }
      outputCount := outputCount + 1
    }
  }

  when(state === DualModeRadixKMrnState.Emit && sharedTree.io.input.fire) {
    state := DualModeRadixKMrnState.Idle
  }
}
