package top

import org.scalatest.funsuite.AnyFunSuite

class ElaborateSpec extends AnyFunSuite {
  test("Vivado wrapper exports all three AXI interfaces with correct directions and widths") {
    val rtl = Elaborate.wrapper(4, 99990005L)
    assert(rtl.contains("input wire [15:0] S_AXI_CTRL_AWADDR"))
    assert(rtl.contains("input wire [19:0] S_AXI_MEM_ARADDR"))
    assert(rtl.contains("output wire [31:0] M_AXI_DDR_AWADDR"))
    assert(rtl.contains("output wire [31:0] M_AXI_DDR_WDATA"))
    assert(rtl.contains("input wire [31:0] M_AXI_DDR_RDATA"))
    assert(rtl.contains("output wire [3:0] M_AXI_DDR_AWID"))
    assert(rtl.contains("output wire S_AXI_CTRL_AWREADY"))
    assert(rtl.contains("input wire S_AXI_CTRL_BREADY"))
    assert(rtl.contains(".io_control_b_bits(S_AXI_CTRL_BRESP)"))
    assert(rtl.contains("PROTOCOL AXI4LITE"))
    assert(rtl.contains("FREQ_HZ 99990005"))
    assert(rtl.contains("ASSOCIATED_BUSIF S_AXI_CTRL:S_AXI_MEM:M_AXI_DDR"))
    assert(rtl.contains(".reset(~ARESETN)"))
    val pins = "\\.io_(control|memory|ddr)_([a-z0-9_]+)\\(".r
      .findAllMatchIn(rtl).map(_.matched).toVector
    assert(pins.size == 19 + 37 + 37)
    assert(pins.distinct.size == pins.size)
    assert(!rtl.contains("io_debug"))
  }

  test("compact aligned apertures translate only the host addresses, never DDR") {
    val rtl = Elaborate.wrapper(4, 100000000L)
    for (channel <- Seq("aw", "ar")) {
      val addr = s"S_AXI_MEM_${channel.toUpperCase}ADDR"
      assert(rtl.contains(s".io_memory_${channel}_bits_addr({3'b0, $addr[19], 9'b0, $addr[18:0]})"))
      assert(rtl.contains(s".io_control_${channel}_bits_addr({16'b0, S_AXI_CTRL_${channel.toUpperCase}ADDR})"))
      assert(rtl.contains(s".io_ddr_${channel}_bits_addr(M_AXI_DDR_${channel.toUpperCase}ADDR)"))
    }
    // Same bit permutation as the wrapper: keep out-of-range DTCM addresses
    // out of range, rather than aliasing them onto the first 16 KiB.
    def translate(offset: Long): Long = ((offset & 0x80000L) << 9) | (offset & 0x7ffffL)
    assert(translate(0) == 0)
    assert(translate(0x7ffffL) == 0x7ffffL)
    assert(translate(0x80000L) == 0x10000000L)
    assert(translate(0x83fffL) == 0x10003fffL)
    assert(translate(0x84000L) == 0x10004000L)
    assert(translate(0xfffffL) == 0x1007ffffL)
  }
}
