package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable
import scala.util.Random

class TrIpMatrixControllerSpec extends AnyFunSuite {
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
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 4)
  private val matrixCfg = TrIpMatrixConfig(tileCfg,
    maxMatrixRows = 8, maxMatrixColumns = 8, maxKTiles = 2)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def driveFiber(target: IndexedFiber, index: Int,
                         fiber: BitmaskFiber): Unit = {
    target.index #= index
    target.fiber.valid #= true
    target.fiber.tag #= fiber.tag
    target.fiber.mask #= fiber.mask
    for (entry <- 0 until cfg.fiberWidth) {
      target.fiber.values(entry) #= fiber.values.lift(entry).getOrElse(0L)
    }
  }

  private def loadA(dut: TrIpMatrixController, index: Int,
                    fiber: BitmaskFiber): Unit = {
    driveFiber(dut.io.aLoad.payload, index, fiber)
    dut.io.aLoad.valid #= true
    var accepted = false
    var timeout = 0
    while (!accepted && timeout < 500) {
      sleep(1)
      accepted = dut.io.aLoad.ready.toBoolean
      dut.clockDomain.waitSampling()
      timeout += 1
    }
    sleep(1)
    dut.io.aLoad.valid #= false
    sleep(1)
    assert(accepted, "A fiber load timed out")
  }

  private def loadB(dut: TrIpMatrixController, index: Int,
                    fiber: BitmaskFiber): Unit = {
    driveFiber(dut.io.bLoad.payload, index, fiber)
    dut.io.bLoad.valid #= true
    var accepted = false
    var timeout = 0
    while (!accepted && timeout < 500) {
      sleep(1)
      accepted = dut.io.bLoad.ready.toBoolean
      dut.clockDomain.waitSampling()
      timeout += 1
    }
    sleep(1)
    dut.io.bLoad.valid #= false
    sleep(1)
    assert(accepted, "B fiber load timed out")
  }

  test("matrix descriptor drives outer M N and K tile loops with explicit modes") {
    simConfig.compile(new TrIpMatrixController(matrixCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.aLoad.valid #= false
      dut.io.bLoad.valid #= false
      dut.io.result.ready #= false

      val random = new Random(0x4d4e)
      val a = DenseMatrix(Vector.tabulate(6, 8) { (_, _) =>
        if (random.nextDouble() < 0.55) random.nextInt(7) - 3 else 0L
      })
      val b = DenseMatrix(Vector.tabulate(8, 6) { (_, _) =>
        if (random.nextDouble() < 0.6) random.nextInt(7) - 3 else 0L
      })
      val expected = MatrixReference.multiply(a, b)

      dut.io.descriptor.payload.rowCount #= a.rows
      dut.io.descriptor.payload.columnCount #= b.columns
      dut.io.descriptor.payload.kTileCount #= 2
      dut.io.descriptor.payload.modes(0) #= TrapezoidDataflow.DenseIp
      dut.io.descriptor.payload.modes(1) #= TrapezoidDataflow.TrIp
      dut.io.descriptor.valid #= true
      sleep(1)
      assert(dut.io.descriptor.ready.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)
      dut.io.descriptor.valid #= false

      val observed = mutable.Map.empty[(Int, Int), Long]
      var tileEnds = 0
      var matrixEnds = 0
      fork {
        val outputRandom = new Random(0x7264)
        var monitorTimeout = 0
        while (!dut.io.done.toBoolean && monitorTimeout < 10000) {
          dut.io.result.ready #= outputRandom.nextBoolean()
          sleep(1)
          if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
            val element = dut.io.result.payload.element
            observed((element.row.toInt, element.column.toInt)) = element.value.toLong
            if (element.endOfTile.toBoolean) tileEnds += 1
            if (dut.io.result.payload.endOfMatrix.toBoolean) matrixEnds += 1
          }
          dut.clockDomain.waitSampling()
          monitorTimeout += 1
        }
      }

      for {
        rowBase <- 0 until a.rows by tileCfg.maxRows
        columnBase <- 0 until b.columns by tileCfg.maxColumns
        kTile <- 0 until 2
      } {
        var timeout = 0
        while ((!dut.io.acceptingFibers.toBoolean ||
            dut.io.rowBase.toInt != rowBase ||
            dut.io.columnBase.toInt != columnBase ||
            dut.io.kTileIndex.toInt != kTile) && timeout < 2000) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(timeout < 2000,
          s"load request timeout: expected=($rowBase,$columnBase,$kTile), " +
            s"actual=(${dut.io.rowBase.toInt},${dut.io.columnBase.toInt}," +
            s"${dut.io.kTileIndex.toInt})")

        val rows = math.min(tileCfg.maxRows, a.rows - rowBase)
        val columns = math.min(tileCfg.maxColumns, b.columns - columnBase)
        val kBase = kTile * cfg.fiberWidth
        for (localRow <- 0 until rows) {
          val values = Vector.tabulate(cfg.fiberWidth)(offset =>
            a(rowBase + localRow, kBase + offset))
          loadA(dut, localRow,
            MatrixReference.encodeFiber(rowBase + localRow, values))
        }
        for (localColumn <- 0 until columns) {
          val values = Vector.tabulate(cfg.fiberWidth)(offset =>
            b(kBase + offset, columnBase + localColumn))
          loadB(dut, localColumn,
            MatrixReference.encodeFiber(columnBase + localColumn, values))
        }

        timeout = 0
        while (dut.io.acceptingFibers.toBoolean && timeout < 100) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(timeout < 100, "controller did not launch a fully loaded tile")
      }

      var doneTimeout = 0
      while (!dut.io.done.toBoolean && doneTimeout < 5000) {
        dut.clockDomain.waitSampling()
        doneTimeout += 1
      }
      sleep(1)
      assert(dut.io.done.toBoolean)
      assert(!dut.io.descriptorError.toBoolean)
      assert(!dut.io.innerError.toBoolean)
      assert(dut.io.completedOutputTiles.toLong == 4)
      assert(tileEnds == 4)
      assert(matrixEnds == 1)

      val observedDense = DenseMatrix(Vector.tabulate(expected.rows, expected.columns) {
        (row, column) => observed.getOrElse((row, column), 0L)
      })
      assert(observedDense == expected)
    }
  }

  test("matrix controller rejects invalid descriptors and incompatible modes") {
    simConfig.compile(new TrIpMatrixController(matrixCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.aLoad.valid #= false
      dut.io.bLoad.valid #= false
      dut.io.result.ready #= true

      def issueDescriptor(rows: Int, columns: Int,
                          mode: TrapezoidDataflow.E): Unit = {
        dut.io.descriptor.payload.rowCount #= rows
        dut.io.descriptor.payload.columnCount #= columns
        dut.io.descriptor.payload.kTileCount #= 1
        dut.io.descriptor.payload.modes(0) #= mode
        dut.io.descriptor.payload.modes(1) #= TrapezoidDataflow.TrIp
        dut.io.descriptor.valid #= true
        sleep(1)
        assert(dut.io.descriptor.ready.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.descriptor.valid #= false
      }

      issueDescriptor(rows = 0, columns = 1, mode = TrapezoidDataflow.TrIp)
      assert(dut.io.done.toBoolean)
      assert(dut.io.descriptorError.toBoolean)

      issueDescriptor(rows = 1, columns = 1, mode = TrapezoidDataflow.TrGt)
      var timeout = 0
      while (!dut.io.acceptingFibers.toBoolean && timeout < 20) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      loadA(dut, 0, MatrixReference.encodeFiber(0, Vector(1L, 0L, 0L, 0L)))
      loadB(dut, 0, MatrixReference.encodeFiber(0, Vector(2L, 0L, 0L, 0L)))

      timeout = 0
      while (!dut.io.done.toBoolean && timeout < 200) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.done.toBoolean)
      assert(dut.io.descriptorError.toBoolean)
      assert(dut.io.innerError.toBoolean)
      assert(dut.io.completedOutputTiles.toLong == 0)
    }
  }
}
