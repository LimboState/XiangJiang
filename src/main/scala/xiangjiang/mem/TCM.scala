package xiangjiang.mem

import chisel3._
import chisel3.util._
import xiangjiang._

/** One physical read/write port; contents deliberately have no reset path. */
class InstructionTcm(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val write = Input(Bool())
    val word = Input(UInt(17.W))
    val data = Input(UInt(32.W))
    val mask = Input(UInt(4.W))
    val readData = Output(UInt(32.W))
  })
  val memory = SyncReadMem(p.itcmBytes / 4, Vec(4, UInt(8.W)))
  io.readData := memory.readWrite(io.word, io.data.asTypeOf(Vec(4, UInt(8.W))),
    io.mask.asBools, io.enable, io.write).asUInt
}

class BankRead(p: XJParams) extends Bundle {
  val row = UInt(log2Ceil(p.bankBytes / 64).W)
}
class BankWrite(p: XJParams) extends BankRead(p) {
  val data = UInt(512.W)
  val mask = UInt(64.W)
}

/** Four independently addressable 512-bit 1R1W SRAM banks, byte write enabled.
  * Clients arbitrate same-row read/write collisions before granting requests.
  */
class DataTcm(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val read = Input(Vec(4, Valid(new BankRead(p))))
    val write = Input(Vec(4, Valid(new BankWrite(p))))
    val readData = Output(Vec(4, UInt(512.W)))
  })
  for (bank <- 0 until 4) {
    val memory = SyncReadMem(p.bankBytes / 64, Vec(64, UInt(8.W)))
    io.readData(bank) := memory.read(io.read(bank).bits.row, io.read(bank).valid).asUInt
    when(io.write(bank).valid) {
      memory.write(io.write(bank).bits.row,
        io.write(bank).bits.data.asTypeOf(Vec(64, UInt(8.W))),
        io.write(bank).bits.mask.asBools)
    }
  }
}
