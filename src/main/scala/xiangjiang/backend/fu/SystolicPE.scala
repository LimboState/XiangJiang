package xiangjiang.backend.fu

import chisel3._

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
    macResult := VectorFloat.fma32(
      VectorFloat.widen(io.aIn), VectorFloat.widen(bOperand), addend)
  } else {
    val product = (io.aIn.asSInt * bOperand.asSInt).pad(32)
    macResult := (addend.asSInt + product).asUInt
  }

  when(io.loadAccumulator) {
    accumulator := io.accumulatorIn
  }.elsewhen(io.macEnable && !io.clearPipe) {
    when(io.useAccumulator) {
      accumulator := macResult
    }.otherwise {
      sum := macResult
      sumValid := true.B
      tag := io.tagIn
    }
  }
}
