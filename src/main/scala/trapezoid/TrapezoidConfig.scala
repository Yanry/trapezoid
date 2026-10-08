package trapezoid

import spinal.core._

/** Hardware sizing parameters for a Trapezoid compute array.
  *
  * The paper configuration is exposed as [[TrapezoidConfig.paper]].  The
  * default is deliberately smaller so that elaboration and simulation remain
  * practical on a workstation.
  */
case class TrapezoidConfig(
    dataWidth: Int = 16,
    accumulatorWidth: Int = 40,
    coordinateWidth: Int = 16,
    fiberWidth: Int = 16,
    maxFibers: Int = 4,
    macLanes: Int = 16,
    cacheLineWords: Int = 4,
    outputLanes: Int = 16,
    peRows: Int = 4,
    localBanks: Int = 4,
    localDepthPerBank: Int = 128
) {
  require(dataWidth > 0)
  require(fiberWidth >= 2)
  require(maxFibers >= 1 && maxFibers <= 4,
    "TrIP limits both A rows and B columns to four fibers")
  require(macLanes >= maxFibers * maxFibers,
    "one response must be able to represent every TrIP output fiber pair")
  require(cacheLineWords > 0 && cacheLineWords <= macLanes)
  require(outputLanes >= math.max(maxFibers * maxFibers, cacheLineWords))
  require(peRows > 0)
  require(localBanks == 4, "the paper's PE row has four local-buffer banks")
  require(localDepthPerBank >= 2)
  require(accumulatorWidth >= 2 * dataWidth + log2Up(macLanes),
    "accumulator is too narrow for a full-lane dot product")

  val fiberIndexWidth: Int = math.max(1, log2Up(maxFibers))
  val fiberValueIndexWidth: Int = math.max(1, log2Up(fiberWidth))
  val aRegisterIndexWidth: Int = math.max(1, log2Up(macLanes))
  val matchCountWidth: Int = log2Up(maxFibers * maxFibers * fiberWidth + 1)
  val outputCountWidth: Int = log2Up(outputLanes + 1)
  val localAddressWidth: Int = log2Up(localDepthPerBank)
}

object TrapezoidConfig {
  val small: TrapezoidConfig = TrapezoidConfig()

  /** Dimensions reported in Table I.  Elaborating this configuration creates
    * 128 PE rows with 128 signed MAC lanes each.
    */
  val paper: TrapezoidConfig = TrapezoidConfig(
    dataWidth = 32,
    accumulatorWidth = 72,
    fiberWidth = 128,
    macLanes = 128,
    cacheLineWords = 16,
    outputLanes = 128,
    peRows = 128,
    localDepthPerBank = 512
  )
}

object TrapezoidDataflow extends SpinalEnum {
  val DenseIp, TrIp, TrGt, TrGs = newElement()
}
