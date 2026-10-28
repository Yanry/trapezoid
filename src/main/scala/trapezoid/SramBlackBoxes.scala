package trapezoid

import spinal.core._

class TrapezoidSyncSram(width: Int, depth: Int) extends BlackBox {
  private val addressWidth = math.max(1, log2Up(depth))

  addGeneric("WIDTH", width)
  addGeneric("DEPTH", depth)
  addGeneric("ADDR_WIDTH", addressWidth)
  setDefinitionName("TrapezoidSyncSram")

  val io = new Bundle {
    val clk = in Bool()
    val write_enable = in Bool()
    val write_address = in UInt(addressWidth bits)
    val write_data = in Bits(width bits)
    val read_enable = in Bool()
    val read_address = in UInt(addressWidth bits)
    val read_data = out Bits(width bits)
  }

  noIoPrefix()
  mapClockDomain(clock = io.clk)
  addRTLPath("src/main/resources/rtl/TrapezoidSram.v")
}

class TrapezoidMaskedSyncSram(width: Int, depth: Int) extends BlackBox {
  require(width % 8 == 0)
  private val addressWidth = math.max(1, log2Up(depth))
  private val maskWidth = width / 8

  addGeneric("WIDTH", width)
  addGeneric("DEPTH", depth)
  addGeneric("ADDR_WIDTH", addressWidth)
  addGeneric("MASK_WIDTH", maskWidth)
  setDefinitionName("TrapezoidMaskedSyncSram")

  val io = new Bundle {
    val clk = in Bool()
    val write_enable = in Bool()
    val write_address = in UInt(addressWidth bits)
    val write_data = in Bits(width bits)
    val write_mask = in Bits(maskWidth bits)
    val read_enable = in Bool()
    val read_address = in UInt(addressWidth bits)
    val read_data = out Bits(width bits)
  }

  noIoPrefix()
  mapClockDomain(clock = io.clk)
  addRTLPath("src/main/resources/rtl/TrapezoidSram.v")
}
