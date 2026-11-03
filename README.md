# XiangJiang

规范：[XJ-NPU 微架构](docs/xj-npu.md) / [XJ-ISA 1.0](docs/xj-isa.md)。
`docs/xj-npu.md` 第 13 节是早期 Scalar-only 实现快照；以下描述当前源码。

## 实现

- **统一前端与提交**：顺序单派发、无分支预测、X/V 值重命名、各 FU 最老就绪
  发射、单条 256-bit CDB、ROB 顺序提交。控制流与配置指令阻止年轻派发；
  同步取指记录 PC/epoch，清空后先排空旧请求再复用元数据。
- **Scalar（33 条）**：32×32-bit X ARF、RV32I 整数子集、LW/SW、分支、FENCE、
  精确 HALT 编码。x0 写入丢弃，但 load/控制目标仍检查故障。
- **Vector（38 条）**：32×256-bit V ARF、8 lane、固定旧 v0 掩码、严格 XJ-V
  编码、BF16 RNE/次正规数/NaN、FP32 顺序归约、无掩码 32 B VLD/VST。
  V-IALU、V-IMUL、V-BFPU、V-BFDIV、V-PERM/RED 各自有 RS 和输出保持缓冲，
  可独立执行。每个单元分时处理 lane；BF16 除法使用逐位迭代，不使用组合除法器。
- **Matrix（10 条）**：1×BF16、2×INT8，三个独立 8×8 PE 阵列，共 192 PE。
  BF16 使用 FP32 fused 累加，按 k=0…7 顺序舍入；INT8 累加模 2^32。
  OS 保持输出位置，INT8 WS 保持 B[k,j] 并传递部分和；数值结果不受 mode 影响。
  输入有效位、owner ROBTag、staging/working 和提交发布保证精确状态。
  只有 tile 搬运进入 MemQ，纯计算与等待使用 Head-only 控制 RS。
- **Core DMA（4 条）**：DSETSIZE/DSETSTRIDE 在匹配 ROB 提交时发布配置；
  MVIN/MVOUT 进入统一 MemQ，ROB Head 授权，完成后才能退休。
  使用 65-bit 全命令预检查、二维 stride、256 B/4 KiB burst 拆分、完整 burst
  缓冲、AXI ID/LAST 检查、错误排空、成功字节/行进度。
- **存储**：256 KiB 单端口同步 ITCM；1 MiB DTCM，4×256 KiB、512-bit 1R1W
  bank，64 B 交织、byte mask。测试允许缩小 DTCM，不允许扩大 ISA 地址窗口。
- **Host**：AXI4-Lite Control Slave、AXI4 Memory Slave，以及独立 `io.external`
  AXI4 Manager；地址/数据宽度均为 32 bit。Control VERSION=2，包含
  FAULT_DETAIL、DMA 配置/进度/状态。BUS_POISONED 保持 BUSY，拒绝 START/ACK
  和新 Host SRAM 请求，只能平台复位恢复。

## 实现参数与边界

- 前端派发/提交各最多一条每周期，不代表所有后端共享一次发射机会。
- Vector 物理 lane 分时执行；Matrix 的 64 个 PE 是物理独立单元，当前 BF16 PE
  为组合 FMA，未承诺具体频率、面积或固定 ISA 延迟。OS/WS 使用寄存器路由，
  不宣称特定工艺的 SRAM macro 或流水化脉动阵列时序。
- DMA **当前最多一个 burst 在途**，每个方向共享 256 B 缓冲，满足文档“最多
  4 个”的上限但不提供四 burst 并发吞吐。读 burst 完整验证后才写 DTCM；
  写 burst 在 AW 前准备好所有数据，AW 接受后必须发完 W 并等待 B。
- DMA_STATUS bit0 ACTIVE、bit1 DIRECTION（1=MVOUT）、bit2 ERROR、bit3
  BUS_POISONED。FAULT_DETAIL 高字节为域，中字节为原因，低 16 bit 为索引；
  DMA 配置/地址原因：1=配置值、2=base 对齐、3=配置未就绪、4=范围、
  5=本地响应错误、6=协议失配；外部错误使用 AXI RESP。
- 软件负责 CPU cache/DMA 一致性；普通 ACK/START 保留架构寄存器、Matrix
  状态、DMA 配置和 SRAM。已经完成的写副作用不回滚。

## 构建与验证

```bash
./mill xj.test
./mill xj.runMain top.Generate generated/xj-isa-1.0-full
```

生成 split SystemVerilog、verification layers、`filelist.f` 和
`xiangjiang-config.json`。修改 ISA 后使用新的输出目录或生成的 filelist；
不要将历史 `generated/` 中的旧模块与新 RTL 混用。

回归规模：**18 个测试套件、45 项测试**。测试覆盖 Scalar/backend/Host AXI 回归、严格 Vector/Matrix/DMA 译码、数值
有理数参考模型、迭代 BF16 除法、跨 FU 执行和顺序提交、Matrix OS/WS、提交
隔离、旧 owner、Vector/Matrix 地址检查、DMA 跨页/多行/重叠/零 stride、
配置保留、错误排空、ID/LAST poison、ACK/restart 和端到端 Host DMA 程序。
仿真通过不等于形式验证、物理实现时序收敛或平台级 AXI 一致性签核。
