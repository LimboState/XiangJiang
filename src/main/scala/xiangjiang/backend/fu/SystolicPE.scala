package xiangjiang.backend.fu

import chisel3._
import chisel3.util._

/** One physical cell in the 8x8 systolic array.
  *
  * A is registered from west to east and B is registered from north to south.
  * The single arithmetic path is shared by all supported dataflows:
  *   - output-stationary (OS) writes the local accumulator;
  *   - INT8 weight-stationary (WS) keeps B in b and registers the partial sum
  *     toward the PE below;
  *   - elementwise operations reuse the same local arithmetic path.
  *
  * There is deliberately no row/column dot-product or reduction-tree path.
  */
class SystolicPE(elementBits: Int, bf16: Boolean) extends Module {
  val io = IO(new Bundle {
    val aIn = Input(UInt(elementBits.W))
    val bIn = Input(UInt(elementBits.W))
    val aInValid = Input(Bool())
    val bInValid = Input(Bool())
    val tagIn = Input(UInt(3.W))
    val moveA = Input(Bool())
    val moveB = Input(Bool())
    val clearPipe = Input(Bool())

    val sumIn = Input(UInt(32.W))
    val sumInValid = Input(Bool())
    val macEnable = Input(Bool())
    val useAccumulator = Input(Bool())
    val useStoredB = Input(Bool())
    val loadAccumulator = Input(Bool())
    val accumulatorIn = Input(UInt(32.W))

    val aOut = Output(UInt(elementBits.W))
    val bOut = Output(UInt(elementBits.W))
    val aOutValid = Output(Bool())
    val bOutValid = Output(Bool())
    val tagOut = Output(UInt(3.W))
    val sumOut = Output(UInt(32.W))
    val sumOutValid = Output(Bool())
    val accumulator = Output(UInt(32.W))
  })

  private val a = Reg(UInt(elementBits.W))
  private val b = Reg(UInt(elementBits.W))
  private val aValid = RegInit(false.B)
  private val bValid = RegInit(false.B)
  private val tag = Reg(UInt(3.W))
  private val sum = RegInit(0.U(32.W))
  private val sumValid = RegInit(false.B)
  private val accumulator = RegInit(0.U(32.W))

  io.aOut := a
  io.bOut := b
  io.aOutValid := aValid
  io.bOutValid := bValid
  io.tagOut := tag
  io.sumOut := sum
  io.sumOutValid := sumValid
  io.accumulator := accumulator

  when(io.clearPipe) {
    aValid := false.B
    bValid := false.B
    sumValid := false.B
  }.otherwise {
    when(io.moveA) {
      a := io.aIn
      aValid := io.aInValid
      tag := io.tagIn
    }
    when(io.moveB) {
      b := io.bIn
      bValid := io.bInValid
    }
    sumValid := false.B
  }

  // One multiplier-adder per PE. Both OS and WS select the addend feeding this
  // same path; no second array-MAC or reduction implementation is instantiated.
  private val addend = Mux(io.useAccumulator, accumulator, io.sumIn)
  private val bOperand = Mux(io.useStoredB, b, io.bIn)
  private val macResult = Wire(UInt(32.W))
  if (bf16) {
    macResult := VectorFloat.fma32Pipelined(
      VectorFloat.widen(io.aIn), VectorFloat.widen(bOperand), addend)
  } else {
    val product = (io.aIn.asSInt * bOperand.asSInt).pad(32)
    macResult := (addend.asSInt + product).asUInt
  }

  // Cancel all in-flight FMA results on prepare/flush. A recurrence must not
  // launch again until the previous result has reached the accumulator.
  private val writeResult = if (bf16) {
    val pending = RegInit(VecInit(Seq.fill(VectorFloat.fma32PipelineStages)(false.B)))
    pending(0) := io.macEnable
    for (i <- 1 until VectorFloat.fma32PipelineStages) pending(i) := pending(i - 1)
    when(io.clearPipe || io.loadAccumulator) { pending.foreach(_ := false.B) }
    pending.last
  } else io.macEnable

  // IEEE-754 roundTiesToEven preserves -0 only for an exact sum of two
  // negative zeros.  The ordinary FMA datapath already computes this sign,
  // but keep the exceptional decision explicit and delayed with the six-stage
  // FMA result.  This is important for FPGA implementations where the sign
  // and zero-detect cones can otherwise arrive at the accumulator separately.
  private val exactNegativeZero = if (bf16) {
    // BF16 zero means exponent and fraction are both zero; the sign bit is
    // intentionally excluded so both +0 and -0 are recognized.
    val productIsZero = io.aIn(14, 0) === 0.U || bOperand(14, 0) === 0.U
    val addendIsZero = addend(30, 0) === 0.U
    val productIsNegative = io.aIn(15) ^ bOperand(15)
    val launch = io.macEnable && productIsZero && addendIsZero &&
      productIsNegative && addend(31)
    val delayed = RegInit(VecInit(Seq.fill(VectorFloat.fma32PipelineStages)(false.B)))
    delayed(0) := launch
    for (i <- 1 until VectorFloat.fma32PipelineStages) delayed(i) := delayed(i - 1)
    when(io.clearPipe || io.loadAccumulator) { delayed.foreach(_ := false.B) }
    delayed.last
  } else false.B
  private val committedMacResult = Mux(exactNegativeZero,
    "h80000000".U(32.W), macResult)

  when(io.loadAccumulator) {
    accumulator := io.accumulatorIn
  }.elsewhen(writeResult && !io.clearPipe) {
    when(io.useAccumulator) {
      accumulator := committedMacResult
    }.otherwise {
      sum := committedMacResult
      sumValid := true.B
      tag := io.tagIn
    }
  }
}
