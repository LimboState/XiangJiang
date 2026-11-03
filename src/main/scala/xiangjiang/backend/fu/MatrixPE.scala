package xiangjiang.backend.fu

import chisel3._
import chisel3.util._

/** One physical PE: BF16 fused product/FP32 add, or signed INT8/INT32 MAC.
  * Registers in the array own working state; this cell is the arithmetic path.
  */
class MatrixPE(bf16: Boolean) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt((if (bf16) 16 else 8).W))
    val b = Input(UInt((if (bf16) 16 else 8).W))
    val sum = Input(UInt(32.W))
    val multiplyOnly = Input(Bool())
    val result = Output(UInt(32.W))
  })
  if (bf16) {
    val product = VectorFloat.mul32(VectorFloat.widen(io.a), VectorFloat.widen(io.b))
    io.result := Mux(io.multiplyOnly, product,
      VectorFloat.fma32(VectorFloat.widen(io.a), VectorFloat.widen(io.b), io.sum))
  } else {
    val product = (io.a.asSInt * io.b.asSInt).pad(32).asUInt
    io.result := product + Mux(io.multiplyOnly, 0.U, io.sum)
  }
}
