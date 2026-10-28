package trapezoid

import spinal.core._
import spinal.lib._

object CacheBankState extends SpinalEnum {
  val Idle, Lookup, RequestRefill, WaitRefill, Respond = newElement()
}

/** Multi-client, banked, set-associative read cache.
  *
  * Each bank accepts one request at a time and may hold one independent miss.
  * Clients carry several outstanding requests. Request-order FIFOs and response
  * FIFOs preserve each client's issue order even when banks/refills complete out
  * of order. Refills choose an invalid way first, then use a per-set round-robin
  * pointer. Data and tags are split by bank and way so the paper cache does not
  * infer one lineWidth*ways-wide memory word.
  */
class BankedReadCache(cacheCfg: CacheConfig) extends Component {
  private val validBitCount = cacheCfg.setsPerBank * cacheCfg.ways
  private val outstandingCountWidth =
    log2Up(cacheCfg.maxOutstandingPerClient + 1)

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
  val validBits = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(Bits(validBitCount bits)) init 0))
  val dataMemories = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    new TrapezoidSyncSram(cacheCfg.lineWidth, cacheCfg.setsPerBank))
  val tagMemories = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    new TrapezoidSyncSram(cacheCfg.tagWidth, cacheCfg.setsPerBank))
  val replacementMemories = Array.fill(cacheCfg.bankCount)(
    Mem(UInt(cacheCfg.wayIndexWidth bits), cacheCfg.setsPerBank))

  val addressRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.addressWidth bits)) init 0))
  val setRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.setIndexWidth bits)) init 0))
  val clientRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.clientIndexWidth bits)) init 0))
  val requestIdRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.requestIdWidth bits)) init 0))
  val victimWayRegs = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(cacheCfg.wayIndexWidth bits)) init 0))
  val responseData = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(Bits(cacheCfg.lineWidth bits)) init 0))
  val responseWasHit = Vec((0 until cacheCfg.bankCount).map(_ => RegInit(False)))
  val responseError = Vec((0 until cacheCfg.bankCount).map(_ => RegInit(False)))
  val outstandingCounts = Vec((0 until cacheCfg.clientCount).map(_ =>
    Reg(UInt(outstandingCountWidth bits)) init 0))
  val requestOrder = Array.fill(cacheCfg.clientCount)(
    StreamFifo(UInt(cacheCfg.requestIdWidth bits),
      cacheCfg.maxOutstandingPerClient))
  val responseQueues = Array.fill(cacheCfg.clientCount)(
    StreamFifo(CacheReadResponse(cacheCfg),
      cacheCfg.maxOutstandingPerClient))
  val hitCount = Reg(UInt(32 bits)) init 0
  val missCount = Reg(UInt(32 bits)) init 0
  val refillCount = Reg(UInt(32 bits)) init 0
  val hitEvents = Bits(cacheCfg.bankCount bits)
  val missEvents = Bits(cacheCfg.bankCount bits)

  io.bankBusy := states.map(_ =/= CacheBankState.Idle).asBits
  for (client <- 0 until cacheCfg.clientCount) {
    requestOrder(client).io.flush := io.clear
    responseQueues(client).io.flush := io.clear
    requestOrder(client).io.push.valid := False
    requestOrder(client).io.push.payload := io.requests(client).payload.requestId
    responseQueues(client).io.push.valid := False
    responseQueues(client).io.push.payload := CacheReadResponse(cacheCfg).getZero
    io.responses(client) <> responseQueues(client).io.pop
    io.clientOutstanding(client) := outstandingCounts(client) =/= 0
  }
  io.hitCount := hitCount
  io.missCount := missCount
  io.refillCount := refillCount

  val bankAccepts = Vec(Bool(), cacheCfg.bankCount)
  val selectedClients = Vec(UInt(cacheCfg.clientIndexWidth bits), cacheCfg.bankCount)
  for (bank <- 0 until cacheCfg.bankCount) {
    var earlierEligible: Bool = False
    val choices = Vec(Bool(), cacheCfg.clientCount)
    for (client <- 0 until cacheCfg.clientCount) {
      val eligible = io.requests(client).valid &&
        outstandingCounts(client) < cacheCfg.maxOutstandingPerClient &&
        requestOrder(client).io.push.ready &&
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

  val readWayData = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    Bits(cacheCfg.lineWidth bits))
  val readWayTags = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    UInt(cacheCfg.tagWidth bits))
  val readReplacement = Vec(UInt(cacheCfg.wayIndexWidth bits), cacheCfg.bankCount)
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
    for (way <- 0 until cacheCfg.ways) {
      val refillWrite = io.refillResponse.fire &&
        io.refillResponse.payload.bank === bank &&
        !io.refillResponse.payload.error && victimWayRegs(bank) === way
      dataMemories(bank)(way).io.read_enable := accept
      dataMemories(bank)(way).io.read_address := requestSet
      dataMemories(bank)(way).io.write_enable := refillWrite
      dataMemories(bank)(way).io.write_address := setRegs(bank)
      dataMemories(bank)(way).io.write_data := io.refillResponse.payload.data
      tagMemories(bank)(way).io.read_enable := accept
      tagMemories(bank)(way).io.read_address := requestSet
      tagMemories(bank)(way).io.write_enable := refillWrite
      tagMemories(bank)(way).io.write_address := setRegs(bank)
      tagMemories(bank)(way).io.write_data := tagOf(addressRegs(bank)).asBits
      readWayData(bank)(way) := dataMemories(bank)(way).io.read_data
      readWayTags(bank)(way) := tagMemories(bank)(way).io.read_data.asUInt
    }
    readReplacement(bank) := replacementMemories(bank).readSync(requestSet, accept)

    when(accept) {
      addressRegs(bank) := selectedRequest.address
      setRegs(bank) := requestSet
      clientRegs(bank) := selectedClients(bank)
      requestIdRegs(bank) := selectedRequest.requestId
      states(bank) := CacheBankState.Lookup
    }

    when(states(bank) === CacheBankState.Lookup) {
      val hitWays = Bits(cacheCfg.ways bits)
      val invalidWays = Bits(cacheCfg.ways bits)
      val selectedHitData = Bits(cacheCfg.lineWidth bits)
      val selectedVictim = UInt(cacheCfg.wayIndexWidth bits)
      selectedHitData := 0
      selectedVictim := readReplacement(bank)
      for (way <- 0 until cacheCfg.ways) {
        val validIndex = if (cacheCfg.ways == 1) {
          setRegs(bank)
        } else {
          (setRegs(bank).asBits ## U(way, cacheCfg.wayIndexWidth bits).asBits).asUInt
        }
        hitWays(way) := validBits(bank)(validIndex) &&
          readWayTags(bank)(way) === tagOf(addressRegs(bank))
        invalidWays(way) := !validBits(bank)(validIndex)
        when(hitWays(way)) {
          selectedHitData := readWayData(bank)(way)
        }
      }
      for (way <- (0 until cacheCfg.ways).reverse) {
        when(invalidWays(way)) {
          selectedVictim := way
        }
      }
      victimWayRegs(bank) := selectedVictim

      when(hitWays.orR) {
        responseData(bank) := selectedHitData
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
        val validIndex = if (cacheCfg.ways == 1) {
          setRegs(bank)
        } else {
          (setRegs(bank).asBits ## victimWayRegs(bank).asBits).asUInt
        }
        validBits(bank)(validIndex) := True
        if (cacheCfg.ways == 1) {
          replacementMemories(bank).write(setRegs(bank),
            U(0, cacheCfg.wayIndexWidth bits))
        } else {
          replacementMemories(bank).write(setRegs(bank),
            (victimWayRegs(bank) + 1).resized)
        }
        refillCount := refillCount + 1
      }
      states(bank) := CacheBankState.Respond
    }
  }

  val acceptedByClient = Bits(cacheCfg.clientCount bits)
  acceptedByClient := 0
  for (bank <- 0 until cacheCfg.bankCount) {
    when(bankAccepts(bank) && !io.clear) {
      for (client <- 0 until cacheCfg.clientCount) {
        when(selectedClients(bank) === client) {
          acceptedByClient(client) := True
          requestOrder(client).io.push.valid := True
          requestOrder(client).io.push.payload :=
            io.requests(client).payload.requestId
        }
      }
    }
  }

  for (client <- 0 until cacheCfg.clientCount) {
    val responseChoices = Bits(cacheCfg.bankCount bits)
    var earlierResponse: Bool = False
    for (bank <- 0 until cacheCfg.bankCount) {
      val eligible = !io.clear && states(bank) === CacheBankState.Respond &&
        clientRegs(bank) === client && requestOrder(client).io.pop.valid &&
        requestOrder(client).io.pop.payload === requestIdRegs(bank)
      responseChoices(bank) := eligible && !earlierResponse
      earlierResponse = earlierResponse || eligible
      when(responseChoices(bank)) {
        responseQueues(client).io.push.valid := True
        responseQueues(client).io.push.payload.requestId := requestIdRegs(bank)
        responseQueues(client).io.push.payload.data := responseData(bank)
        responseQueues(client).io.push.payload.wasHit := responseWasHit(bank)
        responseQueues(client).io.push.payload.error := responseError(bank)
        when(responseQueues(client).io.push.fire) {
          states(bank) := CacheBankState.Idle
        }
      }
    }
    requestOrder(client).io.pop.ready := responseQueues(client).io.push.fire
    switch(acceptedByClient(client) ## io.responses(client).fire) {
      is(B"10") { outstandingCounts(client) := outstandingCounts(client) + 1 }
      is(B"01") { outstandingCounts(client) := outstandingCounts(client) - 1 }
    }
  }

  when(io.clear) {
    for (bank <- 0 until cacheCfg.bankCount) {
      states(bank) := CacheBankState.Idle
      validBits(bank) := 0
    }
    for (client <- 0 until cacheCfg.clientCount) outstandingCounts(client) := 0
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
    setsPerBank = 128,
    clientCount = 32,
    ways = 16
  )))
}
