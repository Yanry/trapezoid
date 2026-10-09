package trapezoid.model

import scala.collection.mutable

sealed trait ReferenceMode
object ReferenceMode {
  case object DenseIp extends ReferenceMode
  case object TrIp extends ReferenceMode
  case object TrGt extends ReferenceMode
  case object TrGs extends ReferenceMode

  val all: Seq[ReferenceMode] = Seq(DenseIp, TrIp, TrGt, TrGs)
}

case class DenseMatrix(values: Vector[Vector[Long]]) {
  val rows: Int = values.length
  val columns: Int = if (values.isEmpty) 0 else values.head.length
  require(values.forall(_.length == columns), "matrix rows must have equal length")

  def apply(row: Int, column: Int): Long = values(row)(column)
}

case class BitmaskFiber(tag: Int, width: Int, mask: BigInt, values: Vector[Long]) {
  require(mask >= 0 && mask.bitLength <= width)
  require(values.length == mask.bitCount)

  def dense: Vector[Long] = {
    var packedIndex = 0
    Vector.tabulate(width) { coordinate =>
      if (mask.testBit(coordinate)) {
        val value = values(packedIndex)
        packedIndex += 1
        value
      } else 0L
    }
  }
}

case class CsrMatrix(rows: Int, columns: Int, rowPointers: Vector[Int],
                     columnIndices: Vector[Int], values: Vector[Long]) {
  require(rowPointers.length == rows + 1)
  require(columnIndices.length == values.length)
  require(rowPointers.head == 0 && rowPointers.last == values.length)

  def dense: DenseMatrix = {
    val result = Array.fill(rows, columns)(0L)
    for (row <- 0 until rows; index <- rowPointers(row) until rowPointers(row + 1)) {
      result(row)(columnIndices(index)) = values(index)
    }
    DenseMatrix(result.map(_.toVector).toVector)
  }
}

case class CscMatrix(rows: Int, columns: Int, columnPointers: Vector[Int],
                     rowIndices: Vector[Int], values: Vector[Long]) {
  require(columnPointers.length == columns + 1)
  require(rowIndices.length == values.length)
  require(columnPointers.head == 0 && columnPointers.last == values.length)

  def dense: DenseMatrix = {
    val result = Array.fill(rows, columns)(0L)
    for (column <- 0 until columns;
         index <- columnPointers(column) until columnPointers(column + 1)) {
      result(rowIndices(index))(column) = values(index)
    }
    DenseMatrix(result.map(_.toVector).toVector)
  }
}

/** Executable matrix-level oracle shared by randomized RTL tests. */
object MatrixReference {
  def multiply(a: DenseMatrix, b: DenseMatrix): DenseMatrix = {
    require(a.columns == b.rows, "inner matrix dimensions must match")
    DenseMatrix(Vector.tabulate(a.rows, b.columns) { (row, column) =>
      (0 until a.columns).map(k => a(row, k) * b(k, column)).sum
    })
  }

  /** Every hardware mode implements the same mathematical operation.  Density
    * affects scheduling efficiency, never correctness or mode availability.
    */
  def multiply(mode: ReferenceMode, a: DenseMatrix, b: DenseMatrix): DenseMatrix = mode match {
    case ReferenceMode.DenseIp => multiply(a, b)
    case ReferenceMode.TrIp => multiply(a, b)
    case ReferenceMode.TrGt => multiply(a, b)
    case ReferenceMode.TrGs => multiply(a, b)
  }

  def rowFibers(matrix: DenseMatrix): Vector[BitmaskFiber] =
    matrix.values.zipWithIndex.map { case (row, tag) => encodeFiber(tag, row) }

  def columnFibers(matrix: DenseMatrix): Vector[BitmaskFiber] =
    Vector.tabulate(matrix.columns) { column =>
      encodeFiber(column, Vector.tabulate(matrix.rows)(row => matrix(row, column)))
    }

  def encodeFiber(tag: Int, dense: Vector[Long]): BitmaskFiber = {
    var mask = BigInt(0)
    val packed = Vector.newBuilder[Long]
    dense.zipWithIndex.foreach { case (value, coordinate) =>
      if (value != 0) {
        mask = mask.setBit(coordinate)
        packed += value
      }
    }
    BitmaskFiber(tag, dense.length, mask, packed.result())
  }

  def toCsr(matrix: DenseMatrix): CsrMatrix = {
    val pointers = mutable.ArrayBuffer(0)
    val columns = mutable.ArrayBuffer.empty[Int]
    val values = mutable.ArrayBuffer.empty[Long]
    matrix.values.foreach { row =>
      row.zipWithIndex.foreach { case (value, column) =>
        if (value != 0) {
          columns += column
          values += value
        }
      }
      pointers += values.length
    }
    CsrMatrix(matrix.rows, matrix.columns, pointers.toVector, columns.toVector, values.toVector)
  }

  def toCsc(matrix: DenseMatrix): CscMatrix = {
    val pointers = mutable.ArrayBuffer(0)
    val rows = mutable.ArrayBuffer.empty[Int]
    val values = mutable.ArrayBuffer.empty[Long]
    for (column <- 0 until matrix.columns) {
      for (row <- 0 until matrix.rows) {
        val value = matrix(row, column)
        if (value != 0) {
          rows += row
          values += value
        }
      }
      pointers += values.length
    }
    CscMatrix(matrix.rows, matrix.columns, pointers.toVector, rows.toVector, values.toVector)
  }
}
