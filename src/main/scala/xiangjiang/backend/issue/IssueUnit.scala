package xiangjiang.backend.issue

import chisel3._
import chisel3.util._
import xiangjiang._

/** Reservation station selecting the oldest ready sequence number, not slot. */
class IssueUnit(p: XJParams, headOnly: Boolean = false, vectorEnabled: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val dispatch = Flipped(Decoupled(new IssueEntry(p)))
    val wakeup = Input(Valid(new ExeResult(p)))
    val issue = Decoupled(new IssueEntry(p))
    val head = Input(Valid(new RobTag(p)))
    val flush = Input(Bool())
    val empty = Output(Bool())
  })

  private val indexBits = log2Ceil(p.nIssue)
  val valid = RegInit(VecInit(Seq.fill(p.nIssue)(false.B)))
  val entries = Reg(Vec(p.nIssue, new IssueEntry(p)))

  def captureWakeup(entry: IssueEntry): IssueEntry = {
    val captured = WireDefault(entry)
    for ((source, target) <- Seq(entry.src1 -> captured.src1, entry.src2 -> captured.src2)) {
      when(!source.ready && io.wakeup.valid && !io.wakeup.bits.exception &&
        TagMatch(source.tag, io.wakeup.bits.tag)) {
        target.ready := true.B
        target.value := io.wakeup.bits.data(31, 0)
      }
    }
    if (vectorEnabled) {
      for ((source, target) <- Seq(entry.vsrc1 -> captured.vsrc1, entry.vsrc2 -> captured.vsrc2,
        entry.vold -> captured.vold, entry.vmask -> captured.vmask)) {
        when(!source.ready && io.wakeup.valid && !io.wakeup.bits.exception &&
          TagMatch(source.tag, io.wakeup.bits.tag)) {
          target.ready := true.B
          target.value := io.wakeup.bits.data
        }
      }
    } else {
      // Scalar stations do not retain vector payloads merely to share the FU port.
      for (operand <- Seq(captured.vsrc1, captured.vsrc2, captured.vold, captured.vmask)) {
        operand := 0.U.asTypeOf(new VectorOperand(p))
        operand.ready := true.B
      }
    }
    captured
  }

  val awakened = Wire(Vec(p.nIssue, new IssueEntry(p)))
  val eligible = Wire(Vec(p.nIssue, Bool()))
  for (i <- 0 until p.nIssue) {
    awakened(i) := captureWakeup(entries(i))
    val atHead = if (headOnly) io.head.valid && TagMatch(entries(i).uop.tag, io.head.bits) else true.B
    val vectorReady = if (vectorEnabled) {
      (!entries(i).uop.usesV1 || awakened(i).vsrc1.ready) &&
        (!entries(i).uop.usesV2 || awakened(i).vsrc2.ready) &&
        (!entries(i).uop.usesVOld || awakened(i).vold.ready) &&
        (!entries(i).uop.usesVMask || awakened(i).vmask.ready)
    } else true.B
    eligible(i) := valid(i) && awakened(i).src1.ready && awakened(i).src2.ready && vectorReady && atHead
  }

  var found: Bool = false.B
  var oldest: UInt = 0.U(indexBits.W)
  var oldestSequence: UInt = 0.U(p.seqBits.W)
  for (i <- 0 until p.nIssue) {
    val choose = eligible(i) && (!found || entries(i).uop.seq < oldestSequence)
    oldest = Mux(choose, i.U(indexBits.W), oldest)
    oldestSequence = Mux(choose, entries(i).uop.seq, oldestSequence)
    found = found || eligible(i)
  }
  val selected = oldest
  io.issue.valid := !io.flush && found
  io.issue.bits := awakened(selected)
  io.dispatch.ready := !io.flush && !valid.asUInt.andR
  io.empty := !valid.asUInt.orR
  val freeIndex = Wire(UInt(indexBits.W))
  freeIndex := PriorityEncoder(~valid.asUInt)

  for (i <- 0 until p.nIssue) {
    when(valid(i)) {
      entries(i).src1 := awakened(i).src1
      entries(i).src2 := awakened(i).src2
      if (vectorEnabled) {
        entries(i).vsrc1 := awakened(i).vsrc1
        entries(i).vsrc2 := awakened(i).vsrc2
        entries(i).vold := awakened(i).vold
        entries(i).vmask := awakened(i).vmask
      }
    }
  }
  when(io.issue.fire) {
    valid(selected) := false.B
  }
  when(io.dispatch.fire) {
    entries(freeIndex) := captureWakeup(io.dispatch.bits)
    valid(freeIndex) := true.B
  }
  when(io.flush) {
    valid.foreach(_ := false.B)
  }
}
