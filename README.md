# XiangJiang

XiangJiang is a single-instruction-stream NPU core with a unified frontend,
out-of-order scalar/vector/matrix execution, and in-order commit.  The current
platform implements the scalar subset defined by `docs/Scalar_ISA.md`, the
fixed 16-lane vector domain defined by `docs/Vector_ISA.md`, and the 16x16
matrix domain defined by `docs/Matrix_ISA.md`, and the ordered DTCM/DDR DMA
extension defined by `docs/DMA_ISA.md`.

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
- DMA: `DSETSIZE`/`DSETSTRIDE`, `MVIN`/`MVOUT`; contiguous and 2D transfers,
  512-bit DTCM interface, 32-bit AXI4 DDR master, 4 KiB burst splitting,
  backpressure and drained error completion. Transfers require 4-byte alignment;
  one command / one burst is active at a time.
- Host: AXI4-Full memory slave and AXI4-Lite control/status registers.
  The separate `io.ddr` master connects to a system DDR controller/interconnect;
  no DDR PHY/controller or cache-coherency logic is included.

```bash
./mill xj.test
./mill xj.runMain top.Generate generated
```

`top.Generate` emits split SystemVerilog, verification layers, `filelist.f`,
and `xiangjiang-config.json` under the selected output directory.

For Vivado IP packaging (ZCU102 by default):

```bash
./mill xj.runMain top.Elaborate generated/vivado 100000000
vivado -mode batch -source generated/vivado/package_ip.tcl
```

`top.Elaborate` additionally emits the `XiangJiangAXI` wrapper with Xilinx AXI
metadata, active-low reset, compact host address apertures, a synthesis manifest,
and an IP packaging script. See [Vivado IP integration](docs/Vivado_IP.md) for
address mapping, clock/reset wiring, and DDR/cache requirements.
