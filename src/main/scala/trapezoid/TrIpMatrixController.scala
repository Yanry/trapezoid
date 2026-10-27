package trapezoid

import spinal.core._
import spinal.lib._

object TrIpMatrixState extends SpinalEnum {
  val Idle, Load, Launch, Run = newElement()
}

/** Descriptor-driven outer M/N/K loops around [[TrIpTileController]].
  *
  * The controller requests one output tile and one K tile at a time through its
  * exposed base indices.  A cache or DMA frontend can use those indices to
  * stream the corresponding bitmask fibers.  Each K tile keeps an explicit
  * DenseIp/TrIp mode; density is never inspected to select a mode.
  */
class TrIpMatrixController(
    matrixCfg: TrIpMatrixConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    externalTileController: Boolean = false
) extends Component {
  private val tileCfg = matrixCfg.tile
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val descriptor = slave(Stream(TrIpMatrixDescriptor(matrixCfg)))
    val aLoad = slave(Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth)))
    val bLoad = slave(Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth)))
    val result = master(Stream(MatrixDescriptorResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val descriptorError = out Bool()
    val innerError = out Bool()
    val acceptingFibers = out Bool()
    val rowBase = out UInt(matrixCfg.rowCountWidth bits)
    val columnBase = out UInt(matrixCfg.columnCountWidth bits)
    val kTileIndex = out UInt(matrixCfg.kTileIndexWidth bits)
    val tileRowCount = out UInt(tileCfg.rowCountWidth bits)
    val tileColumnCount = out UInt(tileCfg.columnCountWidth bits)
    val completedOutputTiles = out UInt(32 bits)
    val tileALoad = if (externalTileController)
      master(Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth))) else null
    val tileBLoad = if (externalTileController)
      master(Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth))) else null
    val tileStart = if (externalTileController)
      master(Stream(TrIpTileStart(tileCfg))) else null
    val tileResult = if (externalTileController)
      slave(Stream(MatrixResult(cfg))) else null
    val tileDone = if (externalTileController) in Bool() else null
    val tileUnsupportedMode = if (externalTileController) in Bool() else null
  }

  val inner = if (externalTileController) null
    else new TrIpTileController(tileCfg, peFactory)
  if (!externalTileController) inner.io.clear := io.clear
  val tileALoad = Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth))
  val tileBLoad = Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth))
  val tileStart = Stream(TrIpTileStart(tileCfg))
  val tileResult = Stream(MatrixResult(cfg))
  val tileDone = Bool()
  val tileUnsupportedMode = Bool()
  if (externalTileController) {
    io.tileALoad << tileALoad
    io.tileBLoad << tileBLoad
    io.tileStart << tileStart
    tileResult << io.tileResult
    tileDone := io.tileDone
    tileUnsupportedMode := io.tileUnsupportedMode
  } else {
    inner.io.aLoad << tileALoad
    inner.io.bLoad << tileBLoad
    inner.io.start << tileStart
    tileResult << inner.io.result
    tileDone := inner.io.done
    tileUnsupportedMode := inner.io.unsupportedMode
  }

  val state = RegInit(TrIpMatrixState.Idle)
  val descriptorReg = Reg(TrIpMatrixDescriptor(matrixCfg))
  val rowBase = Reg(UInt(matrixCfg.rowCountWidth bits)) init 0
  val columnBase = Reg(UInt(matrixCfg.columnCountWidth bits)) init 0
  val kTileIndex = Reg(UInt(matrixCfg.kTileIndexWidth bits)) init 0
  val doneReg = RegInit(False)
  val descriptorError = RegInit(False)
  val completedOutputTiles = Reg(UInt(32 bits)) init 0
  val aLoaded = Vec((0 until tileCfg.maxRows).map(_ => RegInit(False)))
  val bLoaded = Vec((0 until tileCfg.maxColumns).map(_ => RegInit(False)))

  val rowsRemaining = descriptorReg.rowCount - rowBase
  val columnsRemaining = descriptorReg.columnCount - columnBase
  val currentRows = UInt(tileCfg.rowCountWidth bits)
  val currentColumns = UInt(tileCfg.columnCountWidth bits)
  currentRows := Mux(rowsRemaining > tileCfg.maxRows,
    U(tileCfg.maxRows, tileCfg.rowCountWidth bits), rowsRemaining.resized)
  currentColumns := Mux(columnsRemaining > tileCfg.maxColumns,
    U(tileCfg.maxColumns, tileCfg.columnCountWidth bits), columnsRemaining.resized)

  val finalRowTile = rowBase.resize(matrixCfg.rowCountWidth + 1) +
    currentRows.resize(matrixCfg.rowCountWidth + 1) >= descriptorReg.rowCount
  val finalColumnTile = columnBase.resize(matrixCfg.columnCountWidth + 1) +
    currentColumns.resize(matrixCfg.columnCountWidth + 1) >= descriptorReg.columnCount
  val finalKTile = kTileIndex.resize(matrixCfg.kTileCountWidth) + 1 >=
    descriptorReg.kTileCount
  val finalOutputTile = finalRowTile && finalColumnTile

  io.busy := state =/= TrIpMatrixState.Idle
  io.done := doneReg
  io.descriptorError := descriptorError
  io.innerError := tileUnsupportedMode
  io.acceptingFibers := state === TrIpMatrixState.Load
  io.rowBase := rowBase
  io.columnBase := columnBase
  io.kTileIndex := kTileIndex
  io.tileRowCount := currentRows
  io.tileColumnCount := currentColumns
  io.completedOutputTiles := completedOutputTiles

  io.descriptor.ready := !io.clear && state === TrIpMatrixState.Idle
  tileALoad.valid := io.aLoad.valid && state === TrIpMatrixState.Load &&
    io.aLoad.payload.index < currentRows
  tileALoad.payload := io.aLoad.payload
  io.aLoad.ready := state === TrIpMatrixState.Load &&
    io.aLoad.payload.index < currentRows && tileALoad.ready
  tileBLoad.valid := io.bLoad.valid && state === TrIpMatrixState.Load &&
    io.bLoad.payload.index < currentColumns
  tileBLoad.payload := io.bLoad.payload
  io.bLoad.ready := state === TrIpMatrixState.Load &&
    io.bLoad.payload.index < currentColumns && tileBLoad.ready

  when(io.aLoad.fire) {
    for (row <- 0 until tileCfg.maxRows) {
      when(io.aLoad.payload.index === row) { aLoaded(row) := True }
    }
  }
  when(io.bLoad.fire) {
    for (column <- 0 until tileCfg.maxColumns) {
      when(io.bLoad.payload.index === column) { bLoaded(column) := True }
    }
  }

  val allALoaded = Bool()
  val allBLoaded = Bool()
  allALoaded := True
  allBLoaded := True
  for (row <- 0 until tileCfg.maxRows) {
    when(U(row, tileCfg.rowCountWidth bits) < currentRows && !aLoaded(row)) {
      allALoaded := False
    }
  }
  for (column <- 0 until tileCfg.maxColumns) {
    when(U(column, tileCfg.columnCountWidth bits) < currentColumns && !bLoaded(column)) {
      allBLoaded := False
    }
  }

  tileStart.valid := state === TrIpMatrixState.Launch
  tileStart.payload.mode := (if (matrixCfg.maxKTiles == 1) {
    descriptorReg.modes(0)
  } else {
    descriptorReg.modes(kTileIndex)
  })
  tileStart.payload.rowCount := currentRows
  tileStart.payload.columnCount := currentColumns
  tileStart.payload.firstKTile := kTileIndex === 0
  tileStart.payload.lastKTile := finalKTile

  io.result.valid := !io.clear && state === TrIpMatrixState.Run && tileResult.valid
  io.result.payload.element := tileResult.payload
  io.result.payload.endOfMatrix := tileResult.payload.endOfTile && finalOutputTile
  tileResult.ready := !io.clear && state === TrIpMatrixState.Run && io.result.ready

  def clearLoadState(): Unit = {
    for (row <- 0 until tileCfg.maxRows) aLoaded(row) := False
    for (column <- 0 until tileCfg.maxColumns) bLoaded(column) := False
  }

  switch(state) {
    is(TrIpMatrixState.Idle) {
      when(io.descriptor.fire) {
        descriptorReg := io.descriptor.payload
        rowBase := 0
        columnBase := 0
        kTileIndex := 0
        doneReg := False
        completedOutputTiles := 0
        clearLoadState()
        val invalidDescriptor = io.descriptor.payload.rowCount === 0 ||
          io.descriptor.payload.columnCount === 0 ||
          io.descriptor.payload.rowCount > matrixCfg.maxMatrixRows ||
          io.descriptor.payload.columnCount > matrixCfg.maxMatrixColumns ||
          io.descriptor.payload.kTileCount === 0 ||
          io.descriptor.payload.kTileCount > matrixCfg.maxKTiles
        descriptorError := invalidDescriptor
        when(invalidDescriptor) {
          doneReg := True
        } otherwise {
          state := TrIpMatrixState.Load
        }
      }
    }

    is(TrIpMatrixState.Load) {
      when(allALoaded && allBLoaded) { state := TrIpMatrixState.Launch }
    }

    is(TrIpMatrixState.Launch) {
      when(tileStart.fire) { state := TrIpMatrixState.Run }
    }

    is(TrIpMatrixState.Run) {
      when(tileDone) {
        clearLoadState()
        when(tileUnsupportedMode) {
          descriptorError := True
          doneReg := True
          state := TrIpMatrixState.Idle
        } elsewhen(!finalKTile) {
          kTileIndex := kTileIndex + 1
          state := TrIpMatrixState.Load
        } otherwise {
          completedOutputTiles := completedOutputTiles + 1
          kTileIndex := 0
          when(!finalColumnTile) {
            columnBase := (columnBase + tileCfg.maxColumns).resized
            state := TrIpMatrixState.Load
          } elsewhen(!finalRowTile) {
            rowBase := (rowBase + tileCfg.maxRows).resized
            columnBase := 0
            state := TrIpMatrixState.Load
          } otherwise {
            doneReg := True
            state := TrIpMatrixState.Idle
          }
        }
      }
    }
  }

  when(io.clear) {
    state := TrIpMatrixState.Idle
    doneReg := False
    descriptorError := False
    completedOutputTiles := 0
    clearLoadState()
  }
}

object GenerateTrIpMatrixController extends App {
  private val tileCfg = TrIpTileConfig(TrapezoidConfig.small)
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-matrix",
    oneFilePerComponent = true
  ).generate(new TrIpMatrixController(TrIpMatrixConfig(
    tileCfg, maxMatrixRows = 64, maxMatrixColumns = 64, maxKTiles = 16)))
}
