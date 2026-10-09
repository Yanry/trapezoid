package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable
import scala.util.Random

class GustavsonFrontendsSpec extends AnyFunSuite {
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
  private val tileCfg = GustavsonTileConfig(cfg, accumulatorEntries = 8,
    fifoDepthPerSubrow = 2)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("SRAM-backed Gustavson FIFO preserves order across full stalls and wrap-around") {
    simConfig.compile(new GustavsonBFifo(cfg, depth = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.push.valid #= false
      dut.io.pop.ready #= false

      def drivePush(column: Int, value: Int): Unit = {
        dut.io.push.payload.valid #= true
        dut.io.push.payload.aIndex #= 0
        dut.io.push.payload.k #= 0
        dut.io.push.payload.column #= column
        dut.io.push.payload.value #= value
        dut.io.push.valid #= true
        sleep(1)
        while (!dut.io.push.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.push.valid #= false
      }

      def popOne(): (Int, Int) = {
        dut.io.pop.ready #= true
        sleep(1)
        while (!dut.io.pop.valid.toBoolean) dut.clockDomain.waitSampling()
        val result = (dut.io.pop.payload.column.toInt, dut.io.pop.payload.value.toInt)
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.pop.ready #= false
        result
      }

      drivePush(1, 11)
      drivePush(2, 22)
      assert(dut.io.occupancy.toInt == 2)

      dut.io.push.payload.column #= 3
      dut.io.push.payload.value #= 33
      dut.io.push.valid #= true
      sleep(1)
      assert(!dut.io.push.ready.toBoolean)
      dut.io.push.valid #= false

      assert(popOne() == (1, 11))
      drivePush(3, 33)
      assert(popOne() == (2, 22))
      assert(popOne() == (3, 33))
      assert(dut.io.occupancy.toInt == 0)
    }
  }

  private def initializeTrGt(dut: TrGtSubrowScheduler): Unit = {
    dut.io.clear #= false
    dut.io.aLoad.valid #= false
    dut.io.start.valid #= false
    dut.io.finish #= false
    dut.io.result.ready #= false
    for (subrow <- 0 until cfg.localBanks) dut.io.bPush(subrow).valid #= false
  }

  private def loadA(dut: TrGtSubrowScheduler, index: Int,
                    row: Int, k: Int, value: Int): Unit = {
    dut.io.aLoad.payload.index #= index
    dut.io.aLoad.payload.element.valid #= true
    dut.io.aLoad.payload.element.row #= row
    dut.io.aLoad.payload.element.k #= k
    dut.io.aLoad.payload.element.value #= value
    dut.io.aLoad.valid #= true
    sleep(1)
    while (!dut.io.aLoad.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    sleep(1)
    dut.io.aLoad.valid #= false
  }

  private def pushB(dut: TrGtSubrowScheduler, subrow: Int,
                    aIndex: Int, k: Int, column: Int, value: Int): Unit = {
    val stream = dut.io.bPush(subrow)
    stream.payload.valid #= true
    stream.payload.aIndex #= aIndex
    stream.payload.k #= k
    stream.payload.column #= column
    stream.payload.value #= value
    stream.valid #= true
    sleep(1)
    while (!stream.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    sleep(1)
    stream.valid #= false
  }

  test("TrGT subrows drain independent B FIFOs with backpressure") {
    simConfig.compile(new TrGtSubrowScheduler(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGt(dut)

      loadA(dut, 0, row = 2, k = 0, value = 2)
      loadA(dut, 1, row = 2, k = 1, value = 3)
      loadA(dut, 2, row = 3, k = 2, value = 4)
      loadA(dut, 3, row = 4, k = 3, value = 5)

      dut.io.start.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.start.valid #= true
      sleep(1)
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(1)
      dut.io.start.valid #= false

      // Four entries exceed the two-entry FIFO unless the scheduler drains it.
      pushB(dut, 0, 0, 0, 0, 5)
      pushB(dut, 0, 0, 0, 2, 7)
      pushB(dut, 0, 0, 0, 3, 9)
      pushB(dut, 0, 0, 0, 1, 2)
      pushB(dut, 1, 1, 1, 0, 11)
      pushB(dut, 2, 2, 2, 1, 13)
      pushB(dut, 3, 3, 3, 0, 17)

      dut.io.finish #= true
      dut.clockDomain.waitSampling()
      dut.io.finish #= false

      val random = new Random(0x3a71)
      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 1000) {
        dut.io.result.ready #= random.nextBoolean()
        dut.clockDomain.waitSampling()
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong,
            dut.io.result.payload.endOfTile.toBoolean))
        }
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
      assert(observed.map(x => (x._1, x._2, x._3)) == Seq(
        (2, 0, 43L), (2, 1, 4L), (2, 2, 14L),
        (2, 3, 18L), (3, 1, 52L), (4, 0, 85L)),
        s"accepted=${dut.io.acceptedBElements.toLong}, " +
          s"issued=${dut.io.issuedBElements.toLong}")
      assert(observed.map(_._4) == Seq(false, false, false, false, false, true))
    }
  }

  private def initializeTrGs(dut: TrGsSpatialEngine): Unit = {
    dut.io.clear #= false
    dut.io.command.valid #= false
    dut.io.result.ready #= false
  }

  private def issueTrGs(dut: TrGsSpatialEngine, k: Int, aValue: Int,
                        entries: Seq[Option[(Int, Int)]], endOfTile: Boolean): Unit = {
    dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
    dut.io.command.payload.endOfTile #= endOfTile
    dut.io.command.payload.a.valid #= true
    dut.io.command.payload.a.row #= 6
    dut.io.command.payload.a.k #= k
    dut.io.command.payload.a.value #= aValue
    for (lane <- 0 until cfg.cacheLineWords) {
      val b = dut.io.command.payload.b(lane)
      b.valid #= entries(lane).nonEmpty
      b.k #= k
      b.column #= entries(lane).map(_._1).getOrElse(0)
      b.value #= entries(lane).map(_._2).getOrElse(0)
    }
    dut.io.command.valid #= true
    sleep(1)
    while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    sleep(1)
    dut.io.command.valid #= false
  }

  test("TrGS broadcasts A and spatially distributes dense or sparse B lines") {
    simConfig.compile(new TrGsSpatialEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)

      issueTrGs(dut, k = 0, aValue = 2,
        Seq(Some(0 -> 1), Some(1 -> 2), Some(2 -> 3), Some(3 -> 4)),
        endOfTile = false)
      issueTrGs(dut, k = 1, aValue = 3,
        Seq(Some(0 -> 5), None, Some(2 -> 7), None), endOfTile = true)

      val random = new Random(0x591c)
      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 500) {
        dut.io.result.ready #= random.nextBoolean()
        dut.clockDomain.waitSampling()
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
      assert(observed == Seq((6, 0, 17L), (6, 1, 4L), (6, 2, 27L), (6, 3, 8L)))
    }
  }
}
