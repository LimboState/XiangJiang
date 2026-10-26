package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.mem.LSU

class DmaOrderingSpec extends AnyFunSuite with ChiselSim {
  test("DMA requires full ROB-head tag, captured operands and whole-command completion before younger memory") {
    simulate(new LSU(XJParams(nMemq = 2))) { d =>
      d.io.allocate.valid.poke(false); d.io.wakeup.valid.poke(false)
      d.io.head.valid.poke(false); d.io.stop.poke(false); d.io.flush.poke(false)
      d.io.request.ready.poke(false); d.io.response.valid.poke(false)
      d.io.matrixTask.ready.poke(false); d.io.matrixDone.poke(false)
      d.io.dmaCommand.ready.poke(false); d.io.dmaCompletion.valid.poke(false)
      d.io.dmaBusy.poke(false); d.io.result.ready.poke(false)
      def enqueue(slot: Int, op: UOpCode.Type, dmaOp: Int, x: Int, y: Int, waitY: Boolean = false): Unit = {
        val e = d.io.allocate.bits
        e.uop.op.poke(op); e.uop.dmaOp.poke(dmaOp); e.uop.pc.poke(slot * 4)
        e.uop.tag.slot.poke(slot); e.uop.tag.generation.poke(1); e.uop.seq.poke(slot)
        e.uop.imm.poke(0); e.uop.usesVOld.poke(false); e.uop.usesVMask.poke(false)
        e.src1.ready.poke(true); e.src1.value.poke(x)
        e.src2.ready.poke(!waitY); e.src2.value.poke(y)
        e.src2.tag.slot.poke(7); e.src2.tag.generation.poke(1)
        d.io.allocate.valid.poke(true); d.io.allocate.ready.expect(true)
        d.clock.step(); d.io.allocate.valid.poke(false); d.clock.step(2)
      }
      def head(slot: Int, gen: Int = 1): Unit = {
        d.io.head.valid.poke(true); d.io.head.bits.slot.poke(slot); d.io.head.bits.generation.poke(gen)
      }
      def consume(): Unit = {
        d.io.result.valid.expect(true); d.io.result.ready.poke(true)
        d.clock.step(); d.io.result.ready.poke(false)
      }
      head(0)
      enqueue(0, UOpCode.DMA, 0, 128, 2); consume()
      head(1)
      enqueue(1, UOpCode.DMA, 1, 256, 128); consume()
      head(0, 0)
      enqueue(0, UOpCode.DMA, 2, 4092, 60, waitY = true)
      enqueue(1, UOpCode.LW, 0, 64, 0)
      d.io.dmaCommand.valid.expect(false); d.io.request.valid.expect(false)
      val w = d.io.wakeup.bits
      w.tag.slot.poke(7); w.tag.generation.poke(0); w.exception.poke(false); w.data.poke(60)
      d.io.wakeup.valid.poke(true); d.clock.step()
      w.tag.generation.poke(1); d.clock.step(); d.io.wakeup.valid.poke(false)
      d.io.dmaCommand.valid.expect(false) // wrong ROB head generation, despite ready operands
      head(0)
      d.io.dmaCommand.valid.expect(true)
      for (_ <- 0 until 5) {
        d.io.dmaCommand.bits.external.expect(4092); d.io.dmaCommand.bits.local.expect(60)
        d.io.dmaCommand.bits.shape.rowBytes.expect(128); d.io.dmaCommand.bits.shape.rows.expect(2)
        d.io.dmaCommand.bits.shape.externalStride.expect(256); d.io.dmaCommand.bits.shape.localStride.expect(128)
        d.io.request.valid.expect(false); d.clock.step()
      }
      d.io.dmaCommand.ready.poke(true); d.clock.step(); d.io.dmaCommand.ready.poke(false)
      d.io.dmaBusy.poke(true)
      d.clock.step(8); d.io.quiescent.expect(false); d.io.empty.expect(false)
      d.io.request.valid.expect(false); d.io.result.valid.expect(false)
      d.io.dmaCompletion.bits.cause.poke(0); d.io.dmaCompletion.bits.address.poke(0)
      d.io.dmaCompletion.bits.bytesCompleted.poke(256); d.io.dmaCompletion.bits.partial.poke(false)
      d.io.dmaCompletion.valid.poke(true); d.io.dmaCompletion.ready.expect(true)
      d.clock.step(); d.io.dmaCompletion.valid.poke(false); d.io.dmaBusy.poke(false)
      d.io.result.bits.tag.slot.expect(0); d.io.result.bits.tag.generation.expect(1)
      d.clock.step(4); d.io.request.valid.expect(false)
      consume()
      d.io.request.valid.expect(true); d.io.request.bits.addr.expect(64)
      d.io.request.ready.poke(true); d.clock.step(); d.io.request.ready.poke(false)
      d.io.response.bits.data.poke(123); d.io.response.bits.error.poke(false)
      d.io.response.valid.poke(true); d.clock.step(); d.io.response.valid.poke(false); consume()
      d.io.empty.expect(true)
      // Busy is independently checked, even with no queued entries.
      d.io.dmaBusy.poke(true); d.io.empty.expect(false); d.io.quiescent.expect(false)
      d.io.dmaBusy.poke(false)
      enqueue(0, UOpCode.DMA, 3, 8192, 60)
      d.io.dmaCommand.ready.poke(true); d.clock.step(); d.io.dmaCommand.ready.poke(false)
      d.io.dmaBusy.poke(true)
      enqueue(1, UOpCode.SW, 0, 64, 99)
      d.io.stop.poke(true) // stop cannot discard an already accepted DMA
      d.io.dmaCompletion.bits.cause.poke(14); d.io.dmaCompletion.bits.address.poke(8192)
      d.io.dmaCompletion.valid.poke(true); d.io.dmaCompletion.ready.expect(true)
      d.clock.step(); d.io.dmaCompletion.valid.poke(false); d.io.dmaBusy.poke(false)
      d.io.result.bits.exception.expect(true); d.io.result.bits.cause.expect(14); consume()
      d.io.stop.poke(false); head(1); d.clock.step(4)
      d.io.request.valid.expect(false); d.io.empty.expect(false); d.io.quiescent.expect(true)
      d.io.flush.poke(true); d.clock.step(); d.io.flush.poke(false); d.io.empty.expect(true)
    }
  }
}
