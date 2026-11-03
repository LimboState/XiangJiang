package xiangjiang.backend.fu

import chisel3._
import chisel3.util._

/** Restoring unsigned division, one quotient bit per cycle, followed by the
  * common IEEE BF16 special-value and RNE path. No combinational / operator.
  */
class BFloatDivider extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new Bundle { val a = UInt(16.W); val b = UInt(16.W) }))
    val resp = Decoupled(UInt(16.W))
    val flush = Input(Bool())
  })
  val busy = RegInit(false.B)
  val valid = RegInit(false.B)
  val a = Reg(UInt(16.W)); val b = Reg(UInt(16.W))
  val numerator = Reg(UInt(20.W)); val denominator = Reg(UInt(8.W))
  val quotient = Reg(UInt(20.W)); val remainder = Reg(UInt(9.W)); val count = Reg(UInt(5.W))
  val answer = Reg(UInt(16.W))
  io.req.ready := !busy && !valid && !io.flush
  io.resp.valid := valid && !io.flush
  io.resp.bits := answer
  val (num, den) = VectorFloat.divisionOperands(io.req.bits.a, io.req.bits.b)
  when(io.req.fire) {
    a := io.req.bits.a; b := io.req.bits.b
    numerator := num; denominator := den
    quotient := 0.U; remainder := 0.U; count := 0.U; busy := true.B
  }
  val trial = Cat(remainder(7, 0), numerator(19))
  val take = trial >= denominator
  val nextRemainder = Mux(take, trial - denominator, trial)
  val nextQuotient = Cat(quotient(18, 0), take)
  when(busy) {
    numerator := Cat(numerator(18, 0), 0.U(1.W))
    remainder := nextRemainder; quotient := nextQuotient
    when(count === 19.U) {
      answer := VectorFloat.divisionResult(a, b, nextQuotient, nextRemainder)
      busy := false.B; valid := true.B
    }.otherwise { count := count + 1.U }
  }
  when(io.resp.fire) { valid := false.B }
  when(io.flush) { busy := false.B; valid := false.B }
}
