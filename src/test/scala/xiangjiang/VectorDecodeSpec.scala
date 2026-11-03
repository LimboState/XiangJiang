package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.decode.DecodeUnit

class VectorDecodeSpec extends AnyFunSuite with ChiselSim {
  test("XJ-V funct6/form legality and implicit source metadata") {
    simulate(new DecodeUnit(XJParams())) { c =>
      c.io.in.pc.poke(0); c.io.in.exception.poke(false); c.io.in.cause.poke(0); c.io.in.badAddr.poke(0)
      for (f6 <- 0 until 64; f3 <- 0 until 8; vm <- 0 to 1; rs1 <- Seq(0, 7)) {
        val paired = f6 <= 0x11 || (f6 >= 0x1b && f6 <= 0x22)
        val unary = f6 >= 0x12 && f6 <= 0x15
        val slide = f6 == 0x16 || f6 == 0x17
        val reduce = f6 == 0x19 || f6 == 0x1a
        val legal = (paired && (f3 == 0 || f3 == 4)) ||
          (unary && f3 == 2 && rs1 == 0) || (slide && (f3 == 3 || f3 == 4)) ||
          (f6 == 0x18 && f3 == 0) || (reduce && f3 == 1) || (f6 == 0x23 && f3 == 0 && vm == 0)
        val inst = (BigInt(f6) << 26) | (vm << 25) | (3 << 20) | (rs1 << 15) | (f3 << 12) | 0x57
        c.io.in.inst.poke(inst)
        withClue(s"funct6=$f6 funct3=$f3 vm=$vm rs1=$rs1: ") {
          c.io.out.exception.expect(!legal)
          c.io.out.writesRd.expect(false)
          c.io.out.writesV.expect(legal)
          if (legal) {
            c.io.out.maskReg.expect(0)
            c.io.out.usesVMask.expect(vm == 0)
            c.io.out.usesRs1.expect(f3 == 4)
            c.io.out.usesV1.expect(f3 == 0 || reduce)
            c.io.out.usesV2.expect(true)
            c.io.out.usesVOld.expect(reduce || (f6 != 0x23 && (vm == 0 || f6 == 0x0f)))
          }
        }
      }
      for (offset <- Seq(-2048, -32, 0, 32, 2016); store <- Seq(false, true)) {
        val inst = VectorEncoding.instruction(if (store) 60 else 59, 9, 7, offset)
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(false); c.io.out.fu.expect(FuType.mem)
        c.io.out.imm.expect(BigInt(offset) & BigInt("ffffffff", 16))
        c.io.out.unmasked.expect(true); c.io.out.usesVMask.expect(false)
        c.io.out.usesVOld.expect(store); c.io.out.writesV.expect(!store)
        if (store) c.io.out.rs2.expect(9) else c.io.out.rd.expect(9)
      }
      // Removed opcode groups and reserved DMA fields never alias legal instructions.
      for (inst <- Seq(0x07, 0x27, 0x2f, 0x308b, 0x408b, 0x508b, 0x608b)) {
        c.io.in.inst.poke(inst); c.io.out.exception.expect(true)
      }
    }
  }
}
