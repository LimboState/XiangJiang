package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.decode.DecodeUnit

class DmaDecodeSpec extends AnyFunSuite with ChiselSim {
  test("DMA owns only funct3=3 funct7=16..19 rd=0 and captures both scalar sources") {
    simulate(new DecodeUnit(XJParams())) { d =>
      d.io.in.pc.poke(0); d.io.in.exception.poke(false)
      d.io.in.cause.poke(0); d.io.in.badAddr.poke(0)
      for (f7 <- 0 until 128; rd <- Seq(0, 1, 31)) {
        val inst = (BigInt(f7) << 25) | (9 << 20) | (7 << 15) | (3 << 12) | (rd << 7) | 0x2b
        d.io.in.inst.poke(inst)
        val legal = f7 >= 16 && f7 <= 19 && rd == 0
        d.io.out.exception.expect(!legal)
        d.io.out.writesRd.expect(false); d.io.out.writesV.expect(false)
        d.io.out.usesRs1.expect(legal); d.io.out.usesRs2.expect(legal)
        if (legal) {
          d.io.out.op.expect(UOpCode.DMA); d.io.out.fu.expect(FuType.mem)
          d.io.out.dmaOp.expect(f7 - 16); d.io.out.barrier.expect(f7 < 18)
          d.io.out.rs1.expect(7); d.io.out.rs2.expect(9)
        }
      }
      d.io.in.inst.poke(DmaEncoding(2, 0, 0))
      d.io.out.exception.expect(false)
      d.io.in.exception.poke(true); d.io.in.cause.poke(3); d.io.in.badAddr.poke(0x80000)
      d.io.out.exception.expect(true); d.io.out.cause.expect(3)
      d.io.out.usesRs1.expect(false); d.io.out.usesRs2.expect(false)
    }
  }
}
