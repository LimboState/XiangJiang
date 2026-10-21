package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.mem.LSU

class LSUSpec extends AnyFunSuite with ChiselSim {
  test("separates address and data dependencies and retains ordered completion under backpressure") {
    val p = XJParams(nMemq = 3)
    simulate(new LSU(p)) { dut =>
      dut.io.allocate.valid.poke(false); dut.io.wakeup.valid.poke(false)
      dut.io.head.valid.poke(false); dut.io.head.bits.slot.poke(0); dut.io.head.bits.generation.poke(1)
      dut.io.request.ready.poke(false); dut.io.response.valid.poke(false)
      dut.io.response.bits.data.poke(0); dut.io.response.bits.error.poke(false)
      dut.io.result.ready.poke(false); dut.io.stop.poke(false); dut.io.flush.poke(false)
      val w = dut.io.wakeup.bits
      w.tag.slot.poke(7); w.tag.generation.poke(1); w.seq.poke(0)
      w.data.poke(BigInt("deadbeef", 16)); w.exception.poke(false); w.cause.poke(0)
      w.badAddr.poke(0); w.nextPc.poke(0)
      def enqueue(slot: Int, store: Boolean, addr: Int, waitingData: Boolean = false): Unit = {
        val e = dut.io.allocate.bits
        e.uop.inst.poke(0); e.uop.pc.poke(slot * 4)
        e.uop.op.poke(if (store) UOpCode.SW else UOpCode.LW); e.uop.fu.poke(FuType.mem)
        e.uop.rs1.poke(0); e.uop.rs2.poke(if (store) 1 else 0); e.uop.rd.poke(0)
        e.uop.usesRs1.poke(true); e.uop.usesRs2.poke(store); e.uop.writesRd.poke(false)
        e.uop.imm.poke(addr); e.uop.useImm.poke(true); e.uop.barrier.poke(false)
        e.uop.exception.poke(false); e.uop.cause.poke(0); e.uop.badAddr.poke(0)
        e.uop.tag.slot.poke(slot); e.uop.tag.generation.poke(1); e.uop.seq.poke(slot)
        e.src1.ready.poke(true); e.src1.value.poke(0); e.src1.tag.slot.poke(0); e.src1.tag.generation.poke(0)
        e.src2.ready.poke(!waitingData); e.src2.value.poke(0); e.src2.tag.slot.poke(7); e.src2.tag.generation.poke(1)
        dut.io.allocate.valid.poke(true); dut.io.allocate.ready.expect(true)
        dut.clock.step(); dut.io.allocate.valid.poke(false)
      }
      enqueue(0, store = true, addr = 64, waitingData = true)
      dut.io.addressIssued.valid.expect(true); dut.io.addressIssued.bits.slot.expect(0)
      dut.clock.step()
      enqueue(1, store = false, addr = 128)
      enqueue(2, store = false, addr = 192)
      dut.clock.step(3)
      dut.io.request.valid.expect(false) // younger load cannot bypass unready store
      dut.io.head.valid.poke(true)
      w.tag.generation.poke(0); dut.io.wakeup.valid.poke(true); dut.clock.step()
      dut.io.wakeup.valid.poke(false); dut.clock.step(); dut.io.request.valid.expect(false)
      w.tag.generation.poke(1); dut.io.wakeup.valid.poke(true); dut.clock.step()
      dut.io.wakeup.valid.poke(false)
      dut.io.request.valid.expect(true); dut.io.request.bits.write.expect(true)
      dut.io.request.bits.addr.expect(64); dut.io.request.bits.data.expect(BigInt("deadbeef", 16))
      dut.clock.step(3); dut.io.request.bits.data.expect(BigInt("deadbeef", 16))
      dut.io.request.ready.poke(true); dut.clock.step(); dut.io.request.ready.poke(false)
      dut.io.request.valid.expect(false); dut.io.result.valid.expect(false); dut.io.quiescent.expect(false)
      dut.clock.step(3)
      dut.io.response.valid.poke(true); dut.io.response.ready.expect(true)
      dut.clock.step(); dut.io.response.valid.poke(false)
      dut.io.result.valid.expect(true); dut.io.result.bits.tag.slot.expect(0)
      dut.io.result.bits.exception.expect(false)
      dut.clock.step(3); dut.io.request.valid.expect(false); dut.io.result.valid.expect(true)
      dut.io.result.ready.poke(true); dut.clock.step(); dut.io.result.ready.poke(false)
      dut.io.request.valid.expect(true); dut.io.request.bits.addr.expect(128)
      dut.io.request.bits.write.expect(false)
      dut.io.request.ready.poke(true); dut.clock.step(); dut.io.request.ready.poke(false)
      dut.io.response.bits.error.poke(true); dut.io.response.valid.poke(true)
      dut.clock.step(); dut.io.response.valid.poke(false)
      dut.io.result.valid.expect(true); dut.io.result.bits.exception.expect(true)
      dut.io.result.bits.cause.expect(5); dut.io.result.bits.tag.slot.expect(1)
      dut.io.result.ready.poke(true); dut.clock.step(); dut.io.result.ready.poke(false)
      dut.clock.step(4); dut.io.request.valid.expect(false); dut.io.empty.expect(false)
      dut.io.quiescent.expect(true)
      dut.io.flush.poke(true); dut.clock.step(); dut.io.flush.poke(false)
      dut.io.empty.expect(true)
      enqueue(0, store = false, addr = 2)
      dut.clock.step(3)
      dut.io.request.valid.expect(false); dut.io.result.valid.expect(true)
      dut.io.result.bits.exception.expect(true); dut.io.result.bits.cause.expect(4)
      dut.io.result.bits.badAddr.expect(2)
    }
  }
}
