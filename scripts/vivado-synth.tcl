# Standalone synthesis of the current NPU RTL for the ZCU102 FPGA.
# Generate RTL first with: /usr/local/bin/mill --no-server xj.runMain top.Generate generated
# Run: vivado -mode batch -source scripts/vivado-synth.tcl
# Optional arguments: -tclargs <part> <output-directory>
# This produces pre-placement FPGA estimates, not ASIC timing or area.
set project_root [file normalize [file join [file dirname [info script]] ..]]
set part xczu9eg-ffvb1156-2-e
if {[llength $argv] >= 1} { set part [lindex $argv 0] }
set output_dir [file join $project_root out vivado-synth]
if {[llength $argv] >= 2} { set output_dir [file normalize [lindex $argv 1]] }
file mkdir $output_dir

set filelist [file join $project_root generated filelist.f]
if {![file exists $filelist]} { error "Missing $filelist; regenerate RTL first" }
set handle [open $filelist r]
set sources {}
foreach line [split [read $handle] \n] {
    set relative [string trim $line]
    if {$relative eq ""} { continue }
    set source [file join $project_root generated $relative]
    if {![file exists $source]} { error "Missing RTL source: $source" }
    lappend sources $source
}
close $handle

set_param general.maxThreads 2
create_project -in_memory -part $part
set_property verilog_define {SYNTHESIS} [current_fileset]
read_verilog -sv $sources
read_xdc [file join $project_root scripts vivado-synth.xdc]
synth_design -top XiangJiangNPU -part $part -mode out_of_context -flatten_hierarchy none

set blackboxes [get_cells -quiet -hier -filter {IS_BLACKBOX == 1}]
if {[llength $blackboxes]} { error "Unresolved black boxes: $blackboxes" }
report_utilization -file [file join $output_dir utilization.rpt]
report_utilization -hierarchical -file [file join $output_dir utilization_hierarchical.rpt]
report_timing_summary -delay_type min_max -report_unconstrained \
    -check_timing_verbose -max_paths 10 -file [file join $output_dir timing_summary.rpt]
report_timing -delay_type max -max_paths 10 -file [file join $output_dir timing_top10.rpt]
check_timing -verbose -file [file join $output_dir check_timing.rpt]
report_drc -file [file join $output_dir drc.rpt]
write_checkpoint -force [file join $output_dir XiangJiangNPU_synth.dcp]
write_verilog -force -mode funcsim [file join $output_dir XiangJiangNPU_synth.v]
write_xdc -force [file join $output_dir XiangJiangNPU_synth.xdc]
puts "XJ_SYNTH_PART=$part"
puts "XJ_SYNTH_CLOCK_PERIOD_NS=10.000"
puts "XJ_SYNTH_OUTPUT=$output_dir"
puts "XJ_SYNTH_COMPLETE (FPGA pre-placement estimates; inspect timing and DRC reports)"
