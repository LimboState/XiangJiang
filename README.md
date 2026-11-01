# XiangJiang

XiangJiang NPU 是面向机器学习模型推理的单指令流硬件加速器：统一指令前端、
标量/向量/矩阵协同执行、顺序提交，以及软件管理的 TCM 与二维 DMA。

架构基准与唯一事实来源：[XiangJiang NPU V1 架构](docs/XJ-NPU.md)。
迁移追踪矩阵：[docs/XJ-NPU-IMPLEMENTATION-PLAN.md](docs/XJ-NPU-IMPLEMENTATION-PLAN.md)。

**完整 ISA 总表：** [全部指令、编码、操作数与实现状态](docs/XJ-ISA-Support.md)。
该表区分架构目标、当前机器译码可达的指令和仅存在 FU 路径的操作，并列出已知偏差。
以下为功能概览，不代表完整 RISC-V 扩展兼容；具体支持边界以总表为准。

- **标量**：`rv32im_zicsr_zifencei` + Zbb 选集（`ANDN/ORN/XNOR/MIN/MINU/MAX/MAXU/
  CLZ/CTZ/CPOP/SEXT.B/SEXT.H/ZEXT.H/ROL/ROR/RORI/REV8/ORC.B`）+ F 扩展选集
  （`FLW/FSW/FADD.S/FSUB.S/FMUL.S/FDIV.S/FSQRT.S/FMADD.S/FMSUB.S/FNMSUB.S/
  FNMADD.S/FEQ.S/FLT.S/FLE.S/FCVT.W.S/FCVT.WU.S/FCVT.S.W/FCVT.S.WU.S/
  FMV.X.W/FMV.W.X`）。FP32 支持静态舍入模式与 `frm`，候选 `fflags` 按程序
  顺序在提交点合并。逐条编码与状态见
  [docs/XJ-ISA-Support.md](docs/XJ-ISA-Support.md)。
- **向量**：`VLEN=DLEN=128`、`ELEN=32`，`vsetvli/vsetivli/vsetvl` 配置，
  SEW 8/16/32（EMUL≥32 的 LMUL ∈ {1, 1/2, 1/4}；不支持的 vtype 置 `vill`），
  tail/mask 保留，整数/逻辑/移位/比较、FP32 算术/FMA/除法/开方/比较/转换/
  归约、slide/gather/merge，以及 `vle8/vle16/vle32/vse8/vse16/vse32`。
  `vstart>0` 与 `vill` 下执行向量指令按非法指令报错。完整 Zve32f 不声明。
- **矩阵**：三个 8×8 阵列——MU0（BF16 输入 / FP32 累加，仅 OS），MU1/MU2
  （INT8 输入 / INT32 累加，OS/WS）。`MSETMODE/MLOAD.A/MLOAD.B/MLOAD.ACC/
  MMUL/MMACC/MEW/MTRANS/MSTORE/MBARRIER/MFENCE`。结果先入带 ROBTag 的
  staging，仅在 ROB Head 提交时发布到 committed MASF；V1 的 `MWAIT` 已移除
  （非法指令）。
- **访存**：256 KiB ITCM（Core 地址 0x0–0x3FFFF）、1 MiB DTCM（4 × 256 KiB、
  512-bit、1R1W、64 B 交织）、统一 MemQ（标量/FP/向量/矩阵/DMA 顺序进入，
  单 active；Store/MSTORE/MVIN/MVOUT 需 ROB Head 授权）。
- **DMA**：`DSETSIZE/DSETSTRIDE/MVIN/MVOUT`，单活动命令、单 outstanding
  AXI burst、64 B 本地缓冲（单次读 burst 有效数据 ≤ 64 B）、4 KiB 对齐
  INCR、完整命令预检查、first-error/error-address/bytes-completed/partial
  记录与已接受事务排空。
- **Host**：`S_AXI_CTRL`（AXI4-Lite）控制/状态/故障记录/DMA 状态/标识，
  `S_AXI_MEM`（AXI4-Full）窗口 `0x000000–0x03FFFF` ITCM、`0x040000–0x0FFFFF`
  Reserved、`0x100000–0x1FFFFF` DTCM，`M_AXI_EXT`（AXI4-Full）外存主接口。
  寄存器映射见 [docs/XJ-HOST-REGISTERS.md](docs/XJ-HOST-REGISTERS.md)，
  AXI/DMA 行为见 [docs/XJ-AXI-DMA.md](docs/XJ-AXI-DMA.md)。
- **调试**：Halt/Resume/Step 属于架构建议项，当前不实现、不作为 capability
  对外声明；`EBREAK/ECALL` 通过带独立 cause 的精确事件（故障记录）交给 Host。

```bash
./mill xj.test
./mill xj.runMain top.Generate generated
```

`top.Generate` 在目标目录输出拆分 SystemVerilog、`filelist.f` 与
`xiangjiang-config.json`。

Vivado IP 打包（默认 ZCU102）：

```bash
./mill xj.runMain top.Elaborate generated/vivado 100000000
vivado -mode batch -source generated/vivado/package_ip.tcl
```

`top.Elaborate` 额外输出 `XiangJiangAXI` 封装（Xilinx AXI 元数据、低有效复位、
紧凑 Host 地址窗口）、综合清单与 IP 打包脚本。
