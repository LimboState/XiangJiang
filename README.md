# XiangJiang

XiangJiang is a single-instruction-stream NPU core with a unified frontend,
out-of-order scalar/vector execution, and in-order commit.  The current
platform implements the scalar subset defined by `docs/Scalar_ISA.md` and the
fixed 16-lane vector domain defined by `docs/Vector_ISA.md`.

- Scalar: RV32I integer subset, `LW`/`SW`, `FENCE`, and the exact custom-0
  `HALT` word `0x0000000B`.
- Vector: 32 x 512-bit registers, 16 INT32/BF16 lanes, masked arithmetic,
  BF16 fused multiply-add and conversions, permutation, comparison, reduction,
  and `Vexp`/`Vrcp`/`Vrsqrt`.
- Memory: synchronous 512 KiB ITCM, four 512-bit 1R1W DTCM banks, and one
  ordered MemQ with a single active memory item.
- Host: AXI4-Full memory slave and AXI4-Lite control/status registers.

```bash
./mill xj.test
./mill xj.runMain top.Generate generated
```

`top.Generate` emits split SystemVerilog, verification layers, `filelist.f`,
and `xiangjiang-config.json` under the selected output directory.
