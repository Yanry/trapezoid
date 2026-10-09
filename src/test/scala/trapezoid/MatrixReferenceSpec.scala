package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import trapezoid.model.{DenseMatrix, MatrixReference, ReferenceMode}

import scala.util.Random

class MatrixReferenceSpec extends AnyFunSuite {
  private def randomMatrix(random: Random, rows: Int, columns: Int,
                           density: Double): DenseMatrix = {
    DenseMatrix(Vector.tabulate(rows, columns) { (_, _) =>
      if (random.nextDouble() < density) random.nextInt(7) - 3 else 0L
    })
  }

  test("bitmask, CSR, and CSC formats round-trip at all tested densities") {
    val random = new Random(0x51a7)
    for (density <- Seq(0.0, 0.1, 0.5, 1.0); iteration <- 0 until 20) {
      val matrix = randomMatrix(random, 4, 6, density)
      assert(MatrixReference.rowFibers(matrix).map(_.dense) == matrix.values)
      assert(MatrixReference.toCsr(matrix).dense == matrix)
      assert(MatrixReference.toCsc(matrix).dense == matrix)
    }
  }

  test("all dataflow modes preserve matrix multiplication semantics at any density") {
    val random = new Random(0x7a9e)
    for {
      mode <- ReferenceMode.all
      densityA <- Seq(0.0, 0.15, 0.6, 1.0)
      densityB <- Seq(0.0, 0.2, 0.7, 1.0)
      iteration <- 0 until 8
    } {
      val a = randomMatrix(random, 4, 5, densityA)
      val b = randomMatrix(random, 5, 3, densityB)
      assert(MatrixReference.multiply(mode, a, b) == MatrixReference.multiply(a, b))
    }
  }
}
