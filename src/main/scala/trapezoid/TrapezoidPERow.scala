package trapezoid

import spinal.core._
import spinal.lib._

/** A Trapezoid PE row containing MFIU routing, signed MAC lanes, and the
  * merge/reduction behavior used by all four paper dataflows.
  *
  * The controller guarantees that a TrIP command does not overflow.  An
  * overflowing command is still accepted, but returns `overflow` with no
  * results so software or an upstream scheduler can replay fewer B columns.
  */
class TrapezoidPERow(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val command = slave(Stream(PERowCommand(cfg)))
    val response = master(Stream(PERowResponse(cfg)))
  }

  val mfiu = new MultiFiberIntersection(cfg)
  val columnPacker = new DynamicColumnPacker(cfg)
  for (fiber <- 0 until cfg.maxFibers) {
    mfiu.io.aValid(fiber) := io.command.payload.trip.a(fiber).valid
    mfiu.io.aMask(fiber) := io.command.payload.trip.a(fiber).mask
    mfiu.io.bValid(fiber) := io.command.payload.trip.b(fiber).valid
    mfiu.io.bMask(fiber) := io.command.payload.trip.b(fiber).mask
    columnPacker.io.aValid(fiber) := io.command.payload.trip.a(fiber).valid
    columnPacker.io.aMask(fiber) := io.command.payload.trip.a(fiber).mask
    columnPacker.io.bValid(fiber) := io.command.payload.trip.b(fiber).valid
    columnPacker.io.bMask(fiber) := io.command.payload.trip.b(fiber).mask
  }

  val nextResponse = PERowResponse(cfg)
  nextResponse.mode := io.command.payload.mode
  nextResponse.overflow := False
  nextResponse.selectedColumns := 0
  nextResponse.effectualMacs := 0
  nextResponse.count := 0
  for (out <- 0 until cfg.outputLanes) {
    nextResponse.results(out).valid := False
    nextResponse.results(out).row := 0
    nextResponse.results(out).column := 0
    nextResponse.results(out).value := 0
    nextResponse.results(out).endOfTile := io.command.payload.endOfTile
  }

  val tripMode = io.command.payload.mode === TrapezoidDataflow.DenseIp ||
    io.command.payload.mode === TrapezoidDataflow.TrIp

  val tripProducts = Vec(SInt(cfg.accumulatorWidth bits), cfg.macLanes)
  for (lane <- 0 until cfg.macLanes) {
    val route = mfiu.io.routes(lane)
    val aFiber = io.command.payload.trip.a(route.aFiber)
    val bFiber = io.command.payload.trip.b(route.bFiber)
    val product = aFiber.values(route.aValueIndex) * bFiber.values(route.bValueIndex)
    tripProducts(lane) := Mux(route.valid, product.resize(cfg.accumulatorWidth),
      S(0, cfg.accumulatorWidth bits))
  }

  val pairValid = Vec(Bool(), cfg.maxFibers * cfg.maxFibers)
  val pairPrefix = Vec(UInt(cfg.outputCountWidth bits), cfg.maxFibers * cfg.maxFibers + 1)
  pairPrefix(0) := 0
  for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers) {
    val pair = a * cfg.maxFibers + b
    pairValid(pair) := False
    for (lane <- 0 until cfg.macLanes) {
      when(mfiu.io.routes(lane).valid && mfiu.io.routes(lane).aFiber === a &&
          mfiu.io.routes(lane).bFiber === b) {
        pairValid(pair) := True
      }
    }
    pairPrefix(pair + 1) := pairPrefix(pair) + pairValid(pair).asUInt
  }

  when(tripMode) {
    nextResponse.overflow := mfiu.io.overflow
    nextResponse.selectedColumns := columnPacker.io.selectedColumns
    nextResponse.effectualMacs := mfiu.io.count
    nextResponse.count := Mux(mfiu.io.overflow, U(0), pairPrefix.last).resized
    for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers) {
      val pair = a * cfg.maxFibers + b
      var sum = S(0, cfg.accumulatorWidth bits)
      for (lane <- 0 until cfg.macLanes) {
        val belongs = mfiu.io.routes(lane).valid &&
          mfiu.io.routes(lane).aFiber === a && mfiu.io.routes(lane).bFiber === b
        sum = (sum + Mux(belongs, tripProducts(lane), S(0, cfg.accumulatorWidth bits))).resized
      }
      for (out <- 0 until cfg.outputLanes) {
        when(!mfiu.io.overflow && pairValid(pair) && pairPrefix(pair) === out) {
          nextResponse.results(out).valid := True
          nextResponse.results(out).row := io.command.payload.trip.a(a).tag
          nextResponse.results(out).column := io.command.payload.trip.b(b).tag
          nextResponse.results(out).value := sum
        }
      }
    }
  }

  // In TrGT, each valid B lane is normally one FIFO head from a subrow.  In
  // TrGS, several B lanes select the same A register and are processed across N.
  val gustValid = Vec(Bool(), cfg.cacheLineWords)
  val gustRow = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustColumn = Vec(UInt(cfg.coordinateWidth bits), cfg.cacheLineWords)
  val gustProduct = Vec(SInt(cfg.accumulatorWidth bits), cfg.cacheLineWords)
  for (lane <- 0 until cfg.cacheLineWords) {
    val b = io.command.payload.gustavson.b(lane)
    val a = io.command.payload.gustavson.a(b.aIndex)
    gustValid(lane) := b.valid && a.valid && a.k === b.k
    gustRow(lane) := a.row
    gustColumn(lane) := b.column
    gustProduct(lane) := Mux(gustValid(lane),
      (a.value * b.value).resize(cfg.accumulatorWidth), S(0, cfg.accumulatorWidth bits))
  }

  val leader = Vec(Bool(), cfg.cacheLineWords)
  val leaderPrefix = Vec(UInt(cfg.outputCountWidth bits), cfg.cacheLineWords + 1)
  leaderPrefix(0) := 0
  for (lane <- 0 until cfg.cacheLineWords) {
    var seen = False
    for (previous <- 0 until lane) {
      seen = seen || (gustValid(previous) && gustRow(previous) === gustRow(lane) &&
        gustColumn(previous) === gustColumn(lane))
    }
    leader(lane) := gustValid(lane) && !seen
    leaderPrefix(lane + 1) := leaderPrefix(lane) + leader(lane).asUInt
  }

  when(!tripMode) {
    nextResponse.count := leaderPrefix.last.resized
    for (lane <- 0 until cfg.cacheLineWords) {
      var sum = S(0, cfg.accumulatorWidth bits)
      for (other <- 0 until cfg.cacheLineWords) {
        val sameOutput = gustValid(other) && gustRow(other) === gustRow(lane) &&
          gustColumn(other) === gustColumn(lane)
        sum = (sum + Mux(sameOutput, gustProduct(other), S(0, cfg.accumulatorWidth bits))).resized
      }
      for (out <- 0 until cfg.outputLanes) {
        when(leader(lane) && leaderPrefix(lane) === out) {
          nextResponse.results(out).valid := True
          nextResponse.results(out).row := gustRow(lane)
          nextResponse.results(out).column := gustColumn(lane)
          nextResponse.results(out).value := sum
        }
      }
    }
  }

  val responseReg = Reg(PERowResponse(cfg))
  val responseValid = RegInit(False)
  io.command.ready := !responseValid || io.response.ready
  io.response.valid := responseValid
  io.response.payload := responseReg

  when(io.response.fire) {
    responseValid := False
  }
  when(io.command.fire) {
    responseReg := nextResponse
    responseValid := True
  }
}
