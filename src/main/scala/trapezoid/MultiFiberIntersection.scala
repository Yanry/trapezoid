package trapezoid

import spinal.core._
import spinal.lib._

/** Paper Fig. 11's multi-fiber intersection unit (MFIU).
  *
  * All A/B masks are intersected pairwise.  Effectual computations are
  * compacted into monotonically numbered MAC routes.  The value indices are
  * ranks within each compressed fiber, which is the logical result of the
  * shift unit in Fig. 12.
  */
class MultiFiberIntersection(cfg: TrapezoidConfig) extends Component {
  private val candidateCount = cfg.maxFibers * cfg.maxFibers * cfg.fiberWidth

  val io = new Bundle {
    val aValid = in Vec(Bool(), cfg.maxFibers)
    val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val bValid = in Vec(Bool(), cfg.maxFibers)
    val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val routes = out Vec(IntersectionRoute(cfg), cfg.macLanes)
    val count = out UInt(cfg.matchCountWidth bits)
    val overflow = out Bool()
  }

  val hits = Vec(Bool(), candidateCount)
  val prefix = Vec(UInt(cfg.matchCountWidth bits), candidateCount + 1)
  prefix(0) := 0

  for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers; k <- 0 until cfg.fiberWidth) {
    val candidate = (a * cfg.maxFibers + b) * cfg.fiberWidth + k
    hits(candidate) := io.aValid(a) && io.bValid(b) && io.aMask(a)(k) && io.bMask(b)(k)
    prefix(candidate + 1) := prefix(candidate) + hits(candidate).asUInt
  }

  io.count := prefix(candidateCount)
  io.overflow := prefix(candidateCount) > cfg.macLanes

  for (lane <- 0 until cfg.macLanes) {
    io.routes(lane).valid := False
    io.routes(lane).aFiber := 0
    io.routes(lane).bFiber := 0
    io.routes(lane).coordinate := 0
    io.routes(lane).aValueIndex := 0
    io.routes(lane).bValueIndex := 0

    for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers; k <- 0 until cfg.fiberWidth) {
      val candidate = (a * cfg.maxFibers + b) * cfg.fiberWidth + k
      when(hits(candidate) && prefix(candidate) === lane) {
        io.routes(lane).valid := True
        io.routes(lane).aFiber := a
        io.routes(lane).bFiber := b
        io.routes(lane).coordinate := k
        if (k == 0) {
          io.routes(lane).aValueIndex := 0
          io.routes(lane).bValueIndex := 0
        } else {
          io.routes(lane).aValueIndex := CountOne(io.aMask(a)(k - 1 downto 0)).resized
          io.routes(lane).bValueIndex := CountOne(io.bMask(b)(k - 1 downto 0)).resized
        }
      }
    }
  }
}

/** Chooses the largest prefix (one through four) of B columns that fits in a
  * PE row.  A system scheduler takes the minimum choice over all PE rows.
  */
class DynamicColumnPacker(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val aValid = in Vec(Bool(), cfg.maxFibers)
    val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val bValid = in Vec(Bool(), cfg.maxFibers)
    val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val selectedColumns = out UInt(log2Up(cfg.maxFibers + 1) bits)
    val selectedMatches = out UInt(cfg.matchCountWidth bits)
  }

  val cumulative = Vec(UInt(cfg.matchCountWidth bits), cfg.maxFibers + 1)
  cumulative(0) := 0
  for (b <- 0 until cfg.maxFibers) {
    var columnMatches = U(0, cfg.matchCountWidth bits)
    for (a <- 0 until cfg.maxFibers) {
      val matches = CountOne(io.aMask(a) & io.bMask(b)).resize(cfg.matchCountWidth)
      columnMatches = (columnMatches + Mux(io.aValid(a) && io.bValid(b), matches,
        U(0, cfg.matchCountWidth bits))).resized
    }
    cumulative(b + 1) := cumulative(b) + columnMatches
  }

  io.selectedColumns := 0
  io.selectedMatches := 0
  for (columns <- 1 to cfg.maxFibers) {
    when(cumulative(columns) <= cfg.macLanes) {
      io.selectedColumns := columns
      io.selectedMatches := cumulative(columns)
    }
  }
}
