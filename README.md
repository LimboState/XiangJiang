# XiangJiang

XiangJiang is a single-instruction-stream NPU core with a unified frontend,
out-of-order scalar/vector/matrix execution, and in-order commit.  The current
platform implements the scalar subset defined by `docs/Scalar_ISA.md`, the
fixed 16-lane vector domain defined by `docs/Vector_ISA.md`, and the 16x16
matrix domain defined by `docs/Matrix_ISA.md`.

- Scalar: RV32I integer subset, `LW`/`SW`, `FENCE`, and the exact custom-0
  `HALT` word `0x0000000B`.
- Vector: 32 x 512-bit registers, 16 INT32/BF16 lanes, masked arithmetic,
  BF16 fused multiply-add and conversions, permutation, comparison, reduction,
  and `Vexp`/`Vrcp`/`Vrsqrt`.
- Matrix: one BF16 and two INT8 16x16 arrays, `MSETMODE`/`MLOAD`/`MSTORE`
  tile movement, `MMUL`/`MMACC`/`MEW`/`MTRANS`, and `MWAIT`/`MBARRIER`/
  `MFENCE` synchronization.
- Memory: synchronous 512 KiB ITCM, four 512-bit 1R1W DTCM banks, and one
  ordered MemQ with a single active memory item.
- Host: AXI4-Full memory slave and AXI4-Lite control/status registers.

```bash
PATH="$PWD/scripts/verilator-5.052-xinit-zero:/opt/verilator-5.052/bin:$PATH" ./mill xj.test
./mill xj.runMain top.Generate generated
```

`XiangJiangNPUSpec` needs Verilator's initial-X values forced to zero on this
toolchain. Run that test with the repository wrapper so the installed 5.052
binary is used with the required option:

```bash
PATH="$PWD/scripts/verilator-5.052-xinit-zero:/opt/verilator-5.052/bin:$PATH" \
  /usr/local/bin/mill --no-server xj.test.testOnly xiangjiang.XiangJiangNPUSpec
```

`top.Generate` emits split SystemVerilog, verification layers, `filelist.f`,
and `xiangjiang-config.json` under the selected output directory.

Standalone FPGA synthesis uses the Windows Vivado 2021.2 installation from WSL:

```bash
cd /home/zhang/XiangJiang-LimboState-2
/usr/local/bin/mill --no-server xj.runMain top.Generate generated
python3 scripts/run-vivado-synth.py
```

The runner uses `D:\Xilinx\Vivado\2021.2\bin\vivado.bat`, snapshots the current
RTL under `D:\xj-synthesis`, and copies logs and reports back to
`out/vivado-synth/limbostate2-*/`. Input hashes are saved in `inputs.json`.
Use `--vivado /mnt/.../vivado.bat` or `--stage-dir /mnt/...` to change these paths.
The Tcl script synthesizes `XiangJiangNPU` out of context for
`xczu9eg-ffvb1156-2-e` with a 10 ns clock and two synthesis threads.
It reports utilization, timing, unconstrained paths and DRC results, and writes
a checkpoint and netlist. These are FPGA estimates before placement;
board I/O constraints, implementation, bitstream generation and ASIC mapping
require their respective flows. The existing debug outputs are included.
