package xiangjiang.mem

import chisel3._
import chisel3.util._
import xiangjiang._

/** Ordered scalar/vector MemQ with independent oldest-ready address generation. */
class LSU(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new IssueEntry(p)))
    val wakeup = Input(Valid(new ExeResult(p)))
    val head = Input(Valid(new RobTag(p)))
    val request = Decoupled(new WordReq)
    val response = Flipped(Decoupled(new WordResp))
    val result = Decoupled(new ExeResult(p))
    val stop = Input(Bool())
    val flush = Input(Bool())
    val empty = Output(Bool())
    val quiescent = Output(Bool())
    val addressIssued = Output(Valid(new RobTag(p)))
  })

  private val indexBits = log2Ceil(p.nMemq)
  val entries = Reg(Vec(p.nMemq, new IssueEntry(p)))
  val valid = RegInit(VecInit(Seq.fill(p.nMemq)(false.B)))
  val addressReady = RegInit(VecInit(Seq.fill(p.nMemq)(false.B)))
  val address = Reg(Vec(p.nMemq, UInt(32.W)))
  val addressFault = Reg(Vec(p.nMemq, UInt(4.W)))
  val faultAddress = Reg(Vec(p.nMemq, UInt(32.W)))
  val head = RegInit(0.U(indexBits.W))
  val tail = RegInit(0.U(indexBits.W))
  val count = RegInit(0.U(log2Ceil(p.nMemq + 1).W))
  val active = RegInit(false.B)
  val vectorRunning = RegInit(false.B)
  val pendingLanes = Reg(UInt(16.W))
  val loadData = Reg(Vec(16, UInt(32.W)))
  val blocked = RegInit(false.B)
  val resultValid = RegInit(false.B)
  val result = Reg(new ExeResult(p))
  def increment(x: UInt): UInt = Mux(x === (p.nMemq - 1).U, 0.U, x + 1.U)
  def isVector(uop: MicroOp): Bool = uop.op === UOpCode.VECTOR
  def isStore(uop: MicroOp): Bool = uop.op === UOpCode.SW ||
    (isVector(uop) && (uop.vecOp === VecOp.vse32.U || uop.vecOp === VecOp.vse16.U))
  def isVectorHalf(uop: MicroOp): Bool = isVector(uop) &&
    (uop.vecOp === VecOp.vse16.U || uop.vecOp === VecOp.vle16.U)
  def laneMask(entry: IssueEntry): UInt = Mux(entry.uop.unmasked, "hffff".U(16.W), entry.vmask.value(15, 0))

  io.allocate.ready := count < p.nMemq.U && !io.stop && !io.flush && !blocked
  io.empty := count === 0.U && !active && !vectorRunning && !resultValid
  io.quiescent := !active && !vectorRunning && !resultValid
  io.result.valid := resultValid
  io.result.bits := result

  def captureWakeup(entry: IssueEntry): IssueEntry = {
    val captured = WireDefault(entry)
    for ((source, target) <- Seq(entry.src1 -> captured.src1, entry.src2 -> captured.src2)) {
      when(!source.ready && io.wakeup.valid && !io.wakeup.bits.exception &&
        TagMatch(source.tag, io.wakeup.bits.tag)) {
        target.ready := true.B
        target.value := io.wakeup.bits.data(31, 0)
      }
    }
    for ((source, target) <- Seq(entry.vold -> captured.vold, entry.vmask -> captured.vmask)) {
      when(!source.ready && io.wakeup.valid && !io.wakeup.bits.exception &&
        TagMatch(source.tag, io.wakeup.bits.tag)) {
        target.ready := true.B
        target.value := io.wakeup.bits.data
      }
    }
    captured
  }
  for (i <- 0 until p.nMemq) {
    when(valid(i)) {
      val awakened = captureWakeup(entries(i))
      entries(i).src1 := awakened.src1
      entries(i).src2 := awakened.src2
      entries(i).vold := awakened.vold
      entries(i).vmask := awakened.vmask
    }
  }

  val candidate = Wire(Vec(p.nMemq + 1, Bool()))
  val selected = Wire(Vec(p.nMemq + 1, UInt(indexBits.W)))
  candidate(0) := false.B
  selected(0) := 0.U
  for (i <- 0 until p.nMemq) {
    val maskReady = !isVector(entries(i).uop) || !entries(i).uop.usesVMask || entries(i).vmask.ready
    val ready = valid(i) && !addressReady(i) && entries(i).src1.ready && maskReady
    val take = ready && (!candidate(i) || entries(i).uop.seq < entries(selected(i)).uop.seq)
    candidate(i + 1) := candidate(i) || ready
    selected(i + 1) := Mux(take, i.U, selected(i))
  }
  val aguIndex = selected(p.nMemq)
  val aguEntry = entries(aguIndex)
  val aguValid = candidate(p.nMemq) && !io.stop && !io.flush && !blocked
  // The base addition wraps once; lane additions retain their carry for range checking.
  val effectiveAddress = (aguEntry.src1.value + aguEntry.uop.imm)(31, 0)
  val aguStore = isStore(aguEntry.uop)
  val aguVector = isVector(aguEntry.uop)
  val aguHalf = isVectorHalf(aguEntry.uop)
  val alignCause = Mux(aguStore, FaultCode.storeAlign, FaultCode.loadAlign)
  val accessCause = Mux(aguStore, FaultCode.storeAccess, FaultCode.loadAccess)
  val scalarMisaligned = effectiveAddress(1, 0).orR
  val scalarFault = Mux(scalarMisaligned, alignCause,
    Mux(effectiveAddress >= p.dtcmBytes.U, accessCause, FaultCode.none))
  var foundBad: Bool = false.B
  var firstCause: UInt = FaultCode.none
  var firstAddress: UInt = effectiveAddress
  for (lane <- 0 until 16) {
    val laneAddress = Cat(0.U(1.W), effectiveAddress) + Mux(aguHalf, (lane * 2).U(33.W), (lane * 4).U(33.W))
    val misaligned = Mux(aguHalf, laneAddress(0), laneAddress(1, 0).orR)
    val laneFault = Mux(misaligned, alignCause,
      Mux(laneAddress >= p.dtcmBytes.U(33.W), accessCause, FaultCode.none))
    val bad = laneMask(aguEntry)(lane) && laneFault =/= FaultCode.none
    firstCause = Mux(bad && !foundBad, laneFault, firstCause)
    firstAddress = Mux(bad && !foundBad, laneAddress(31, 0), firstAddress)
    foundBad = foundBad || bad
  }
  io.addressIssued.valid := aguValid
  io.addressIssued.bits := aguEntry.uop.tag
  when(aguValid) {
    address(aguIndex) := effectiveAddress
    addressReady(aguIndex) := true.B
    addressFault(aguIndex) := Mux(aguVector, firstCause, scalarFault)
    faultAddress(aguIndex) := Mux(aguVector, firstAddress, effectiveAddress)
  }

  val oldest = entries(head)
  val vector = isVector(oldest.uop)
  val store = isStore(oldest.uop)
  val half = isVectorHalf(oldest.uop)
  val authorized = !store || (io.head.valid && TagMatch(io.head.bits, oldest.uop.tag))
  val dataReady = oldest.src2.ready && (!vector || !oldest.uop.usesVOld || oldest.vold.ready)
  val canStart = count =/= 0.U && addressReady(head) && dataReady && authorized &&
    !active && !vectorRunning && !resultValid && !blocked && !io.stop && !io.flush
  val lane = PriorityEncoder(pendingLanes)
  val elementAddress = (address(head) + Mux(half, Cat(lane, 0.U(1.W)), Cat(lane, 0.U(2.W))))(31, 0)
  val requestAddress = Mux(vector, elementAddress, address(head))
  val storeLanes = oldest.vold.value.asTypeOf(Vec(16, UInt(32.W)))
  val storeElement = storeLanes(lane)
  val halfData = Mux(requestAddress(1), Cat(storeElement(15, 0), 0.U(16.W)),
    Cat(0.U(16.W), storeElement(15, 0)))
  val halfMask = Mux(requestAddress(1), "b1100".U, "b0011".U)
  val issuingVector = vectorRunning && pendingLanes.orR && !active && !resultValid &&
    !blocked && !io.stop && !io.flush
  io.request.valid := (canStart && !vector && addressFault(head) === FaultCode.none) || issuingVector
  io.request.bits.addr := Cat(requestAddress(31, 2), 0.U(2.W))
  io.request.bits.data := Mux(vector, Mux(half, halfData, storeElement), oldest.src2.value)
  io.request.bits.write := store
  io.request.bits.mask := Mux(half, halfMask, "b1111".U)
  io.response.ready := active && !resultValid

  def setResult(data: UInt, fault: UInt, badAddress: UInt): Unit = {
    result.tag := oldest.uop.tag
    result.seq := oldest.uop.seq
    result.data := data
    result.exception := fault =/= FaultCode.none
    result.cause := fault
    result.badAddr := badAddress
    result.nextPc := oldest.uop.pc + 4.U
    resultValid := true.B
  }
  when(canStart) {
    when(addressFault(head) =/= FaultCode.none) {
      setResult(0.U, addressFault(head), faultAddress(head))
      blocked := true.B
    }.elsewhen(vector) {
      when(!laneMask(oldest).orR) {
        // An empty mask neither checks the base address nor accesses SRAM.
        setResult(Mux(store, 0.U, oldest.vold.value), FaultCode.none, 0.U)
      }.otherwise {
        vectorRunning := true.B
        pendingLanes := laneMask(oldest)
        loadData := oldest.vold.value.asTypeOf(Vec(16, UInt(32.W)))
      }
    }
  }
  when(io.request.fire) { active := true.B }
  when(io.response.fire) {
    active := false.B
    val fault = Mux(io.response.bits.error,
      Mux(store, FaultCode.storeAccess, FaultCode.loadAccess), FaultCode.none)
    val returnedHalf = Mux(requestAddress(1), io.response.bits.data(31, 16), io.response.bits.data(15, 0))
    when(vector) {
      val updatedData = WireDefault(loadData)
      when(!store) {
        updatedData(lane) := Mux(half, Cat(0.U(16.W), returnedHalf), io.response.bits.data)
      }
      loadData := updatedData
      val remaining = pendingLanes & ~UIntToOH(lane, 16)
      pendingLanes := remaining
      when(io.response.bits.error || !remaining.orR) {
        setResult(Mux(store, 0.U, updatedData.asUInt), fault, requestAddress)
        vectorRunning := false.B
      }
    }.otherwise {
      setResult(io.response.bits.data, fault, requestAddress)
    }
    when(io.response.bits.error) { blocked := true.B }
  }
  // A precise older fault may abandon unissued load lanes, never an accepted request.
  when(io.stop && vectorRunning && !active && !resultValid) { vectorRunning := false.B }

  val dequeue = io.result.fire && !io.result.bits.exception
  when(io.result.fire) { resultValid := false.B }
  when(dequeue) {
    valid(head) := false.B
    addressReady(head) := false.B
    head := increment(head)
  }
  when(io.allocate.fire) {
    entries(tail) := captureWakeup(io.allocate.bits)
    valid(tail) := true.B
    addressReady(tail) := false.B
    tail := increment(tail)
  }
  when(io.allocate.fire =/= dequeue) {
    count := Mux(io.allocate.fire, count + 1.U, count - 1.U)
  }
  when(io.flush) {
    assert(!active, "LSU flush must not cancel an accepted SRAM transaction")
    valid.foreach(_ := false.B)
    addressReady.foreach(_ := false.B)
    head := 0.U
    tail := 0.U
    count := 0.U
    active := false.B
    vectorRunning := false.B
    resultValid := false.B
    blocked := false.B
  }
}
