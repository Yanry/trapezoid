package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable

class ModeSharedTileControllerSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 1,
    localDepthPerBank = 8
  )
  private val tripCfg = TrIpTileConfig(cfg, maxColumns = 8)
  private val gustavsonCfg = GustavsonTileConfig(
    cfg, accumulatorEntries = 8, fifoDepthPerSubrow = 2)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("TrIP, TrGT, and TrGS share one PE row across tile boundaries") {
    simConfig.compile(new ModeSharedTileController(
      tripCfg, gustavsonCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= true
      dut.io.tripALoad.valid #= false
      dut.io.tripBLoad.valid #= false
      dut.io.tripStart.valid #= false
      dut.io.tripResult.ready #= false
      dut.io.trgtALoad(0).valid #= false
      dut.io.trgtStart(0).valid #= false
      dut.io.trgtFinish #= 0
      dut.io.trgtResult(0).ready #= false
      dut.io.trgsCommand(0).valid #= false
      dut.io.trgsResult(0).ready #= false
      dut.io.trgsResultLine(0).ready #= false
      for (subrow <- 0 until cfg.localBanks) {
        dut.io.trgtBPush(0)(subrow).valid #= false
      }
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      dut.clockDomain.waitSampling()
      def ownerPosition: Int =
        SimEnumPimper(dut.io.localBufferOwners(0)).toEnum.position
      assert(ownerPosition == LocalBufferOwner.TrIp.position)

      def driveFiber(target: IndexedFiber, index: Int, tag: Int,
                     mask: Int, values: Seq[Int]): Unit = {
        target.index #= index
        target.fiber.valid #= true
        target.fiber.tag #= tag
        target.fiber.mask #= mask
        for (entry <- 0 until cfg.fiberWidth) {
          target.fiber.values(entry) #= values.lift(entry).getOrElse(0)
        }
      }

      driveFiber(dut.io.tripALoad.payload, index = 0, tag = 1,
        mask = 0x3, values = Seq(2, 3))
      dut.io.tripALoad.valid #= true
      while (!dut.io.tripALoad.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.tripALoad.valid #= false

      driveFiber(dut.io.tripBLoad.payload, index = 0, tag = 4,
        mask = 0x3, values = Seq(5, 7))
      dut.io.tripBLoad.valid #= true
      while (!dut.io.tripBLoad.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.tripBLoad.valid #= false

      dut.io.tripStart.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.tripStart.payload.rowCount #= 1
      dut.io.tripStart.payload.columnCount #= 1
      dut.io.tripStart.payload.firstKTile #= true
      dut.io.tripStart.payload.lastKTile #= true
      dut.io.tripStart.valid #= true
      while (!dut.io.tripStart.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.tripStart.valid #= false
      dut.io.tripResult.ready #= true

      val tripResults = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.tripDone.toBoolean && timeout < 300) {
        sleep(1)
        if (dut.io.tripResult.valid.toBoolean &&
            dut.io.tripResult.ready.toBoolean) {
          tripResults += ((dut.io.tripResult.payload.row.toInt,
            dut.io.tripResult.payload.column.toInt,
            dut.io.tripResult.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.tripDone.toBoolean)
      assert(tripResults == Seq((1, 4, 31L)))
      assert(ownerPosition == LocalBufferOwner.TrIp.position)

      dut.io.trgtALoad(0).payload.index #= 0
      dut.io.trgtALoad(0).payload.element.valid #= true
      dut.io.trgtALoad(0).payload.element.row #= 6
      dut.io.trgtALoad(0).payload.element.k #= 0
      dut.io.trgtALoad(0).payload.element.value #= 3
      dut.io.trgtALoad(0).valid #= true
      while (!dut.io.trgtALoad(0).ready.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling()
      dut.io.trgtALoad(0).valid #= false

      dut.io.trgtStart(0).payload.mode #= TrapezoidDataflow.TrGt
      dut.io.trgtStart(0).valid #= true
      while (!dut.io.trgtStart(0).ready.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling()
      dut.io.trgtStart(0).valid #= false
      sleep(1)
      assert(ownerPosition == LocalBufferOwner.Gustavson.position)

      dut.io.tripStart.valid #= true
      sleep(1)
      assert(!dut.io.tripStart.ready.toBoolean)
      dut.io.tripStart.valid #= false

      val b = dut.io.trgtBPush(0)(0)
      b.payload.valid #= true
      b.payload.aIndex #= 0
      b.payload.k #= 0
      b.payload.column #= 2
      b.payload.value #= 4
      b.valid #= true
      while (!b.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      b.valid #= false
      dut.io.trgtFinish #= 1
      dut.clockDomain.waitSampling()
      dut.io.trgtFinish #= 0
      dut.io.trgtResult(0).ready #= true

      val trgtResults = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      timeout = 0
      while ((dut.io.trgtDone.toBigInt & 1) == 0 && timeout < 300) {
        sleep(1)
        if (dut.io.trgtResult(0).valid.toBoolean &&
            dut.io.trgtResult(0).ready.toBoolean) {
          trgtResults += ((dut.io.trgtResult(0).payload.row.toInt,
            dut.io.trgtResult(0).payload.column.toInt,
            dut.io.trgtResult(0).payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert((dut.io.trgtDone.toBigInt & 1) != 0)
      assert(trgtResults == Seq((6, 2, 12L)))
      dut.clockDomain.waitSampling()
      assert(ownerPosition == LocalBufferOwner.TrIp.position)
      assert((dut.io.trgtOverflow.toBigInt & 1) == 0)
      assert((dut.io.trgtUnsupportedMode.toBigInt & 1) == 0)

      val trgsCommand = dut.io.trgsCommand(0)
      trgsCommand.payload.mode #= TrapezoidDataflow.TrGs
      trgsCommand.payload.endOfTile #= false
      trgsCommand.payload.a.valid #= true
      trgsCommand.payload.a.row #= 8
      trgsCommand.payload.a.k #= 1
      trgsCommand.payload.a.value #= 5
      for (lane <- 0 until cfg.cacheLineWords) {
        val element = trgsCommand.payload.b(lane)
        element.valid #= lane == 0
        element.k #= 1
        element.column #= (if (lane == 0) 3 else 0)
        element.value #= (if (lane == 0) 6 else 0)
      }
      dut.io.trgsResult(0).ready #= true
      trgsCommand.valid #= true
      while (!trgsCommand.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      trgsCommand.valid #= false
      sleep(1)
      assert(ownerPosition == LocalBufferOwner.Gustavson.position)

      dut.io.tripStart.valid #= true
      sleep(1)
      assert(!dut.io.tripStart.ready.toBoolean)
      dut.io.tripStart.valid #= false

      trgsCommand.payload.endOfTile #= true
      trgsCommand.payload.a.k #= 2
      trgsCommand.payload.a.value #= 2
      for (lane <- 0 until cfg.cacheLineWords) {
        val element = trgsCommand.payload.b(lane)
        element.k #= 2
        element.value #= (if (lane == 0) 4 else 0)
      }
      trgsCommand.valid #= true
      while (!trgsCommand.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      trgsCommand.valid #= false

      val trgsResults = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      timeout = 0
      while ((dut.io.trgsDone.toBigInt & 1) == 0 && timeout < 300) {
        sleep(1)
        if (dut.io.trgsResult(0).valid.toBoolean &&
            dut.io.trgsResult(0).ready.toBoolean) {
          trgsResults += ((dut.io.trgsResult(0).payload.row.toInt,
            dut.io.trgsResult(0).payload.column.toInt,
            dut.io.trgsResult(0).payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert((dut.io.trgsDone.toBigInt & 1) != 0)
      assert(trgsResults == Seq((8, 3, 38L)))
      assert(dut.io.trgsAccumulatorUpdates(0).toLong == 2)
      assert((dut.io.trgsOverflow.toBigInt & 1) == 0)
      assert((dut.io.trgsUnsupportedMode.toBigInt & 1) == 0)
      dut.clockDomain.waitSampling()
      assert(ownerPosition == LocalBufferOwner.TrIp.position)
    }
  }
}
