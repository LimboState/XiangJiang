# Timing optimization (2026-11-04)

## Report baseline

Input reports: `setup_all_violating_endpoints.rpt` and
`hold_all_violating_endpoints.rpt`, Vivado 2021.2, design d7209cca.
Both contain `unplaced` nets: routing delays are estimates, not routed signoff.

Parsed setup paths: 14,170. Parsed hold paths: 70,980 (`-nworst 1`).
Representative worst setup paths by destination:

| Destination | Slack ns | Data path ns |
|---|---:|---:|
| BF16 Matrix arrays_0 | -14.996 | 24.747 |
| vectors_2 | -13.060 | 22.811 |
| vectors_4 | -8.027 | 17.778 |
| ALU | -5.123 | 14.874 |
| DTCM RAM input | -4.754 | 14.186 |
| DMA | -4.571 | 14.223 |

Worst hold slack: -0.096 ns in SmartConnect distributed RAM address paths.
Do not insert RTL delay chains, false paths, or multicycle exceptions to mask
these synchronous paths. Reassess hold after placement and routing and use
Vivado implementation hold fixing where necessary.

## Changes

- `VectorFloat.fma32Pipelined` / `fmaPipelined`: six internal boundaries:
  input capture, normalized product/metadata, aligned magnitudes, sum/difference,
  normalization, underflow shift. Final rounding is combinational before the
  caller's result register. Special-value metadata is delayed by the same amount.
  No intermediate rounding was introduced. Existing combinational APIs retain
  their arithmetic behavior.
- BF16 SystolicPE carries six valid stages; prepare/flush/load cancels pending
  results. The accumulator update takes seven clocks including input acceptance.
- Matrix BF16 OS injects successive k terms every eight clocks. Physical
  neighbour links advance every clock and carry valid bubbles. Terms meet at
  cycle i+j+8*k. Last pair is phase 70; phase 76 drains the last FMA write,
  followed by result capture. INT8 OS/WS scheduling is unchanged.
- MEW launches once and waits for the BF16 pipeline before capturing results.
- Vector lanes capture operands before execution. Vector FMA waits six internal
  stages; FP32 ordered sum reduction waits four internal stages. Lane and
  accumulator state remain stable until the result is consumed. Dependency
  order, masking, completion retention, and flush behavior remain intact.

This trades latency and FF resources for shorter combinational paths. It does
not promise one BF16 MAC per PE per clock for a single accumulator recurrence,
nor does it add an alternative matrix multiplication datapath.

## Validation and remaining work

`./mill xj.test`: all 19 suites passed, including new FmaPipelineSpec tests.
Random/special FP32 FMA inputs compare the six-stage output with the original
combinational implementation at full pipeline throughput. PE tests check drain,
flush cancellation, and restart. Existing Matrix/Vector/core regression passes.

No ISA encoding, instruction result definition, or timing exception changed.
No Vivado synthesis/implementation was available locally: new WNS/TNS, area,
and routed hold closure are not measured. Remaining baseline risks include
hostIdle/start/flush paths, writeback/wakeup paths in non-branch units, other
vector arithmetic, and Matrix store formatting to DTCM. Re-run synthesis and
implementation before selecting the next bottleneck. Generate into a fresh
directory and use its filelist; do not mix with stale generated RTL.
