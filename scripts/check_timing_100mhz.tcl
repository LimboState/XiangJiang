# Run after opening the integrated design's routed checkpoint:
#   open_checkpoint <impl_1/design_1_wrapper_routed.dcp>
#   source scripts/check_timing_100mhz.tcl
# This checks the actual clocks; it does NOT relax or replace constraints.
set report_dir [file normalize reports/xj_100mhz]
file mkdir $report_dir
report_clocks -file $report_dir/clocks.rpt
check_timing -verbose -file $report_dir/check_timing.rpt
report_timing_summary -delay_type min_max -report_unconstrained -max_paths 50 \
    -file $report_dir/timing_summary.rpt
report_timing -delay_type max -max_paths 100 -nworst 1 \
    -file $report_dir/setup_paths.rpt
report_timing -delay_type min -max_paths 100 -nworst 1 \
    -file $report_dir/hold_paths.rpt
report_utilization -hierarchical -file $report_dir/utilization.rpt
report_route_status -file $report_dir/route_status.rpt
report_methodology -file $report_dir/methodology.rpt

set clocks [get_clocks -quiet]
if {[llength $clocks] == 0} { error "No clocks: cannot validate 100 MHz" }
# Current wrapper is single-clock. Unrelated faster/slower BD clocks require
# reviewing the clock list and checking the NPU clock specifically.
foreach clk $clocks {
    if {[get_property PERIOD $clk] > 10.0001} {
        error "Clock $clk exceeds 10ns: configure PS PL_CLK0 / clock wizard for 100MHz"
    }
}
foreach type {max min} {
    set paths [get_timing_paths -quiet -delay_type $type -max_paths 1]
    if {[llength $paths] == 0} { error "No $type timing path found" }
    set slack [get_property SLACK [lindex $paths 0]]
    if {$slack < 0} { error "$type timing failed: worst slack $slack ns; see $report_dir" }
}
puts "Setup/hold path checks pass. Also review route completion, unconstrained/no-clock checks, pulse width and methodology reports before hardware signoff."
