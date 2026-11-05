# Registered writeback / issue timing boundary

Baseline: `/home/free/Downloads/setup_all_violating_endpoints.rpt`, Vivado
2021.2 report for c6e2addf dated 2026-11-05. All 11,469 reported violating
endpoint paths originate at core/dma/poisoned_reg. Worst: ALU completion data,
slack -4.619 ns, data delay 14.370 ns, 51 logic levels. Nets are unplaced.

The representative path crosses DMA busy, quiescence/flush, completion-valid
masking, the completion tournament, ROB acceptance, wakeup, issue and ALU.
The fix does not delay the poison signal or weaken immediate issue/retire stops.

## Changes

- Register the completion tournament output before ROB/CDB. All completion
  sources handshake into this stage; ROB processes the old record while the
  next record enters. Throughput remains at most one record per clock, with
  one extra cycle of latency. Rejected stale/duplicate records are consumed
  rather than retried, as before.
- Include writebackPending in quiescence, fault cleanup eligibility, generation
  recycling, and system-instruction completion eligibility. Do not gate pending
  writeback draining on stopIssue; precise faults need outstanding work drained.
- Configure all core IssueUnits to issue from stored ready bits and operands,
  not same-cycle wakeup bypasses. Dispatch-time and resident wakeups are still
  captured; consumers may issue in the following cycle. LSU already uses
  registered operands for address generation.
- Assert single completion acceptance and no flush with an undrained record.

No ISA, Matrix datapath, DMA protocol behavior, or timing exceptions changed.
Cost: one full completion register plus valid, one additional writeback cycle,
and one wakeup-to-issue cycle for newly awakened consumers. No extra completion
throughput bubble is inserted.

## Validation

`./mill xj.test`: all 19 suites pass. Added vector registered-wakeup tests
including 256-bit data and exception rejection. ScalarCore tests check no
lost/duplicate completions, minimum ALU-to-writeback register latency, and an
empty pending-ALU set at HALT; existing fault/drain/restart regressions pass.

RTL generated with:

```
./mill xj.runMain top.Generate generated/timing-writeback
```

Use this directory's filelist, not stale generated RTL. Vivado was not run
locally. Re-run synthesis and implementation, then use
`scripts/report_timing_closure.tcl` on the new design. Shorter structural paths
do not establish 100 MHz closure; check new setup, hold, pulse width and
unconstrained endpoints, especially the arbiter-to-register and ROB-to-RS stages.
