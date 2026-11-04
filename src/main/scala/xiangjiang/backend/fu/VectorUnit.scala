package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** Folded eight-lane execution with one stable, full-vector completion. */
class VectorUnit(p: XJParams, group: Int = -1) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val idle :: selectLane :: running :: complete :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val uop = Reg(new MicroOp(p))
  private val source1 = Reg(Vec(8, UInt(32.W)))
  private val source2 = Reg(Vec(8, UInt(32.W)))
  private val old = Reg(Vec(8, UInt(32.W)))
  private val scalar = Reg(UInt(32.W))
  private val mask = Reg(UInt(8.W))
  private val lane = Reg(UInt(3.W))
  private val accumulated = Reg(UInt(256.W))
  private val reduction = Reg(UInt(32.W))
  private val completion = Reg(new ExeResult(p))

  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := completion
  io.busy := state =/= idle

  private val op = uop.vecOp
  private val active = uop.unmasked || mask(lane)
  // Isolate the dynamic lane mux from arithmetic. Operands remain stable
  // throughout multi-cycle arithmetic and reduction feedback.
  private val a = Reg(UInt(32.W))
  private val b = Reg(UInt(32.W))
  private val oldLane = Reg(UInt(16.W))
  private val arithmeticCycle = RegInit(0.U(3.W))
  when(state === selectLane) {
    a := Mux(active || op === VecOp.vmerge.U, source2(lane), 0.U)
    b := Mux(active, Mux(op(0), scalar, source1(lane)), 0.U)
    oldLane := Mux(active, old(lane)(15, 0), 0.U)
    arithmeticCycle := 0.U
  }
  when(state === running) { arithmeticCycle := arithmeticCycle + 1.U }
  private val isComparison = op >= VecOp.vmseq.U && op <= (VecOp.vmfle + 1).U
  private val isReduction = op === VecOp.fredsum.U || op === VecOp.fredmax.U
  private val value = WireDefault(0.U(32.W))
  private val comparison = WireDefault(false.B)
  private val laneDone = WireDefault(true.B)
  def supports(g: Int): Boolean = group == -1 || group == g
  if (supports(0)) { switch(op) {
    is(VecOp.vadd.U, (VecOp.vadd + 1).U) { value := a + b }
    is(VecOp.vsub.U, (VecOp.vsub + 1).U) { value := a - b }
    is(VecOp.vmax.U, (VecOp.vmax + 1).U) { value := Mux(a.asSInt > b.asSInt, a, b) }
    is(VecOp.vmin.U, (VecOp.vmin + 1).U) { value := Mux(a.asSInt < b.asSInt, a, b) }
    is(VecOp.vand.U, (VecOp.vand + 1).U) { value := a & b }
    is(VecOp.vor.U, (VecOp.vor + 1).U) { value := a | b }
    is(VecOp.vxor.U, (VecOp.vxor + 1).U) { value := a ^ b }
    is(VecOp.vsll.U, (VecOp.vsll + 1).U) { value := (a << b(4, 0))(31, 0) }
    is(VecOp.vsrl.U, (VecOp.vsrl + 1).U) { value := a >> b(4, 0) }
    is(VecOp.vsra.U, (VecOp.vsra + 1).U) { value := (a.asSInt >> b(4, 0)).asUInt }
    is(VecOp.vmseq.U, (VecOp.vmseq + 1).U) { comparison := a === b }
    is(VecOp.vmslt.U, (VecOp.vmslt + 1).U) { comparison := a.asSInt < b.asSInt }
    is(VecOp.vmsle.U, (VecOp.vmsle + 1).U) { comparison := a.asSInt <= b.asSInt }
    is(VecOp.vmsltu.U, (VecOp.vmsltu + 1).U) { comparison := a < b }
    is(VecOp.vmsleu.U, (VecOp.vmsleu + 1).U) { comparison := a <= b }
  } }
  if (supports(1)) {
    when(op === VecOp.vmul.U || op === (VecOp.vmul + 1).U) { value := (a * b)(31, 0) }
  }
  if (supports(3)) {
    val div = Module(new BFloatDivider)
    val sent = RegInit(false.B)
    val divide = op === VecOp.vfdiv.U || op === (VecOp.vfdiv + 1).U
    div.io.flush := io.flush
    div.io.req.valid := state === running && divide && active && !sent
    div.io.req.bits.a := a(15, 0); div.io.req.bits.b := b(15, 0)
    div.io.resp.ready := state === running && divide
    when(div.io.req.fire) { sent := true.B }
    when(div.io.resp.fire || io.flush) { sent := false.B }
    when(divide) { value := div.io.resp.bits; laneDone := !active || div.io.resp.valid }
  }
  if (supports(2)) { switch(op) {
    is(VecOp.vfadd.U, (VecOp.vfadd + 1).U) { value := VectorFloat.add(a(15, 0), b(15, 0)) }
    is(VecOp.vfsub.U, (VecOp.vfsub + 1).U) { value := VectorFloat.sub(a(15, 0), b(15, 0)) }
    is(VecOp.vfmul.U, (VecOp.vfmul + 1).U) { value := VectorFloat.mul(a(15, 0), b(15, 0)) }
    is(VecOp.vfmacc.U, (VecOp.vfmacc + 1).U) {
      value := VectorFloat.fmaPipelined(a(15, 0), b(15, 0), oldLane)
      laneDone := arithmeticCycle === VectorFloat.fma32PipelineStages.U
    }
    is(VecOp.vfmax.U, (VecOp.vfmax + 1).U) { value := VectorFloat.max(a(15, 0), b(15, 0)) }
    is(VecOp.vfmin.U, (VecOp.vfmin + 1).U) { value := VectorFloat.min(a(15, 0), b(15, 0)) }
    is(VecOp.vmfeq.U, (VecOp.vmfeq + 1).U) { comparison := VectorFloat.eq(a(15, 0), b(15, 0)) }
    is(VecOp.vmflt.U, (VecOp.vmflt + 1).U) { comparison := VectorFloat.lt(a(15, 0), b(15, 0)) }
    is(VecOp.vmfle.U, (VecOp.vmfle + 1).U) { comparison := VectorFloat.le(a(15, 0), b(15, 0)) }
    is(VecOp.vunary.U) {
      switch(uop.vecUnary) {
        is(VecOp.uWiden.U) { value := VectorFloat.widen(a(15, 0)) }
        is(VecOp.uNarrow.U) { value := VectorFloat.narrow(a) }
        is(VecOp.uIntToFloat.U) { value := VectorFloat.fromInt(a) }
        is(VecOp.uFloatToInt.U) { value := VectorFloat.toInt(a) }
      }
    }
  } }
  if (supports(4)) {
    when(op === VecOp.vmerge.U) { value := Mux(mask(lane), source1(lane), a) }
    when(op === VecOp.vrgather.U) {
      value := Mux(source1(lane) < 8.U, source2(source1(lane)(2, 0)), 0.U)
    }
  val distance = Mux(op(0), uop.rs1, scalar)
  val upIndex = lane - distance(2, 0)
  val downIndex = lane +& distance(2, 0)
  when(op === VecOp.vslideup.U || op === VecOp.vslideupImm.U) {
    value := Mux(distance < 8.U && lane >= distance, source2(upIndex), 0.U)
  }
  when(op === VecOp.vslidedown.U || op === VecOp.vslidedownImm.U) {
    value := Mux(distance < 8.U && downIndex < 8.U, source2(downIndex(2, 0)), 0.U)
  }
  }

  private val nextLanes = Wire(Vec(8, UInt(32.W)))
  nextLanes := accumulated.asTypeOf(Vec(8, UInt(32.W)))
  when(active || op === VecOp.vmerge.U) { nextLanes(lane) := value }
  private val bitMask = (1.U(256.W) << lane)(255, 0)
  private val nextComparison = Mux(active,
    (accumulated & ~bitMask) | Mux(comparison, bitMask, 0.U), accumulated)
  private val nextVector = Mux(isComparison, nextComparison, nextLanes.asUInt)
  private val reduced = if (supports(4)) Mux(op === VecOp.fredsum.U,
    VectorFloat.add32Pipelined(reduction, a), VectorFloat.max32(reduction, a)) else 0.U(32.W)
  if (supports(4)) {
    when(op === VecOp.fredsum.U) { laneDone := arithmeticCycle === 4.U }
  }
  private val nextReduction = Mux(active, reduced, reduction)

  when(io.flush) {
    state := idle
  }.otherwise {
    when(io.req.fire) {
      uop := io.req.bits.uop
      source1 := io.req.bits.vsrc1.value.asTypeOf(Vec(8, UInt(32.W)))
      source2 := io.req.bits.vsrc2.value.asTypeOf(Vec(8, UInt(32.W)))
      old := io.req.bits.vold.value.asTypeOf(Vec(8, UInt(32.W)))
      scalar := io.req.bits.src1.value
      mask := io.req.bits.vmask.value(7, 0)
      val compare = io.req.bits.uop.vecOp >= VecOp.vmseq.U &&
        io.req.bits.uop.vecOp <= (VecOp.vmfle + 1).U
      accumulated := Mux(compare, io.req.bits.vold.value(7, 0).pad(256), io.req.bits.vold.value)
      reduction := io.req.bits.vsrc1.value(31, 0)
      lane := 0.U
      state := selectLane
    }
    when(state === selectLane) { state := running }
    when(state === running && laneDone) {
      accumulated := nextVector
      reduction := nextReduction
      when(lane === 7.U) {
        completion := 0.U.asTypeOf(new ExeResult(p))
        completion.tag := uop.tag
        completion.seq := uop.seq
        completion.nextPc := uop.pc + 4.U
        completion.data := Mux(isReduction, Cat(old.asUInt(255, 32), nextReduction), nextVector)
        state := complete
      }.otherwise {
        lane := lane + 1.U
        state := selectLane
      }
    }
    when(io.resp.fire) { state := idle }
  }
}
