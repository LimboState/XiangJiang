package device

import chisel3._
import chisel3.util._

class AxiAddress(idBits: Int) extends Bundle {
  val id = UInt(idBits.W)
  val addr = UInt(32.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val lock = UInt(1.W)
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
}
class AxiWriteData extends Bundle {
  val data = UInt(32.W)
  val strb = UInt(4.W)
  val last = Bool()
}
class AxiWriteResponse(idBits: Int) extends Bundle {
  val id = UInt(idBits.W)
  val resp = UInt(2.W)
}
class AxiReadData(idBits: Int) extends Bundle {
  val id = UInt(idBits.W)
  val data = UInt(32.W)
  val resp = UInt(2.W)
  val last = Bool()
}
class Axi4Slave(idBits: Int) extends Bundle {
  val aw = Flipped(Decoupled(new AxiAddress(idBits)))
  val w = Flipped(Decoupled(new AxiWriteData))
  val b = Decoupled(new AxiWriteResponse(idBits))
  val ar = Flipped(Decoupled(new AxiAddress(idBits)))
  val r = Decoupled(new AxiReadData(idBits))
}
class LiteAddress extends Bundle {
  val addr = UInt(32.W)
  val prot = UInt(3.W)
}
class LiteWriteData extends Bundle {
  val data = UInt(32.W)
  val strb = UInt(4.W)
}
class LiteReadData extends Bundle {
  val data = UInt(32.W)
  val resp = UInt(2.W)
}
class AxiLiteSlave extends Bundle {
  val aw = Flipped(Decoupled(new LiteAddress))
  val w = Flipped(Decoupled(new LiteWriteData))
  val b = Decoupled(UInt(2.W))
  val ar = Flipped(Decoupled(new LiteAddress))
  val r = Decoupled(new LiteReadData)
}

class DmaAxiAddress extends Bundle {
  val id = UInt(4.W)
  val addr = UInt(64.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
}

class DmaAxiWriteData extends Bundle {
  val data = UInt(128.W)
  val strb = UInt(16.W)
  val last = Bool()
}

class DmaAxiWriteResponse extends Bundle {
  val id = UInt(4.W)
  val resp = UInt(2.W)
}

class DmaAxiReadData extends Bundle {
  val id = UInt(4.W)
  val data = UInt(128.W)
  val resp = UInt(2.W)
  val last = Bool()
}

/** AXI master used by the on-chip DMA to reach external memory. */
class DmaAxiMaster extends Bundle {
  val aw = Decoupled(new DmaAxiAddress)
  val w = Decoupled(new DmaAxiWriteData)
  val b = Flipped(Decoupled(new DmaAxiWriteResponse))
  val ar = Decoupled(new DmaAxiAddress)
  val r = Flipped(Decoupled(new DmaAxiReadData))
}
