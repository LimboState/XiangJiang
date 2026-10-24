# Baseline 100 MHz NPU clock for standalone FPGA synthesis.
create_clock -name npu_clock -period 10.000 [get_ports clock]
# Board interface timing and reset synchronization are intentionally not assumed.
# check_timing.rpt records unconstrained I/O for this out-of-context run.
