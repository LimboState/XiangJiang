package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.issue.IssueUnit
import xiangjiang.backend.rename.RenameStage
import xiangjiang.backend.rob.Rob

class BackendSpec extends AnyFunSuite with ChiselSim {
  private case class Tag(slot: Int, generation: Int)
  private case class Allocation(tag: Tag, seq: Int)

  private def pokeTag(port: RobTag, tag: Tag): Unit = {
    port.slot.poke(tag.slot.U)
    port.generation.poke(tag.generation.U)
  }
  private def expectTag(port: RobTag, tag: Tag): Unit = {
    port.slot.expect(tag.slot.U)
    port.generation.expect(tag.generation.U)
  }
  private def pokeUop(port: MicroOp, tag: Tag = Tag(0, 0), seq: Int = 0,
      rd: Int = 0, rs1: Int = 0, rs2: Int = 0, uses1: Boolean = false,
      uses2: Boolean = false, exception: Boolean = false): Unit = {
    port.inst.poke("h00000013".U)
    port.pc.poke((seq * 4).U)
    port.op.poke(UOpCode.ADDI)
    port.fu.poke(FuType.alu)
    port.rs1.poke(rs1.U)
    port.rs2.poke(rs2.U)
    port.rd.poke(rd.U)
    port.usesRs1.poke(uses1.B)
    port.usesRs2.poke(uses2.B)
    port.writesRd.poke((rd != 0 && !exception).B)
    port.imm.poke(0.U)
    port.useImm.poke(false.B)
    port.barrier.poke(exception.B)
    port.exception.poke(exception.B)
    port.cause.poke((if (exception) 1 else 0).U)
    port.badAddr.poke((if (exception) 0x1234 else 0).U)
    pokeTag(port.tag, tag)
    port.seq.poke(seq.U)
  }
  private def pokeOperand(port: Operand, ready: Boolean = true, value: BigInt = 0,
      tag: Tag = Tag(0, 0)): Unit = {
    port.ready.poke(ready.B)
    port.value.poke(value.U)
    pokeTag(port.tag, tag)
  }
  private def pokeResult(port: ExeResult, allocation: Allocation = Allocation(Tag(0, 0), 0),
      data: BigInt = 0, exception: Boolean = false): Unit = {
    pokeTag(port.tag, allocation.tag)
    port.seq.poke(allocation.seq.U)
    port.data.poke(data.U)
    port.exception.poke(exception.B)
    port.cause.poke((if (exception) 5 else 0).U)
    port.badAddr.poke((if (exception) 0x1000 else 0).U)
    port.nextPc.poke((allocation.seq * 4 + 4).U)
  }
  private def pokeCommit(port: CommitRecord, tag: Tag = Tag(0, 0), rd: Int = 0,
      data: BigInt = 0, exception: Boolean = false): Unit = {
    pokeUop(port.uop, tag = tag, rd = rd)
    port.data.poke(data.U)
    port.exception.poke(exception.B)
    port.cause.poke((if (exception) 5 else 0).U)
    port.badAddr.poke(0.U)
    port.nextPc.poke(4.U)
  }
  private def initRob(dut: Rob): Unit = {
    dut.io.allocate.valid.poke(false.B)
    pokeUop(dut.io.allocate.bits)
    dut.io.retire.ready.poke(false.B)
    dut.io.wb.valid.poke(false.B)
    pokeResult(dut.io.wb.bits)
    dut.io.query.foreach(pokeTag(_, Tag(0, 0)))
    dut.io.flush.poke(false.B)
    dut.io.quiescent.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }
  private def allocate(dut: Rob, rd: Int = 0, exception: Boolean = false): Allocation = {
    val allocation = Allocation(
      Tag(dut.io.allocTag.slot.peek().litValue.toInt, dut.io.allocTag.generation.peek().litValue.toInt),
      dut.io.allocSeq.peek().litValue.toInt)
    pokeUop(dut.io.allocate.bits, allocation.tag, allocation.seq, rd, exception = exception)
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step()
    dut.io.allocate.valid.poke(false.B)
    allocation
  }
  private def complete(dut: Rob, allocation: Allocation, data: BigInt = 0,
      exception: Boolean = false): Unit = {
    pokeResult(dut.io.wb.bits, allocation, data, exception)
    dut.io.wb.valid.poke(true.B)
    dut.io.wbAccepted.expect(true.B)
    dut.clock.step()
    dut.io.wb.valid.poke(false.B)
  }
  private def retire(dut: Rob, allocation: Allocation, data: BigInt = 0): Unit = {
    dut.io.retire.valid.expect(true.B)
    expectTag(dut.io.retire.bits.uop.tag, allocation.tag)
    dut.io.retire.bits.uop.seq.expect(allocation.seq.U)
    dut.io.retire.bits.data.expect(data.U)
    dut.io.retire.bits.exception.expect(false.B)
    dut.io.retire.ready.poke(true.B)
    dut.clock.step()
    dut.io.retire.ready.poke(false.B)
  }

  test("ROB result capacity is independent and no-result operations still complete and retire") {
    simulate(new Rob(XJParams(nRob = 8, nXRes = 2))) { dut =>
      initRob(dut)
      val first = allocate(dut, rd = 1)
      val second = allocate(dut, rd = 2)
      pokeUop(dut.io.allocate.bits, rd = 3)
      dut.io.allocate.ready.expect(false.B)
      pokeTag(dut.io.query(0), first.tag)
      dut.io.values(0).ready.expect(false.B)

      val noResults = (0 until 6).map(_ => allocate(dut))
      dut.io.allocate.ready.expect(false.B)
      complete(dut, second, 22)
      dut.io.retire.valid.expect(false.B)
      complete(dut, first, 11)
      pokeTag(dut.io.query(0), first.tag)
      pokeTag(dut.io.query(1), second.tag)
      dut.io.values(0).ready.expect(true.B)
      dut.io.values(0).value.expect(11.U)
      dut.io.values(1).value.expect(22.U)
      pokeUop(dut.io.allocate.bits, rd = 3)
      dut.io.allocate.ready.expect(false.B)
      retire(dut, first, 11)
      dut.io.values(0).ready.expect(false.B)
      dut.io.allocate.ready.expect(true.B)
      retire(dut, second, 22)
      for (entry <- noResults) {
        dut.io.retire.valid.expect(false.B)
        complete(dut, entry, data = BigInt("deadbeef", 16))
        pokeTag(dut.io.query(0), entry.tag)
        dut.io.values(0).ready.expect(false.B)
        retire(dut, entry)
      }
      dut.io.empty.expect(true.B)
    }
  }

  test("ROB entry exhaustion stalls even when result slots remain available") {
    simulate(new Rob(XJParams(nRob = 2, nXRes = 4))) { dut =>
      initRob(dut)
      val first = allocate(dut, rd = 1)
      val second = allocate(dut, rd = 2)
      dut.io.allocate.ready.expect(false.B)
      complete(dut, second, 20)
      dut.io.retire.valid.expect(false.B)
      complete(dut, first, 10)
      retire(dut, first, 10)
      dut.io.allocate.ready.expect(true.B)
      val third = allocate(dut, rd = 3)
      assert(third.tag.slot == first.tag.slot)
      assert(third.tag.generation != first.tag.generation)
      complete(dut, third, 30)
      retire(dut, second, 20)
      retire(dut, third, 30)
    }
  }

  test("ROB rejects stale and duplicate broadcasts and reports precise head exceptions") {
    simulate(new Rob(XJParams(nRob = 2, nXRes = 2))) { dut =>
      initRob(dut)
      val old = allocate(dut, rd = 1)
      val spacer = allocate(dut)
      complete(dut, old, 7)
      pokeResult(dut.io.wb.bits, old, 99)
      dut.io.wb.valid.poke(true.B)
      dut.io.wbAccepted.expect(false.B)
      dut.clock.step()
      dut.io.wb.valid.poke(false.B)
      retire(dut, old, 7)
      complete(dut, spacer)
      retire(dut, spacer)
      val current = allocate(dut, rd = 2)
      val decodedFault = allocate(dut, exception = true)
      pokeResult(dut.io.wb.bits, old, 88)
      dut.io.wb.valid.poke(true.B)
      dut.io.wbAccepted.expect(false.B)
      dut.clock.step()
      dut.io.wb.valid.poke(false.B)
      dut.io.retire.valid.expect(false.B)
      pokeTag(dut.io.query(0), old.tag)
      dut.io.values(0).ready.expect(false.B)
      complete(dut, current, 23)
      pokeTag(dut.io.query(1), current.tag)
      dut.io.values(1).ready.expect(true.B)
      dut.io.values(1).value.expect(23.U)
      retire(dut, current, 23)
      dut.io.retire.valid.expect(true.B)
      expectTag(dut.io.retire.bits.uop.tag, decodedFault.tag)
      dut.io.retire.bits.exception.expect(true.B)
      dut.io.retire.bits.cause.expect(1.U)
      dut.io.retire.bits.badAddr.expect(0x1234.U)
      val younger = allocate(dut, rd = 3)
      complete(dut, younger, 42)
      dut.clock.step(3)
      expectTag(dut.io.retire.bits.uop.tag, decodedFault.tag)
      dut.io.retire.bits.exception.expect(true.B)
      dut.io.quiescent.poke(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.empty.expect(true.B)
      dut.io.retire.valid.expect(false.B)
      pokeTag(dut.io.query(0), younger.tag)
      dut.io.values(0).ready.expect(false.B)
    }
  }

  test("ROB handles allocation, different-entry writeback, and retirement together") {
    simulate(new Rob(XJParams(nRob = 4, nXRes = 3))) { dut =>
      initRob(dut)
      val first = allocate(dut, rd = 1)
      val second = allocate(dut, rd = 2)
      complete(dut, first, 101)
      val third = Allocation(Tag(2, 0), 2)
      pokeUop(dut.io.allocate.bits, third.tag, third.seq, rd = 3)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.ready.expect(true.B)
      pokeResult(dut.io.wb.bits, second, 202)
      dut.io.wb.valid.poke(true.B)
      dut.io.wbAccepted.expect(true.B)
      dut.io.retire.ready.poke(true.B)
      dut.io.retire.valid.expect(true.B)
      dut.io.retire.bits.data.expect(101.U)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)
      dut.io.wb.valid.poke(false.B)
      dut.io.retire.ready.poke(false.B)
      dut.io.retire.valid.expect(true.B)
      expectTag(dut.io.retire.bits.uop.tag, second.tag)
      dut.io.retire.bits.data.expect(202.U)
      complete(dut, third, exception = true)
      pokeTag(dut.io.query(0), third.tag)
      dut.io.values(0).ready.expect(false.B)
      retire(dut, second, 202)
      dut.io.retire.valid.expect(true.B)
      expectTag(dut.io.retire.bits.uop.tag, third.tag)
      dut.io.retire.bits.exception.expect(true.B)
      dut.io.retire.bits.cause.expect(5.U)
      dut.io.retire.bits.badAddr.expect(0x1000.U)
      dut.io.retire.bits.data.expect(0.U)
    }
  }

  test("generation exhaustion requires both an empty ROB and drained completion sources") {
    simulate(new Rob(XJParams(nRob = 2, nXRes = 1, generationBits = 1, seqBits = 5))) { dut =>
      initRob(dut)
      val initial = Seq(allocate(dut), allocate(dut))
      initial.foreach { entry => complete(dut, entry); retire(dut, entry) }
      val lastGeneration = Seq(allocate(dut), allocate(dut))
      assert(lastGeneration.map(_.tag.generation) == Seq(1, 1))
      complete(dut, lastGeneration.head)
      retire(dut, lastGeneration.head)
      dut.io.allocate.ready.expect(false.B)
      dut.io.quiescent.poke(true.B)
      dut.clock.step(2)
      dut.io.allocate.ready.expect(false.B)
      dut.io.empty.expect(false.B)
      complete(dut, lastGeneration.last)
      dut.io.quiescent.poke(false.B)
      retire(dut, lastGeneration.last)
      dut.io.empty.expect(true.B)
      dut.clock.step(3)
      dut.io.allocate.ready.expect(false.B)
      dut.io.quiescent.poke(true.B)
      dut.clock.step()
      dut.io.allocate.ready.expect(true.B)
      expectTag(dut.io.allocTag, Tag(0, 0))
      dut.io.allocSeq.expect(0.U)
      val afterDrain = allocate(dut, rd = 1)
      complete(dut, afterDrain, 55)
      retire(dut, afterDrain, 55)
    }
  }

  test("sequence exhaustion drains before unsigned age zero can be reused") {
    simulate(new Rob(XJParams(nRob = 2, nXRes = 1, generationBits = 4, seqBits = 3))) { dut =>
      initRob(dut)
      for (seq <- 0 until 8) {
        val entry = allocate(dut)
        assert(entry.seq == seq)
        complete(dut, entry)
        retire(dut, entry)
      }
      dut.io.empty.expect(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.allocate.ready.expect(false.B)
      dut.io.quiescent.poke(true.B)
      dut.clock.step()
      dut.io.allocSeq.expect(0.U)
      expectTag(dut.io.allocTag, Tag(0, 0))
      val next = allocate(dut)
      complete(dut, next)
      retire(dut, next)
    }
  }

  private def initRename(dut: RenameStage): Unit = {
    pokeUop(dut.io.uop)
    dut.io.dispatch.poke(false.B)
    dut.io.lookupValues.foreach(pokeOperand(_, ready = false))
    dut.io.wakeup.valid.poke(false.B)
    pokeResult(dut.io.wakeup.bits)
    dut.io.commit.valid.poke(false.B)
    pokeCommit(dut.io.commit.bits)
    dut.io.flush.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  test("rename captures old source versions and preserves younger WAW mappings at commit") {
    simulate(new RenameStage(XJParams())) { dut =>
      initRename(dut)
      val old = Tag(0, 0)
      val young = Tag(1, 0)
      val newest = Tag(2, 0)
      dut.io.debugRegs.foreach(_.expect(0.U))
      pokeUop(dut.io.uop, old, rd = 5)
      dut.io.dispatch.poke(true.B)
      dut.clock.step()
      pokeUop(dut.io.uop, young, rd = 5, rs1 = 5, uses1 = true)
      dut.io.renamed.src1.ready.expect(false.B)
      expectTag(dut.io.renamed.src1.tag, old)
      pokeResult(dut.io.wakeup.bits, Allocation(old, 0), 11)
      dut.io.wakeup.valid.poke(true.B)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(11.U)
      dut.io.debugRegs(5).expect(0.U)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      dut.io.wakeup.valid.poke(false.B)
      pokeUop(dut.io.uop, rs1 = 5, uses1 = true)
      expectTag(dut.io.lookupTags(0), young)
      dut.io.renamed.src1.ready.expect(false.B)

      pokeCommit(dut.io.commit.bits, old, rd = 5, data = 11)
      dut.io.commit.valid.poke(true.B)
      dut.io.renamed.src1.ready.expect(false.B)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.debugRegs(5).expect(11.U)
      expectTag(dut.io.lookupTags(0), young)
      dut.io.renamed.src1.ready.expect(false.B)
      pokeOperand(dut.io.lookupValues(0), ready = true, value = 22, tag = young)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(22.U)

      pokeUop(dut.io.uop, newest, rd = 5, rs1 = 5, uses1 = true)
      dut.io.dispatch.poke(true.B)
      pokeCommit(dut.io.commit.bits, young, rd = 5, data = 22)
      dut.io.commit.valid.poke(true.B)
      dut.io.renamed.src1.value.expect(22.U)
      expectTag(dut.io.renamed.src1.tag, young)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      dut.io.commit.valid.poke(false.B)
      pokeOperand(dut.io.lookupValues(0), ready = false)
      pokeUop(dut.io.uop, rs1 = 5, uses1 = true)
      dut.io.debugRegs(5).expect(22.U)
      dut.io.renamed.src1.ready.expect(false.B)
      expectTag(dut.io.renamed.src1.tag, newest)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(22.U)
      dut.io.debugRegs(5).expect(22.U)
    }
  }

  test("rename handles commit bypass, stale generations, x0, unused operands, and fault cleanup") {
    simulate(new RenameStage(XJParams())) { dut =>
      initRename(dut)
      val current = Tag(3, 2)
      pokeUop(dut.io.uop, rs1 = 7, uses1 = true)
      pokeCommit(dut.io.commit.bits, Tag(1, 0), rd = 7, data = 70)
      dut.io.commit.valid.poke(true.B)
      dut.io.renamed.src1.value.expect(70.U)
      dut.io.debugRegs(7).expect(0.U)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.debugRegs(7).expect(70.U)

      pokeUop(dut.io.uop, current, rd = 7)
      dut.io.dispatch.poke(true.B)
      dut.clock.step()
      dut.io.dispatch.poke(false.B)
      pokeUop(dut.io.uop, rs1 = 7, rs2 = 7, uses1 = true, uses2 = true)
      pokeOperand(dut.io.lookupValues(0), ready = true, value = 90, tag = Tag(3, 1))
      pokeResult(dut.io.wakeup.bits, Allocation(Tag(3, 1), 0), 90)
      dut.io.wakeup.valid.poke(true.B)
      dut.io.renamed.src1.ready.expect(false.B)
      dut.io.renamed.src2.ready.expect(false.B)
      pokeResult(dut.io.wakeup.bits, Allocation(current, 0), 90, exception = true)
      dut.io.renamed.src1.ready.expect(false.B)
      dut.io.wakeup.valid.poke(false.B)
      pokeCommit(dut.io.commit.bits, current, rd = 7, data = 77)
      dut.io.commit.valid.poke(true.B)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(77.U)
      dut.io.renamed.src2.value.expect(77.U)
      dut.clock.step()
      dut.io.commit.valid.poke(false.B)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(77.U)
      dut.io.debugRegs(7).expect(77.U)

      pokeUop(dut.io.uop, rs1 = 0, rs2 = 7, uses1 = true, uses2 = false)
      pokeCommit(dut.io.commit.bits, Tag(0, 0), rd = 0, data = 99)
      dut.io.commit.bits.uop.writesRd.poke(true.B)
      dut.io.commit.valid.poke(true.B)
      dut.io.uop.writesRd.poke(true.B)
      dut.io.dispatch.poke(true.B)
      dut.io.renamed.src1.ready.expect(true.B)
      dut.io.renamed.src1.value.expect(0.U)
      dut.io.renamed.src2.ready.expect(true.B)
      dut.io.renamed.src2.value.expect(0.U)
      dut.clock.step()
      dut.io.debugRegs(0).expect(0.U)
      dut.io.dispatch.poke(false.B)
      pokeCommit(dut.io.commit.bits, current, rd = 7, data = 999, exception = true)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.commit.valid.poke(false.B)
      dut.io.debugRegs(7).expect(77.U)
    }
  }

  private def pokeIssue(port: IssueEntry, allocation: Allocation,
      src1Ready: Boolean = true, src1Tag: Tag = Tag(0, 0),
      src2Ready: Boolean = true, src2Tag: Tag = Tag(0, 0)): Unit = {
    pokeUop(port.uop, allocation.tag, allocation.seq, rd = 1, rs1 = 2, rs2 = 3,
      uses1 = true, uses2 = true)
    pokeOperand(port.src1, src1Ready, 100 + allocation.seq, src1Tag)
    pokeOperand(port.src2, src2Ready, 200 + allocation.seq, src2Tag)
  }
  private def initIssue(dut: IssueUnit): Unit = {
    dut.io.dispatch.valid.poke(false.B)
    pokeIssue(dut.io.dispatch.bits, Allocation(Tag(0, 0), 0))
    dut.io.wakeup.valid.poke(false.B)
    pokeResult(dut.io.wakeup.bits)
    dut.io.issue.ready.poke(false.B)
    dut.io.head.valid.poke(false.B)
    pokeTag(dut.io.head.bits, Tag(0, 0))
    dut.io.flush.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }
  private def enqueue(dut: IssueUnit, allocation: Allocation,
      src1Ready: Boolean = true, src1Tag: Tag = Tag(0, 0),
      src2Ready: Boolean = true, src2Tag: Tag = Tag(0, 0)): Unit = {
    pokeIssue(dut.io.dispatch.bits, allocation, src1Ready, src1Tag, src2Ready, src2Tag)
    dut.io.dispatch.valid.poke(true.B)
    dut.io.dispatch.ready.expect(true.B)
    dut.clock.step()
    dut.io.dispatch.valid.poke(false.B)
  }

  test("issue selects ready age rather than slot and retains entries until acceptance") {
    simulate(new IssueUnit(XJParams(nIssue = 3))) { dut =>
      initIssue(dut)
      val producer = Tag(7, 1)
      val older = Allocation(Tag(5, 0), 10)
      val younger = Allocation(Tag(1, 0), 11)
      val youngest = Allocation(Tag(0, 0), 12)
      enqueue(dut, older, src1Ready = false, src1Tag = producer)
      enqueue(dut, younger, src2Ready = false, src2Tag = producer)
      enqueue(dut, youngest, src1Ready = false, src1Tag = producer)
      dut.io.dispatch.ready.expect(false.B)
      dut.io.issue.valid.expect(false.B)
      pokeResult(dut.io.wakeup.bits, Allocation(Tag(7, 0), 0), 999)
      dut.io.wakeup.valid.poke(true.B)
      dut.clock.step()
      dut.io.issue.valid.expect(false.B)
      pokeResult(dut.io.wakeup.bits, Allocation(producer, 0), 999, exception = true)
      dut.clock.step()
      dut.io.issue.valid.expect(false.B)
      pokeResult(dut.io.wakeup.bits, Allocation(producer, 0), 777)
      dut.io.issue.valid.expect(true.B)
      expectTag(dut.io.issue.bits.uop.tag, older.tag)
      dut.io.issue.bits.src1.value.expect(777.U)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.wakeup.valid.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      expectTag(dut.io.issue.bits.uop.tag, younger.tag)
      dut.io.issue.bits.src2.value.expect(777.U)
      dut.clock.step()
      val newest = Allocation(Tag(2, 0), 13)
      enqueue(dut, newest)
      expectTag(dut.io.issue.bits.uop.tag, younger.tag)
      dut.clock.step(2)
      expectTag(dut.io.issue.bits.uop.tag, younger.tag)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      expectTag(dut.io.issue.bits.uop.tag, youngest.tag)
      dut.clock.step()
      expectTag(dut.io.issue.bits.uop.tag, newest.tag)
      dut.clock.step()
      dut.io.empty.expect(true.B)
      dut.io.issue.valid.expect(false.B)
    }
  }

  test("issue skips an unready older entry and captures wakeup during simultaneous enqueue and issue") {
    simulate(new IssueUnit(XJParams(nIssue = 3))) { dut =>
      initIssue(dut)
      val producer = Tag(4, 2)
      val first = Allocation(Tag(0, 0), 0)
      val second = Allocation(Tag(1, 0), 1)
      val third = Allocation(Tag(2, 0), 2)
      enqueue(dut, first, src1Ready = false, src1Tag = producer)
      enqueue(dut, second)
      expectTag(dut.io.issue.bits.uop.tag, second.tag)
      dut.clock.step()
      pokeIssue(dut.io.dispatch.bits, third, src1Ready = false, src1Tag = producer,
        src2Ready = false, src2Tag = producer)
      dut.io.dispatch.valid.poke(true.B)
      dut.io.dispatch.ready.expect(true.B)
      pokeResult(dut.io.wakeup.bits, Allocation(producer, 0), 444)
      dut.io.wakeup.valid.poke(true.B)
      // A newly ready older instruction takes priority before acceptance.
      expectTag(dut.io.issue.bits.uop.tag, first.tag)
      dut.io.issue.bits.src1.value.expect(444.U)
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.poke(false.B)
      dut.io.wakeup.valid.poke(false.B)
      expectTag(dut.io.issue.bits.uop.tag, second.tag)
      dut.io.issue.bits.src1.value.expect(101.U)
      dut.clock.step()
      expectTag(dut.io.issue.bits.uop.tag, third.tag)
      dut.io.issue.bits.src1.ready.expect(true.B)
      dut.io.issue.bits.src1.value.expect(444.U)
      dut.io.issue.bits.src2.ready.expect(true.B)
      dut.io.issue.bits.src2.value.expect(444.U)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }

  test("head-only issue requires the complete ROB tag and flush frees a full single-entry station") {
    simulate(new IssueUnit(XJParams(nIssue = 1), headOnly = true)) { dut =>
      initIssue(dut)
      val branch = Allocation(Tag(2, 3), 6)
      enqueue(dut, branch)
      dut.io.dispatch.ready.expect(false.B)
      dut.io.issue.valid.expect(false.B)
      dut.io.head.valid.poke(true.B)
      pokeTag(dut.io.head.bits, Tag(2, 2))
      dut.io.issue.valid.expect(false.B)
      pokeTag(dut.io.head.bits, branch.tag)
      dut.io.issue.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.empty.expect(false.B)
      expectTag(dut.io.issue.bits.uop.tag, branch.tag)
      dut.io.flush.poke(true.B)
      dut.io.issue.valid.expect(false.B)
      dut.io.dispatch.ready.expect(false.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.io.empty.expect(true.B)
      dut.io.dispatch.ready.expect(true.B)
      dut.io.issue.valid.expect(false.B)
      enqueue(dut, branch)
      dut.io.issue.ready.poke(true.B)
      dut.io.issue.valid.expect(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
    }
  }
}
