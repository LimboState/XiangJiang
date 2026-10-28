package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** Captures one instruction, visits sixteen lanes, then holds one completion.
  * Response becomes valid sixteen cycles after acceptance; minimum initiation
  * interval is eighteen cycles, including completion consumption.
  */
class VectorUnit(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val idle :: running :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val uop = Reg(new MicroOp(p))
  private val source1 = Reg(Vec(16, UInt(32.W)))
  private val source2 = Reg(Vec(16, UInt(32.W)))
  private val old = Reg(Vec(16, UInt(32.W)))
  private val scalar = Reg(UInt(32.W))
  private val mask = Reg(UInt(16.W))
  private val lane = Reg(UInt(4.W))
  private val accumulated = Reg(UInt(512.W))
  private val reduction = Reg(UInt(32.W))
  private val completion = Reg(new ExeResult(p))

  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := completion
  io.busy := state =/= idle

  private val op = uop.vecOp
  private val a = source2(lane)
  private val b = Mux(op(0), scalar, source1(lane))
  private val active = uop.unmasked || mask(lane)
  private val isComparison = op >= VecOp.vmseq.U && op <= (VecOp.vmfle + 1).U
  private val isReduction = op === VecOp.vunary.U &&
    (uop.rs1 === VecOp.uFredSum.U || uop.rs1 === VecOp.uFredMax.U)
  private val value = WireDefault(0.U(32.W))
  private val comparison = WireDefault(false.B)
  switch(op) {
    is(VecOp.vadd.U, (VecOp.vadd + 1).U) { value := a + b }
    is(VecOp.vsub.U, (VecOp.vsub + 1).U) { value := a - b }
    is(VecOp.vmul.U, (VecOp.vmul + 1).U) { value := (a * b)(31, 0) }
    is(VecOp.vmax.U, (VecOp.vmax + 1).U) { value := Mux(a.asSInt > b.asSInt, a, b) }
    is(VecOp.vmin.U, (VecOp.vmin + 1).U) { value := Mux(a.asSInt < b.asSInt, a, b) }
    is(VecOp.vand.U, (VecOp.vand + 1).U) { value := a & b }
    is(VecOp.vor.U, (VecOp.vor + 1).U) { value := a | b }
    is(VecOp.vxor.U, (VecOp.vxor + 1).U) { value := a ^ b }
    is(VecOp.vsll.U, (VecOp.vsll + 1).U) { value := (a << b(4, 0))(31, 0) }
    is(VecOp.vsrl.U, (VecOp.vsrl + 1).U) { value := a >> b(4, 0) }
    is(VecOp.vsra.U, (VecOp.vsra + 1).U) { value := (a.asSInt >> b(4, 0)).asUInt }
    is(VecOp.vfadd.U, (VecOp.vfadd + 1).U) { value := VectorFloat.add(a(15, 0), b(15, 0)) }
    is(VecOp.vfsub.U, (VecOp.vfsub + 1).U) { value := VectorFloat.sub(a(15, 0), b(15, 0)) }
    is(VecOp.vfmul.U, (VecOp.vfmul + 1).U) { value := VectorFloat.mul(a(15, 0), b(15, 0)) }
    is(VecOp.vfdiv.U, (VecOp.vfdiv + 1).U) { value := VectorFloat.div(a(15, 0), b(15, 0)) }
    is(VecOp.vfmacc.U, (VecOp.vfmacc + 1).U) {
      value := VectorFloat.fma(a(15, 0), b(15, 0), old(lane)(15, 0))
    }
    is(VecOp.vfmax.U, (VecOp.vfmax + 1).U) { value := VectorFloat.max(a(15, 0), b(15, 0)) }
    is(VecOp.vfmin.U, (VecOp.vfmin + 1).U) { value := VectorFloat.min(a(15, 0), b(15, 0)) }
    is(VecOp.vmseq.U, (VecOp.vmseq + 1).U) { comparison := a === b }
    is(VecOp.vmslt.U, (VecOp.vmslt + 1).U) { comparison := a.asSInt < b.asSInt }
    is(VecOp.vmsle.U, (VecOp.vmsle + 1).U) { comparison := a.asSInt <= b.asSInt }
    is(VecOp.vmsltu.U, (VecOp.vmsltu + 1).U) { comparison := a < b }
    is(VecOp.vmsleu.U, (VecOp.vmsleu + 1).U) { comparison := a <= b }
    is(VecOp.vmfeq.U, (VecOp.vmfeq + 1).U) { comparison := VectorFloat.eq(a(15, 0), b(15, 0)) }
    is(VecOp.vmflt.U, (VecOp.vmflt + 1).U) { comparison := VectorFloat.lt(a(15, 0), b(15, 0)) }
    is(VecOp.vmfle.U, (VecOp.vmfle + 1).U) { comparison := VectorFloat.le(a(15, 0), b(15, 0)) }
    is(VecOp.vmerge.U) { value := Mux(mask(lane), source1(lane), a) }
    is(VecOp.vrgather.U) {
      value := Mux(source1(lane) < 16.U, source2(source1(lane)(3, 0)), 0.U)
    }
    is(VecOp.vunary.U) {
      switch(uop.rs1) {
        is(VecOp.uWiden.U) { value := VectorFloat.widen(a(15, 0)) }
        is(VecOp.uNarrow.U) { value := VectorFloat.narrow(a) }
        is(VecOp.uIntToFloat.U) { value := VectorFloat.fromInt(a) }
        is(VecOp.uFloatToInt.U) { value := VectorFloat.toInt(a) }
      }
    }
  }
  private val distance = Mux(op(0), uop.rs1, scalar)
  private val upIndex = lane - distance(3, 0)
  private val downIndex = lane +& distance(3, 0)
  when(op === VecOp.vslideup.U || op === VecOp.vslideupImm.U) {
    value := Mux(distance < 16.U && lane >= distance, source2(upIndex), 0.U)
  }
  when(op === VecOp.vslidedown.U || op === VecOp.vslidedownImm.U) {
    value := Mux(distance < 16.U && downIndex < 16.U, source2(downIndex(3, 0)), 0.U)
  }

  private val nextLanes = Wire(Vec(16, UInt(32.W)))
  nextLanes := accumulated.asTypeOf(Vec(16, UInt(32.W)))
  when(active || op === VecOp.vmerge.U) { nextLanes(lane) := value }
  private val bitMask = (1.U(512.W) << lane)(511, 0)
  private val nextComparison = Mux(active,
    (accumulated & ~bitMask) | Mux(comparison, bitMask, 0.U), accumulated)
  private val nextVector = Mux(isComparison, nextComparison, nextLanes.asUInt)
  private val reduced = Mux(uop.rs1 === VecOp.uFredSum.U,
    VectorFloat.add32(reduction, VectorFloat.widen(a(15, 0))),
    VectorFloat.max(reduction(15, 0), a(15, 0)).pad(32))
  private val nextReduction = Mux(active, reduced, reduction)

  when(io.flush) {
    state := idle
  }.otherwise {
    when(io.req.fire) {
      uop := io.req.bits.uop
      source1 := io.req.bits.vsrc1.value.asTypeOf(Vec(16, UInt(32.W)))
      source2 := io.req.bits.vsrc2.value.asTypeOf(Vec(16, UInt(32.W)))
      old := io.req.bits.vold.value.asTypeOf(Vec(16, UInt(32.W)))
      scalar := io.req.bits.src1.value
      mask := io.req.bits.vmask.value(15, 0)
      accumulated := io.req.bits.vold.value
      reduction := Mux(io.req.bits.uop.rs1 === VecOp.uFredMax.U, "h0000ff80".U, 0.U)
      lane := 0.U
      state := running
    }
    when(state === running) {
      accumulated := nextVector
      reduction := nextReduction
      when(lane === 15.U) {
        completion := 0.U.asTypeOf(new ExeResult(p))
        completion.tag := uop.tag
        completion.seq := uop.seq
        completion.nextPc := uop.pc + 4.U
        completion.data := Mux(isReduction, nextReduction.pad(512), nextVector)
        state := complete
      }.otherwise {
        lane := lane + 1.U
      }
    }
    when(io.resp.fire) { state := idle }
  }
}
