package device

import chisel3._
import chisel3.util._
import xiangjiang._
import xiangjiang.mem._

/** Host burst engine and scalar adapters over real synchronous TCM ports.
  * Burst address errors are checked before any write. Malformed WSTRB/WLAST
  * suppress the offending beat and make B sticky-error; earlier valid beats
  * are not rolled back. AWLEN, never WLAST, determines drain length.
  */
class MemorySubsystem(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val axi = new Axi4Slave(p.axiIdBits)
    val coreBusy = Input(Bool())
    val dmaBusy = Input(Bool())
    val hostIdle = Output(Bool())
    val dmaReq = Flipped(Decoupled(new DmaLocalRequest))
    val dmaResp = Decoupled(new DmaLocalResponse)
    val fetchReq = Flipped(Decoupled(UInt(32.W)))
    val fetchResp = Output(Valid(new FetchPacket))
    val dataReq = Flipped(Decoupled(new WordReq))
    val dataResp = Decoupled(new WordResp)
    val matrixReq = Flipped(Decoupled(new MatrixBeatReq))
    val matrixResp = Decoupled(new MatrixBeatResp)
  })
  val itcm = Module(new InstructionTcm(p))
  val dtcm = Module(new DataTcm(p))
  val dtcmBase = "h10000000".U(32.W)

  def burstBytes(a: AxiAddress): UInt = ((a.len +& 1.U).pad(34) << a.size)(33, 0)
  def beatBytes(a: AxiAddress): UInt = (1.U(34.W) << a.size)(33, 0)
  def burstError(a: AxiAddress): Bool = {
    val bytes = beatBytes(a)
    val span = burstBytes(a)
    val start = a.addr.pad(34)
    val lower = Mux(a.burst === 2.U, start & ~(span - 1.U), start)
    val upper = lower + Mux(a.burst === 0.U, bytes, span) - 1.U
    val inItcm = lower < p.itcmBytes.U && upper < p.itcmBytes.U
    val inDtcm = lower >= dtcmBase && upper < (BigInt("10000000", 16) + p.dtcmBytes).U
    val wrapLength = a.len === 1.U || a.len === 3.U || a.len === 7.U || a.len === 15.U
    a.size > 2.U || (start & (bytes - 1.U)) =/= 0.U || a.lock =/= 0.U ||
      a.burst === 3.U || (a.burst === 0.U && a.len > 15.U) ||
      (a.burst === 2.U && !wrapLength) || lower(33, 12) =/= upper(33, 12) ||
      !(inItcm || inDtcm)
  }
  def nextAddress(a: AxiAddress, current: UInt): UInt = {
    val next = current + beatBytes(a)
    val mask = burstBytes(a) - 1.U
    Mux(a.burst === 0.U, current,
      Mux(a.burst === 2.U, (a.addr.pad(34) & ~mask) | (next & mask), next))(31, 0)
  }
  def lanes(size: UInt, address: UInt): UInt =
    Mux(size === 0.U, 1.U(4.W) << address(1, 0),
      Mux(size === 1.U, 3.U(4.W) << address(1, 0), 15.U(4.W)))(3, 0)
  def byteMask(mask: UInt): UInt = VecInit((0 until 4).map(i => Fill(8, mask(i)))).asUInt

  val awValid = RegInit(false.B)
  val aw = Reg(new AxiAddress(p.axiIdBits))
  val wAddress = Reg(UInt(32.W))
  val wBeat = RegInit(0.U(8.W))
  val wError = RegInit(false.B)
  val wValid = RegInit(false.B)
  val w = Reg(new AxiWriteData)
  val wBusy = Reg(Bool())
  val bValid = RegInit(false.B)
  val b = Reg(new AxiWriteResponse(p.axiIdBits))
  io.axi.aw.ready := !awValid && !bValid
  io.axi.w.ready := !wValid && !bValid
  io.axi.b.valid := bValid
  io.axi.b.bits := b
  when(io.axi.aw.fire) {
    awValid := true.B
    aw := io.axi.aw.bits
    wAddress := io.axi.aw.bits.addr
    wBeat := 0.U
    wError := burstError(io.axi.aw.bits) || io.coreBusy || io.dmaBusy
  }
  when(io.axi.w.fire) {
    wValid := true.B
    w := io.axi.w.bits
    wBusy := io.coreBusy || io.dmaBusy
  }
  when(io.axi.b.fire) { bValid := false.B }

  val arValid = RegInit(false.B)
  val ar = Reg(new AxiAddress(p.axiIdBits))
  val rAddress = Reg(UInt(32.W))
  val rBeat = RegInit(0.U(8.W))
  val rError = RegInit(false.B)
  val rValid = RegInit(false.B)
  val r = Reg(new AxiReadData(p.axiIdBits))
  val rPending = RegInit(false.B)
  io.axi.ar.ready := !arValid && !rValid && !rPending
  io.axi.r.valid := rValid
  io.axi.r.bits := r
  when(io.axi.ar.fire) {
    arValid := true.B
    ar := io.axi.ar.bits
    rAddress := io.axi.ar.bits.addr
    rBeat := 0.U
    rError := burstError(io.axi.ar.bits) || io.coreBusy || io.dmaBusy
  }
  when(io.axi.r.fire) {
    rValid := false.B
    when(r.last) { arValid := false.B }
      .otherwise { rBeat := rBeat + 1.U; rAddress := nextAddress(ar, rAddress) }
  }
  io.hostIdle := !awValid && !wValid && !bValid && !arValid && !rValid && !rPending &&
    !io.dmaBusy &&
    !io.axi.aw.valid && !io.axi.w.valid && !io.axi.ar.valid

  // Fetch consumes precisely one cycle, including faults (which never touch SRAM).
  val fetchMisaligned = io.fetchReq.bits(1, 0) =/= 0.U
  val fetchOutside = io.fetchReq.bits >= p.itcmBytes.U
  val fetchError = fetchMisaligned || fetchOutside
  io.fetchReq.ready := true.B
  val fetchAccess = io.fetchReq.fire && !fetchError
  io.fetchResp.valid := RegNext(io.fetchReq.fire, false.B)
  io.fetchResp.bits.pc := RegEnable(io.fetchReq.bits, io.fetchReq.fire)
  io.fetchResp.bits.exception := RegEnable(fetchError, io.fetchReq.fire)
  io.fetchResp.bits.cause := RegEnable(Mux(fetchMisaligned, FaultCode.fetchAlign,
    Mux(fetchOutside, FaultCode.fetchAccess, FaultCode.none)), io.fetchReq.fire)
  io.fetchResp.bits.badAddr := io.fetchResp.bits.pc
  io.fetchResp.bits.inst := Mux(io.fetchResp.bits.exception, 0.U, itcm.io.readData)

  // One scalar response slot is reserved before granting a data request.
  val dataPending = RegInit(false.B)
  val dataValid = RegInit(false.B)
  val data = Reg(new WordResp)
  io.dataReq.ready := !dataPending && !dataValid
  io.dataResp.valid := dataValid
  io.dataResp.bits := data
  when(io.dataResp.fire) { dataValid := false.B }
  val dataError = io.dataReq.bits.addr(1, 0) =/= 0.U || io.dataReq.bits.addr >= p.dtcmBytes.U
  val dataAccess = io.dataReq.fire && !dataError
  dataPending := io.dataReq.fire
  val dataWasError = RegEnable(dataError, io.dataReq.fire)
  val dataWasWrite = RegEnable(io.dataReq.bits.write, io.dataReq.fire)
  val dataBank = RegEnable(io.dataReq.bits.addr(7, 6), io.dataReq.fire)
  val dataWord = RegEnable(io.dataReq.bits.addr(5, 2), io.dataReq.fire)
  when(dataPending) {
    dataValid := true.B
    data.error := dataWasError
    data.data := Mux(dataWasError || dataWasWrite, 0.U,
      (dtcm.io.readData(dataBank) >> Cat(dataWord, 0.U(5.W)))(31, 0))
  }

  // Matrix MLOAD/MSTORE moves one full 64-byte DTCM row per beat.
  val matrixPending = RegInit(false.B)
  val matrixValid = RegInit(false.B)
  val matrix = Reg(new MatrixBeatResp)
  io.matrixReq.ready := !matrixPending && !matrixValid
  io.matrixResp.valid := matrixValid
  io.matrixResp.bits := matrix
  when(io.matrixResp.fire) { matrixValid := false.B }
  val matrixError = io.matrixReq.bits.addr(5, 0) =/= 0.U || io.matrixReq.bits.addr >= p.dtcmBytes.U
  val matrixAccess = io.matrixReq.fire && !matrixError
  matrixPending := io.matrixReq.fire
  val matrixWasError = RegEnable(matrixError, io.matrixReq.fire)
  val matrixWasWrite = RegEnable(io.matrixReq.bits.write, io.matrixReq.fire)
  val matrixBank = RegEnable(io.matrixReq.bits.addr(7, 6), io.matrixReq.fire)
  when(matrixPending) {
    matrixValid := true.B
    matrix.data := Mux(matrixWasError || matrixWasWrite, 0.U, dtcm.io.readData(matrixBank))
  }

  val coreDataAccess = dataAccess || matrixAccess
  val dmaReadPending = RegInit(false.B)
  val dmaReadValid = RegInit(false.B)
  val dmaReadBank = Reg(UInt(2.W))
  val dmaReadQuarter = Reg(UInt(2.W))
  val dmaReadData = Reg(UInt(128.W))
  val hostBusy = awValid || wValid || bValid || arValid || rValid || rPending
  io.dmaReq.ready := !io.coreBusy && !coreDataAccess &&
    !hostBusy && !dmaReadPending && !dmaReadValid
  io.dmaResp.valid := dmaReadValid
  io.dmaResp.bits.data := dmaReadData
  io.dmaResp.bits.error := false.B
  when(io.dmaResp.fire) { dmaReadValid := false.B }
  when(io.dmaReq.fire && !io.dmaReq.bits.write) {
    dmaReadPending := true.B
    dmaReadBank := io.dmaReq.bits.addr(7, 6)
    dmaReadQuarter := io.dmaReq.bits.addr(5, 4)
  }
  when(dmaReadPending) {
    dmaReadValid := true.B
    dmaReadData := (dtcm.io.readData(dmaReadBank) >> Cat(dmaReadQuarter, 0.U(7.W)))(127, 0)
    dmaReadPending := false.B
  }
  val badWriteBeat = wBusy || io.coreBusy ||
    (w.strb & ~lanes(aw.size, wAddress)) =/= 0.U || w.last =/= (wBeat === aw.len)
  val writeFault = wError || badWriteBeat
  val writeItcm = wAddress < p.itcmBytes.U
  val writeWaiting = awValid && wValid && !bValid
  // Core gets exclusive ITCM access; scalar/matrix DTCM traffic has priority over host.
  val writeGrant = writeWaiting && (writeFault ||
    Mux(writeItcm, !fetchAccess, !coreDataAccess))
  val hostWrite = writeGrant && !writeFault && !io.dmaBusy
  when(writeGrant) {
    wValid := false.B
    wError := writeFault
    when(wBeat === aw.len) {
      awValid := false.B
      bValid := true.B
      b.id := aw.id
      b.resp := Mux(writeFault, 2.U, 0.U)
    }.otherwise {
      wBeat := wBeat + 1.U
      wAddress := nextAddress(aw, wAddress)
    }
  }

  val readFault = rError || io.coreBusy || io.dmaBusy
  val readItcm = rAddress < p.itcmBytes.U
  val sameDtcmRow = wAddress(31, 6) === rAddress(31, 6)
  val readConflict = Mux(readItcm, fetchAccess || (hostWrite && writeItcm),
    coreDataAccess || (hostWrite && !writeItcm && sameDtcmRow))
  val readGrant = arValid && !rPending && !rValid && (readFault || !readConflict)
  val hostRead = readGrant && !readFault
  rPending := readGrant
  val readWasFault = RegEnable(readFault, readGrant)
  val readWasItcm = RegEnable(readItcm, readGrant)
  val readBank = RegEnable(rAddress(7, 6), readGrant)
  val readWord = RegEnable(rAddress(5, 2), readGrant)
  val readLanes = RegEnable(lanes(ar.size, rAddress), readGrant)
  when(readGrant && (io.coreBusy || io.dmaBusy)) { rError := true.B }
  when(rPending) {
    rValid := true.B
    r.id := ar.id
    r.last := rBeat === ar.len
    r.resp := Mux(readWasFault, 2.U, 0.U)
    val word = Mux(readWasItcm, itcm.io.readData,
      (dtcm.io.readData(readBank) >> Cat(readWord, 0.U(5.W)))(31, 0))
    r.data := Mux(readWasFault, 0.U, word & byteMask(readLanes))
  }

  itcm.io.enable := fetchAccess || (hostWrite && writeItcm) || (hostRead && readItcm)
  itcm.io.write := hostWrite && writeItcm
  itcm.io.word := Mux(fetchAccess, io.fetchReq.bits,
    Mux(hostWrite && writeItcm, wAddress, rAddress))(18, 2)
  itcm.io.data := w.data
  itcm.io.mask := w.strb

  for (bank <- 0 until 4) {
    val dmaBankAccess = io.dmaReq.fire && io.dmaReq.bits.addr(7, 6) === bank.U
    val scalarRead = dataAccess && !io.dataReq.bits.write && io.dataReq.bits.addr(7, 6) === bank.U
    val scalarWrite = dataAccess && io.dataReq.bits.write && io.dataReq.bits.addr(7, 6) === bank.U
    val matrixRead = matrixAccess && !io.matrixReq.bits.write && io.matrixReq.bits.addr(7, 6) === bank.U
    val matrixWrite = matrixAccess && io.matrixReq.bits.write && io.matrixReq.bits.addr(7, 6) === bank.U
    val hostBankRead = hostRead && !readItcm && rAddress(7, 6) === bank.U
    val hostBankWrite = hostWrite && !writeItcm && wAddress(7, 6) === bank.U
    val writeAddr = Mux(scalarWrite, io.dataReq.bits.addr,
      Mux(matrixWrite, io.matrixReq.bits.addr,
        Mux(hostBankWrite, wAddress - dtcmBase, io.dmaReq.bits.addr)))
    val readAddr = Mux(scalarRead, io.dataReq.bits.addr,
      Mux(matrixRead, io.matrixReq.bits.addr,
        Mux(hostBankRead, rAddress - dtcmBase, io.dmaReq.bits.addr)))
    dtcm.io.read(bank).valid := scalarRead || matrixRead || hostBankRead ||
      (dmaBankAccess && !io.dmaReq.bits.write)
    dtcm.io.read(bank).bits.row := readAddr >> 8
    dtcm.io.write(bank).valid := scalarWrite || matrixWrite || hostBankWrite ||
      (dmaBankAccess && io.dmaReq.bits.write)
    dtcm.io.write(bank).bits.row := writeAddr >> 8
    // Data shifts count bits; each mask bit enables one byte.
    val dmaBitShift = Cat(io.dmaReq.bits.addr(5, 4), 0.U(7.W))
    val dmaByteShift = Cat(io.dmaReq.bits.addr(5, 4), 0.U(4.W))
    val dmaMask = ("hffff".U(64.W) << dmaByteShift)(63, 0)
    val dmaData = (io.dmaReq.bits.data.pad(512) << dmaBitShift)(511, 0)
    dtcm.io.write(bank).bits.data := Mux(matrixWrite, io.matrixReq.bits.data,
      Mux(scalarWrite, Fill(16, io.dataReq.bits.data),
        Mux(hostBankWrite, Fill(16, w.data), dmaData)))
    dtcm.io.write(bank).bits.mask := Mux(matrixWrite, io.matrixReq.bits.mask,
      Mux(scalarWrite, io.dataReq.bits.mask.pad(64) << Cat(writeAddr(5, 2), 0.U(2.W)),
        Mux(hostBankWrite, w.strb.pad(64) << Cat(writeAddr(5, 2), 0.U(2.W)), dmaMask)))
  }
}
