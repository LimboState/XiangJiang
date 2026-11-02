package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** 128-bit vector datapath (VLEN=DLEN=128, ELEN=32).
  *
  * Executes one full architectural vector instruction over up to 16 elements.
  * Element width follows the dispatch-time vtype snapshot (SEW 8/16/32);
  * elements at or beyond vl preserve the old destination (tail), and
  * mask-inactive elements also preserve the old value. Legacy V1 BF16
  * operations keep their fixed 8-lane 16-bit layout.
  *
  * FP32 operations require SEW=32, which dispatch enforces. The unit visits
  * registers lane selection, arithmetic and merge separately. FP add has an
  * additional unpack register; completion reads only merged registers.
  */
class VectorUnit(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val maxElements = 16
  private val elementBits = log2Ceil(maxElements)
  private val idle :: selectLane :: running :: mergeLane :: finish :: complete :: Nil = Enum(6)
  private val state = RegInit(idle)
  private val uop = Reg(new MicroOp(p))
  private val source1 = Reg(UInt(p.vlen.W))
  private val source2 = Reg(UInt(p.vlen.W))
  private val old = Reg(UInt(p.vlen.W))
  private val scalar = Reg(UInt(32.W))
  private val fScalar = Reg(UInt(32.W))
  private val mask = Reg(UInt(p.vlen.W))
  private val el = Reg(UInt(elementBits.W))
  private val accumulated = Reg(UInt(p.vlen.W))
  private val reduction = Reg(UInt(32.W))
  private val completion = Reg(new ExeResult(p))
  // Hold operands/index stable until the five-stage FP add drains.
  private val laneCycle = RegInit(0.U(3.W))
  private val a = Reg(UInt(32.W))
  private val b = Reg(UInt(32.W))
  private val reductionA = Reg(UInt(32.W))
  private val reductionB = Reg(UInt(32.W))
  private val laneValue = Reg(UInt(32.W))
  private val laneComparison = Reg(Bool())
  private val laneReduction = Reg(UInt(32.W))
  private val laneActive = Reg(Bool())
  private val laneMerge = Reg(Bool())
  private val laneReduceActive = Reg(Bool())
  private val laneWriteIndex = Reg(UInt(6.W))
  private val laneWriteBytes = Reg(UInt(3.W))
  private val permutationValue = Reg(UInt(32.W))

  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := completion
  io.busy := state =/= idle

  private val op = uop.vecOp
  private val sew = uop.vsew // 0=8, 1=16, 2=32
  private val sewBytes = Mux(sew === 0.U, 1.U, Mux(sew === 1.U, 2.U, 4.U))
  private val byteOf = el * sewBytes
  private val legacyBf16 = op >= VecOp.vfadd.U && op <= (VecOp.vmfle + 1).U
  private val unaryOp = op === VecOp.vunary.U
  private val fp32Op = op >= VecOp.vfadd32.U && op <= VecOp.vfredmax32.U
  private val isComparison = op >= VecOp.vmseq.U && op <= (VecOp.vmfle + 1).U
  private val fp32Comparison = op >= VecOp.vfeq32.U && op <= VecOp.vfle32s.U
  private val isReduction = unaryOp &&
    (uop.rs1 === VecOp.uFredSum.U || uop.rs1 === VecOp.uFredMax.U)
  private val fp32Reduction = op === VecOp.vfredsum32.U || op === VecOp.vfredmax32.U
  private val fp32UnaryCvt = op >= VecOp.vfcvtF2I32.U && op <= VecOp.vfcvtU2F32.U
  private val vmergeOp = op === VecOp.vmerge.U
  private val vgatherOp = op === VecOp.vrgather.U
  private val pipelinedLane = legacyBf16 || fp32Op || isReduction

  // Element extraction at SEW granularity (byte addressing over 128 bits).
  private def elem(reg: UInt, index: UInt): UInt = {
    val shift = MuxLookup(sew, 0.U(6.W))(Seq(
      0.U -> (8.U * index),
      1.U -> (16.U * index),
      2.U -> (32.U * index)))
    (reg >> shift)(31, 0)
  }
  private def bf16Lane(reg: UInt, index: UInt): UInt = (reg >> (16.U * index))(15, 0)
  // BF16 legacy operations only use lanes 0..7; clamp to stay in bounds.
  private val elSafe = Mux(legacyBf16 || unaryOp, Mux(el < 8.U, el, 0.U), el)

  private def maskBit(index: UInt): Bool = (mask >> index)(0)
  private val legacyActive = el < 8.U && (uop.unmasked || maskBit(el))
  private val vlActive = el < uop.vl && (uop.unmasked || maskBit(el))
  private val active = Mux(legacyBf16 || unaryOp, legacyActive, vlActive)
  private val isMerge = vmergeOp && el < uop.vl
  private val selectedA = Mux(legacyBf16 || unaryOp, bf16Lane(source2, elSafe), elem(source2, elSafe))
  private val bScalar = Mux(legacyBf16, scalar(15, 0),
    Mux(fp32Op && !fp32UnaryCvt && !fp32Reduction && op(0) && op =/= VecOp.vfsqrt32.U,
      fScalar, scalar))
  private val selectedB = Mux(op(0), bScalar, Mux(legacyBf16 || unaryOp,
    bf16Lane(source1, elSafe), elem(source1, elSafe)))
  private val sewBits = Mux(sew === 0.U, 8.U, Mux(sew === 1.U, 16.U, 32.U))
  private val signExt = Mux(sew === 0.U, Cat(Fill(24, a(7)), a(7, 0)),
    Mux(sew === 1.U, Cat(Fill(16, a(15)), a(15, 0)), a))
  private val bSignExt = Mux(sew === 0.U, Cat(Fill(24, b(7)), b(7, 0)),
    Mux(sew === 1.U, Cat(Fill(16, b(15)), b(15, 0)), b))
  private val shamtBits = Mux(sew === 0.U, b(2, 0), Mux(sew === 1.U, b(3, 0), b(4, 0)))

  private val bfAdd = VectorFloat.add(a(15, 0),
    Cat(b(15) ^ (op === VecOp.vfsub.U || op === (VecOp.vfsub + 1).U), b(14, 0)), pipelined = true, splitInput = true)
  private val bfMul = VectorFloat.mul(a(15, 0), b(15, 0), pipelined = true)
  private val fpAdd = VectorFloat.add32(a,
    Cat(b(31) ^ (op === VecOp.vfsub32.U || op === (VecOp.vfsub32 + 1).U), b(30, 0)), pipelined = true, splitInput = true)
  private val fpMul = VectorFloat.mul32(a, b, pipelined = true)
  private val reductionAdd = VectorFloat.add32(reductionA, reductionB,
    pipelined = true, splitInput = true)
  private val value = WireDefault(0.U(32.W))
  private val comparison = WireDefault(false.B)
  switch(op) {
    is(VecOp.vadd.U, (VecOp.vadd + 1).U) { value := a + b }
    is(VecOp.vsub.U, (VecOp.vsub + 1).U) { value := a - b }
    is(VecOp.vmul.U, (VecOp.vmul + 1).U) { value := (a * b)(31, 0) }
    is(VecOp.vmax.U, (VecOp.vmax + 1).U) { value := Mux(signExt.asSInt > bSignExt.asSInt, a, b) }
    is(VecOp.vmin.U, (VecOp.vmin + 1).U) { value := Mux(signExt.asSInt < bSignExt.asSInt, a, b) }
    is(VecOp.vand.U, (VecOp.vand + 1).U) { value := a & b }
    is(VecOp.vor.U, (VecOp.vor + 1).U) { value := a | b }
    is(VecOp.vxor.U, (VecOp.vxor + 1).U) { value := a ^ b }
    is(VecOp.vsll.U, (VecOp.vsll + 1).U) { value := (a << shamtBits)(31, 0) }
    is(VecOp.vsrl.U, (VecOp.vsrl + 1).U) { value := a >> shamtBits }
    is(VecOp.vsra.U, (VecOp.vsra + 1).U) { value := (signExt.asSInt >> shamtBits).asUInt }
    is(VecOp.vfadd.U, (VecOp.vfadd + 1).U, VecOp.vfsub.U, (VecOp.vfsub + 1).U) { value := bfAdd }
    is(VecOp.vfmul.U, (VecOp.vfmul + 1).U) { value := bfMul }
    is(VecOp.vfmax.U, (VecOp.vfmax + 1).U) { value := VectorFloat.max(a(15, 0), b(15, 0)) }
    is(VecOp.vfmin.U, (VecOp.vfmin + 1).U) { value := VectorFloat.min(a(15, 0), b(15, 0)) }
    is(VecOp.vmseq.U, (VecOp.vmseq + 1).U) { comparison := a === b }
    is(VecOp.vmslt.U, (VecOp.vmslt + 1).U) { comparison := signExt.asSInt < bSignExt.asSInt }
    is(VecOp.vmsle.U, (VecOp.vmsle + 1).U) { comparison := signExt.asSInt <= bSignExt.asSInt }
    is(VecOp.vmsltu.U, (VecOp.vmsltu + 1).U) { comparison := a < b }
    is(VecOp.vmsleu.U, (VecOp.vmsleu + 1).U) { comparison := a <= b }
    is(VecOp.vmfeq.U, (VecOp.vmfeq + 1).U) { comparison := VectorFloat.eq(a(15, 0), b(15, 0)) }
    is(VecOp.vmflt.U, (VecOp.vmflt + 1).U) { comparison := VectorFloat.lt(a(15, 0), b(15, 0)) }
    is(VecOp.vmfle.U, (VecOp.vmfle + 1).U) { comparison := VectorFloat.le(a(15, 0), b(15, 0)) }
    is(VecOp.vfadd32.U, (VecOp.vfadd32 + 1).U, VecOp.vfsub32.U, (VecOp.vfsub32 + 1).U) { value := fpAdd }
    is(VecOp.vfmul32.U, (VecOp.vfmul32 + 1).U) { value := fpMul }
    is(VecOp.vfmax32.U, (VecOp.vfmax32 + 1).U) { value := VectorFloat.max32(a, b) }
    is(VecOp.vfmin32.U, (VecOp.vfmin32 + 1).U) { value := VectorFloat.min32(a, b) }
    is(VecOp.vfeq32.U, (VecOp.vfeq32 + 1).U) { comparison := VectorFloat.eq32(a, b) }
    is(VecOp.vflt32.U, (VecOp.vflt32 + 1).U) { comparison := VectorFloat.lt32(a, b) }
    is(VecOp.vfle32.U, (VecOp.vfle32 + 1).U) { comparison := VectorFloat.le32(a, b) }
    is(VecOp.vfcvtF2I32.U) { value := VectorFloat.toInt32(a) }
    is(VecOp.vfcvtF2U32.U) { value := VectorFloat.toUInt32(a) }
    is(VecOp.vfcvtI2F32.U) { value := VectorFloat.fromInt32(a) }
    is(VecOp.vfcvtU2F32.U) { value := VectorFloat.fromUInt32(a) }
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
  private val upIndex = Mux(el >= distance, el - distance, 0.U)
  private val downIndex = Mux(el +& distance <= (maxElements - 1).U, el + distance, 0.U)
  private val selectedPermutation = WireDefault(0.U(32.W))
  private val permutationOp = op === VecOp.vslideup.U || op === VecOp.vslideupImm.U ||
    op === VecOp.vslidedown.U || op === VecOp.vslidedownImm.U || vmergeOp || vgatherOp
  when(permutationOp) { value := permutationValue }
  when(op === VecOp.vslideup.U || op === VecOp.vslideupImm.U) {
    selectedPermutation := Mux(el < distance, 0.U, Mux(legacyBf16 || unaryOp,
      bf16Lane(source2, upIndex), elem(source2, upIndex)))
  }
  when(op === VecOp.vslidedown.U || op === VecOp.vslidedownImm.U) {
    // Elements whose source index reaches or passes vl are tail and keep the
    // old destination value.
    selectedPermutation := Mux(downIndex < uop.vl, Mux(legacyBf16 || unaryOp,
      bf16Lane(source2, downIndex), elem(source2, downIndex)),
      Mux(legacyBf16 || unaryOp, bf16Lane(old, elSafe), elem(old, elSafe)))
  }
  when(vmergeOp) {
    selectedPermutation := Mux(maskBit(el), Mux(legacyBf16 || unaryOp,
      bf16Lane(source1, elSafe), elem(source1, elSafe)),
      Mux(legacyBf16 || unaryOp, bf16Lane(source2, elSafe), elem(source2, elSafe)))
  }
  when(vgatherOp) {
    val idx = Mux(elem(source1, el) < maxElements.U, elem(source1, el), 0.U)
    selectedPermutation := Mux(idx < uop.vl, Mux(legacyBf16 || unaryOp,
      bf16Lane(source2, idx), elem(source2, idx)), 0.U)
  }

  // Exactly 16 physical bytes, not the former 64-byte temporary. Fixed
  // destinations avoid a cascade of dynamically indexed byte writes.
  private val nextBytes = Wire(Vec(p.vlen / 8, UInt(8.W)))
  nextBytes := accumulated.asTypeOf(Vec(p.vlen / 8, UInt(8.W)))
  for (dest <- 0 until p.vlen / 8; src <- 0 until 4) {
    when((laneActive || laneMerge) && laneWriteBytes > src.U &&
      laneWriteIndex + src.U === dest.U) {
      nextBytes(dest) := laneValue(8 * src + 7, 8 * src)
    }
  }
  private val comparisonBit = el
  private val nextComparison = Wire(UInt(p.vlen.W))
  private val comparisonMask = (1.U(p.vlen.W) << comparisonBit)(p.vlen - 1, 0)
  nextComparison := Mux(laneActive,
    (accumulated & ~comparisonMask) | Mux(laneComparison, comparisonMask, 0.U), accumulated)
  private val nextVector = Mux(isComparison || fp32Comparison, nextComparison,
    nextBytes.asUInt)
  private val reducedLegacy = Mux(uop.rs1 === VecOp.uFredSum.U,
    reductionAdd,
    VectorFloat.max(reduction(15, 0), a(15, 0)).pad(32))
  private val reducedFp32 = Mux(op === VecOp.vfredsum32.U,
    reductionAdd, VectorFloat.max32(reduction, a))
  private val nextReduction = Mux(fp32Reduction, reducedFp32, reducedLegacy)
  private val reduceActive = Mux(fp32Reduction, vlActive, legacyActive)

  when(io.flush) {
    state := idle
  }.otherwise {
    when(io.req.fire) {
      uop := io.req.bits.uop
      source1 := io.req.bits.vsrc1.value
      source2 := io.req.bits.vsrc2.value
      old := io.req.bits.vold.value
      scalar := io.req.bits.src1.value
      fScalar := io.req.bits.fsrc1.value
      mask := io.req.bits.vmask.value
      accumulated := io.req.bits.vold.value
      reduction := Mux(io.req.bits.uop.vecOp === VecOp.vfredsum32.U || io.req.bits.uop.vecOp === VecOp.vfredmax32.U,
        Mux(io.req.bits.uop.vecOp === VecOp.vfredmax32.U, "hff800000".U(32.W), 0.U),
        Mux(io.req.bits.uop.rs1 === VecOp.uFredMax.U, "h0000ff80".U(32.W), 0.U))
      el := 0.U
      laneCycle := 0.U
      state := selectLane
    }
    when(state === selectLane) {
      a := selectedA
      b := selectedB
      reductionA := reduction
      reductionB := Mux(fp32Reduction, selectedA, VectorFloat.widen(selectedA(15, 0)))
      permutationValue := selectedPermutation
      laneActive := active
      laneMerge := isMerge
      laneReduceActive := reduceActive
      laneWriteIndex := Mux(legacyBf16 || unaryOp, el * 2.U, byteOf)
      laneWriteBytes := Mux(legacyBf16 || unaryOp, 2.U, sewBytes)
      laneCycle := 0.U
      state := running
    }
    when(state === running) {
      laneCycle := laneCycle + 1.U
      when(!pipelinedLane || laneCycle === 5.U) {
        laneValue := value
        laneComparison := comparison
        laneReduction := nextReduction
        state := mergeLane
      }
    }
    when(state === mergeLane) {
      accumulated := nextVector
      when(laneReduceActive) { reduction := laneReduction }
      when(el === (maxElements - 1).U) {
        state := finish
      }.otherwise {
        el := el + 1.U
        state := selectLane
      }
    }
    when(state === finish) {
      completion := 0.U.asTypeOf(new ExeResult(p))
      completion.tag := uop.tag
      completion.seq := uop.seq
      completion.nextPc := uop.pc + 4.U
      completion.data := Mux(isReduction || fp32Reduction, reduction.pad(p.vlen), accumulated)
      state := complete
    }
    when(io.resp.fire) { state := idle }
  }
}
