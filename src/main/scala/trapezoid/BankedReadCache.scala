package trapezoid

import spinal.core._
import spinal.lib._

object CacheBankState extends SpinalEnum {
  val Idle, Lookup, RequestRefill, WaitRefill, Respond = newElement()
}

/** Multi-client, banked, direct-mapped read cache.
  *
  * Each bank accepts one request at a time and may hold one independent miss.
  * A client may have one outstanding request, which makes response routing
  * deterministic without a reorder buffer.  The shared refill port can return
  * banks out of order by carrying the bank and line address in every response.
  */
class BankedReadCache(cacheCfg: CacheConfig) extends Component {
  val io = new Bundle {
    val clear = in Bool()
    val requests = Vec(slave(Stream(CacheReadRequest(cacheCfg))), cacheCfg.clientCount)
    val responses = Vec(master(Stream(CacheReadResponse(cacheCfg))), cacheCfg.clientCount)
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val bankBusy = out Bits(cacheCfg.bankCount bits)
    val clientOutstanding = out Bits(cacheCfg.clientCount bits)
    val hitCount = out UInt(32 bits)
    val missCount = out UInt(32 bits)
    val refillCount = out UInt(32 bits)
  }

  private def bankOf(address: UInt): UInt = if (cacheCfg.bankCount == 1) {
    U(0, cacheCfg.bankIndexWidth bits)
  } else {
    address(log2Up(cacheCfg.bankCount) - 1 downto 0)
  }

  private def setOf(address: UInt): UInt = {
    val low = log2Up(cacheCfg.bankCount)
    address(low + log2Up(cacheCfg.setsPerBank) - 1 downto low)
  }

  private def tagOf(address: UInt): UInt = {
    val low = log2Up(cacheCfg.bankCount) + log2Up(cacheCfg.setsPerBank)
    address(cacheCfg.addressWidth - 1 downto low)
  }

  val states = Vec((0 until cacheCfg.bankCount).map(_ => RegInit(CacheBankState.Idle)))
  val validBits = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.setsPerBank).map(_ => RegInit(False))))
  val dataMemories = Array.fill(cacheCfg.bankCount)(
    Mem(Bits(cacheCfg.lineWidth bits), cacheCfg.setsPerBank))
  val tagMemories = Array.fill(cacheCfg.bankCount)(
    Mem(UInt(cacheCfg.tagWidth bits), cacheCfg.setsPerBank))

  val addressRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.addressWidth bits)) init 0))
  val setRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.setIndexWidth bits)) init 0))
  val clientRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.clientIndexWidth bits)) init 0))
  val requestIdRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.requestIdWidth bits)) init 0))
  val responseData = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(Bits(cacheCfg.lineWidth bits)) init 0))
  val responseWasHit = Vec((0 until cacheCfg.bankCount).map(_ => RegInit(False)))
  val responseError = Vec((0 until cacheCfg.bankCount).map(_ => RegInit(False)))
  val clientOutstanding = Vec((0 until cacheCfg.clientCount).map(_ => RegInit(False)))
  val hitCount = Reg(UInt(32 bits)) init 0
  val missCount = Reg(UInt(32 bits)) init 0
  val refillCount = Reg(UInt(32 bits)) init 0
  val hitEvents = Bits(cacheCfg.bankCount bits)
  val missEvents = Bits(cacheCfg.bankCount bits)

  io.bankBusy := states.map(_ =/= CacheBankState.Idle).asBits
  io.clientOutstanding := clientOutstanding.asBits
  io.hitCount := hitCount
  io.missCount := missCount
  io.refillCount := refillCount

  val bankAccepts = Vec(Bool(), cacheCfg.bankCount)
  val selectedClients = Vec(UInt(cacheCfg.clientIndexWidth bits), cacheCfg.bankCount)
  for (bank <- 0 until cacheCfg.bankCount) {
    var earlierEligible: Bool = False
    val choices = Vec(Bool(), cacheCfg.clientCount)
    for (client <- 0 until cacheCfg.clientCount) {
      val eligible = io.requests(client).valid && !clientOutstanding(client) &&
        bankOf(io.requests(client).payload.address) === bank
      choices(client) := eligible && !earlierEligible
      earlierEligible = earlierEligible || eligible
    }
    bankAccepts(bank) := choices.asBits.orR && states(bank) === CacheBankState.Idle
    selectedClients(bank) := 0
    for (client <- 0 until cacheCfg.clientCount) {
      when(choices(client)) { selectedClients(bank) := client }
    }
  }

  for (client <- 0 until cacheCfg.clientCount) {
    io.requests(client).ready := False
    for (bank <- 0 until cacheCfg.bankCount) {
      when(bankAccepts(bank) && selectedClients(bank) === client) {
        io.requests(client).ready := !io.clear
      }
    }
  }

  val readData = Vec(Bits(cacheCfg.lineWidth bits), cacheCfg.bankCount)
  val readTag = Vec(UInt(cacheCfg.tagWidth bits), cacheCfg.bankCount)
  for (bank <- 0 until cacheCfg.bankCount) {
    hitEvents(bank) := False
    missEvents(bank) := False
    val selectedRequest = CacheReadRequest(cacheCfg)
    selectedRequest := CacheReadRequest(cacheCfg).getZero
    for (client <- 0 until cacheCfg.clientCount) {
      when(selectedClients(bank) === client) {
        selectedRequest := io.requests(client).payload
      }
    }
    val accept = bankAccepts(bank) && !io.clear
    val requestSet = setOf(selectedRequest.address)
    readData(bank) := dataMemories(bank).readSync(requestSet, accept)
    readTag(bank) := tagMemories(bank).readSync(requestSet, accept)

    when(accept) {
      addressRegs(bank) := selectedRequest.address
      setRegs(bank) := requestSet
      clientRegs(bank) := selectedClients(bank)
      requestIdRegs(bank) := selectedRequest.requestId
      for (client <- 0 until cacheCfg.clientCount) {
        when(selectedClients(bank) === client) { clientOutstanding(client) := True }
      }
      states(bank) := CacheBankState.Lookup
    }

    when(states(bank) === CacheBankState.Lookup) {
      when(validBits(bank)(setRegs(bank)) &&
          readTag(bank) === tagOf(addressRegs(bank))) {
        responseData(bank) := readData(bank)
        responseWasHit(bank) := True
        responseError(bank) := False
        hitEvents(bank) := True
        states(bank) := CacheBankState.Respond
      } otherwise {
        responseWasHit(bank) := False
        missEvents(bank) := True
        states(bank) := CacheBankState.RequestRefill
      }
    }
  }

  val hitsThisCycle = CountOne(hitEvents).resize(32)
  val missesThisCycle = CountOne(missEvents).resize(32)
  when(hitsThisCycle =/= 0) { hitCount := hitCount + hitsThisCycle }
  when(missesThisCycle =/= 0) { missCount := missCount + missesThisCycle }

  val refillChoices = Vec(Bool(), cacheCfg.bankCount)
  var earlierRefill: Bool = False
  for (bank <- 0 until cacheCfg.bankCount) {
    val eligible = states(bank) === CacheBankState.RequestRefill
    refillChoices(bank) := eligible && !earlierRefill
    earlierRefill = earlierRefill || eligible
  }
  val refillBank = UInt(cacheCfg.bankIndexWidth bits)
  refillBank := 0
  for (bank <- 0 until cacheCfg.bankCount) {
    when(refillChoices(bank)) { refillBank := bank }
  }
  io.refillRequest.valid := !io.clear && refillChoices.asBits.orR
  io.refillRequest.payload.bank := refillBank
  io.refillRequest.payload.address := 0
  for (bank <- 0 until cacheCfg.bankCount) {
    when(refillBank === bank) {
      io.refillRequest.payload.address := addressRegs(bank)
    }
    when(io.refillRequest.fire && refillChoices(bank)) {
      states(bank) := CacheBankState.WaitRefill
    }
  }

  io.refillResponse.ready := False
  for (bank <- 0 until cacheCfg.bankCount) {
    val targetsBank = io.refillResponse.payload.bank === bank
    val expected = states(bank) === CacheBankState.WaitRefill &&
      io.refillResponse.payload.address === addressRegs(bank)
    when(targetsBank) { io.refillResponse.ready := !io.clear && expected }
    when(io.refillResponse.fire && targetsBank) {
      responseData(bank) := io.refillResponse.payload.data
      responseError(bank) := io.refillResponse.payload.error
      when(!io.refillResponse.payload.error) {
        dataMemories(bank).write(setRegs(bank), io.refillResponse.payload.data)
        tagMemories(bank).write(setRegs(bank), tagOf(addressRegs(bank)))
        validBits(bank)(setRegs(bank)) := True
        refillCount := refillCount + 1
      }
      states(bank) := CacheBankState.Respond
    }
  }

  for (client <- 0 until cacheCfg.clientCount) {
    io.responses(client).valid := False
    io.responses(client).payload := CacheReadResponse(cacheCfg).getZero
    for (bank <- 0 until cacheCfg.bankCount) {
      when(!io.clear && states(bank) === CacheBankState.Respond &&
          clientRegs(bank) === client) {
        io.responses(client).valid := True
        io.responses(client).payload.requestId := requestIdRegs(bank)
        io.responses(client).payload.data := responseData(bank)
        io.responses(client).payload.wasHit := responseWasHit(bank)
        io.responses(client).payload.error := responseError(bank)
        when(io.responses(client).fire) {
          states(bank) := CacheBankState.Idle
          clientOutstanding(client) := False
        }
      }
    }
  }

  when(io.clear) {
    for (bank <- 0 until cacheCfg.bankCount) {
      states(bank) := CacheBankState.Idle
      for (set <- 0 until cacheCfg.setsPerBank) validBits(bank)(set) := False
    }
    for (client <- 0 until cacheCfg.clientCount) clientOutstanding(client) := False
    hitCount := 0
    missCount := 0
    refillCount := 0
  }
}

object GenerateBankedReadCache extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cache",
    oneFilePerComponent = true
  ).generate(new BankedReadCache(CacheConfig(
    wordWidth = 32,
    wordsPerLine = 16,
    addressWidth = 24,
    bankCount = 32,
    setsPerBank = 64,
    clientCount = 32
  )))
}
