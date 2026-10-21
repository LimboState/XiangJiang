# XiangJiang

Scalar-only Chisel NPU with a 33-instruction RV32I subset, ordered commit, precise faults, AXI4-Lite control registers, and AXI4 host access to ITCM/DTCM. Vector, matrix, and DMA engines are not implemented.

## Build and simulate

Set up the `XJ` environment using the [toolchain instructions](docs/sketchNPU.md#appendix-a-rtl-development-environment), then run from the repository root:

```bash
conda activate XJ
mill xj.test
mill xj.runMain top.Generate generated
```

The RTL top module is `XiangJiangNPU`. Use `generated/filelist.f` with `generated/` as the downstream compiler's working directory.

## Source organization

Under `src/main/scala`, `xiangjiang` contains the core (`frontend`, `backend`, and `mem`), `device` contains the host interfaces and peripherals, and `top` contains integration and RTL generation. Directory and package boundaries follow [XiangShan](https://github.com/OpenXiangShan/XiangShan/tree/kunminghu-v3/src/main/scala); the implementation does not depend on XiangShan RTL or Rocket Chip.

- [Architecture, module map, CSRs, and verification](docs/sketchNPU.md#appendix-b-implemented-scalar-only-platform)
- [Scalar ISA and encodings](docs/Scalar_ISA.md)