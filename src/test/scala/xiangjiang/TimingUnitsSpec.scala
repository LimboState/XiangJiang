package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu._
import xiangjiang.backend.decode.DecodeUnit

class TimingUnitHarness(kind: String) extends Module {
  private val p = XJParams()
  val io = IO(new Bundle {
    val op = Input(UOpCode())
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val rm = Input(UInt(3.W))
    val frm = Input(UInt(3.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
    val flush = Input(Bool())
    val busy = Output(Bool())
    val resp = Decoupled(new ExeResult(p))
  })
  val port = kind match {
    case "mul" => Module(new MluUnit(p)).io
    case "div" => Module(new DvuUnit(p)).io
    case "float" => Module(new FloatCore(p)).io
  }
  port.req.bits := 0.U.asTypeOf(new IssueEntry(p))
  port.req.bits.uop.op := io.op
  port.req.bits.uop.pc := 0x100.U
  port.req.bits.uop.tag.slot := 3.U
  port.req.bits.uop.tag.generation := 5.U
  port.req.bits.uop.seq := 17.U
  port.req.bits.uop.rm := io.rm
  port.req.bits.frm := io.frm
  port.req.bits.src1.value := io.a
  port.req.bits.src2.value := io.b
  port.req.bits.fsrc1.value := io.a
  port.req.bits.fsrc2.value := io.b
  port.req.valid := io.valid
  port.flush := io.flush
  io.ready := port.req.ready
  io.busy := port.busy
  io.resp <> port.resp
}

class TimingUnitsSpec extends AnyFunSuite with ChiselSim {
  private val mask = (BigInt(1) << 32) - 1
  private def signed(x: BigInt): BigInt = if (x.testBit(31)) x - (BigInt(1) << 32) else x
  private def init(d: TimingUnitHarness): Unit = {
    d.io.valid.poke(false); d.io.flush.poke(false); d.io.resp.ready.poke(false)
    d.io.a.poke(0); d.io.b.poke(0); d.io.op.poke(UOpCode.ADD)
    d.io.rm.poke(0); d.io.frm.poke(0)
  }
  private def run(d: TimingUnitHarness, op: UOpCode.Type, a: BigInt, b: BigInt,
                  expected: BigInt, latency: Int, flags: Option[Int] = None): Unit = {
    d.io.op.poke(op); d.io.a.poke(a & mask); d.io.b.poke(b & mask)
    d.io.valid.poke(true); d.io.ready.expect(true)
    d.clock.step()
    d.io.valid.poke(false); d.io.a.poke(0xdeadbeefL); d.io.b.poke(0xabcdef01L)
    for (_ <- 1 until latency) {
      d.io.resp.valid.expect(false); d.io.ready.expect(false); d.io.busy.expect(true)
      d.clock.step()
    }
    for (_ <- 0 until 3) {
      d.io.resp.valid.expect(true); d.io.ready.expect(false)
      d.io.resp.bits.data.expect(expected & mask)
      d.io.resp.bits.tag.slot.expect(3); d.io.resp.bits.tag.generation.expect(5)
      d.io.resp.bits.seq.expect(17); d.io.resp.bits.nextPc.expect(0x104)
      flags.foreach(f => d.io.resp.bits.fflags.expect(f))
      d.clock.step()
    }
    d.io.resp.ready.poke(true); d.clock.step(); d.io.resp.ready.poke(false)
    d.io.busy.expect(false); d.io.resp.valid.expect(false)
  }
  test("registered multiplier covers all signed high halves and input/backpressure isolation") {
    simulate(new TimingUnitHarness("mul")) { d =>
      init(d)
      val random = new scala.util.Random(123)
      val cases = Seq((BigInt(0), mask), (mask, mask), (BigInt(1) << 31, mask)) ++
        Seq.fill(30)((BigInt(32, random), BigInt(32, random)))
      for ((a, b) <- cases) {
        run(d, UOpCode.MUL, a, b, a * b, 3)
        run(d, UOpCode.MULH, a, b, (signed(a) * signed(b)) >> 32, 3)
        run(d, UOpCode.MULHSU, a, b, (signed(a) * b) >> 32, 3)
        run(d, UOpCode.MULHU, a, b, (a * b) >> 32, 3)
      }
    }
  }
  test("34-cycle divider covers boundaries, random quotient/remainder and all flush phases") {
    simulate(new TimingUnitHarness("div")) { d =>
      init(d)
      val random = new scala.util.Random(456)
      val boundary = Seq(BigInt(0), BigInt(1), mask, BigInt(1) << 31, (BigInt(1) << 31) - 1)
      val cases = (for (a <- boundary; b <- boundary) yield (a, b)) ++
        Seq.fill(40)((BigInt(32, random), BigInt(32, random)))
      for ((a, b) <- cases; signedOp <- Seq(false, true)) {
        val x = if (signedOp) signed(a) else a
        val y = if (signedOp) signed(b) else b
        run(d, if (signedOp) UOpCode.DIV else UOpCode.DIVU, a, b,
          if (b == 0) mask else x / y, 34)
        run(d, if (signedOp) UOpCode.REM else UOpCode.REMU, a, b,
          if (b == 0) a else x % y, 34)
      }
      for (age <- Seq(1, 16, 33, 34)) {
        d.io.op.poke(UOpCode.DIVU); d.io.a.poke(99); d.io.b.poke(7)
        d.io.valid.poke(true); d.clock.step(); d.io.valid.poke(false)
        d.clock.step(age - 1)
        d.io.flush.poke(true); d.io.resp.valid.expect(false); d.io.ready.expect(false)
        d.clock.step(); d.io.flush.poke(false)
        d.clock.step(36); d.io.busy.expect(false); d.io.resp.valid.expect(false)
        run(d, UOpCode.DIVU, 100, 7, 14, 34)
      }
    }
  }
  test("six-cycle float core retains rounding, flags, conversion source and flush isolation") {
    simulate(new TimingUnitHarness("float")) { d =>
      init(d)
      run(d, UOpCode.FADD, 0x3f800000L, 0x40000000L, 0x40400000L, 6, Some(0))
      run(d, UOpCode.FSUB, 0x40400000L, 0x3f800000L, 0x40000000L, 6, Some(0))
      run(d, UOpCode.FMUL, 0x3fc00000L, 0x40000000L, 0x40400000L, 6, Some(0))
      run(d, UOpCode.FMUL, 1, 0x3f000000L, 0, 6, Some(3))
      run(d, UOpCode.FMUL, 0x7f800000L, 0, 0x7fc00000L, 6, Some(16))
      run(d, UOpCode.FCVTSW, -7, 0, 0xc0e00000L, 6, Some(0))
      run(d, UOpCode.FCVTSWU, mask, 0, 0x4f800000L, 6, Some(1))
      for (rm <- 0 to 4) {
        d.io.rm.poke(rm)
        run(d, UOpCode.FADD, 0x3f800000L, 0x33800000L,
          if (rm == 3 || rm == 4) 0x3f800001L else 0x3f800000L, 6, Some(1))
      }
      d.io.rm.poke(7); d.io.frm.poke(3)
      run(d, UOpCode.FADD, 0x3f800000L, 0x33800000L, 0x3f800001L, 6, Some(1))
      for (age <- 1 to 6) {
        d.io.op.poke(UOpCode.FMUL); d.io.valid.poke(true); d.clock.step()
        d.io.valid.poke(false); d.clock.step(age - 1)
        d.io.flush.poke(true); d.clock.step(); d.io.flush.poke(false)
        d.io.rm.poke(0)
        run(d, UOpCode.FADD, 0x3f800000L, 0x40000000L, 0x40400000L, 6, Some(0))
      }
    }
  }
  test("removed scalar/vector encodings trap and basic vector replacements decode") {
    simulate(new DecodeUnit(XJParams())) { d =>
      d.io.in.pc.poke(0); d.io.in.exception.poke(false); d.io.in.cause.poke(0); d.io.in.badAddr.poke(0)
      val removed = Seq(Rv32.fdivs(1, 2, 3), Rv32.fsqrts(1, 2)) ++
        Seq(0x43, 0x47, 0x4b, 0x4f).map(op => BigInt(op | (1 << 7))) ++
        Seq(0x57, 0x2f).map(op => BigInt(op | (2 << 12) | (1 << 25) | (1 << 7)))
      for (inst <- removed) {
        d.io.in.inst.poke(inst); d.io.out.exception.expect(true)
        d.io.out.cause.expect(FaultCode.illegal); d.io.out.badAddr.expect(inst)
        d.io.out.writesF.expect(false); d.io.out.writesV.expect(false)
      }
      for (f3 <- Seq(4, 5); scalar <- Seq(0, 1)) {
        d.io.in.inst.poke(BigInt(0x7b | (f3 << 12) | (scalar << 25) | (1 << 7)))
        d.io.out.exception.expect(false)
        d.io.out.vecOp.expect((if (f3 == 4) VecOp.vfsub32 else VecOp.vfmul32) + scalar)
        d.io.out.writesV.expect(true)
        d.io.out.usesF1.expect(scalar == 1)
      }
    }
  }
}
