package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.decode.DecodeUnit

class DmaDecodeSpec extends AnyFunSuite with ChiselSim {
  test("all DMA reserved bits are strict and configuration is a dispatch barrier") {
    simulate(new DecodeUnit(XJParams())) { c =>
      c.io.in.pc.poke(0); c.io.in.exception.poke(false); c.io.in.cause.poke(0); c.io.in.badAddr.poke(0)
      val ops = Seq(UOpCode.DSETSIZE, UOpCode.DSETSTRIDE, UOpCode.MVIN, UOpCode.MVOUT)
      for (f3 <- 3 to 6; f7 <- 0 until 128; rd <- Seq(0, 1, 31)) {
        val inst = (BigInt(f7) << 25) | (11 << 20) | (7 << 15) | (f3 << 12) | (rd << 7) | 0x0b
        c.io.in.inst.poke(inst)
        val legal = f7 == 0 && rd == 0
        c.io.out.exception.expect(!legal)
        c.io.out.writesRd.expect(false); c.io.out.writesV.expect(false)
        if (legal) {
          c.io.out.op.expect(ops(f3 - 3)); c.io.out.rs1.expect(7); c.io.out.rs2.expect(11)
          c.io.out.usesRs1.expect(true); c.io.out.usesRs2.expect(true)
          c.io.out.barrier.expect(f3 <= 4)
          c.io.out.fu.expect(if (f3 <= 4) FuType.dmaConfig else FuType.mem)
        }
      }
    }
  }
}
