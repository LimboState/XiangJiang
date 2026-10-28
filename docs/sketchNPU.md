# XiangJiang NPU Architecture Draft

> Implementation update: the current platform includes scalar, vector, matrix and DMA.
> [DMA_ISA.md](DMA_ISA.md) defines the implemented DMA encoding, AXI master, alignment,
> range prechecks, faults and status registers, superseding the DMA open items below.
> Appendix B records the original scalar baseline; historical test counts and scalar-only
> descriptions are not a statement of the current feature set. HALT is the exact word
> `0x0000000B` as defined in Scalar_ISA.md, not the obsolete zero-word draft encoding.

## 1. Architecture Overview

The XiangJiang neural processing unit (NPU) uses a single instruction stream, a single 32-bit program counter (PC), a unified front end, and an in-order commit controller.

INT8, INT16, and INT32 denote signed integer formats of the corresponding widths; UINT32 denotes a 32-bit unsigned integer. BF16 denotes the 16-bit brain floating-point format, and FP32 denotes a 32-bit floating-point format. `bit` denotes a bit, `B` denotes a byte, and `KiB` denotes 1024 B.

| Execution domain | Responsibilities and boundaries |
| --- | --- |
| Scalar | Integer arithmetic, address generation, condition evaluation, and branch target calculation; does not perform vector BF16 arithmetic or bypass global commit control |
| Vector | Register-based vector arithmetic, format conversion, permutation, and reduction; scalar interfaces share the `x` register namespace, with no separate scalar floating-point register file |
| Matrix | One BF16 array, two INT8 arrays, and their respective input buffers and task controllers; internal accumulators do not participate in scalar or vector register renaming |

The unified front end fetches instructions from instruction tightly coupled memory (ITCM), then performs legality checks, decoding, source-version capture, destination renaming, and in-order dispatch. The back-end structures have the following responsibilities:

- **Reservation stations (RSs):** Hold source operand values or dependency tags. An instruction issues when its operands are ready and the target functional unit (FU) can accept it.
- **Register alias tables (RATs):** `RAT-X` and `RAT-V` map architectural destinations to their latest uncommitted versions. Renaming does not cover the PC, host control registers, matrix accumulators, or data memory addresses.
- **Reorder buffer (ROB):** Allocates entries in program order and records results, completion status, and exceptions. The unified commit controller processes only the ROB head and controls architectural register updates, configuration updates, authorization of irreversible memory operations, normal termination, and fault-induced shutdown.
- **Memory queue (MemQ):** Maintained by the unified data memory controller. It orders scalar, vector, matrix, and direct memory access (DMA) requests to data tightly coupled memory (DTCM) or external interfaces, as specified in §5.

The baseline fetch, dispatch, and commit widths are each at most one instruction per cycle. Different FUs may issue their respective ready instructions from the instruction window in the same cycle. Out-of-order multiple issue does not imply multiple-instruction fetch or dispatch. Increasing dispatch or commit width requires corresponding ports and explicit same-cycle ordering and bypass rules.

The design does not predict branches or execute past unresolved control transfers. At most one MemQ entry is active at a time. Independent compute-only instructions may execute early, but they do not update architectural registers before commit. The ROB does not make a multi-beat DMA operation transactionally atomic.

## 2. Functional Units

### 2.1. Scalar Execution Domain

The scalar domain implements a subset of the fixed-length encodings and integer semantics of the RISC-V 32-bit base integer instruction set (RV32I): 21 integer operations, eight branch/jump instructions, `LW`, `SW`, `FENCE`, and a custom `HALT`. It does not implement compressed instructions, the M extension, scalar floating-point instructions, atomic extensions, privileged modes, or Zicsr. It is not a complete RV32I implementation.

Registers `x0–x31` are 32 bits wide and hold untyped bit patterns; `x0` is hardwired to zero. Setting `rd=x0` suppresses only the register write, not memory accesses, exceptions, control transfers, or ordering effects. For example, `LW x0, ...` still accesses memory and checks the address, and `JAL x0, ...` still transfers control.

#### Encodings and Immediates

`sext32` denotes sign extension to 32 bits. Immediate fields are reconstructed as follows; S-, B-, and J-type immediates must not be decoded as contiguous I-type fields.

| Format | Execution value | Range and constraints |
| --- | --- | --- |
| I | `sext32(inst[31:20])` | -2048 to 2047; interpreted as a byte offset for memory accesses |
| S | `sext32({inst[31:25], inst[11:7]})` | -2048 to 2047, in bytes |
| B | `sext32({inst[31], inst[7], inst[30:25], inst[11:8], 0})` | -4096 to 4094, encoded in 2-byte increments |
| U | `{inst[31:12], 12'b0}` | Already a 32-bit execution value; must not be shifted left again |
| J | `sext32({inst[31], inst[19:12], inst[20], inst[30:21], 0})` | -1048576 to 1048574, encoded in 2-byte increments |
| Shift immediate | `shamt=inst[24:20]` | 0–31; `inst[31:25]` determines legality and shift type |

| Instruction | Decode constraints | Semantics |
| --- | --- | --- |
| `ADD`, `SUB` | `opcode=0x33, funct3=000`; `funct7` is `0x00/0x20`, respectively | Add/subtract; retain the low 32 bits and do not raise an integer overflow exception |
| `AND`, `OR`, `XOR` | `opcode=0x33, funct7=0x00`; `funct3` is `111/110/100`, respectively | Bitwise operations on the full 32-bit operands |
| `SLL`, `SRL`, `SRA` | `opcode=0x33`; `(funct3, funct7)` is `(001, 0x00)/(101, 0x00)/(101, 0x20)`, respectively | Shift amount is `x[rs2][4:0]`; left shift with zero fill, right shift with zero fill, or right shift with sign fill |
| `SLT`, `SLTU` | `opcode=0x33, funct7=0x00`; `funct3=010/011`, respectively | Signed/unsigned comparison; produce a full 32-bit integer 0 or 1 |
| `ADDI`, `ANDI`, `ORI`, `XORI` | `opcode=0x13`; `funct3=000/111/110/100`, respectively | Sign-extend the I-type immediate before the operation; addition retains the low 32 bits without an overflow exception |
| `SLLI`, `SRLI`, `SRAI` | `opcode=0x13`; `(funct3, inst[31:25])` is `(001, 0x00)/(101, 0x00)/(101, 0x20)`, respectively | Shift by `shamt` using the same fill rules as the register forms; other upper-field encodings are illegal |
| `SLTI`, `SLTIU` | `opcode=0x13`; `funct3=010/011`, respectively | Sign-extend the I-type immediate before signed/unsigned comparison; produce a 32-bit integer 0 or 1. `SLTIU` does not zero-extend the immediate |
| `LUI` | `opcode=0x37` | Write the U-type execution value, with the low 12 bits cleared |
| `AUIPC` | `opcode=0x17` | Add the U-type execution value to this instruction's PC and retain the low 32 bits; do not use the current fetch PC |
| `LW` | `opcode=0x03, funct3=010` | Use the low 32 bits of `x[rs1] + sext32(imm12)` as the address; load four consecutive bytes into the full destination `x` register |
| `SW` | `opcode=0x23, funct3=010` | Use the low 32 bits of the base address plus the S-type execution value; store the captured `x[rs2][31:0]`; no register destination |
| `BEQ`, `BNE` | `opcode=0x63`; `funct3=000/001`, respectively | Compare full bit patterns; when taken, select this instruction's PC plus the B-type execution value; otherwise select `PC+4` |
| `BLT`, `BGE`, `BLTU`, `BGEU` | `opcode=0x63`; `funct3=100/101/110/111`, respectively | The first two compare signed values and the latter two compare unsigned values; target selection is as above |
| `JAL` | `opcode=0x6F` | Target is this instruction's PC plus the J-type execution value; link value is this instruction's `PC+4` |
| `JALR` | `opcode=0x67, funct3=000` | Capture the old base value first; target is `(x[rs1] + sext32(imm12)) & 0xFFFFFFFE`; link value is this instruction's `PC+4`; `rd=rs1` is permitted |
| `FENCE` | `opcode=0x0F, funct3=000` | Conservative global memory barrier covering scalar, vector, matrix, and DMA accesses; not a no-op and does not add cache-maintenance operations |
| `HALT` | Machine word `0x00000000` | Allocate a ROB entry in order and block subsequent dispatch; terminate the run after earlier work and external transactions have completed |

Decoding must check at least the opcode and the required `funct3/funct7` fields. Among the R-type instructions above, only `SUB/SRA` use `funct7=0x20`; all others use `0x00`. M-extension encodings must not be accepted inadvertently. Branch encodings with `funct3=010/011` are illegal.

No control-transfer instruction has an architectural delay slot. PC-relative additions, link values, and effective addresses use 32-bit address arithmetic. Range checks must precede truncation to a static random-access memory (SRAM) index. The selected control-transfer target must be 4-byte aligned; see §4.5.

#### Execution Resources

| Resource | Inputs and operations | Outputs and constraints |
| --- | --- | --- |
| Scalar arithmetic logic unit (S-ALU) | Two 32-bit sources, or a source and an immediate; integer operations and `LUI/AUIPC` | One 32-bit result and a ROB tag; a single execution datapath accepts at most one instruction per cycle |
| Scalar address generation unit (S-AGU) | Base address and sign-extended offset; `SW` tracks its store-data dependency independently | Write the address and check results to the allocated MemQ entry; address readiness does not imply memory-access completion |
| Scalar branch unit (S-BRU) | Condition operands, indirect-jump base, this instruction's PC, and immediate | Resolved next PC, target-check status, and an optional link value |
| System/configuration control | `FENCE`, `HALT`, and configuration commands | Progress according to ROB-head conditions and control records; do not synthesize results through the S-ALU |

The S-AGU may compute an address early and accept another task once that calculation finishes. Actual read and write requests require MemQ authorization. Address and store-data dependencies are tracked separately; address readiness does not imply store-data readiness.

The S-ALU, S-AGU, and vector FUs may operate concurrently. A single S-ALU cannot accept two operations in the same cycle. The S-BRU waits for earlier tasks to commit; separate circuits do not imply sustained throughput of three scalar instructions per cycle.

### 2.2. Vector Execution Domain

The vector domain uses a custom instruction set architecture (ISA) with a fixed width of 16 lanes. Each lane is 32 bits wide, and each arithmetic instruction processes at most 16 elements. Naming follows the conventions of the RISC-V Vector Extension (RVV), but neither mnemonic nor encoding compatibility is claimed.

#### Registers, Types, and Masks

Registers `v0–v31` are each 512 bits wide and contain sixteen 32-bit lanes. INT32 and FP32 values occupy an entire lane. A BF16 value occupies the low 16 bits; writing an active BF16 result clears the upper 16 bits. Each vector register holds only 16 BF16 elements.

| Form or input | Interpretation |
| --- | --- |
| `.vv` | Vector-vector operation |
| `.vx` | Vector-scalar operation using `x[rs1]`, broadcast to active lanes; BF16 also uses `.vx`, not the `.vf` interface that requires a separate floating-point register file |
| `.vi` | Vector-immediate operation, only for instructions that explicitly support it |
| INT32 scalar | Read `x[rs1][31:0]`; interpret signed operands as two's-complement values and unsigned comparisons as UINT32 |
| BF16 scalar | Read and decode `x[rs1][15:0]` as BF16; a BF16 scalar write clears the upper 16 bits |
| Slide distances, shift amounts, and other control operands | Interpret as integers, independently of the data type |

Registers have no implicit type tags. The bit pattern `0x00003F80` represents BF16 `1.0` or INT32 `16256`, depending on the instruction. Integer `1` is represented by `0x00000001` and cannot be used directly as BF16 `1.0`. Register `x0` supplies integer zero or BF16 positive zero. BF16 is not INT16 and is not integer sign-extended; conversion to FP32 requires a floating-point format conversion rather than reinterpretation of an integer-extended bit pattern.

The low 16 bits of the explicit mask register `vm` control the lanes: bit `i` controls lane `i`. An unmasked form enables all lanes. Arithmetic operations and loads preserve the entire old 32-bit destination value in masked-off lanes; stores generate no memory accesses for those lanes.

Dispatch must capture the old destination, the mask, the `vfmacc` accumulator value, and the old destination mask for comparisons before updating the destination mapping. A source that names the destination still refers to the old version.

#### INT32 Arithmetic and Bitwise Operations

All instructions below support `.vv / .vx`. Ordinary addition, subtraction, and multiplication retain the low 32 bits, do not saturate or raise overflow exceptions, and do not propagate carries between lanes.

| Instruction | Per-lane semantics |
| --- | --- |
| `vadd`, `vsub`, `vmul` | Addition, subtraction, and multiplication |
| `vmax`, `vmin` | Signed INT32 maximum/minimum; not a reduction |
| `vand`, `vor`, `vxor` | Bitwise AND, OR, and XOR |
| `vsll` | Left shift |
| `vsrl` | Logical right shift with zero fill |
| `vsra` | Arithmetic right shift with sign fill |

#### BF16 Arithmetic

All instructions below support `.vv / .vx` and operate directly on BF16 without a preceding conversion to FP32. Vector inputs come from the low 16 bits of each lane; scalar inputs come from `x[rs1][15:0]`. Outputs use the BF16 lane layout defined above. The internal intermediate precision used to implement rounding is not constrained.

| Instruction | Per-lane semantics |
| --- | --- |
| `vfadd` | BF16 addition |
| `vfsub` | BF16 subtraction; `.vx` computes vector minus scalar |
| `vfmul` | BF16 multiplication |
| `vfdiv` | BF16 division; `.vx` computes vector divided by scalar |
| `vfmacc` | Fused multiply-add: `.vv` computes `vd[i] = vs1[i] × vs2[i] + old vd[i]`; `.vx` replaces `vs1[i]` with the BF16 scalar; round only once, at the final result |
| `vfmax`, `vfmin` | BF16 maximum/minimum; not a reduction |

`BF16(bits)` means decoding a bit pattern as BF16, not numerically converting an integer. Fused multiply-add retains the multiplier-first assembly operand order:

```asm
vfadd.vx  v8, v4, x1    # v8[i] = BF16(v4[i]) + BF16(x1[15:0])
vfmacc.vx v8, x1, v4    # v8[i] = BF16(x1[15:0]) * BF16(v4[i]) + BF16(old v8[i])
```

Vector BF16 arithmetic produces BF16 results directly. Matrix BF16 operations retain FP32 partial sums across K-dimension tiles. These numerical contracts are distinct; matrix behavior is specified in §2.3.

#### Format Conversion

Rounding is fixed to round to nearest, ties to even (RNE); there is no writable `frm/fcsr` floating-point control state. All inputs and outputs below reside in vector registers, with FP32 values occupying full 32-bit lanes. The `.f/.x` components in instruction names identify conversion types, not scalar register sources.

| Instruction | Numerical conversion |
| --- | --- |
| `vfwcvtbf16.f.f.v` | BF16 → FP32, exact widening |
| `vfncvtbf16.f.f.w` | FP32 → BF16, RNE |
| `vfcvt.f.x.v` | INT32 → FP32 |
| `vfcvt.x.f.v` | FP32 → INT32, `frm=RNE` |

#### Permutation and Reduction

| Instruction | Form | Semantics |
| --- | --- | --- |
| `vslideup` | `.vx / .vi` | Move elements toward higher lane indices; fill vacated positions with zero of the same element type |
| `vslidedown` | `.vx / .vi` | Move elements toward lower lane indices; fill vacated positions with zero of the same element type |
| `vrgather` | `.vv`, optional implementation | `vd[i] = vs2[vs1[i]]`; index register elements, not memory |
| `vfredsum` | Unspecified | Floating-point sum reduction |
| `vfredmax` | Unspecified | Floating-point maximum reduction |

Vector_ISA.md describes unsigned indexing and zero results for indices greater than or equal to the maximum vector length (`VLMAX`) only as standard RVV behavior. This must not be assumed to define the boundary behavior of the custom ISA.

#### Comparisons, Masks, and Selection

Comparisons write their Boolean results to the corresponding bits of the destination mask register. BF16 comparisons compare floating-point values, not the integer ordering of their encodings. Mask layout and old-destination dependencies follow the preceding rules; unresolved comparison write semantics are listed in §6.3.

| Instruction | Form | Semantics |
| --- | --- | --- |
| `vmseq` | `.vv / .vx` | 32-bit integer equality |
| `vmslt`, `vmsle` | `.vv / .vx` | Signed INT32 less-than/less-than-or-equal |
| `vmsltu`, `vmsleu` | `.vv / .vx` | UINT32 less-than/less-than-or-equal |
| `vmfeq`, `vmflt`, `vmfle` | `.vv / .vx` | BF16 equality/less-than/less-than-or-equal |
| `vmerge.vvm` | Two vectors and a mask | Select one of the two source vectors independently for each lane according to the mask |

#### Special-Function Extensions*

| Custom instruction | Per-lane operation | Standard RVV comparison, for reference only |
| --- | --- | --- |
| `Vexp` | `exp(x) = e^x` | The standard V extension has no direct exponential instruction |
| `Vrcp` | `1/x` | `vfrec7.v` provides a reciprocal estimate with approximately seven significant binary bits of precision |
| `Vrsqrt` | `1/sqrt(x)` | `vfrsqrt7.v` provides a reciprocal square-root estimate with approximately seven significant binary bits of precision |

The capitalization of these custom instruction names is preserved. At a selected element width (SEW) of 32 bits, the standard estimate instructions consume and produce FP32 values, but do not provide full FP32 computational precision. Their behavior does not define the custom instructions' types, error bounds, or special-value handling.

### 2.3. Matrix Execution Domain

The three independent matrix units each contain a 16×16 systolic array with 256 processing elements (PEs). Their basic operation is multiply-accumulate on two 16×16 input matrices. The hardware supports parallel operation, but complete-task activation is constrained by the single-active-entry MemQ policy in §5.

| Unit | Dataflow | Multiplication, accumulation, and output |
| --- | --- | --- |
| One BF16 unit | Output-stationary (OS) | MMUL/MMACC use BF16×BF16→BF16 multiplication (RNE), register the BF16 product, then widen exactly for FP32+FP32→FP32 accumulation in the next cycle; partial sums remain FP32 in their PEs; MSTORE converts the accumulated result to BF16 with RNE |
| Two INT8 units | Each unit selects either OS or weight-stationary (WS), with only one mode active at a time | Signed 8×8-bit multiplication produces a full INT16 product, which is sign-extended to INT32 before accumulation; output INT32 directly without narrowing |

Each INT8 unit uses register control and data routing to reuse the same multipliers and adders across modes. OS retains INT32 partial sums in the PEs. WS retains INT8 weights in the PEs and forwards and accumulates INT32 partial sums along the reduction path.

## 3. Memory Hierarchy

### 3.1. Register Files

Scalar and vector register counts and layouts are specified in §2.1 and §2.2. Read and write ports are determined by the corresponding FU implementations. Register `x0` is read-only and contains no sequential storage logic.

Each matrix unit has one pair of register-based input skew buffers, one for A and one for B, for a total of three pairs and six buffers. Each pair uses a 16×16 staircase/skewed organization. Elements are 16 bits wide in the BF16 pair and 8 bits wide in each INT8 pair. The buffers feed the arrays only; they do not receive computation results, which are written directly to DTCM.

### 3.2. On-Chip Memory

#### 3.2.1. Instruction Tightly Coupled Memory (ITCM)

ITCM is explicitly loaded by software and does not use cache-hit detection, miss handling, or automatic replacement. It stores scalar, control-flow, vector, matrix, and DMA instructions. Weights, activations, and intermediate results reside primarily in DTCM or external double data rate (DDR) memory.

| Parameter | Definition |
| --- | --- |
| Capacity and port | 512 KiB, implemented as 32-bit single-port synchronous SRAM; holds 131072 instruction words |
| Addressing | The PC is byte-addressed; valid addresses are 4-byte aligned and shifted right by two bits to form a 17-bit instruction-word index |
| Range | Internal byte offsets are `0x00000`–`0x7FFFF`; the last valid instruction start address is `0x7FFFC` |
| Timing | Accept an address on a clock edge and return the instruction in the next cycle; validate the response and discard wrong-path responses |
| Error checks | Detect out-of-range and misaligned addresses before index truncation; silent wraparound is prohibited |

Program loading and execution proceed as follows:

1. While the NPU is idle, the host processing system (PS) writes the program through the Advanced eXtensible Interface (AXI).
2. After confirming that loading has completed, the host starts execution through the `start` bit in a control and status register (CSR). Execution begins at `PC=0`.
3. During execution, the core has exclusive access to the ITCM port. The host must not overwrite the program, but may continue to access the CSRs.

Reset initializes control and pipeline state only; it does not guarantee that ITCM is cleared. Software must load a valid program before starting execution. The host CSR interface does not imply implementation of Zicsr.

#### 3.2.2. Data Tightly Coupled Memory (DTCM)

DTCM contains four independent banks for weights, activations, and intermediate results. It uses 64-byte interleaving with `bank=addr[7:6]`. The per-bank capacity is configured by `B_bank`; total capacity is four times that value. Capacity constraints are listed in §6.2.

| Property | Definition |
| --- | --- |
| Per-bank ports | One independent 512-bit read port and one independent 512-bit write port (1R1W), each able to service one access in the same cycle |
| Transfer size and write enables | 64 B/beat; 64-bit byte-write-enable mask |
| Theoretical bandwidth | With one accepted beat per port per cycle and no bank conflicts, aggregate read and write bandwidths are each 2048 bit/cycle, or 256 B/cycle |
| Arbitration | Requests to different banks may proceed in parallel; multiple reads or multiple writes to the same bank are serialized, and requests without a grant stall |

The scalar domain, vector domain, three matrix units, and DMA access DTCM through the interconnect. Software should distribute concurrently accessed input, output, and staging buffers across banks. Peak port bandwidth does not imply equivalent task-level concurrency under the policy in §5.

### 3.3. Direct Memory Access (DMA)

The baseline implementation is specified in [DMA_ISA.md](DMA_ISA.md). It has separate
inbound/outbound command queues and 64-byte data buffers with a shared serial scheduler
and address/burst state. The DDR master is 32-bit and has one outstanding burst; commands
require 4-byte alignment and support both contiguous and unsigned-stride 2D transfers.

DMA uses a single AXI4 master interface and separate `Mvin` (external memory → DTCM) and `Mvout` (DTCM → external memory) engines. The hardware provides independent datapaths for the two directions, but the current scheduler does not activate commands in both directions concurrently.

Each engine contains a command queue, an address generator, burst-splitting logic, and a first-in, first-out (FIFO) data buffer. Commands specify the external address, DTCM address, transfer length, row count, and stride, supporting contiguous and two-dimensional transfers.

- The DTCM-side datapath is 512 bits wide. The AXI-side width matches the system interconnect, with width conversion where required.
- Split transactions according to the configured maximum burst length. No burst may cross a 4 KiB boundary.
- Stall request acceptance or issue when a command queue is full or data-buffer space is insufficient. Reserve space for return data before issuing a read request.
- `Mvin` completes after all external reads have finished and all destination bytes have been written successfully to DTCM. `Mvout` completes after all source reads have finished and every external write has received a successful response.
- Command identifiers and completion/error status support dependency tracking and synchronization. Do not reuse an identifier while outstanding responses could still be confused with responses to a newer command.

## 4. Out-of-Order Execution Policy

### 4.1. Tags, Age, and Resource Allocation

Each dynamic instruction owns one ROB entry. Instruction age is compared using `seq`; `tag={ROB_slot, generation}` identifies a version and matches dependencies. Register numbers cannot replace tags, and a circular-buffer slot index does not directly indicate age.

Dispatch performs the following steps atomically. If any required resource is unavailable, stall the current and subsequent instructions without leaving partial allocations or RAT updates:

1. Identify all actual sources and destinations, including the old destination, masks, store data, and configuration operands. Illegal encodings create exception entries.
2. Resolve sources: `x0` supplies zero; an unmapped source reads the architectural register file; a mapped source obtains a ready result or records its producer tag.
3. Allocate the ROB entry, required RS entry or control record, destination result slot, and MemQ entry together.
4. After source versions have been captured, update the RAT for each register destination other than `x0`.

An instruction with no register destination, or with destination `x0`, still occupies a ROB entry but allocates neither a RAT mapping nor a persistent result slot. It reports completion through a tagged event. `LW x0` must wait for the read response and check for errors before discarding the data.

When dispatch, writeback, and commit access the same version in the same cycle, the value must be delivered through a broadcast, a ready result slot, or a commit bypass. A consumer must not miss a broadcast and wait indefinitely. A producer's result slot must remain allocated until multi-cycle source capture has finished.

### 4.2. Back-End State Structures

The following table describes logical records and their lifetimes, without introducing unspecified signal names or binary encodings. Capacity and field-width constraints are listed in §6.2.

| Structure | Main fields | Update and release rules |
| --- | --- | --- |
| ROB | Valid bit, generation number, `seq`, PC, class, destination, result-slot/MemQ reference, execution/broadcast completion, exception, side-effect status | Allocate in order and update on writeback; release at commit or fault cleanup. An idle FU does not imply instruction completion |
| `RAT-X` / `RAT-V` | Valid bit, latest uncommitted `tag` | Update at dispatch, not cleared by writeback; commit clears only a matching mapping |
| RS | ROB tag, operation, immediate, source `ready/value/tag`, destination type, mask, control values | Release after all sources have been delivered and the FU accepts the instruction; retain the ROB entry and result slot |
| FU state | Task tag, pipeline-valid bits or iteration progress, acceptance conditions, completion-buffer occupancy | Advance through handshakes; define execution latency separately from initiation interval |
| Result buffer | Tag, type, 32-bit or 512-bit result, status, pending-recipient state | Arbitrate delivery to the ROB and all dependent consumers before release; hold data stable under backpressure |
| MemQ | Tag, `seq`, class, address/store-data dependencies, width, byte enables, active lanes, task-snapshot reference, progress, error | Reserve at dispatch; only the head may start. Dequeue on success; an error blocks subsequent entries from starting |
| Matrix task record | Owner tag, configuration snapshot, mode, active dimensions, K-tile position, partial-sum validity, output-write progress | Acquire physical resources after authorization; release after all output writes or fault cleanup have completed |
| DMA task record | Command snapshot, command/burst identifier (ID), progress, outstanding responses, FIFO reservations, first error, partial-completion status | Track from request acceptance; do not reuse IDs while doing so could confuse outstanding responses |
| Control-barrier record | ROB tag, kind, condition/configuration operands, next PC or termination state | Branches, configuration commands, `FENCE`, and `HALT` block subsequent dispatch; release the barrier after commit or exception handling |

RS, ROB, and result-slot capacities are independent. A free ROB entry does not imply an available result slot. Stores with no register result still occupy ROB and MemQ entries.

### 4.3. Issue, Completion, and Broadcast

An instruction may issue only when all required sources are ready, the FU can accept it, and completion-buffer capacity is available. Each FU selects the oldest ready candidate. An older instruction with unavailable operands does not block other executable instructions; shared circuits and ports still require arbitration.

The baseline has one logical common data bus (CDB), carrying at most one result per cycle with a maximum width of 512 bits. Scalar results use the low 32 bits and carry type information. Results completed by multiple FUs in the same cycle are buffered and broadcast using oldest-ready-first arbitration; unselected results remain buffered.

A broadcast writes the ROB result slot and wakes consumers in RSs, MemQ, branch records, and configuration records. It does not directly update architectural registers or the RAT. A producer whose mapping has been superseded by a younger writer in a write-after-write (WAW) dependency must still broadcast and commit. A vector result must combine new values for active lanes with old values for masked-off lanes; partial readiness is not exposed.

Instructions without register results complete through tagged events. Completion and error events sharing a port with data writeback must be buffered and arbitrated. Responses with invalid tags or mismatched generation numbers are discarded.

### 4.4. Commit and Version Reclamation

Only the ROB head may commit. If it has an exception, apply the fault constraints in §4.7 rather than the normal commit path. If execution, required broadcasts, or memory completion acknowledgments remain outstanding, the head waits; younger instructions cannot commit past it.

A normal result updates the architectural register file; only writes to `x0` are discarded. Clear the RAT mapping if it still matches the committing tag; otherwise retain the younger writer's mapping. The committing result must still update the architectural register to preserve the older version required for precise exceptions. Configuration commands, barriers, control transfers, and memory operations release their resources after their respective ROB-head actions complete.

A result slot can be reclaimed only after every registered consumer has captured its value. Consumers dispatched after commit read the architectural register file or the updated RAT mapping. ROB-slot reuse requires generation checks, and responses that could become ambiguous must be drained before the generation number wraps. Clearing a queue does not stop FUs or the external bus from returning responses.

### 4.5. Branches and Jumps

There is no direction predictor, branch target buffer (BTB), or return-address prediction stack. Control transfers are handled as follows:

1. On recognizing a conditional branch, `JAL`, or `JALR`, retain this instruction's PC, source values/tags, and immediate, and allocate a ROB entry in order. Stop subsequent fetch and dispatch, and invalidate younger prefetched responses. A full ROB must not allow instructions after the branch to proceed.
2. Earlier instructions continue executing and committing. Once the control-transfer instruction reaches the ROB head and its sources are ready, the S-BRU resolves the next PC.
3. Check only the selected next address for 4-byte alignment and ITCM bounds. The encoded target of a not-taken branch does not cause a target fault. The 2-byte encoding granularity of B- and J-type immediates does not guarantee alignment; after `JALR` clears bit 0, bit 1 must still be checked.
4. On a target fault, record an exception and do not commit the link value. Otherwise, commit the link result and resume fetching from the resolved next PC.

Every control transfer waits for earlier work to drain, including DMA and matrix tasks. Loop-branch overhead therefore depends on more than comparator latency.

### 4.6. Memory Barriers, Configuration, and Normal Termination

All three classes allocate ROB entries in order, block subsequent dispatch when recognized, and perform their actions at the ROB head:

| Operation | ROB-head action and completion conditions |
| --- | --- |
| `FENCE` | Wait until earlier instructions have committed and earlier MemQ entries and external transactions have completed; after commit, resume at `PC+4`. Ordering may be stronger than `pred/succ` requires, but not weaker |
| Configuration command | Wait for source operands, update the specified fields, commit, and resume; operands may be produced by scalar operations, loads, or vector reductions |
| `HALT` | Wait until earlier instructions have committed, no MemQ entry is active, and FUs, result buffers, and external transactions have completed their outstanding work; commit and mark the run complete. Does not write ordinary registers and is not a reset |

`HALT` takes effect only in a valid instruction stream. A prefetched zero word must not stop execution immediately, and a wrong-path `HALT` must remain cancelable.

### 4.7. Exception and Fault-Handling Constraints

This section consolidates the fault boundaries already established by the source document. It does not define new exception encodings, host registers, or recovery protocols.

- **Recording and commit:** Illegal encodings create ROB exception entries. Execution and memory errors update the corresponding state by tag. An exception at the ROB head invokes fault handling; younger instructions cannot commit past it.
- **Error-event delivery:** Error events sharing a port with result writeback require buffering and arbitration. A busy port must not cause an error event to be lost. Responses with invalid tags or mismatched generation numbers are discarded.
- **Memory-access blocking:** A MemQ error prevents subsequent entries from starting. DMA records the first error, partial-completion status, and outstanding responses; a failed task must not be dequeued as successful.
- **Outstanding transaction completion:** Clearing internal queues does not cancel external transactions. Task records and response identifiers remain subject to the release and reuse rules in §4.2 and §4.4.
- **Partial side effects:** In-order ROB commit does not make a multi-beat DMA operation transactionally atomic. Completed memory writes must not be assumed to roll back.

Instruction-fetch and selected control-transfer target checks are specified in §3.2.1 and §4.5. Fault-reporting fields, internal cleanup and drain ordering, host acknowledgment, and restart conditions remain unspecified; see §6.3.

## 5. Memory Access Policy

### 5.1. Enqueue, Activation, and Completion

Scalar `LW/SW`, vector loads and stores, complete matrix tasks, and DMA execution commands reserve MemQ entries in program order at dispatch, without waiting for address readiness. ITCM fetches do not enter MemQ. Configuration commands without memory side effects are managed by control barriers.

Only one entry may be active at a time. A younger entry cannot bypass a head entry that is not ready. The design performs no address speculation, load bypass of older stores, memory-access replay, or store-to-load forwarding. An individual entry may use bank-level parallelism, split transfers into beats, and pipeline bursts, but subsequent entries must wait for the entire preceding entry to complete.

| Operation | Activation conditions | Successful dequeue and ROB completion conditions |
| --- | --- | --- |
| Scalar `LW`, vector load | MemQ head; required address/mask inputs and return-data slots are ready; limited to DTCM reads without read side effects | All active data has returned, been merged, written to the ROB, and received by consumers; architectural register updates still wait for commit |
| Scalar `SW`, vector store | Both MemQ head and ROB head; address and store data ready; prechecks passed | All active bytes have been accepted by the target SRAM and reached a completion point observable by subsequent reads; merely entering a request FIFO is insufficient |
| Complete matrix task | Both MemQ head and ROB head; configuration and array resources ready | All input processing and K-tile computation complete, all outputs written to DTCM, and no task error |
| DMA `Mvin` | Both MemQ head and ROB head; command and return buffers valid | All external reads complete and all destination DTCM bytes written successfully |
| DMA `Mvout` | Both MemQ head and ROB head; command and transmit resources valid | All source reads complete and every external write has received a successful response |

An irreversible operation follows this sequence: reach the head → receive authorization to start → wait for completion → commit. Authorization is not successful commit. Waiting for commit before starting would create a deadlock in which completion and commit wait for each other.

A DTCM load may execute before reaching the ROB head and supply younger compute-only instructions because it reads ordinary SRAM without read side effects. This rule must not automatically be applied to future device reads. Ordinary integer addresses wrap to the integer register width (XLEN, 32 bits) before validation; DMA and matrix address-range generation must use arithmetic wide enough to detect length and stride overflow.

### 5.2. Data Dependencies and Deadlock-Avoidance Constraints

- Store data, masks, old destinations, and matrix/DMA parameters use the versions captured at dispatch. Execution must not reread architectural registers that have since been overwritten.
- An all-masked-off memory operation issues no SRAM request but still completes in order. A masked load preserves the old destination and cannot be treated as an instruction with no destination.
- Register dependencies of the MemQ head must come from older producers. Matrix inputs must come from earlier completed writes or accesses performed by the current task; the task cannot wait for a later MemQ entry to supply its data.
- Younger entries that have not started may occupy command records only. They must not acquire arrays, skew buffers, return buffers, or DTCM service paths needed by the head entry.
- Multiple matrix/DMA tasks cannot start early as background work. `FENCE` and `HALT` must still check outstanding external transactions rather than relying only on queue-valid bits.

### 5.3. Effective Concurrency Limits

| Scenario | Current policy |
| --- | --- |
| S-ALU, distinct vector FUs, and computation within one active memory entry | May execute concurrently |
| Multiple bank requests within one task, or multiple outstanding bursts within one DMA command | Permitted |
| Two independent active load entries, or a load bypassing an older store | Prohibited |
| A DMA command active concurrently with another matrix/DMA memory entry | Prohibited |
| Three complete matrix tasks running simultaneously on the three arrays | Prohibited |
| Younger independent compute-only instructions while a long DMA operation occupies the ROB head | May execute and buffer results, but cannot commit; the front end stalls when ROB entries, RS entries, or result slots are exhausted |

Four-bank peak bandwidth, bidirectional DMA datapaths, and three arrays describe hardware capabilities. They do not imply that the current ordered memory policy can exploit all of that concurrency simultaneously.

## 6. Parameter Summary

### 6.1. Fixed Architectural Configuration

This table summarizes the configuration; detailed semantics and boundaries are specified in the referenced sections.

| Property | Configuration | Definition |
| --- | --- | --- |
| Instruction streams / PC | One stream / 32-bit PC | §1 |
| Instruction length / fetch alignment | 32 bits / 4 B | §2.1, §3.2.1 |
| Scalar registers | 32×32 bits; `x0` is constant zero; no separate floating-point register file | §2.1, §3.1 |
| Vector registers | 32×512 bits; sixteen 32-bit lanes per register; BF16 occupies the low 16 bits | §2.2 |
| Fetch / dispatch / commit | Each at most one instruction per cycle | §1 |
| Global broadcast | One result per cycle, up to 512 bits | §4.3 |
| Branch policy | No prediction; wait for earlier instructions to commit | §4.5 |
| ITCM | 512 KiB, 32-bit single-port synchronous SRAM; response in the next cycle | §3.2.1 |
| DTCM | Four banks, each with 512-bit 1R1W ports; 64-byte interleaving, `bank=addr[7:6]`; theoretical read and write bandwidths of 256 B/cycle each | §3.2.2 |
| Matrix arrays | One BF16 and two INT8 arrays, each with 16×16 PEs; BF16 uses OS, INT8 supports OS/WS | §2.3 |
| Matrix accumulation / output | BF16 input: FP32/BF16; INT8 input: INT32/INT32; retain partial sums across K-dimension tiles within a task | §2.3 |
| Active MemQ entries | At most one; stores, matrix tasks, and DMA also require ROB-head authorization | §5.1 |
| DMA | One AXI4 master interface with `Mvin`/`Mvout` datapaths; commands in the two directions are not activated concurrently | §3.3, §5.3 |
| Rounding / floating-point control | Fixed RNE; no writable `frm/fcsr` | §2.2 |

### 6.2. Implementation-Defined Parameters

These parameters affect area, timing, and platform mapping without changing the specified semantics. Values must not be invented without register-transfer level (RTL) or synthesis evidence. Hardware and software must use the same generated configuration.

| Parameter | Constraints |
| --- | --- |
| `N_ROB` | Must accommodate the current dispatch allocation; enlarging the window does not increase commit width |
| `N_RS[fu]` | Each implemented FU must have at least one available candidate record |
| `N_XRES/N_VRES` | Scalar/vector result slots store 32-bit/512-bit values, respectively, and are allocated atomically with the ROB entry |
| `N_MEMQ` | At least one; increasing queue depth does not increase the number of active entries |
| `N_RESULT[fu]` | Issue must guarantee that a result can be buffered or backpressured |
| `G_tag/W_seq` | Generation-number/sequence-number widths; drain outstanding responses or guarantee unambiguous age comparisons before wraparound |
| `B_bank` | Per-bank byte capacity must be a multiple of 64 B; total capacity is four times this value and is constrained by the 32-bit data address range |
| `A_AXI/W_AXI` | External address/data widths; configuration fields, width conversion, write strobes (`WSTRB`), and drivers must agree |
| AXI maximum burst length / outstanding transaction count | Bursts must not cross a 4 KiB boundary; FIFO and response-ID capacity must cover accepted transactions |
| `L_fu/II_fu` | Specify operation latency and initiation interval separately; iterative division and matrix tasks must not be modeled as having single-cycle throughput |
| Host memory-mapped input/output (MMIO) windows | ITCM, DTCM, and control regions must not overlap; offsets from earlier capacity configurations must not be reused without adjustment |

### 6.3. Open Specification Issues

The following items still lack actionable interface or boundary definitions. Established constraints are retained in the preceding sections. Standard RVV behavior and common implementation practice must not be substituted for unresolved design decisions.

| Topic | Established context and missing definitions |
| --- | --- |
| Reduction interface | The source architecture routes scalar reduction outputs to the `x` namespace. Vector_ISA.md defines only the functions of `vfredsum/vfredmax`, not their forms, destination types, initial values, precision, or masking rules; a concrete instruction interface cannot be inferred |
| Vector encodings and memory interfaces | Vector_ISA.md does not define machine encodings, vector load/store mnemonics, addressing, or in-memory element layouts; general memory-access rules do not replace these definitions |
| Permutation and shift boundaries | `vrgather.vv` is optional. Unsigned indices and out-of-range zero results are RVV references only, with no decision on adoption by this ISA. Out-of-range slide distances, effective shift-amount bits, and `.vi` encoding ranges remain unspecified |
| Mask details | Writes outside the low 16 destination bits for comparisons, exact behavior of masked-off comparison lanes, and the mapping from mask-bit values to the two `vmerge.vvm` sources are not fully defined |
| Floating-point boundaries | Fixed RNE is specified, but not-a-number (NaN), infinity, subnormal, signed-zero, division-by-zero, out-of-range conversion, and exception-reporting behavior are incomplete. Forms, data types, error bounds, and special-value handling for `Vexp/Vrcp/Vrsqrt` are also unspecified |
| Matrix/DMA/configuration fields | Task parameters and record categories are listed, but complete command encodings, field widths, and configuration-register maps are absent. Matrix active-dimension boundaries and INT32 accumulation overflow behavior are unspecified |
| Data-address prechecks | Scalar `LW/SW` alignment, DTCM addressing and host burst errors are defined in Appendix B and Scalar_ISA.md. Vector/matrix/DMA multi-beat behavior remains open |
| Fault-handling procedure | Appendix B defines scalar fault records, drain/cleanup, acknowledgment and restart. Fault handling for the unimplemented vector/matrix/DMA engines remains open |

## 7. References

- [RISC-V V specification](https://docs.riscv.org/reference/isa/unpriv/v-st-ext): naming and functional comparison only; not a compatibility claim.
- [Ibex documentation](https://ibex-core.readthedocs.io/en/latest/index.html).
- [BOOM documentation](https://docs.boom-core.org/en/latest/index.html).

## Appendix A. RTL Development Environment

The `XJ` conda environment provides the Linux x86-64 RTL toolchain. On this workstation, modern conda is installed under `~/miniforge3` and `XJ` under `~/miniforge3/envs/XJ`; the legacy `~/anaconda` installation is retained. Bash initializes modern conda without automatically activating an environment.

### A.1. Activate and Use

Run from the repository root using the repository launcher; no shell activation is required:

```bash
./mill xj.compile
./mill xj.test
```

`./mill` runs the Mill installed in `XJ` through `conda run -n XJ`, always with `--no-server`. It uses `CONDA_EXE` when set, otherwise `$HOME/miniforge3/bin/conda`. The launcher selects the toolchain only for its child process; bare `mill` still depends on the parent shell's `PATH`. To use native tools interactively:

```bash
source "$HOME/miniforge3/etc/profile.d/conda.sh"
conda activate XJ
command -v verilator
verilator --version
```

The selected Verilator should be `$CONDA_PREFIX/bin/verilator`, version 5.052. If a test log shows `/usr/bin/verilator` followed by `%Error: Invalid Option: --build`, it selected the retained system Verilator 4.028, which cannot compile the ChiselSim harness. Run `./mill xj.test` instead of bare `mill xj.test`; do not remove `--build` or change RTL to work around this toolchain error.

The launcher was verified with Conda activation variables unset and `PATH=/usr/bin:/bin`: all 30 simulations across six suites passed, and the generated simulation Makefile selected `~/miniforge3/envs/XJ/bin/verilator`.

Place Chisel sources in `src/main/scala`. The `xj` module in `build.mill` pins Scala, the Chisel library, and the matching compiler plugin; Chisel is not a Python package. `.mill-jvm-opts` supplies the project root. Chisel automatically resolves its matching firtool; do not override it with an unrelated system version. First-time dependency resolution requires network access.

Use `gtkwave path/to/waveform.vcd` to inspect a simulation trace. GTKWave requires a graphical display; its native window was verified through WSLg on this workstation. Run `conda deactivate` to leave the environment; activation/deactivation switches and restores `JAVA_HOME`.

### A.2. Verified Versions

| Component | Version |
| --- | --- |
| Python | 3.12.15 |
| OpenJDK | 21.0.10-internal |
| Mill | 1.0.6, official upstream JVM distribution |
| Scala | 2.13.18 |
| Chisel and compiler plugin | 7.16.0 |
| Chisel-managed firtool | 1.160.0 |
| Verilator | 5.052 |
| GTKWave | 3.3.121 |
| GNU C++ compiler | 15.3.0 |

Verification used a temporary signed INT8 multiply-accumulate circuit: Chisel emitted SystemVerilog, Verilator compiled and ran the C++ simulation, and GTKWave displayed the resulting VCD. Checks covered reset, positive and negative products, enable hold, an INT8 boundary, and reset priority. The temporary circuit and simulation files are not part of the NPU implementation.

### A.3. Recreate the Environment

With modern conda available, run from the repository root:

```bash
conda env create --file environment.yml
conda activate XJ
```

`environment.yml` installs the native tools from conda-forge. Mill is installed separately because the tested conda-forge `mill=1.0.6` package incorrectly requested unpublished `SNAPSHOT` dependencies. Install the verified [official Mill 1.0.6 artifact](https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.0.6/) into the active environment using system `curl` and coreutils:

```bash
(
  set -eu
  : "${CONDA_PREFIX:?Activate XJ first}"
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  curl --fail --location --output "$tmp" \
    https://repo.maven.apache.org/maven2/com/lihaoyi/mill-dist/1.0.6/mill-dist-1.0.6.exe
  printf '%s  %s\n' \
    b24c8777c4be9581b31fad821c16d60fbdaae22a27a765b06265f194a6146b08 \
    "$tmp" | sha256sum --check
  install -m 755 "$tmp" "$CONDA_PREFIX/bin/mill"
)
./mill xj.compile
```

The `.exe` filename is upstream's cross-platform JVM launcher, not a Windows-only binary. Scala/Chisel configuration follows the [official Chisel installation guidance](https://www.chisel-lang.org/docs/installation).

## Appendix B. Original Scalar-Only Platform Baseline

This appendix describes the original scalar configuration. The current
`src/main/scala/top/XiangJiangNPU.scala` also includes vector, matrix, and DMA, including
an external-memory AXI master; see README.md and DMA_ISA.md. It still has no cache,
branch predictor or privilege architecture. [Scalar_ISA.md](Scalar_ISA.md) fixes the 33 supported instructions and their encodings; this is an RV32I subset plus a custom zero-word HALT, not a complete RV32I platform.

### B.1. Module Organization and Execution

Directory and package organization follow [XiangShan's Scala source layout](https://github.com/OpenXiangShan/XiangShan/tree/kunminghu-v3/src/main/scala): `xiangjiang` owns the core, `device` owns host-facing peripherals, and `top` owns platform integration and RTL generation. This is an organizational reference, not an import of XiangShan RTL, Rocket Chip, or its speculative execution model. Paths below are relative to `src/main/scala`.

| Directory / module | Responsibility |
| --- | --- |
| `xiangjiang/Parameters.scala`, `xiangjiang/Bundle.scala` | Elaboration parameters, full ROB tags, captured operands, micro-operations and completion records |
| `xiangjiang/frontend/Frontend.scala`: `Frontend` | One outstanding synchronous ITCM request, one fetch buffer, barrier pause and committed redirect |
| `xiangjiang/backend/decode/DecodeUnit.scala`: `DecodeUnit` | Strict instruction legality and source/destination metadata |
| `xiangjiang/backend/rename/RenameStage.scala`: `RenameStage` | 31 stored architectural registers, RAT, source-version capture and commit/CDB/result bypasses; x0 has no storage |
| `xiangjiang/backend/issue/IssueUnit.scala`: `IssueUnit` | Separate ALU and branch reservation stations, oldest-ready issue and full-tag wakeup; branch issue is ROB-head-only |
| `xiangjiang/backend/fu/FunctionalUnit.scala`: `ALUUnit`, `BranchUnit` | Integer execution and selected-target validation; registered result buffers retain completions under backpressure |
| `xiangjiang/backend/rob/Rob.scala`: `Rob` | Ordered retirement, exception metadata and an independently allocated scalar-result pool |
| `xiangjiang/mem/LSU.scala`: `LSU` | Ordered MemQ, independent oldest-base-ready address scheduler, captured store data and one active memory operation |
| `xiangjiang/mem/TCM.scala`: `InstructionTcm`, `DataTcm` | Actual synchronous single-port ITCM and four independent 512-bit 1R1W DTCM banks |
| `xiangjiang/ScalarCore.scala`: `ScalarCore` | Atomic dispatch, single oldest-completion CDB, unified commit, barrier handling and precise fault shutdown |
| `device/AXI.scala` | AXI4/AXI4-Lite bundles; AXI4 takes an ID width rather than core parameters |
| `device/ControlRegisters.scala`, `device/MemorySubsystem.scala` | AXI4-Lite CSRs and AXI4-Full host SRAM access |
| `top/XiangJiangNPU.scala`, `top/Generate.scala` | Connect the core and host devices; emit RTL and configuration |

Core sources do not import `device` or `top`. AXI bundles are independent of `XJParams`; the control-register and memory devices remain XiangJiang-specific adapters that use the core's status, capacity and TCM interfaces. TCM organization stays in `xiangjiang/mem` because its banking and access rules belong to the NPU architecture.

Dispatch and commit each accept at most one instruction per cycle. S-ALU and S-AGU schedule independently; a ready younger ALU instruction can execute behind an older blocked dependency. ALU/branch completion latency is one cycle, with initiation interval one when the previous result can leave. This does not imply one instruction per cycle through the single-outstanding frontend.

Only successful commit changes architectural registers. Each source captures its pre-remap producer version or value; store data is never reread from a younger register version. No-result instructions consume ROB/queue records but no scalar-result slot. The CDB atomically updates the ROB and all wakeup consumers; stale or duplicate full `{slot,generation}` tags cannot complete a new instruction. Resources freed on an edge are available the following cycle, not through same-cycle allocation bypass.

Generation and age counters never silently wrap: allocation stops at exhaustion until the ROB is empty and all completion sources are drained, then counters restart. Fault cleanup stops new issue, drains accepted memory traffic and buffered results, then discards unissued reservations, RAT entries and uncommitted ROB/results. Queued but unissued work is not an outstanding completion source.

### B.2. Default Parameters and SRAM Addressing

`XJParams` is the elaboration-time configuration. `top.Generate` emits its selected values in `generated/xiangjiang-config.json`; software can also read capacities through CSRs.

| Parameter | Default |
| --- | --- |
| ITCM capacity | 512 KiB, fixed |
| DTCM bank capacity / total | 4 KiB / 16 KiB |
| ROB entries | 8 |
| ALU / branch reservation entries | 4 each |
| Scalar-result slots | 6 |
| MemQ entries | 4 |
| Generation / sequence width | 4 / 16 bit |
| AXI address / data / ID width | 32 / 32 / 4 bit |

Core instruction and data spaces are separate. Core `LW/SW` use DTCM addresses starting at zero; they must not include the host window base. Accesses are little-endian, 4 B aligned, and range-checked before SRAM index truncation. Misaligned `LW/SW` fault without issuing a memory request.

| AXI4-Full host byte range | Target |
| --- | --- |
| `[0x00000000, 0x00080000)` | ITCM |
| `[0x10000000, 0x10000000 + DTCM_BYTES)` | DTCM |
| All other addresses | `SLVERR`, no alias |

For zero-based DTCM byte offset `a`, bank is `a[7:6]`, row is `a >> 8`, and byte lane within the 512-bit row is `a[5:0]`. Each bank capacity is a multiple of 64 B. The scalar adapter accesses one 32-bit word and uses byte write enables; the physical banks expose independent read and write ports. Host/core adapters arbitrate port collisions, including same-row read/write conflicts. Reset does not initialize or erase SRAM contents; hosts must load all code/data they will read.

### B.3. AXI4-Full Host Contract

The memory port is a slave, distinct from the AXI4-Lite control slave. The integrating system chooses the control slave's base address. No global control address is implied by its local CSR offsets.

- Independent AW/W/B and AR/R channels; W may precede AW. At most one write burst and one read burst are outstanding. Responses preserve IDs and remain stable until accepted.
- Support aligned 1-, 2-, and 4-byte beats. `INCR` supports 1–256 beats, `FIXED` 1–16, and `WRAP` 2/4/8/16. A burst must stay within a 4 KiB page and wholly within one permitted SRAM window. Address calculations retain overflow bits during validation.
- Narrow writes use address-selected byte lanes; asserted WSTRB bits outside those lanes are errors. Inactive narrow-read lanes are zero. Exclusive/locked transactions are rejected; cache/prot/qos attributes are ignored.
- Address, size, burst, length, bounds and permission errors are checked before any write. Invalid bursts still drain their specified beats and return `SLVERR`; read errors return zero data and preserve the requested response count/last marker.
- Incorrect WLAST or invalid WSTRB suppresses the offending beat and all remaining beats, with sticky `SLVERR`. Earlier valid writes are not rolled back. AWLEN, not WLAST, determines the drain length; masters must still supply all AWLEN+1 beats.
- Host memory accesses accepted while the core is busy return `SLVERR` and do not change SRAM. CSR reads remain available. Start is rejected while any host memory channel is buffered, in flight, awaiting response acceptance, or presenting a new request.

### B.4. AXI4-Lite Control Registers

All offsets below are local, 32-bit and 4 B aligned. AW and W may arrive independently in either order. Responses are buffered and stable under backpressure. Unknown/misaligned accesses and writes to read-only registers return `SLVERR`.

| Offset | Name | Access / value |
| --- | --- | --- |
| `0x00` | CONTROL | Write bit 0: START; bit 1: ACK. Both together are invalid. Read returns zero |
| `0x04` | STATUS | Read: bit 0 BUSY, bit 1 DONE, bit 2 FAULT |
| `0x08` | FAULT_PC | Faulting instruction PC |
| `0x0C` | FAULT_INST | Original instruction word; zero for a failed instruction fetch |
| `0x10` | FAULT_CAUSE | Local fault code from Scalar_ISA §8, not privileged `mcause` |
| `0x14` | FAULT_ADDR | Fault address, or the illegal instruction word for illegal-instruction faults |
| `0x18` | ITCM_BYTES | ITCM capacity |
| `0x1C` | DTCM_BYTES | Total DTCM capacity |
| `0x20` | CONFIG_ROB | ROB entry count |
| `0x24` | CONFIG_XRES | Scalar-result slot count |
| `0x28` | CONFIG_MEMQ | MemQ entry count |
| `0x2C` | CONFIG_ISSUE | Reservation entries per issue unit |
| `0x30` | VERSION | Interface version 1 |
| `0x100 + 4*n` | XREG[n] | Committed x0–x31, read only; x0 always zero |

CONTROL honors WSTRB: START/ACK are interpreted only when byte lane 0 is enabled; other data bits are ignored. START requires `!BUSY && !DONE && !FAULT` and a completely idle host memory interface. ACK requires `!BUSY`, clears terminal status/fault fields, and does not clear registers or SRAM. A successful control write produces a single pulse only after both AW and W are captured and permission checks pass.

### B.5. Host Run and Fault Recovery

1. Reset the core, or ACK an earlier terminal state after BUSY becomes zero.
2. Load the program at ITCM offset zero and initialize required DTCM data over AXI4-Full. Consume all B/R responses before START.
3. Write START. Every run begins at PC zero; there is no programmable entry-PC register.
4. Poll STATUS. HALT commits only after older work and all execution/memory activity drain, then sets DONE and clears BUSY. FENCE drains older work and resumes at PC+4.
5. On FAULT, wait for BUSY to clear before accessing SRAM or acknowledging. Fault fields latch when the exception reaches the ROB head; BUSY can remain set during cleanup. Earlier committed state is retained, the faulting instruction does not commit, and younger state is discarded. No younger store receives ROB-head authorization.
6. Read results or fault fields, ACK, optionally replace program/data, and START again. Successful HALT and fault recovery both preserve committed registers and SRAM; only architectural-register reset clears x1–x31.

There is no instruction-level trap handler or resume-at-fault mechanism. A restart is a new execution from PC zero with retained committed state. Scalar stores are checked before their single word request; malformed host multi-beat writes follow the partial-side-effect rule in §B.3.

### B.6. Build and Verification

From the repository root using the `XJ`-aware launcher (no prior shell activation required):

```bash
./mill xj.test
./mill xj.runMain top.Generate generated
```

The generator emits split SystemVerilog, verification layers, `filelist.f`, and `xiangjiang-config.json`. Downstream tools should consume `filelist.f` **with `generated/` as their working directory**, rather than compiling only `XiangJiangNPU.sv`; file-list entries are relative to that directory.

All 30 hardware simulations across six suites in `src/test/scala/xiangjiang` passed after the package migration. Coverage includes decoder/ALU/branch boundaries, ROB/result capacity, rename hazards, stale tags and wrap drains, independent issue, ordered memory, AXI channels/bursts/protection, bank concurrency, complete host-loaded programs, precise faults and restart. The integrated program suite also exercises singleton result/issue/MemQ resources.

The migrated `top.Generate` entry emitted RTL, verification layers, a file list and configuration that were byte-for-byte identical to the pre-refactor output. An independent Verilator run compiled this output using `filelist.f`, loaded a summation program through AXI, and obtained x2=x3=DTCM[64]=21 with exactly 24 ordered commits and FENCE/HALT. A misaligned store faulted at PC=4 with cause=6 and address=66 without younger register or SRAM changes. ACK and restart preserved committed state and produced x5=DTCM[68]=22. The temporary smoke driver is not part of the design.

