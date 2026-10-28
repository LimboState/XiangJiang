# XiangJiang / ZCU102 FPGA 资源规划

## 依据与边界

板级资料：用户提供的 `ug1182-zcu102-eval-bd.pdf`，UG1182 v1.7，2023-02-21。
已核对 Table 1-1（印刷页 8–9）、PS DDR（页 25）、PL DDR（页 29–30）、
Table 3-12 时钟（页 44）。这是一份资源规划，不是综合利用率报告；不改变当前 RTL/ISA 参数。

- 器件：XCZU9EG-2FFVB1156E，Vivado part `xczu9eg-ffvb1156-2-e`。
- 手册列出：599,550 logic cells、548,160 CLB FF、32.1 Mb block RAM、
  8.8 Mb 最大 distributed RAM、2,520 DSP slices。
- Logic cells 不是 LUT 数。ZU9EG 的规划口径采用 274,080 LUT、912 RAMB36 等效块
  （1,824 RAMB18），这些具体原语数量需以 Vivado 所选器件的 Available 列核对；
  不应由手册四舍五入后的 32.1 Mb 直接求精确块数。
- PS DDR：4 GB、64-bit SODIMM，使用 PS 硬 DDR 控制器。
- PL DDR：4 Gb = 512 MB、16-bit，独立 DDR4 组件，需要 PL DDR controller/PHY。
- USER_SI570 的 300 MHz 是参考时钟，不是 NPU 已验证的运行频率。
- 本方案不预算 UltraRAM；不要将 32.1 Mb BRAM 误算成 32.1 MB。

## 推荐映射

| 模块 | 当前规模 | 推荐资源 | 说明 |
| --- | --- | --- | --- |
| ITCM | 512 KiB，131072×32，字节写使能 | BRAM | 不允许默默退化为大量 FF/LUT |
| DTCM | 4×4 KiB，每 bank 64×512，1R1W | 先比较 LUTRAM / BRAM 两版 | 保持同步读一拍、64-bit byte mask |
| BF16 PE product | 256×8×8 有效数乘法，16-bit BF16 product 寄存器 | DSP 优先试验，和 LUT 版比较 | 浮点指数/舍入仍需逻辑资源 |
| BF16 FP32 ACC | 256×32-bit | FF + LUT/Carry | 非原生 DSP48E2 浮点累加；仍需检查反馈路径 |
| 两个 INT8 阵列 | 512 个 signed 8×8 + INT32 ACC | 优先推断 DSP48E2 MAC | 需核对复位/使能/加载优先级能否吸收进 DSP |
| A/B tile buffers | 三阵列共 16,384 bit | FF 或分 bank LUTRAM | 多行/列并行取数，不能直接替换为单个双口 BRAM |
| PE A/B links、valid | 本地邻接流水 | FF | 保留局部布线和流水边界 |
| Scalar/rename/ROB/issue | 小型、多端口、旁路密集 | FF/LUT | 不能仅按 bit 容量决定 BRAM 化 |
| Vector | 16 lane 数据，当前逐 lane 执行 | FF/LUT + 共享 DSP | 不是 16 套并行完整浮点单元 |
| DDR DMA / AXI | 32-bit 外部 AXI | LUT/FF；FIFO 按深度选择 | 无须额外 Xilinx AXI DMA |

### BRAM 几何预算

以下是假设采用适配的标准 RAMB 组织的估算，不是保证推断结果：

- ITCM：每 RAMB36 以 1024×36 组织保存 1024×32 数据，约 **128 RAMB36**。
- 当前 DTCM：每 bank 512-bit 宽，SDP 模式每 RAMB36 最多 72 bit，
  约 ceil(512/72)=8 块/bank，四 bank 约 **32 RAMB36 等效**。
  Vivado 也可能用 64 RAMB18，或选择其他组织，必须看报告。
- ITCM + BRAM DTCM：约 **160/912 = 17.5%** BRAM 等效占用。
- 若当前 DTCM 用 LUTRAM：64 深×512 宽，四 bank 的原始存储约需 **2048 个 LUT6**，
  不含输出寄存器、byte-enable 和额外控制；实际应以推断报告为准。

**建议的后续容量档位：** 若需要更多数据缓存，可将每 bank 设为 32 KiB，
总 DTCM 128 KiB（512 行×512 bit×4）。在 512×72 SDP BRAM 组织下仍约 32 RAMB36，
比 16 KiB DTCM 更充分利用已占用的块深度。256 KiB DTCM 通常升至约 64 RAMB36。
此处仅为建议，当前默认仍是 16 KiB；变更时要更新生成配置、软件布局和集成测试，
不能只改 Vivado RAM 参数。现有 compact aperture 最多可容纳 512 KiB DTCM。

### DSP 预算

以每个 PE 的乘法/整数 MAC 占一 DSP 为规划基线：

- BF16 有效数乘法：256。
- 两个 INT8 阵列：512。
- 主阵列共 **768/2520 = 30.5%**。
- 额外为向量、MEW 和辅助乘法预留约 128 DSP，预算约 896（35.6%）。

这不是对综合结果的承诺。尤其 8×8 很小，LUT 实现可能更经济；BF16 FP32 加法并非
一个 DSP48E2 即可完成。禁止对整个 BF16/Vector 模块泛化强制 `use_dsp=yes`，应针对
明确的乘法/整数 MAC 子模块做 A/B 综合，比较 DSP、LUT、WNS 和功耗。

### FF 的已知底数

按源码寄存器 bit 数，未考虑优化、DSP 内寄存器吸收或 RAM 推断：

- BF16 PE：A/B 32 + valids 2 + ACC 32 + product 16 + valid 1 = 83 bit/PE。
  256 PE + A/B buffers = 21,248 + 8,192 = 29,440 bit。
- 每个 INT8 阵列：256×(16+2+32) + 4,096 = 16,896 bit。
- 三阵列上述数据寄存器合计 **63,232 bit**，约为器件 FF 数的 11.5%。

不含控制器、scalar/vector 寄存器、ROB、互连和调试；这不能用来估计 LUT 数。
256 个 FP32 adders 的 LUT 和布线压力更可能是瓶颈。

## 资源上限与实施顺序

第一版以如下软预算约束实现，而不是以填满芯片为目标：

- LUT：优先低于 65%（约 178k），超过 70% 先分析拥塞和浮点单元。
- FF：优先低于 60%；BRAM：优先低于 50%；DSP：优先低于 50%。
- ILA 仅抓少量 AXI/状态/PC 信号，不抓全部向量寄存器和 256 个 ACC；
  初期可预留约 16–32 RAMB36 等效作调试，具体按 probe 宽度和采样深度计算。
- 预算是工程目标，不是器件限制或实际占用预测。资源低于预算也不保证时序收敛。

1. 保留当前 ISA、三个 16×16 阵列和两级 BF16 PE，先综合建立 baseline。
2. 确保 ITCM 正确推断 BRAM。若失败，使用受版本管理的 FPGA RAM/XPM wrapper，
   保持一拍读延迟、字节使能和冲突语义，不永久手改生成文件。
3. DTCM 16 KiB 比较 LUTRAM/BRAM；需要扩容时优先评估总计 128 KiB 的 BRAM 档位。
4. 对 PE 内乘法定向尝试 DSP，避免浪费 DSP 于大量简单指数加减。
5. 检查 BF16 FP32 累加、MEW 动态 mux 和 MSTORE narrow；不能只关注乘法器。
6. VectorUnit 每拍处理一个 lane，但除法、exp、rsqrt 内部仍是组合硬件。
   若成为关键路径，需要迭代/流水化并修改完成握手，不能仅添加时序例外。
7. 初期目标 50–100 MHz，必须以实现后的 WNS/TNS 为准。不要用 multicycle/false path
   掩盖每拍实际更新的 MAC 反馈；要同时验证 hold 和未约束路径。

## 板级资源选择

首版仅使用 PS DDR。PS→HPM→Host Interconnect→NPU CTRL/MEM，
NPU DDR Master→SmartConnect→PS HP0→PS DDR。
不启用独立 PL DDR/MIG，以节省 LUT/BRAM、时钟与 CDC 复杂度。
当前 DMA 为 32-bit、单 outstanding，100 MHz 下每拍 4 B 的理想总线峰值仅
400 MB/s（不是实测吞吐），因此不应仅为了 DDR 带宽马上添加 PL DDR。
未来提高 DMA 位宽/突发并发后再评估独立 PL DDR 是否值得。

PS DDR 4 GB 不代表当前 32-bit DMA 能线性访问全部容量。按 PS 地址映射及 HP 可达窗口
分配低地址 buffer，通常选低于 2 GiB；使用 DMA API/缓存维护及保留内存。
PS_REF_CLK=33.33 MHz 与 PL AXI clock 不同；首版用 PS pl_clk0 驱动单一 PL clock domain。
使用板载风扇，检查板温和功耗。256 个 BF16 PE/512 个 INT8 PE 的高翻转率不能仅用
空闲时温度估计；时钟使能保持空闲数据稳定，有条件时用 SAIF 估计实际功耗。

## Vivado 验收报告

```tcl
open_run synth_1
report_utilization -hierarchical -file utilization_hier.rpt
puts "RAMB36E2: [llength [get_cells -hier -filter {REF_NAME == RAMB36E2}]]"
puts "RAMB18E2: [llength [get_cells -hier -filter {REF_NAME == RAMB18E2}]]"
puts "DSP48E2:  [llength [get_cells -hier -filter {REF_NAME == DSP48E2}]]"

open_run impl_1
report_utilization -hierarchical -file utilization_impl.rpt
report_timing_summary -report_unconstrained -file timing_summary_impl.rpt
report_timing -delay_type max -max_paths 50 -file timing_top50_impl.rpt
report_power -file power_impl.rpt
```

按层级检查 `bf16Array`、两个 INT8 array、`VectorUnit`、`InstructionTcm`、`DataTcm`、
AXI Interconnect。记录实际资源/Slack 后再调整预算；没有这些报告，不能宣布资源分配
或 100 MHz 时序已经验证。Elaborate 现已按本规划添加定向综合属性：ITCM `block`，默认浅 DTCM `distributed`，
PE 乘法与 INT8 accumulator `use_dsp=yes`；可用第三/第四参数选择 DTCM `block` 或 PE
`use_dsp=no` 做 A/B 比较。其余逻辑保持自动映射。每次导出附带 `resource-policy.txt`，
这些属性仍需 Vivado 验证，不代表已经验证资源数量、BRAM 推断或频率。
