package trapezoid

import spinal.core._
import spinal.lib._

/**
  * Head-selection and consume control for a Gustavson merge.
  *
  * The owner presents one independently stateful head per TrGT subrow. This
  * block selects the minimum of those K heads instead of embedding another
  * old-vs-new comparator in the tile engine. The temporal MRN arithmetic still
  * receives one old head and one selected new head per cycle;
  * equal-coordinate accumulation remains in the shared PE-row MRN cell.
  *
  * `request.fire` is the commit point for head consumption.  This keeps the
  * control decision aligned with ready/valid backpressure while allowing the
  * temporal response register to overlap the next selection.
  */
class GustavsonRadixKMrnControl(
    cfg: TrapezoidConfig,
    sourceCount: Int
) extends Component {
  require(sourceCount >= 2)

  private val sourceIndexWidth = scala.math.max(1, log2Up(sourceCount))

  val io = new Bundle {
    val oldHead = in(MrnNodeEntry(cfg))
    val rightHeads = in Vec(MrnNodeEntry(cfg), sourceCount)
    val request = master(Stream(DualModeMrnRequest(cfg)))
    val selectedRightIndex = out UInt(sourceIndexWidth bits)
    val selectedRightValid = out Bool()
    val consumeLeft = out Bool()
    val consumeRight = out Bool()
  }

  var selectedValidExpr: Bool = False
  var selectedIndexExpr: UInt = U(0, sourceIndexWidth bits)
  var selectedRowExpr: UInt = U(0, cfg.coordinateWidth bits)
  var selectedColumnExpr: UInt = U(0, cfg.coordinateWidth bits)
  var selectedValueExpr: SInt = S(0, cfg.accumulatorWidth bits)
  val selectedValid = Bool()
  val selectedIndex = UInt(sourceIndexWidth bits)
  val selectedEntry = MrnNodeEntry(cfg)

  // A balanced tree can replace this generated priority network during the
  // next area pass.  Keeping all K candidates here makes that replacement a
  // local implementation change and removes policy duplication from TrGT.
  for (source <- 0 until sourceCount) {
    val candidate = io.rightHeads(source)
    val candidatePrecedes = !selectedValidExpr ||
      DualModeMrnPolicy.leftBefore(
        candidate.row, candidate.column,
        selectedRowExpr, selectedColumnExpr)
    val selectCandidate = candidate.valid && candidatePrecedes
    selectedValidExpr = selectedValidExpr || candidate.valid
    selectedIndexExpr = Mux(selectCandidate,
      U(source, sourceIndexWidth bits), selectedIndexExpr)
    selectedRowExpr = Mux(selectCandidate, candidate.row, selectedRowExpr)
    selectedColumnExpr = Mux(selectCandidate, candidate.column,
      selectedColumnExpr)
    selectedValueExpr = Mux(selectCandidate, candidate.value, selectedValueExpr)
  }

  selectedValid := selectedValidExpr
  selectedIndex := selectedIndexExpr
  selectedEntry.valid := selectedValidExpr
  selectedEntry.row := selectedRowExpr
  selectedEntry.column := selectedColumnExpr
  selectedEntry.value := selectedValueExpr

  io.request.valid := io.oldHead.valid || selectedValid
  io.request.payload.mode := MrnNodeMode.GustavsonMerge
  io.request.payload.left := io.oldHead
  io.request.payload.right := selectedEntry

  val sameCoordinate = DualModeMrnPolicy.sameCoordinate(
    io.oldHead.valid,
    io.oldHead.row,
    io.oldHead.column,
    selectedValid,
    selectedEntry.row,
    selectedEntry.column)
  val oldBeforeRight = io.oldHead.valid && selectedValid &&
    DualModeMrnPolicy.leftBefore(
      io.oldHead.row,
      io.oldHead.column,
      selectedEntry.row,
      selectedEntry.column)
  val consumeLeft = sameCoordinate ||
    (io.oldHead.valid && (!selectedValid || oldBeforeRight))
  val consumeRight = sameCoordinate || (!consumeLeft && selectedValid)

  io.selectedRightIndex := selectedIndex
  io.selectedRightValid := selectedValid
  io.consumeLeft := io.request.fire && consumeLeft
  io.consumeRight := io.request.fire && consumeRight
}
