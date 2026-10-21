package trapezoid

import spinal.core._
import spinal.lib._

/** Tile-level TrIP/TrGT integration around one PE and local buffer per row.
  *
  * TrIP reserves every PE row for one matrix tile. TrGT reserves rows
  * independently, allowing temporal Gustavson tiles to overlap when TrIP is
  * idle. Both schedulers issue into the same physical TrapezoidPERow, matching
  * the paper's mode-selected compute and local-buffer organization.
  */
class ModeSharedTileController(
    tripCfg: TrIpTileConfig,
    gustavsonCfg: GustavsonTileConfig
) extends Component {
  private val cfg = tripCfg.core
  require(gustavsonCfg.core == cfg,
    "TrIP and TrGT must use the same PE-row configuration")

  val io = new Bundle {
    val clear = in Bool()

    val tripALoad = slave(Stream(IndexedFiber(cfg, tripCfg.rowIndexWidth)))
    val tripBLoad = slave(Stream(IndexedFiber(cfg, tripCfg.columnIndexWidth)))
    val tripStart = slave(Stream(TrIpTileStart(tripCfg)))
    val tripResult = master(Stream(MatrixResult(cfg)))
    val tripBusy = out Bool()
    val tripDone = out Bool()
    val tripAwaitingKTile = out Bool()
    val tripUnsupportedMode = out Bool()

    val trgtALoad = Vec(slave(Stream(IndexedGustavsonA(cfg))), cfg.peRows)
    val trgtStart = Vec(slave(Stream(GustavsonStart())), cfg.peRows)
    val trgtBPush = Vec(
      Vec(slave(Stream(GustavsonB(cfg))), cfg.localBanks), cfg.peRows)
    val trgtFinish = in Bits(cfg.peRows bits)
    val trgtResult = Vec(master(Stream(MatrixResult(cfg))), cfg.peRows)
    val trgtBusy = out Bits(cfg.peRows bits)
    val trgtDone = out Bits(cfg.peRows bits)
    val trgtOverflow = out Bits(cfg.peRows bits)
    val trgtUnsupportedMode = out Bits(cfg.peRows bits)

    val localBufferOwners = out Vec(LocalBufferOwner(), cfg.peRows)
  }

  val trip = new TrIpTileController(
    tripCfg, modeSharedLocalBuffers = true, externalPeArray = true)
  val trgt = Array.fill(cfg.peRows)(
    new TrGtSubrowScheduler(gustavsonCfg,
      externalFifoStorage = true, externalPe = true))
  val peRows = Array.fill(cfg.peRows)(new TrapezoidPERow(cfg))
  val trgtOwned = Vec((0 until cfg.peRows).map(_ => RegInit(False)))

  trip.io.clear := io.clear
  trip.io.aLoad.valid := io.tripALoad.valid
  trip.io.aLoad.payload := io.tripALoad.payload
  io.tripALoad.ready := trip.io.aLoad.ready
  trip.io.bLoad.valid := io.tripBLoad.valid
  trip.io.bLoad.payload := io.tripBLoad.payload
  io.tripBLoad.ready := trip.io.bLoad.ready

  val anyTrgtOwned = trgtOwned.asBits.orR
  val tripMayStart = !anyTrgtOwned
  trip.io.start.valid := io.tripStart.valid && tripMayStart
  trip.io.start.payload := io.tripStart.payload
  io.tripStart.ready := trip.io.start.ready && tripMayStart
  io.tripResult <> trip.io.result
  io.tripBusy := trip.io.busy
  io.tripDone := trip.io.done
  io.tripAwaitingKTile := trip.io.awaitingKTile
  io.tripUnsupportedMode := trip.io.unsupportedMode

  val tripClaimsArray = io.tripStart.valid && tripMayStart
  for (row <- 0 until cfg.peRows) {
    val scheduler = trgt(row)
    val pe = peRows(row)
    val rowMayStart = !trip.io.busy && !tripClaimsArray && !trgtOwned(row)

    scheduler.io.clear := io.clear
    scheduler.io.fifoStorageActive := trgtOwned(row)
    scheduler.io.aLoad.valid := io.trgtALoad(row).valid
    scheduler.io.aLoad.payload := io.trgtALoad(row).payload
    io.trgtALoad(row).ready := scheduler.io.aLoad.ready
    scheduler.io.start.valid := io.trgtStart(row).valid && rowMayStart
    scheduler.io.start.payload := io.trgtStart(row).payload
    io.trgtStart(row).ready := scheduler.io.start.ready && rowMayStart
    scheduler.io.finish := io.trgtFinish(row)
    for (subrow <- 0 until cfg.localBanks) {
      scheduler.io.bPush(subrow).valid := io.trgtBPush(row)(subrow).valid
      scheduler.io.bPush(subrow).payload := io.trgtBPush(row)(subrow).payload
      io.trgtBPush(row)(subrow).ready := scheduler.io.bPush(subrow).ready
    }
    io.trgtResult(row) <> scheduler.io.result
    io.trgtBusy(row) := scheduler.io.busy
    io.trgtDone(row) := scheduler.io.done
    io.trgtOverflow(row) := scheduler.io.overflow
    io.trgtUnsupportedMode(row) := scheduler.io.unsupportedMode

    val commandFromTrgt = trgtOwned(row)
    pe.io.command.valid := Mux(commandFromTrgt,
      scheduler.io.peCommand.valid, trip.io.peCommands(row).valid)
    pe.io.command.payload := Mux(commandFromTrgt,
      scheduler.io.peCommand.payload, trip.io.peCommands(row).payload)
    scheduler.io.peCommand.ready := pe.io.command.ready && commandFromTrgt
    trip.io.peCommands(row).ready := pe.io.command.ready && !commandFromTrgt

    // Response ownership is captured with the command so a clear or mode
    // transition cannot redirect an in-flight PE response to the other client.
    val responseForTrgt = RegInit(False)
    when(pe.io.command.fire) {
      responseForTrgt := commandFromTrgt
    }
    scheduler.io.peResponse.valid :=
      pe.io.response.valid && responseForTrgt
    scheduler.io.peResponse.payload := pe.io.response.payload
    trip.io.peResponses(row).valid :=
      pe.io.response.valid && !responseForTrgt
    trip.io.peResponses(row).payload := pe.io.response.payload
    pe.io.response.ready := Mux(responseForTrgt,
      scheduler.io.peResponse.ready, trip.io.peResponses(row).ready)

    val owner = Mux(trgtOwned(row),
      LocalBufferOwner.Gustavson, LocalBufferOwner.TrIp)
    io.localBufferOwners(row) := owner
    trip.io.localBufferOwners(row) := owner
    for (bank <- 0 until cfg.localBanks) {
      trip.io.gustavsonLocalBuffers(row).writes(bank) :=
        scheduler.io.fifoStorage.writes(bank)
      trip.io.gustavsonLocalBuffers(row).readCommands(bank) :=
        scheduler.io.fifoStorage.readCommands(bank)
      scheduler.io.fifoStorage.readResponses(bank) :=
        trip.io.gustavsonLocalBuffers(row).readResponses(bank)
    }

    when(scheduler.io.start.fire) {
      trgtOwned(row) := True
    }
    when(trgtOwned(row) && scheduler.io.done && !scheduler.io.busy) {
      trgtOwned(row) := False
    }
    when(io.clear) {
      trgtOwned(row) := False
    }
  }
}

object GenerateModeSharedTileController extends App {
  private val cfg = TrapezoidConfig.small
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-shared-tile",
    oneFilePerComponent = true
  ).generate(new ModeSharedTileController(
    TrIpTileConfig(cfg), GustavsonTileConfig(cfg)))
}
