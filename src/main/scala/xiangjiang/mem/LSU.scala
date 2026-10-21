package xiangjiang.mem

import chisel3._
import chisel3.util._
import xiangjiang._

/** Ordered MemQ with an independent oldest-ready address-generation scheduler. */
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
  val head = RegInit(0.U(indexBits.W))
  val tail = RegInit(0.U(indexBits.W))
  val count = RegInit(0.U(log2Ceil(p.nMemq + 1).W))
  val active = RegInit(false.B)
  val blocked = RegInit(false.B)
  val resultValid = RegInit(false.B)
  val result = Reg(new ExeResult(p))
  def increment(x: UInt): UInt = Mux(x === (p.nMemq - 1).U, 0.U, x + 1.U)

  io.allocate.ready := count < p.nMemq.U && !io.stop && !io.flush && !blocked
  io.empty := count === 0.U && !active && !resultValid
  io.quiescent := !active && !resultValid
  io.result.valid := resultValid
  io.result.bits := result

  for (i <- 0 until p.nMemq) {
    for (source <- Seq(entries(i).src1, entries(i).src2)) {
      when(valid(i) && !source.ready && io.wakeup.valid &&
        !io.wakeup.bits.exception && TagMatch(source.tag, io.wakeup.bits.tag)) {
        source.ready := true.B
        source.value := io.wakeup.bits.data
      }
    }
  }

  val candidate = Wire(Vec(p.nMemq + 1, Bool()))
  val selected = Wire(Vec(p.nMemq + 1, UInt(indexBits.W)))
  candidate(0) := false.B
  selected(0) := 0.U
  for (i <- 0 until p.nMemq) {
    val ready = valid(i) && !addressReady(i) && entries(i).src1.ready
    val take = ready && (!candidate(i) || entries(i).uop.seq < entries(selected(i)).uop.seq)
    candidate(i + 1) := candidate(i) || ready
    selected(i + 1) := Mux(take, i.U, selected(i))
  }
  val aguIndex = selected(p.nMemq)
  val aguValid = candidate(p.nMemq) && !io.stop && !io.flush && !blocked
  val effectiveAddress = (entries(aguIndex).src1.value + entries(aguIndex).uop.imm)(31, 0)
  val aguStore = entries(aguIndex).uop.op === UOpCode.SW
  io.addressIssued.valid := aguValid
  io.addressIssued.bits := entries(aguIndex).uop.tag
  when(aguValid) {
    address(aguIndex) := effectiveAddress
    addressReady(aguIndex) := true.B
    addressFault(aguIndex) := Mux(effectiveAddress(1, 0) =/= 0.U,
      Mux(aguStore, FaultCode.storeAlign, FaultCode.loadAlign),
      Mux(effectiveAddress >= p.dtcmBytes.U,
        Mux(aguStore, FaultCode.storeAccess, FaultCode.loadAccess), FaultCode.none))
  }

  val oldest = entries(head)
  val store = oldest.uop.op === UOpCode.SW
  val authorized = !store || (io.head.valid && TagMatch(io.head.bits, oldest.uop.tag))
  val canStart = count =/= 0.U && addressReady(head) && oldest.src2.ready &&
    authorized && !active && !resultValid && !blocked && !io.stop && !io.flush
  io.request.valid := canStart && addressFault(head) === FaultCode.none
  io.request.bits.addr := address(head)
  io.request.bits.data := oldest.src2.value
  io.request.bits.write := store
  io.request.bits.mask := "b1111".U
  io.response.ready := active && !resultValid

  def setResult(data: UInt, fault: UInt): Unit = {
    result.tag := oldest.uop.tag
    result.seq := oldest.uop.seq
    result.data := data
    result.exception := fault =/= FaultCode.none
    result.cause := fault
    result.badAddr := address(head)
    result.nextPc := oldest.uop.pc + 4.U
    resultValid := true.B
  }
  when(canStart && addressFault(head) =/= FaultCode.none) {
    setResult(0.U, addressFault(head))
    blocked := true.B
  }
  when(io.request.fire) { active := true.B }
  when(io.response.fire) {
    active := false.B
    val fault = Mux(io.response.bits.error,
      Mux(store, FaultCode.storeAccess, FaultCode.loadAccess), FaultCode.none)
    setResult(io.response.bits.data, fault)
    when(io.response.bits.error) { blocked := true.B }
  }

  val dequeue = io.result.fire && !io.result.bits.exception
  when(io.result.fire) { resultValid := false.B }
  when(dequeue) {
    valid(head) := false.B
    addressReady(head) := false.B
    head := increment(head)
  }
  when(io.allocate.fire) {
    entries(tail) := io.allocate.bits
    valid(tail) := true.B
    addressReady(tail) := false.B
    for ((src, incoming) <- Seq(
      (entries(tail).src1, io.allocate.bits.src1),
      (entries(tail).src2, io.allocate.bits.src2))) {
      when(!incoming.ready && io.wakeup.valid && !io.wakeup.bits.exception &&
        TagMatch(incoming.tag, io.wakeup.bits.tag)) {
        src.ready := true.B
        src.value := io.wakeup.bits.data
      }
    }
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
    resultValid := false.B
    blocked := false.B
  }
}
