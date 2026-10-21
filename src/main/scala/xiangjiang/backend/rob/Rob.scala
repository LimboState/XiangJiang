package xiangjiang.backend.rob

import chisel3._
import chisel3.util._
import xiangjiang._

/** In-order retirement with independently allocated scalar result storage. */
class Rob(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new MicroOp(p)))
    val allocTag = Output(new RobTag(p))
    val allocSeq = Output(UInt(p.seqBits.W))
    val head = Output(Valid(new MicroOp(p)))
    val retire = Decoupled(new CommitRecord(p))
    val wb = Input(Valid(new ExeResult(p)))
    val wbAccepted = Output(Bool())
    val query = Input(Vec(2, new RobTag(p)))
    val values = Output(Vec(2, new Operand(p)))
    val flush = Input(Bool())
    val quiescent = Input(Bool())
    val empty = Output(Bool())
  })

  private val resultBits = log2Ceil(p.nXRes)
  private val maxGeneration = ((BigInt(1) << p.generationBits) - 1).U
  private val maxSequence = ((BigInt(1) << p.seqBits) - 1).U
  val valid = RegInit(VecInit(Seq.fill(p.nRob)(false.B)))
  val done = RegInit(VecInit(Seq.fill(p.nRob)(false.B)))
  val uops = Reg(Vec(p.nRob, new MicroOp(p)))
  val hasResult = Reg(Vec(p.nRob, Bool()))
  val resultIndex = Reg(Vec(p.nRob, UInt(resultBits.W)))
  val exception = Reg(Vec(p.nRob, Bool()))
  val cause = Reg(Vec(p.nRob, UInt(4.W)))
  val badAddr = Reg(Vec(p.nRob, UInt(32.W)))
  val nextPc = Reg(Vec(p.nRob, UInt(32.W)))
  val resultUsed = RegInit(VecInit(Seq.fill(p.nXRes)(false.B)))
  val resultData = Reg(Vec(p.nXRes, UInt(32.W)))

  val headIndex = RegInit(0.U(p.robBits.W))
  val tailIndex = RegInit(0.U(p.robBits.W))
  val count = RegInit(0.U(log2Ceil(p.nRob + 1).W))
  val nextGeneration = RegInit(VecInit(Seq.fill(p.nRob)(0.U(p.generationBits.W))))
  val generationExhausted = RegInit(VecInit(Seq.fill(p.nRob)(false.B)))
  val nextSequence = RegInit(0.U(p.seqBits.W))
  val sequenceExhausted = RegInit(false.B)

  val needsResult = io.allocate.bits.writesRd && io.allocate.bits.rd =/= 0.U &&
    !io.allocate.bits.exception
  val freeResults = ~resultUsed.asUInt
  val freeResultIndex = Wire(UInt(resultBits.W))
  freeResultIndex := PriorityEncoder(freeResults)
  val mustDrain = sequenceExhausted || generationExhausted(tailIndex)
  io.empty := count === 0.U
  io.allocTag.slot := tailIndex
  io.allocTag.generation := nextGeneration(tailIndex)
  io.allocSeq := nextSequence
  io.allocate.ready := !io.flush && !mustDrain && count =/= p.nRob.U &&
    (!needsResult || freeResults.orR)

  io.head.valid := !io.empty && !io.flush
  io.head.bits := uops(headIndex)
  io.retire.valid := io.head.valid && done(headIndex)
  io.retire.bits.uop := uops(headIndex)
  io.retire.bits.data := Mux(hasResult(headIndex) && !exception(headIndex),
    resultData(resultIndex(headIndex)), 0.U)
  io.retire.bits.exception := exception(headIndex)
  io.retire.bits.cause := cause(headIndex)
  io.retire.bits.badAddr := badAddr(headIndex)
  io.retire.bits.nextPc := nextPc(headIndex)

  val wbSlot = io.wb.bits.tag.slot
  io.wbAccepted := io.wb.valid && !io.flush && valid(wbSlot) && !done(wbSlot) &&
    TagMatch(uops(wbSlot).tag, io.wb.bits.tag)

  for (port <- 0 until 2) {
    val slot = io.query(port).slot
    val readable = !io.flush && valid(slot) && done(slot) && hasResult(slot) &&
      !exception(slot) && TagMatch(uops(slot).tag, io.query(port))
    io.values(port).tag := io.query(port)
    io.values(port).ready := readable
    io.values(port).value := Mux(readable, resultData(resultIndex(slot)), 0.U)
  }

  when(io.wbAccepted) {
    done(wbSlot) := true.B
    exception(wbSlot) := io.wb.bits.exception
    cause(wbSlot) := io.wb.bits.cause
    badAddr(wbSlot) := io.wb.bits.badAddr
    nextPc(wbSlot) := io.wb.bits.nextPc
    when(hasResult(wbSlot) && !io.wb.bits.exception) {
      resultData(resultIndex(wbSlot)) := io.wb.bits.data
    }
  }

  when(io.retire.fire) {
    valid(headIndex) := false.B
    done(headIndex) := false.B
    headIndex := headIndex + 1.U
    when(hasResult(headIndex)) {
      resultUsed(resultIndex(headIndex)) := false.B
    }
  }

  when(io.allocate.fire) {
    valid(tailIndex) := true.B
    done(tailIndex) := io.allocate.bits.exception
    uops(tailIndex) := io.allocate.bits
    hasResult(tailIndex) := needsResult
    resultIndex(tailIndex) := freeResultIndex
    exception(tailIndex) := io.allocate.bits.exception
    cause(tailIndex) := io.allocate.bits.cause
    badAddr(tailIndex) := io.allocate.bits.badAddr
    nextPc(tailIndex) := io.allocate.bits.pc + 4.U
    tailIndex := tailIndex + 1.U
    when(needsResult) {
      resultUsed(freeResultIndex) := true.B
    }
    when(nextGeneration(tailIndex) === maxGeneration) {
      generationExhausted(tailIndex) := true.B
    }.otherwise {
      nextGeneration(tailIndex) := nextGeneration(tailIndex) + 1.U
    }
    when(nextSequence === maxSequence) {
      sequenceExhausted := true.B
    }.otherwise {
      nextSequence := nextSequence + 1.U
    }
  }

  when(io.allocate.fire =/= io.retire.fire) {
    count := Mux(io.allocate.fire, count + 1.U, count - 1.U)
  }

  // Exhausted counters never silently wrap. Quiescence includes every external
  // completion source; empty alone cannot make a recycled tag unambiguous.
  when((mustDrain && io.empty && io.quiescent) || io.flush) {
    nextGeneration.foreach(_ := 0.U)
    generationExhausted.foreach(_ := false.B)
    nextSequence := 0.U
    sequenceExhausted := false.B
    headIndex := 0.U
    tailIndex := 0.U
  }
  when(io.flush) {
    assert(io.quiescent, "ROB flush requires drained completion sources")
    valid.foreach(_ := false.B)
    done.foreach(_ := false.B)
    resultUsed.foreach(_ := false.B)
    count := 0.U
  }
}
