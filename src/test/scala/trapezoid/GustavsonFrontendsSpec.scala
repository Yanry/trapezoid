package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

class ExternalBufferedTrGtHarness(tileCfg: GustavsonTileConfig)
    extends Component {
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val aLoad = slave(Stream(IndexedGustavsonA(cfg)))
    val start = slave(Stream(GustavsonStart()))
    val bPush = Vec(slave(Stream(GustavsonB(cfg))), cfg.localBanks)
    val finish = in Bool()
    val result = master(Stream(MatrixResult(cfg)))
    val done = out Bool()
    val overflow = out Bool()
    val unsupportedMode = out Bool()
    val acceptedBElements = out UInt(32 bits)
    val issuedBElements = out UInt(32 bits)
  }

  val scheduler = new TrGtSubrowScheduler(
    tileCfg, externalFifoStorage = true)
  val storage = new BankedLocalBuffer(cfg)

  scheduler.io.clear := io.clear
  scheduler.io.fifoStorageActive := True
  scheduler.io.aLoad.valid := io.aLoad.valid
  scheduler.io.aLoad.payload := io.aLoad.payload
  io.aLoad.ready := scheduler.io.aLoad.ready
  scheduler.io.start.valid := io.start.valid
  scheduler.io.start.payload := io.start.payload
  io.start.ready := scheduler.io.start.ready
  scheduler.io.finish := io.finish
  for (bank <- 0 until cfg.localBanks) {
    scheduler.io.bPush(bank).valid := io.bPush(bank).valid
    scheduler.io.bPush(bank).payload := io.bPush(bank).payload
    io.bPush(bank).ready := scheduler.io.bPush(bank).ready
    storage.io.bus.writes(bank) := scheduler.io.fifoStorage.writes(bank)
    storage.io.bus.readCommands(bank) :=
      scheduler.io.fifoStorage.readCommands(bank)
    scheduler.io.fifoStorage.readResponses(bank) :=
      storage.io.bus.readResponses(bank)
  }
  io.result.valid := scheduler.io.result.valid
  io.result.payload := scheduler.io.result.payload
  scheduler.io.result.ready := io.result.ready
  io.done := scheduler.io.done
  io.overflow := scheduler.io.overflow
  io.unsupportedMode := scheduler.io.unsupportedMode
  io.acceptedBElements := scheduler.io.acceptedBElements
  io.issuedBElements := scheduler.io.issuedBElements
}

class ExternalBufferedTrGsHarness(tileCfg: GustavsonTileConfig,
                                  wideOutput: Boolean) extends Component {
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(TrGsCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val resultLine = master(Stream(MatrixResultLine(cfg)))
    val done = out Bool()
    val overflow = out Bool()
    val accumulatorUpdates = out UInt(32 bits)
    val bankReplayCycles = out UInt(32 bits)
  }

  val engine = new TrGsSpatialEngine(tileCfg, wideOutput = wideOutput,
    externalAccumulatorStorage = true)
  val storage = new BankedLocalBuffer(cfg)
  engine.io.clear := io.clear
  engine.io.command <> io.command
  io.result <> engine.io.result
  io.resultLine <> engine.io.resultLine
  io.done := engine.io.done
  io.overflow := engine.io.overflow
  io.accumulatorUpdates := engine.io.accumulatorUpdates
  io.bankReplayCycles := engine.io.bankReplayCycles
  for (bank <- 0 until cfg.localBanks) {
    storage.io.bus.writes(bank) := engine.io.accumulatorStorage.writes(bank)
    storage.io.bus.readCommands(bank) :=
      engine.io.accumulatorStorage.readCommands(bank)
    engine.io.accumulatorStorage.readResponses(bank) :=
      storage.io.bus.readResponses(bank)
  }
}

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

  test("banked Gustavson FIFO preserves independent order across stalls and wrap-around") {
    simConfig.compile(new BankedGustavsonBFifo(cfg, depth = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.owner #= LocalBufferOwner.Gustavson
      for (bank <- 0 until cfg.localBanks) {
        dut.io.pushes(bank).valid #= false
        dut.io.pops(bank).ready #= false
        dut.io.tripBuffer.writes(bank).valid #= false
        dut.io.tripBuffer.readCommands(bank).valid #= false
      }

      def drivePush(bank: Int, column: Int, value: Int): Unit = {
        val push = dut.io.pushes(bank)
        push.payload.valid #= true
        push.payload.aIndex #= bank
        push.payload.k #= bank
        push.payload.column #= column
        push.payload.value #= value
        push.valid #= true
        sleep(1)
        while (!push.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        push.valid #= false
      }

      def popOne(bank: Int): (Int, Int, Int) = {
        val pop = dut.io.pops(bank)
        pop.ready #= true
        sleep(1)
        while (!pop.valid.toBoolean) dut.clockDomain.waitSampling()
        val result = (pop.payload.aIndex.toInt, pop.payload.column.toInt,
          pop.payload.value.toInt)
        dut.clockDomain.waitSampling()
        sleep(1)
        pop.ready #= false
        result
      }

      drivePush(0, 1, 11)
      drivePush(0, 2, 22)
      drivePush(1, 7, 77)
      assert(dut.io.occupancy(0).toInt == 2)
      assert(dut.io.occupancy(1).toInt == 1)

      dut.io.pushes(0).payload.column #= 3
      dut.io.pushes(0).payload.value #= 33
      dut.io.pushes(0).valid #= true
      sleep(1)
      assert(!dut.io.pushes(0).ready.toBoolean)
      dut.io.pushes(0).valid #= false

      assert(popOne(1) == (1, 7, 77))
      assert(popOne(0) == (0, 1, 11))
      drivePush(0, 3, 33)
      assert(popOne(0) == (0, 2, 22))
      assert(popOne(0) == (0, 3, 33))
      assert(dut.io.occupancy(0).toInt == 0)
      assert(dut.io.occupancy(1).toInt == 0)
    }
  }

  test("TrIP can own the physical FIFO SRAM and receives an in-flight read after owner changes") {
    simConfig.compile(new BankedGustavsonBFifo(cfg, depth = 2)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.owner #= LocalBufferOwner.TrIp
      for (bank <- 0 until cfg.localBanks) {
        dut.io.pushes(bank).valid #= false
        dut.io.pops(bank).ready #= false
        dut.io.tripBuffer.writes(bank).valid #= false
        dut.io.tripBuffer.readCommands(bank).valid #= false
      }

      val data = BigInt("123456789abcdef", 16)
      dut.io.tripBuffer.writes(2).payload.address #= 3
      dut.io.tripBuffer.writes(2).payload.data #= data
      dut.io.tripBuffer.writes(2).payload.mask #=
        ((BigInt(1) << cfg.localLineBytes) - 1)
      dut.io.tripBuffer.writes(2).valid #= true
      dut.clockDomain.waitSampling()
      dut.io.tripBuffer.writes(2).valid #= false

      dut.io.tripBuffer.readCommands(2).payload #= 3
      dut.io.tripBuffer.readCommands(2).valid #= true
      dut.clockDomain.waitSampling()
      dut.io.tripBuffer.readCommands(2).valid #= false
      dut.io.owner #= LocalBufferOwner.Gustavson
      sleep(1)

      assert(dut.io.tripBuffer.readResponses(2).valid.toBoolean)
      assert(dut.io.tripBuffer.readResponses(2).payload.toBigInt == data)
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
                    row: Int, k: Int, value: Int,
                    groupId: Boolean = false): Unit = {
    dut.io.aLoad.payload.groupId #= groupId
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
      dut.io.start.payload.groupId #= false
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

  test("TrGT carries finish with the first and final streamed B element") {
    simConfig.compile(new TrGtSubrowScheduler(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGt(dut)
      dut.io.result.ready #= true

      loadA(dut, 0, row = 1, k = 2, value = 3)

      dut.io.start.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.start.payload.groupId #= false
      dut.io.start.valid #= true
      dut.io.bPush(0).payload.valid #= true
      dut.io.bPush(0).payload.aIndex #= 0
      dut.io.bPush(0).payload.k #= 2
      dut.io.bPush(0).payload.column #= 4
      dut.io.bPush(0).payload.value #= 5
      dut.io.bPush(0).valid #= true
      dut.io.finish #= true
      sleep(1)
      assert(dut.io.start.ready.toBoolean)
      assert(dut.io.bPush(0).ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false
      dut.io.bPush(0).valid #= false
      dut.io.finish #= false

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 200) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong,
            dut.io.result.payload.endOfTile.toBoolean))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(observed == Seq((1, 4, 15L, true)))
      assert(dut.io.acceptedBElements.toLong == 1)
      assert(dut.io.issuedBElements.toLong == 1)
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
    }
  }

  test("TrGT loads the inactive A context while the active group runs") {
    simConfig.compile(new TrGtSubrowScheduler(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGt(dut)
      dut.io.result.ready #= true

      def startGroup(groupId: Boolean): Unit = {
        dut.io.start.payload.mode #= TrapezoidDataflow.TrGt
        dut.io.start.payload.groupId #= groupId
        dut.io.start.valid #= true
        sleep(1)
        while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.start.valid #= false
      }

      def finishAndCollect(): Seq[(Int, Int, Long)] = {
        dut.io.finish #= true
        dut.clockDomain.waitSampling()
        dut.io.finish #= false
        val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
        var timeout = 0
        while (!dut.io.done.toBoolean && timeout < 300) {
          sleep(1)
          if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
            observed += ((dut.io.result.payload.row.toInt,
              dut.io.result.payload.column.toInt,
              dut.io.result.payload.value.toLong))
          }
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(timeout < 300)
        observed.toSeq
      }

      loadA(dut, 0, row = 1, k = 0, value = 3, groupId = false)
      startGroup(groupId = false)
      loadA(dut, 0, row = 2, k = 0, value = 7, groupId = true)
      assert(dut.io.busy.toBoolean,
        "the inactive A context must be writable while group 0 is active")
      pushB(dut, 0, aIndex = 0, k = 0, column = 4, value = 5)
      assert(finishAndCollect() == Seq((1, 4, 15L)))

      startGroup(groupId = true)
      pushB(dut, 0, aIndex = 0, k = 0, column = 6, value = 5)
      assert(finishAndCollect() == Seq((2, 6, 35L)))
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
    }
  }

  test("TrGT scheduler runs through an externally supplied synchronous SRAM") {
    simConfig.compile(new ExternalBufferedTrGtHarness(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.aLoad.valid #= false
      dut.io.start.valid #= false
      dut.io.finish #= false
      dut.io.result.ready #= false
      for (subrow <- 0 until cfg.localBanks) {
        dut.io.bPush(subrow).valid #= false
      }

      dut.io.aLoad.payload.index #= 0
      dut.io.aLoad.payload.groupId #= false
      dut.io.aLoad.payload.element.valid #= true
      dut.io.aLoad.payload.element.row #= 5
      dut.io.aLoad.payload.element.k #= 0
      dut.io.aLoad.payload.element.value #= 3
      dut.io.aLoad.valid #= true
      sleep(1)
      while (!dut.io.aLoad.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.aLoad.valid #= false

      dut.io.start.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.start.payload.groupId #= false
      dut.io.start.valid #= true
      sleep(1)
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false

      def pushExternal(column: Int, value: Int): Unit = {
        val stream = dut.io.bPush(0)
        stream.payload.valid #= true
        stream.payload.aIndex #= 0
        stream.payload.k #= 0
        stream.payload.column #= column
        stream.payload.value #= value
        stream.valid #= true
        sleep(1)
        while (!stream.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        stream.valid #= false
      }

      pushExternal(column = 1, value = 4)
      pushExternal(column = 2, value = 5)
      dut.io.finish #= true
      dut.clockDomain.waitSampling()
      dut.io.finish #= false
      dut.io.result.ready #= true

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 300) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(observed == Seq((5, 1, 12L), (5, 2, 15L)))
      assert(dut.io.acceptedBElements.toLong == 2)
      assert(dut.io.issuedBElements.toLong == 2)
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
    }
  }

  private def initializeTrGs(dut: TrGsSpatialEngine): Unit = {
    dut.io.clear #= false
    dut.io.command.valid #= false
    dut.io.result.ready #= false
    dut.io.resultLine.ready #= false
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
      assert(dut.io.accumulatorCycles.toLong == 2)
      assert(dut.io.accumulatorUpdates.toLong == 6)
      assert(dut.io.bankReplayCycles.toLong == 0)
    }
  }

  test("TrGS accepts consecutive cache lines into spatial accumulators") {
    simConfig.compile(new TrGsSpatialEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)
      dut.io.result.ready #= true

      for (command <- 0 until 3) {
        dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
        dut.io.command.payload.endOfTile #= (command == 2)
        dut.io.command.payload.a.valid #= true
        dut.io.command.payload.a.row #= 7
        dut.io.command.payload.a.k #= command
        dut.io.command.payload.a.value #= command + 1
        for (lane <- 0 until cfg.cacheLineWords) {
          val b = dut.io.command.payload.b(lane)
          b.valid #= true
          b.k #= command
          b.column #= lane
          b.value #= lane + 1
        }
        dut.io.command.valid #= true
        sleep(1)
        assert(dut.io.command.ready.toBoolean,
          s"cache line $command was not accepted without a bubble")
        dut.clockDomain.waitSampling()
      }
      dut.io.command.valid #= false

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 200) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(observed == Seq(
        (7, 0, 6L), (7, 1, 12L), (7, 2, 18L), (7, 3, 24L)))
      assert(!dut.io.overflow.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
    }
  }

  test("TrGS rejects coordinates from more than one accumulator column block") {
    simConfig.compile(new TrGsSpatialEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)
      dut.io.result.ready #= true

      issueTrGs(dut, k = 0, aValue = 2,
        Seq(Some(0 -> 1), Some(1 -> 2), None, None), endOfTile = false)
      issueTrGs(dut, k = 1, aValue = 3,
        Seq(None, None, None, Some(11 -> 5)), endOfTile = true)

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 200) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.done.toBoolean)
      assert(dut.io.overflow.toBoolean)
      assert(observed == Seq((6, 0, 2L), (6, 1, 4L)))
    }
  }

  test("TrGS serializes same-bank spatial accumulator updates") {
    simConfig.compile(new TrGsSpatialEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)
      dut.io.result.ready #= true

      // Columns 0 and 4 share bank 0 under the four-bank interleaved
      // accumulator mapping. The response must be serialized rather than
      // dropped or reported as an overflow.
      issueTrGs(dut, k = 0, aValue = 2,
        Seq(Some(0 -> 3), Some(4 -> 5), None, None), endOfTile = true)

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 200) {
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.done.toBoolean)
      assert(!dut.io.overflow.toBoolean)
      assert(observed == Seq((6, 0, 6L), (6, 4, 10L)))
      assert(dut.io.accumulatorCycles.toLong == 2)
      assert(dut.io.accumulatorUpdates.toLong == 2)
      assert(dut.io.bankReplayCycles.toLong == 1)
    }
  }

  test("TrGS wide writeback emits one cache-line batch per cycle") {
    val wideTileCfg = tileCfg.copy(accumulatorEntries = 16)
    simConfig.compile(new TrGsSpatialEngine(
      wideTileCfg, wideOutput = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)

      issueTrGs(dut, k = 0, aValue = 2,
        Seq(Some(0 -> 1), Some(1 -> 2), Some(2 -> 3), Some(3 -> 4)),
        endOfTile = false)
      issueTrGs(dut, k = 1, aValue = 3,
        Seq(Some(12 -> 5), Some(13 -> 6), None, None), endOfTile = true)

      var timeout = 0
      while (!dut.io.resultLine.valid.toBoolean && timeout < 100) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 100)
      assert(dut.io.resultLine.payload.count.toInt == 4)
      val stalled = (0 until cfg.cacheLineWords).map { lane =>
        val result = dut.io.resultLine.payload.results(lane)
        (result.valid.toBoolean, result.row.toInt, result.column.toInt,
          result.value.toLong, result.endOfTile.toBoolean)
      }
      for (_ <- 0 until 3) {
        dut.clockDomain.waitSampling()
        val current = (0 until cfg.cacheLineWords).map { lane =>
          val result = dut.io.resultLine.payload.results(lane)
          (result.valid.toBoolean, result.row.toInt, result.column.toInt,
            result.value.toLong, result.endOfTile.toBoolean)
        }
        assert(current == stalled)
      }

      dut.io.resultLine.ready #= true
      val lines = mutable.ArrayBuffer.empty[
        (Int, Boolean, Seq[(Int, Int, Long, Boolean)])]
      while (!dut.io.done.toBoolean && timeout < 200) {
        sleep(1)
        if (dut.io.resultLine.valid.toBoolean &&
            dut.io.resultLine.ready.toBoolean) {
          val count = dut.io.resultLine.payload.count.toInt
          val results = (0 until count).map { lane =>
            val result = dut.io.resultLine.payload.results(lane)
            assert(result.valid.toBoolean)
            (result.row.toInt, result.column.toInt, result.value.toLong,
              result.endOfTile.toBoolean)
          }
          lines += ((count, dut.io.resultLine.payload.endOfTile.toBoolean, results))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(lines.map(_._1) == Seq(4, 2))
      assert(lines.map(_._2) == Seq(false, true))
      assert(lines.flatMap(_._3).map(x => (x._1, x._2, x._3)) == Seq(
        (6, 0, 2L), (6, 1, 4L), (6, 2, 6L),
        (6, 3, 8L), (6, 12, 15L), (6, 13, 18L)))
      assert(lines.flatMap(_._3).map(_._4) ==
        Seq(false, false, false, false, false, true))
      assert(!dut.io.result.valid.toBoolean)
      assert(!dut.io.overflow.toBoolean)
    }
  }

  test("TrGS accumulates and prefetches wide output through external SRAM") {
    simConfig.compile(new ExternalBufferedTrGsHarness(
      tileCfg, wideOutput = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= false
      dut.io.resultLine.ready #= true

      def issue(k: Int, aValue: Int, leftValue: Int, rightValue: Int,
                endOfTile: Boolean): Unit = {
        dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
        dut.io.command.payload.endOfTile #= endOfTile
        dut.io.command.payload.a.valid #= true
        dut.io.command.payload.a.row #= 9
        dut.io.command.payload.a.k #= k
        dut.io.command.payload.a.value #= aValue
        for (lane <- 0 until cfg.cacheLineWords) {
          val element = dut.io.command.payload.b(lane)
          element.valid #= lane < 2
          element.k #= k
          element.column #= (if (lane == 0) 0 else if (lane == 1) 4 else 0)
          element.value #= (if (lane == 0) leftValue
            else if (lane == 1) rightValue else 0)
        }
        dut.io.command.valid #= true
        sleep(1)
        while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.command.valid #= false
      }

      issue(k = 0, aValue = 2, leftValue = 3, rightValue = 5,
        endOfTile = false)
      issue(k = 1, aValue = 3, leftValue = 7, rightValue = 11,
        endOfTile = true)

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      var lineCount = 0
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 300) {
        sleep(1)
        if (dut.io.resultLine.valid.toBoolean &&
            dut.io.resultLine.ready.toBoolean) {
          lineCount += 1
          for (lane <- 0 until cfg.cacheLineWords) {
            val result = dut.io.resultLine.payload.results(lane)
            if (result.valid.toBoolean) {
              observed += ((result.row.toInt, result.column.toInt,
                result.value.toLong))
            }
          }
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(dut.io.done.toBoolean)
      assert(!dut.io.overflow.toBoolean)
      assert(lineCount == 2)
      assert(observed == Seq((9, 0, 27L), (9, 4, 43L)))
      assert(dut.io.accumulatorUpdates.toLong == 4)
      assert(dut.io.bankReplayCycles.toLong > 0)
    }
  }

  test("TrGS clear drains in-flight responses without contaminating the next tile") {
    simConfig.compile(new TrGsSpatialEngine(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initializeTrGs(dut)
      dut.io.result.ready #= true

      dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.command.payload.endOfTile #= false
      dut.io.command.payload.a.valid #= true
      dut.io.command.payload.a.row #= 1
      dut.io.command.payload.a.k #= 0
      dut.io.command.payload.a.value #= 9
      for (lane <- 0 until cfg.cacheLineWords) {
        val b = dut.io.command.payload.b(lane)
        b.valid #= (lane == 0)
        b.k #= 0
        b.column #= lane
        b.value #= 9
      }
      dut.io.command.valid #= true
      sleep(1)
      assert(dut.io.command.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.command.valid #= false
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false

      var timeout = 0
      while (dut.io.busy.toBoolean && timeout < 50) {
        assert(!dut.io.result.valid.toBoolean)
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(!dut.io.busy.toBoolean)
      assert(!dut.io.done.toBoolean)

      issueTrGs(dut, k = 1, aValue = 2,
        Seq(Some(2 -> 3), None, None, None), endOfTile = true)
      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      while (!dut.io.done.toBoolean && timeout < 100) {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(observed == Seq((6, 2, 6L)))
      assert(dut.io.done.toBoolean)
    }
  }

  test("TrGS spatial scheduler overlaps tiles across PE rows") {
    val arrayCfg = cfg.copy(peRows = 2)
    val arrayTileCfg = tileCfg.copy(core = arrayCfg)
    simConfig.compile(new TrGsSpatialRowScheduler(arrayTileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= false
      dut.io.resultLine.ready #= false

      def issue(row: Int, column: Int, aValue: Int, bValue: Int): Unit = {
        dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
        dut.io.command.payload.endOfTile #= true
        dut.io.command.payload.a.valid #= true
        dut.io.command.payload.a.row #= row
        dut.io.command.payload.a.k #= 0
        dut.io.command.payload.a.value #= aValue
        for (lane <- 0 until arrayCfg.cacheLineWords) {
          val b = dut.io.command.payload.b(lane)
          b.valid #= lane == 0
          b.k #= 0
          b.column #= column
          b.value #= bValue
        }
        dut.io.command.valid #= true
        sleep(1)
        while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.command.valid #= false
      }

      issue(row = 10, column = 1, aValue = 2, bValue = 3)
      issue(row = 20, column = 2, aValue = 4, bValue = 5)

      assert(dut.io.assignedTiles.toLong == 2)
      assert(dut.io.allocated.toBigInt == 3,
        "both spatial rows must remain allocated while output is backpressured")

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long, Boolean)]
      dut.io.result.ready #= true
      var timeout = 0
      while (observed.size < 2 && timeout < 300) {
        sleep(1)
        if (dut.io.result.valid.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong,
            dut.io.result.payload.endOfTile.toBoolean))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }

      assert(observed == Seq((10, 1, 6L, true), (20, 2, 20L, true)))
      while (dut.io.completedTiles.toLong != 2 && timeout < 320) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.completedTiles.toLong == 2)
      assert(dut.io.allocated.toBigInt == 0)
      assert(dut.io.overflow.toBigInt == 0)
      assert(dut.io.unsupportedMode.toBigInt == 0)
    }
  }
}
