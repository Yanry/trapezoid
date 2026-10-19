package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable
import scala.util.Random

class TrIpTileControllerSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 2,
    localDepthPerBank = 8
  )
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 8)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def initialize(dut: TrIpTileController): Unit = {
    dut.io.clear #= false
    dut.io.aLoad.valid #= false
    dut.io.bLoad.valid #= false
    dut.io.start.valid #= false
    dut.io.start.payload.firstKTile #= true
    dut.io.start.payload.lastKTile #= true
    dut.io.result.ready #= false
  }

  private def driveFiber(target: IndexedFiber, index: Int, fiber: BitmaskFiber): Unit = {
    target.index #= index
    target.fiber.valid #= true
    target.fiber.tag #= fiber.tag
    target.fiber.mask #= fiber.mask
    for (entry <- 0 until cfg.fiberWidth) {
      target.fiber.values(entry) #= fiber.values.lift(entry).getOrElse(0L)
    }
  }

  private def loadA(dut: TrIpTileController, index: Int, fiber: BitmaskFiber): Unit = {
    driveFiber(dut.io.aLoad.payload, index, fiber)
    dut.io.aLoad.valid #= true
    while (!dut.io.aLoad.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    dut.io.aLoad.valid #= false
  }

  private def loadB(dut: TrIpTileController, index: Int, fiber: BitmaskFiber): Unit = {
    driveFiber(dut.io.bLoad.payload, index, fiber)
    dut.io.bLoad.valid #= true
    while (!dut.io.bLoad.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    dut.io.bLoad.valid #= false
  }

  private def execute(dut: TrIpTileController, mode: TrapezoidDataflow.E,
                      a: DenseMatrix, b: DenseMatrix, random: Random): DenseMatrix = {
    dut.io.clear #= true
    dut.clockDomain.waitSampling()
    dut.io.clear #= false

    MatrixReference.rowFibers(a).zipWithIndex.foreach { case (fiber, index) =>
      loadA(dut, index, fiber)
    }
    MatrixReference.columnFibers(b).zipWithIndex.foreach { case (fiber, index) =>
      loadB(dut, index, fiber)
    }

    dut.io.start.payload.mode #= mode
    dut.io.start.payload.rowCount #= a.rows
    dut.io.start.payload.columnCount #= b.columns
    dut.io.start.payload.firstKTile #= true
    dut.io.start.payload.lastKTile #= true
    dut.io.start.valid #= true
    while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
    dut.clockDomain.waitSampling()
    dut.io.start.valid #= false

    val observed = mutable.Map.empty[(Int, Int), Long]
    val observedOrder = mutable.ArrayBuffer.empty[(Int, Int)]
    var lastCount = 0
    var finished = false
    var cycles = 0
    while (!finished && cycles < 2000) {
      dut.io.result.ready #= random.nextBoolean()
      dut.clockDomain.waitSampling()
      if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
        val row = dut.io.result.payload.row.toInt
        val column = dut.io.result.payload.column.toInt
        val value = dut.io.result.payload.value.toLong
        assert(!observed.contains((row, column)),
          s"duplicate output ($row,$column), aOffset=${dut.io.activeAOffset.toInt}, " +
            s"group=${dut.io.activeAGroupSize.toInt}, bBase=${dut.io.activeBBase.toInt}")
        observed((row, column)) = value
        observedOrder += ((row, column))
        if (dut.io.result.payload.endOfTile.toBoolean) lastCount += 1
      }
      finished = dut.io.done.toBoolean
      cycles += 1
    }
    assert(finished, "tile controller timed out")
    assert(!dut.io.unsupportedMode.toBoolean,
      s"scheduler error: aOffset=${dut.io.activeAOffset.toInt} " +
        s"groupSize=${dut.io.activeAGroupSize.toInt} bBase=${dut.io.activeBBase.toInt} " +
        s"candidate=${dut.io.candidateColumnCount.toInt} " +
        s"planned=${dut.io.plannedColumns.toInt} activeRows=${dut.io.activeRows.toBigInt}")

    val dense = DenseMatrix(Vector.tabulate(a.rows, b.columns) { (row, column) =>
      observed.getOrElse((row, column), 0L)
    })
    assert(observedOrder == observedOrder.sorted, "results must be emitted in row-major order")
    assert(lastCount == (if (observed.nonEmpty) 1 else 0))
    dense
  }

  test("tile scheduler matches the reference model across density and backpressure") {
    simConfig.compile(new TrIpTileController(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initialize(dut)
      val random = new Random(0x42d1)

      val cases = Seq(
        (TrapezoidDataflow.DenseIp, 4, 4, 1.0, 1.0),
        (TrapezoidDataflow.DenseIp, 3, 3, 0.25, 0.5),
        (TrapezoidDataflow.TrIp, 4, 4, 1.0, 1.0),
        (TrapezoidDataflow.TrIp, 4, 8, 0.6, 0.7),
        (TrapezoidDataflow.TrIp, 3, 2, 0.2, 0.35),
        (TrapezoidDataflow.TrIp, 2, 4, 0.0, 1.0)
      )

      for ((mode, rows, columns, densityA, densityB) <- cases) {
        val a = DenseMatrix(Vector.tabulate(rows, 4) { (_, _) =>
          if (random.nextDouble() < densityA) random.nextInt(7) - 3 else 0L
        })
        val b = DenseMatrix(Vector.tabulate(4, columns) { (_, _) =>
          if (random.nextDouble() < densityB) random.nextInt(7) - 3 else 0L
        })
        assert(execute(dut, mode, a, b, random) == MatrixReference.multiply(a, b))
      }

      // A later dense B column must not cause the scheduler to shrink an A
      // group after earlier columns have already been accumulated.
      val lateDenseA = DenseMatrix(Vector(
        Vector(3L, 4L, 1L, 0L), Vector(0L, 4L, 1L, 2L),
        Vector(3L, 4L, 1L, 2L), Vector(0L, 4L, 1L, 0L)))
      val lateDenseB = DenseMatrix(Vector(
        Vector(1L, 2L, 3L, 4L), Vector(1L, 0L, 3L, 4L),
        Vector(0L, 0L, 3L, 4L), Vector(1L, 0L, 3L, 4L)))
      assert(execute(dut, TrapezoidDataflow.DenseIp,
        lateDenseA, lateDenseB, random) == MatrixReference.multiply(lateDenseA, lateDenseB))

      dut.io.start.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.start.payload.rowCount #= 2
      dut.io.start.payload.columnCount #= 2
      dut.io.start.payload.firstKTile #= true
      dut.io.start.payload.lastKTile #= true
      dut.io.start.valid #= true
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false
      dut.clockDomain.waitSampling()
      assert(dut.io.busy.toBoolean)
      dut.clockDomain.waitSampling(3)
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false

      var abortTimeout = 0
      while (dut.io.busy.toBoolean && abortTimeout < 20) {
        dut.clockDomain.waitSampling()
        abortTimeout += 1
      }
      assert(!dut.io.busy.toBoolean)
      assert(!dut.io.done.toBoolean)

      dut.io.start.payload.mode #= TrapezoidDataflow.TrIp
      dut.io.start.payload.rowCount #= 1
      dut.io.start.payload.columnCount #= tileCfg.maxColumns + 1
      dut.io.start.payload.firstKTile #= true
      dut.io.start.payload.lastKTile #= true
      dut.io.start.valid #= true
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false
      while (!dut.io.done.toBoolean) dut.clockDomain.waitSampling()
      assert(dut.io.unsupportedMode.toBoolean)
    }
  }

  test("tile scheduler accumulates partial sums across K tiles") {
    simConfig.compile(new TrIpTileController(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initialize(dut)
      dut.io.result.ready #= true

      val aTiles = Seq(
        DenseMatrix(Vector(Vector(1L, 0L, 2L, 0L), Vector(0L, 3L, 0L, 4L))),
        DenseMatrix(Vector(Vector(5L, 0L, 6L, 0L), Vector(0L, 7L, 0L, 8L)))
      )
      val bTiles = Seq(
        DenseMatrix(Vector(Vector(1L, 2L), Vector(3L, 0L),
          Vector(4L, 5L), Vector(0L, 6L))),
        DenseMatrix(Vector(Vector(7L, 0L), Vector(8L, 9L),
          Vector(0L, 10L), Vector(11L, 12L)))
      )

      for (tile <- 0 until 2) {
        MatrixReference.rowFibers(aTiles(tile)).zipWithIndex.foreach {
          case (fiber, index) => loadA(dut, index, fiber)
        }
        MatrixReference.columnFibers(bTiles(tile)).zipWithIndex.foreach {
          case (fiber, index) => loadB(dut, index, fiber)
        }
        dut.io.start.payload.mode #= (if (tile == 0) TrapezoidDataflow.TrIp
          else TrapezoidDataflow.DenseIp)
        dut.io.start.payload.rowCount #= 2
        dut.io.start.payload.columnCount #= 2
        dut.io.start.payload.firstKTile #= (tile == 0)
        dut.io.start.payload.lastKTile #= (tile == 1)
        dut.io.start.valid #= true
        while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.start.valid #= false

        if (tile == 0) {
          var timeout = 0
          while (!dut.io.done.toBoolean && timeout < 500) {
            dut.clockDomain.waitSampling()
            assert(!dut.io.result.valid.toBoolean)
            timeout += 1
          }
          assert(dut.io.done.toBoolean)
          assert(dut.io.awaitingKTile.toBoolean)
        }
      }

      val observed = mutable.Map.empty[(Int, Int), Long]
      dut.clockDomain.waitSampling()
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 1000) {
        dut.clockDomain.waitSampling()
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt)) = dut.io.result.payload.value.toLong
        }
        timeout += 1
      }
      assert(dut.io.done.toBoolean,
        s"K accumulation timed out at aOffset=${dut.io.activeAOffset.toInt}, " +
          s"bBase=${dut.io.activeBBase.toInt}")
      assert(!dut.io.awaitingKTile.toBoolean)

      val expected = for {
        row <- 0 until 2
        column <- 0 until 2
      } yield (row, column) -> (MatrixReference.multiply(aTiles(0), bTiles(0))(row, column) +
        MatrixReference.multiply(aTiles(1), bTiles(1))(row, column))
      assert(observed.toMap == expected.toMap)
    }
  }
}
