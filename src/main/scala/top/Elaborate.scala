package top

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import xiangjiang.XJParams

/** FPGA export: top.Elaborate [directory] [clockHz] [dtcmRam] [peDsp] */
object Elaborate {
  def main(args: Array[String]): Unit = {
    require(args.length <= 4, "Usage: top.Elaborate [directory=generated/vivado] [clockHz=100000000] [dtcmRam=block|distributed] [peDsp=yes|no]")
    val defaults = VivadoResources.Policy()
    val policy = VivadoResources.Policy(
      args.lift(2).getOrElse(defaults.dtcmRam), args.lift(3).getOrElse(defaults.peDsp))
    val target = Paths.get(args.headOption.getOrElse("generated/vivado")).toAbsolutePath.normalize()
    val frequency = args.lift(1).map(_.toLong).getOrElse(100000000L)
    require(frequency > 0 && frequency <= 1000000000L, "clockHz must be in 1..1000000000")
    val p = XJParams()
    require(p.dtcmBytes <= 512 * 1024, "Compact FPGA aperture supports at most 512 KiB DTCM")
    val rtl = target.resolve("rtl")
    Files.createDirectories(rtl)
    Generate.main(Array(rtl.toString))

    // Chisel 7 already emits split sources. Do not concatenate them or import
    // bind/assert layers into the synthesis fileset. Use the emitted manifest,
    // not a directory glob which could pick up stale RTL from another build.
    val sources = Files.readAllLines(rtl.resolve("filelist.f")).asScala
      .map(_.trim).filter(_.nonEmpty).filterNot(_.startsWith("verification/"))
      .map { name =>
        val file = rtl.resolve(name).normalize()
        require(file.startsWith(rtl) && Files.isRegularFile(file), s"Invalid RTL source: $name")
        s"rtl/$name"
      }.toVector
    // Validate every transformation before writing any annotated source.
    val original = sources.map(name => name -> Files.readString(target.resolve(name))).toMap
    val annotated = VivadoResources(original, policy)
    annotated.sources.foreach { case (name, text) =>
      if (text != original(name)) write(target.resolve(name), text)
    }
    write(target.resolve("resource-policy.txt"), annotated.report)
    write(target.resolve("XiangJiangAXI.sv"), wrapper(p.axiIdBits, frequency))
    write(target.resolve("synthesis.f"), (sources :+ "XiangJiangAXI.sv").mkString("", "\n", "\n"))
    write(target.resolve("package_ip.tcl"), packagingTcl)
    println(s"Vivado export: $target\nTop: XiangJiangAXI; clock: $frequency Hz\n" +
      s"Package with: vivado -mode batch -source $target/package_ip.tcl")
  }

  private def write(path: Path, text: String): Unit = { Files.writeString(path, text); () }

  private case class Signal(name: String, suffix: String, bits: Int, intoSlave: Boolean)
  private def signals(lite: Boolean, idBits: Int, addressBits: Int): Seq[Signal] = {
    def s(name: String, suffix: String, bits: Int = 1, in: Boolean = true) =
      Signal(name, suffix, bits, in)
    def address(ch: String): Seq[Signal] = {
      val prefix = if (lite) Seq.empty else Seq(s(s"${ch}ID", s"${ch.toLowerCase}_bits_id", idBits))
      val payload = Seq(s(s"${ch}ADDR", s"${ch.toLowerCase}_bits_addr", addressBits)) ++
        (if (lite) Seq.empty else Seq(
          s(s"${ch}LEN", s"${ch.toLowerCase}_bits_len", 8),
          s(s"${ch}SIZE", s"${ch.toLowerCase}_bits_size", 3),
          s(s"${ch}BURST", s"${ch.toLowerCase}_bits_burst", 2),
          s(s"${ch}LOCK", s"${ch.toLowerCase}_bits_lock"),
          s(s"${ch}CACHE", s"${ch.toLowerCase}_bits_cache", 4))) ++
        Seq(s(s"${ch}PROT", s"${ch.toLowerCase}_bits_prot", 3)) ++
        (if (lite) Seq.empty else Seq(s(s"${ch}QOS", s"${ch.toLowerCase}_bits_qos", 4)))
      prefix ++ payload ++ Seq(s(s"${ch}VALID", s"${ch.toLowerCase}_valid"),
        s(s"${ch}READY", s"${ch.toLowerCase}_ready", in = false))
    }
    address("AW") ++ Seq(s("WDATA", "w_bits_data", 32), s("WSTRB", "w_bits_strb", 4)) ++
      (if (lite) Seq.empty else Seq(s("WLAST", "w_bits_last"))) ++
      Seq(s("WVALID", "w_valid"), s("WREADY", "w_ready", in = false)) ++
      (if (lite) Seq.empty else Seq(s("BID", "b_bits_id", idBits, in = false))) ++
      Seq(s("BRESP", if (lite) "b_bits" else "b_bits_resp", 2, in = false),
        s("BVALID", "b_valid", in = false), s("BREADY", "b_ready")) ++
      address("AR") ++
      (if (lite) Seq.empty else Seq(s("RID", "r_bits_id", idBits, in = false))) ++
      Seq(s("RDATA", "r_bits_data", 32, in = false), s("RRESP", "r_bits_resp", 2, in = false)) ++
      (if (lite) Seq.empty else Seq(s("RLAST", "r_bits_last", in = false))) ++
      Seq(s("RVALID", "r_valid", in = false), s("RREADY", "r_ready"))
  }

  private[top] def wrapper(idBits: Int, frequency: Long): String = {
    val interfaces = Seq(
      ("S_AXI_CTRL", "control", true, false, 16),
      ("S_AXI_MEM", "memory", false, false, 20),
      ("M_AXI_DDR", "ddr", false, true, 32))
    val ports = interfaces.flatMap { case (name, _, lite, master, addressBits) =>
      signals(lite, idBits, addressBits).map { signal =>
        val direction = if (signal.intoSlave != master) "input" else "output"
        val width = if (signal.bits == 1) "" else s"[${signal.bits - 1}:0] "
        val parameter = if (signal.name == "AWADDR") {
          val protocol = if (lite) "AXI4LITE" else "AXI4"
          val extra = if (lite) "" else s", ID_WIDTH $idBits, HAS_BURST 1, HAS_LOCK 1, HAS_CACHE 1, HAS_QOS 1, HAS_REGION 0, SUPPORTS_NARROW_BURST 1, MAX_BURST_LENGTH 256"
          s"  (* X_INTERFACE_PARAMETER = \"XIL_INTERFACENAME $name, PROTOCOL $protocol, DATA_WIDTH 32, ADDR_WIDTH $addressBits, HAS_PROT 1, FREQ_HZ $frequency$extra\" *)\n"
        } else ""
        s"  (* X_INTERFACE_INFO = \"xilinx.com:interface:aximm:1.0 $name ${signal.name}\" *)\n" +
          parameter + s"  $direction wire $width${name}_${signal.name}"
      }
    }
    val connections = interfaces.flatMap { case (name, io, lite, _, addressBits) =>
      signals(lite, idBits, addressBits).map { signal =>
        val external = s"${name}_${signal.name}"
        val value = if (!signal.name.endsWith("ADDR")) external else io match {
          case "control" => s"{16'b0, $external}"
          // External [0,512KiB) -> ITCM; [512KiB,1MiB) -> DTCM.
          // Preserve low 19 bits so out-of-range DTCM accesses fail in the core.
          case "memory" => s"{3'b0, $external[19], 9'b0, $external[18:0]}"
          case _ => external
        }
        s"    .io_${io}_${signal.suffix}($value)"
      }
    }
    s"""// Generated by top.Elaborate. Package this wrapper, not XiangJiangNPU.
// CTRL: 64 KiB aligned aperture; MEM: 1 MiB aligned aperture.
// MEM +0 = ITCM, +0x80000 = DTCM. DDR addresses are unchanged.
// All interfaces share ACLK. There is no CDC or CPU cache coherency here.
module XiangJiangAXI (
  (* X_INTERFACE_INFO = "xilinx.com:signal:clock:1.0 ACLK CLK" *)
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI_CTRL:S_AXI_MEM:M_AXI_DDR, ASSOCIATED_RESET ARESETN, FREQ_HZ $frequency" *)
  input wire ACLK,
  (* X_INTERFACE_INFO = "xilinx.com:signal:reset:1.0 ARESETN RST" *)
  (* X_INTERFACE_PARAMETER = "POLARITY ACTIVE_LOW" *)
  input wire ARESETN,
${ports.mkString(",\n")}
);
  // Status/retire/debug outputs are intentionally not exported as package pins.
  XiangJiangNPU u_npu (
    .clock(ACLK),
    .reset(~ARESETN),
${connections.mkString(",\n")}
  );
endmodule
"""
  }

  private val packagingTcl = """# Run with: vivado -mode batch -source package_ip.tcl
# Optional: -tclargs <FPGA part> <IP repository directory>
set here [file dirname [file normalize [info script]]]
set part xczu9eg-ffvb1156-2-e
if {[llength $argv] > 0} { set part [lindex $argv 0] }
set repo [file join $here ip_repo]
if {[llength $argv] > 1} { set repo [file normalize [lindex $argv 1]] }
create_project -force xiangjiang_ip [file join $here vivado_project] -part $part
set f [open [file join $here synthesis.f] r]
set manifest [read $f]
close $f
foreach source [split $manifest "\n"] {
    set source [string trim $source]
    if {$source ne ""} { read_verilog -sv [file join $here $source] }
}
set_property top XiangJiangAXI [current_fileset]
update_compile_order -fileset sources_1
ipx::package_project -root_dir [file join $repo XiangJiang] \
    -vendor xiangjiang.org -library user -taxonomy /UserIP -import_files -set_current true
set core [ipx::current_core]
set_property name XiangJiangAXI $core
set_property display_name {XiangJiang NPU} $core
set_property version 1.0 $core
set_property description {Single-clock NPU: AXI-Lite control, AXI TCM slave, AXI DDR master} $core

# Interface attributes carry pin maps and clock/reset association. Fail loudly if
# inference did not recognize them; do not silently package individual wires.
foreach {bus mode} {S_AXI_CTRL slave S_AXI_MEM slave M_AXI_DDR master} {
    set intf [ipx::get_bus_interfaces $bus -of_objects $core]
    if {[llength $intf] != 1} { error "AXI interface not inferred: $bus" }
    set_property interface_mode $mode $intf
}
foreach {bus bytes} {S_AXI_CTRL 65536 S_AXI_MEM 1048576} {
    set intf [ipx::get_bus_interfaces $bus -of_objects $core]
    set map [ipx::get_memory_maps $bus -of_objects $core]
    if {[llength $map] == 0} { set map [ipx::add_memory_map $bus $core] }
    set_property slave_memory_map_ref $bus $intf
    foreach block [ipx::get_address_blocks -of_objects $map] {
        ipx::remove_address_block [get_property NAME $block] $map
    }
    set block [ipx::add_address_block regs $map]
    set_property base_address 0 $block
    set_property range $bytes $block
    set_property width 32 $block
    set_property usage memory $block
}
set master [ipx::get_bus_interfaces M_AXI_DDR -of_objects $core]
set space [ipx::get_address_spaces M_AXI_DDR -of_objects $core]
if {[llength $space] == 0} { set space [ipx::add_address_space M_AXI_DDR $core] }
set_property range 4294967296 $space
set_property width 32 $space
set_property master_address_space_ref M_AXI_DDR $master
ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::check_integrity $core
ipx::save_core $core
puts "Packaged XiangJiang IP at [file join $repo XiangJiang]"
close_project
"""
}
