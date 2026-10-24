# XiangJiang 28 nm NPU 芯片规格书

**文档编号：XJ-NPU-SPEC-002**\
**版本：v0.2**\
**日期：2026-10-03**\
**状态：正式评审发布候选；关键 TBD 项冻结后进入 v1.0**

本文以我们作为芯片总体设计方的第一视角描述 XiangJiang 首版产品。本文定义产品目标、默认方案、备选方案、接口和验收方法。文中的“我们选择/定义/要求”是本项目的设计决定；“备选”只有在评审批准并更新版本后才能替代默认方案。

本文不把尚未获得的工艺、IP、时序、功耗或商业报价写成已实现事实。涉及华虹的实际 fab、28 nm 平台、PDK release 和可用 IP，必须以 NDA 后的书面确认作为 v1.0 的输入。

## 0. 文档规则与版本边界

### 0.1 设计目标和验证状态

我们把首版目标分成三层：

| 层级 | 本规格的定义 | 通过条件 |
|---|---|---|
| 功能目标 | 芯片可由 Host Linux 通过 PCIe 枚举、配置、搬运数据、提交任务、取得结果和读取错误；Matrix/Vector 计算结果正确 | RTL/FPGA/ASIC模型及驱动回归通过 |
| 产品目标 | 单芯片 NPU 面向端侧 2B Qwen 的 prefill 主干算子；芯片有 ITCM/DTCM SRAM、PCIe、JTAG、UART、GPIO | 指定工作负载和软件栈端到端通过 |
| 性能目标 | NPU 核心工作频率目标 600 MHz；核心理论峰值约 0.307 TOPS（INT8 MAC 按两次运算计） | 28 nm 多角 STA 通过，回片完成频率/电压/温度表征 |

“功能通过”不等于“600 MHz 通过”，“PCIe link-up”不等于“DMA、错误恢复和驱动通过”。没有任何流程能保证流片必然一次成功；我们用可追溯规格、真实宏模型、故障注入、DFT 和后端签核降低风险。

### 0.2 默认方案与备选方案的写法

每个重要决定都按以下格式写入本文：

1. **我们要解决的问题**：为什么该模块存在，以及它解决哪一种产品风险。
2. **我们的默认方案**：v0.2 默认实现和验收边界。
3. **备选方案**：只有在默认方案不可行、成本或性能不合适时采用。
4. **取舍**：面积、性能、成本、实现风险、软件复杂度和后续扩展影响。

候选决策的编号、责任人和正式选择记录在[选型决策表](选型决策表.csv)；实现任务记录在[实施与关闭清单](实施与关闭清单.csv)；验证组记录在[验证与签核矩阵](验证与签核矩阵.csv)。

### 0.3 当前代码基线

我们以 `XiangJiang` 提交 `9c79a969d8f9d583a1b6d5799541bd28d1dc76b4` 为 v0.2 的 RTL 基线：

| 项目 | 当前实现 | v0.2 目标 |
|---|---|---|
| 标量控制 | 32 bit 地址/指令/GPR，RV32I 子集和自定义指令 | 保留已实现指令，冻结非法指令和异常语义 |
| Matrix | 16×16 INT8，INT32 累加 | 保持结构，补齐边界/错误/时序约束 |
| Vector | 8 路 BF16，部分中间运算 FP32 | 保持结构，冻结数值和吞吐语义 |
| ITCM | 32 KiB，32 bit，FPGA/仿真 SRAM 抽象 | 换成目标工艺 1RW SRAM hard macro |
| DTCM | 512 KiB，128 bit 行，逻辑双端口，FPGA BRAM | 优先真实 2RW macro；不可用时改成 banked 1RW 并重新验证 |
| Host接口 | S_AXI 32 bit，M_AXI 128 bit，当前接 ZCU102 PS | PCIe Endpoint 通过 AXI 适配到内部 fabric |
| PCIe/IRQ/调试 | 顶层尚无独立 PCIe、IRQ、UART、JTAG 访问接口 | 加入第三方 PCIe Controller/PHY、自有管理和调试逻辑 |
| 片外DRAM | 当前访问 ZCU102 PS DRAM | 默认不在 NPU die/封装/板卡增加 DRAM，使用 Host DRAM |

现有 FPGA wrapper 的约 100 MHz 是平台时钟配置，不是 NPU 的 ASIC Fmax；现有代码也没有 ASIC PDK、SRAM macro、PCIe PHY 或后端签核结果。

## 1. 设计目标与功能需求

### 1.1 我们要做什么

我们要做一颗采用华虹体系 28 nm 工艺的单 NPU die，并将它封装在 PCIe 加速卡上。NPU die 内包含计算资源、片上 SRAM、PCIe 相关接口、DMA/内部 AXI 适配、错误管理和调试接口；**die 内不集成 DRAM/eDRAM**。Host CPU 及其 DRAM 是首版的主要大容量存储，模型和中间结果由软件分块搬入 NPU 的 DTCM。

首版产品的可见能力是：

- Host Linux 发现 PCIe Endpoint，读取 chip ID、能力和状态。
- 驱动/runtime 将程序、权重和输入从 Host DRAM DMA 到 ITCM/DTCM，提交计算任务并取回结果。
- Matrix 单元执行 INT8 主干计算，Vector 单元执行 BF16/FP32 辅助计算。
- 通过 CSR、MSI 或轮询获取完成和错误状态。
- 通过 JTAG、UART、GPIO 进行实验室调试、错误观察和受控状态读取。
- 软件栈支持端侧 2B Qwen prefill 的算子拆分、量化/打包、tile 调度和结果校验。

首版不承诺 CUDA 二进制兼容、完整 CUDA 编译器兼容、统一虚拟地址、CPU cache 一致性、多进程抢占或完整 Qwen 解码优化。它首先是一个有明确软件 ABI 的独立 NPU 设备。

### 1.2 目标工作负载：端侧 2B Qwen prefill

我们以“2B Qwen 模型、batch=1、prefill 主干路径”作为首版软件和系统验证的默认工作负载。序列长度、量化格式和端到端时延在 v1.0 冻结前填写具体数值；v0.2 先冻结以下原则：

| 内容 | 默认定义 | 备选 | 取舍 |
|---|---|---|---|
| 权重格式 | 优先离线 INT8 权重，匹配现有 Matrix | BF16 权重或混合精度 | INT8 减少 PCIe 搬运和 DTCM 占用；BF16 精度路径简单但带宽压力更大 |
| 激活/累加 | INT8 输入/乘法，INT32 累加；Vector 负责 BF16/FP32 辅助算子 | 更高精度累加或软件 fallback | 默认复用现有 datapath；精度和算子覆盖需要模型级验证 |
| batch | 1 | 小 batch 离线吞吐 | batch=1符合端侧延迟；小 batch可提高阵列利用率但增加缓存需求 |
| 运行范围 | prefill 主干 GEMM、量化、归一化/激活等已映射算子 | 未覆盖算子由 Host 参考实现或下一版硬件支持 | 默认先确保主干闭环，避免宣称完整模型覆盖 |
| 模型存储 | Host DRAM 保存完整模型，DTCM保存当前 tile/中间结果 | 后续增加板载 DRAM | 默认省去 DDR PHY、引脚和训练风险；PCIe带宽成为瓶颈 |

我们必须建立算子清单：每个 Qwen 算子记录是否映射到 Matrix、Vector、标量核或 Host fallback，输入输出精度、DTCM占用、Host搬运字节数和验证结果。没有该清单，不能把“支持 2B Qwen”写成完整端到端承诺。

### 1.3 算力估算：600 MHz 是否达到 1 TOPS

当前 Matrix 结构有 (16 \times 16=256) 个 INT8 乘加位置。若每个周期完成一组 MAC：

```text
256 MAC/cycle × 600 MHz = 153.6 GMAC/s
按一次乘法和一次加法计 2 operations：153.6 × 2 = 307.2 GOPS = 0.3072 TOPS
```

当前 8 路 Vector 即使按每路每周期一个 FMA 估算，也只有 `8 × 600 MHz × 2 = 9.6 GOPS`，约 0.0096 TOPS；实际吞吐还受 DTCM 端口、流水线、指令和搬运限制。因此，**当前结构在 600 MHz 下没有 1 TOPS，默认理论峰值约 0.307 TOPS**。

达到 1 TOPS 需要约 833 个每周期完成的 MAC（按 2 ops/MAC），或者在 600 MHz 下约 834 个 INT8 MAC/cycle；这意味着扩大阵列、增加并行核或采用不同 TOPS 统计口径，不能通过简单把频率从 100 MHz 改成 600 MHz 达到。我们将 0.307 TOPS 作为当前结构的理论上限，不把它当作端到端 Qwen 性能。

### 1.4 软件环境栈

我们的默认软件栈如下；NPU die 内不运行完整 Linux。

```text
┌────────────────────────────────────────────────────────────┐
│ Host application / Qwen inference service                  │
├────────────────────────────────────────────────────────────┤
│ XiangJiang runtime: device API, memory, queue, profiling   │
├────────────────────────────────────────────────────────────┤
│ Qwen graph compiler / kernel mapper / quantizer / packer   │
├────────────────────────────────────────────────────────────┤
│ Linux kernel driver: PCIe probe, BAR, DMA, MSI, reset      │
├────────────────────────────────────────────────────────────┤
│ Host Linux + CPU DRAM                                      │
└──────────────────────┬─────────────────────────────────────┘
                       │ PCIe Gen3 x4
┌──────────────────────▼─────────────────────────────────────┐
│ XiangJiang NPU die                                        │
│ CSR/doorbell → command queue → DMA/AXI → ITCM/DTCM        │
│ Matrix / Vector / scalar control → completion/error        │
└────────────────────────────────────────────────────────────┘
```

默认 Host OS 是 Linux；具体发行版和内核版本在 v1.0 冻结。默认设备控制方式是“PCIe 管理寄存器 + Host runtime”，可选小型片上管理状态机或 ROM，但不在首版加入通用管理 CPU。软件必须提供 `open/query/load/copy/submit/wait/error` 语义和硬件 ABI 版本检查。

我们把首版推理引擎命名为 **XJ-RT（XiangJiang Runtime）**：它由 Linux kernel driver、用户态 C/C++ runtime、XiangJiang graph compiler、Matrix/Vector kernel library、量化/打包工具和 Qwen 模型适配器组成。默认 Host OS 为 Linux 6.x 系列，默认设备侧没有通用 OS；如果未来加入管理核，管理固件仍只承担启动/队列/错误服务，不改变 Host runtime 的设备 ABI。

### 1.5 功能需求表

| 编号 | 我们解决的问题 | 默认需求 | 备选 | 默认取舍 |
|---|---|---|---|---|
| F-01 | 设备必须能独立被主机发现 | PCIe Endpoint 上电后无需加载 NPU 程序即可枚举，提供 chip ID/能力/状态 | 外接管理 MCU 辅助枚举 | 默认减少板级器件；要求 PCIe IP/复位路径完整 |
| F-02 | 大模型数据不能靠 CPU 逐字写寄存器 | 64 bit DMA 地址，DMA 在 Host DRAM 与 DTCM 之间搬运 | Host 软件分段搬运 | 默认性能较好；增加 DMA 错误和生命周期处理 |
| F-03 | 计算资源要匹配现有 XiangJiang | 保留 16×16 INT8 Matrix、8 路 BF16 Vector、32 KiB ITCM、512 KiB DTCM | 扩大阵列或多核 | 默认控制首版面积和验证规模；峰值低于 1 TOPS |
| F-04 | 片上高速工作集需要确定性访问 | ITCM/DTCM 为软件管理 scratchpad，不宣称 cache | 增加 cache 或多核私有 cache | 默认没有一致性和替换协议，软件可控但编程负担更大 |
| F-05 | 错误发生后不能只看到 busy 卡住 | sticky 错误 CSR、任务 FAILED/ABORTED、首错地址/来源/任务快照、小型 trace RAM | 增大 trace RAM 或增加错误 FIFO | 默认用少量历史事件补充首错 CSR；容量按 PPA 冻结 |
| F-06 | 回片后需要独立定位问题 | JTAG 实际总线访问 + UART TX/RX 双向命令通道 + 4 个 GPIO 状态/触发 + 小型 trace RAM | 标准 RISC-V Debug、更多 trace RAM | UART RX只承载低速诊断/控制，不承担主数据面；量产版本可锁定权限 |
| F-07 | 2B Qwen prefill 需要软件闭环 | Linux 驱动、runtime、编译/打包工具、参考模型和算子库 | CUDA-like 多进程运行时 | 默认先完成单设备、显式搬运 ABI，降低软件和硬件范围 |

## 2. 芯片、封装、板卡总体架构

### 2.1 我们解决的问题

我们需要把“Host CPU/DRAM、PCIe 卡、NPU die、调试接口”划清边界，避免把 Host DRAM误解为片上 DRAM，也避免在 RTL、封装和板卡之间重复或遗漏接口。

### 2.2 默认物理结构

```text
                         Host 主机
              ┌─────────────────────────────┐
              │ CPU / Linux / Host DRAM     │
              │ PCIe Root Complex           │
              └──────────────┬──────────────┘
                             │ PCIe Gen3 x4
┌────────────────────────────▼───────────────────────────────┐
│                         NPU 加速卡 PCB                      │
│  PCIe connector     clock / power / reset / JTAG / UART     │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ NPU package                                         │  │
│  │ ┌──────────────────────────────────────────────────┐ │  │
│  │ │ XiangJiang 28 nm NPU die                       │ │  │
│  │ │ PCIe PHY/controller · AXI/DMA · Matrix/Vector  │ │  │
│  │ │ ITCM/DTCM SRAM · CSR/error · JTAG/UART/GPIO    │ │  │
│  │ └──────────────────────────────────────────────────┘ │  │
│  │              package substrate + BGA balls            │  │
│  └──────────────────────────────────────────────────────┘  │
│  电源、参考时钟、复位、散热、测试点                         │
└────────────────────────────────────────────────────────────┘
```

**默认没有 NPU die 内 DRAM、NPU 封装内 DRAM 或板载 DRAM。**Host DRAM 通过 PCIe DMA访问；数据路径是 `Host DRAM → PCIe → DMA/AXI → DTCM`，结果反向搬运。ITCM/DTCM 使用 SRAM，不是 DRAM。

### 2.3 物理层次和责任

| 层次 | 我们定义的内容 | 厂商详细实现的内容 |
|---|---|---|
| NPU die | 模块、协议、地址、功耗目标、pad类型、测试/调试能力 | PDK单元布局、宏拼接、物理实现、IO电气细节 |
| package | 默认 BGA、允许尺寸/高度、功地预算、高速通道、热边界 | wire-bond/flip-chip结构、基板、键合/凸点、寄生和装配 |
| PCB | PCIe lane、时钟、复位、JTAG/UART、供电、散热、测试点 | 具体层叠、走线、过孔、器件布局、生产和SI/PI报告 |
| Host | Linux、驱动、runtime、CPU DRAM、Root Complex | 主机平台适配和软件发行版本 |

我们把需求交给后端、IP、封装和板卡团队，由他们提交可行方案；最终能否满足 PCIe、供电、散热、成本和可测试性，仍由我们组织总体评审和批准。

### 2.4 默认接口和备选

| 子系统 | 我们的默认方案 | 备选方案 | 取舍 |
|---|---|---|---|
| PCIe | Gen3 Endpoint x4 | Gen3 x1 | x4单向理论编码带宽约3.94 GB/s；x1约0.985 GB/s。x1省PHY/封装资源但更可能成为Qwen搬运瓶颈 |
| 大容量存储 | Host DRAM | 板载DDR4/LPDDR4 | 默认省掉DDR PHY、训练、引脚和SI/PI；板载DRAM可显著减少反复PCIe搬运，但增加成本和首硅风险 |
| 封装 | FCBGA作为默认评估方案 | wire-bond BGA | FCBGA有利于供电和高速通道；wire-bond成本可能更低，但必须用完整通道SI/PI证明Gen3 x4可行 |
| 调试 | JTAG总线访问、UART TX/RX、4 GPIO候选、4 KiB trace RAM目标 | 标准RISC-V Debug、更大 trace RAM | 默认保留历史事件辅助定位；容量和记录宽度需 PPA 后冻结 |
| 主机通知 | 单 MSI，保留轮询 | MSI-X多向量 | 单队列/单设备足够；MSI-X适合多队列但增加配置和驱动复杂度 |

### 2.5 数据和控制工作流程

#### 2.5.1 正常工作流程

```text
┌──────────────┐
│ 上电/复位    │
└──────┬───────┘
       ▼
┌──────────────────────────────┐
│ POR/PLL lock/管理CSR可用      │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ PCIe Endpoint 枚举            │
│ Host读取 CHIP_ID/CAP/STATUS   │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ 驱动分配 Host DRAM buffer     │
│ DMA map + 写入 descriptor     │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ BAR0 doorbell/command queue   │
│ 提交 task、地址、长度、tag    │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ PCIe outbound DMA             │
│ Host DRAM ──PCIe──> DTCM/ITCM│
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ Core调度 Matrix/Vector        │
│ 从DTCM读取tile并执行计算      │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ 结果写回 DTCM                 │
│ DTCM ──DMA/PCIe──> Host DRAM  │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ completion record + TASK_STATUS│
│ SUCCESS；MSI或轮询通知Host    │
└──────────────┬───────────────┘
               ▼
┌──────────────────────────────┐
│ runtime回收buffer并返回结果   │
└──────────────────────────────┘
```

这个流程中，BAR 主要承载控制和状态访问，Host DRAM 数据通过 DMA 搬运；CPU 不需要通过 BAR 逐字写完整模型。`TASK_STATUS=SUCCESS` 只有在所需 DMA、计算和 completion record 都完成后才能产生。

#### 2.5.2 错误工作流程

```text
┌────────────────────────────────────────────┐
│ AXI/DMA/PCIe/Matrix/Vector/SRAM/时钟监测器 │
│ 检测到非法地址、错误响应、超时或执行错误   │
└───────────────────┬────────────────────────┘
                    ▼
┌────────────────────────────────────────────┐
│ ErrorManager                                │
│ 锁存 first-fault：code/source/task/address  │
│ PC/raw response；更新 sticky/count/overflow │
│ 同时写入 trace RAM 当前事件                 │
└───────────────────┬────────────────────────┘
                    ▼
┌────────────────────────────────────────────┐
│ 任务状态改为 FAILED 或 ABORTED              │
│ 禁止依赖该任务结果的 Matrix/Vector 新操作   │
│ DMA 停止发起新请求，处理已握手在途事务       │
└───────────────────┬────────────────────────┘
                    ▼
┌────────────────────────────────────────────┐
│ 状态保存                                     │
│ fault CSR 可读；trace RAM 冻结/保留上下文    │
└──────────────┬─────────────┬───────────────┘
               │             │
               ▼             ▼
      ┌────────────────┐  ┌───────────────────┐
      │ MSI / 轮询      │  │ UART TX / JTAG    │
      │ 通知Host驱动    │  │ 独立诊断读取       │
      └────────┬───────┘  └─────────┬─────────┘
               └─────────────┬─────┘
                             ▼
┌────────────────────────────────────────────┐
│ 驱动读取 CSR/trace，确认错误并判断恢复策略 │
│ quiesce → 局部DMA/Core复位或全设备复位     │
│ 重新装载/重新映射后，才允许提交新任务       │
└────────────────────────────────────────────┘
```

中断只是通知，不承载完整错误信息；完整信息保存在 fault CSR 和 trace RAM 中。若 PCIe 已掉链，MSI 可能无法送达，但本地 CSR、JTAG、UART 和 trace RAM 仍应保留可诊断状态。超时不能简单清除 `busy` 后立即复用 descriptor；已经握手的 AXI/PCIe 事务必须排空、隔离或配合复位，防止迟到响应污染新任务。

## 3. 芯片前端架构设计

### 3.1 我们要解决的问题

我们需要把现有 FPGA 逻辑变成 ASIC 可集成的模块集合，明确哪些是自研 RTL、哪些购买第三方 IP、哪些由厂商生成 hard macro，并保留 AXI 作为芯片内部互连协议的稳定边界。

### 3.2 顶层模块结构

```text
                       ┌────────────────────────┐
PCIe PHY (第三方) ────►│ PCIe Endpoint Controller│
                       └──────────┬─────────────┘
                                  │ PCIe user/AXI interface
                        ┌─────────▼─────────┐
                        │ PCIe-AXI Adapter   │
                        │ BAR0 / DMA / IRQ   │
                        └──────┬──────┬──────┘
                               │      │
                       AXI fabric     │ outbound DMA
              ┌────────────────┘      │
              ▼                       ▼
       CSR/Error/Debug          DMA Engine (自研默认)
              │                       │
              ├──────────────┬────────┤
              ▼              ▼        │
        Scalar/Core      Matrix     Host DRAM
              │              │        ▲
              └──────┬───────┘        │
                     ▼                │
              ITCM/DTCM SRAM ◄────────┘
                     │
                Vector Unit

             PLL/POR/Reset/Clock/DFT 横向连接所有模块
```

### 3.3 模块选型

| 模块 | 我们默认采用 | 备选 | 购买/自研边界 | 主要取舍 |
|---|---|---|---|---|
| Matrix | 现有 16×16 systolic Matrix，自研 RTL | 扩大阵列/多核 | 自研；由我们冻结指令和数值 | 复用代码、风险低；峰值约0.307 TOPS |
| Vector | 现有8路BF16/FP32 Vector，自研 RTL | 增加lane或专用激活单元 | 自研 | 满足辅助算子；不是通用GPU向量处理器 |
| Scalar/Core | 现有控制核和自定义 ISA | 外部RISC-V核+自定义协处理器 | 默认自研；若换核需重做软件/Debug | 保持现有程序路径；完整RISC-V兼容范围有限 |
| ITCM/DTCM | wrapper + 工艺SRAM compiler macro | 多bank/1RW宏 | macro由foundry/IP提供；wrapper/仲裁自研 | 硬宏真实可制造；端口和延迟可能迫使改RTL |
| PCIe Controller | 第三方硬核/firm IP，Gen3 Endpoint | 另一家工艺匹配IP | 必须购买/授权；我们负责集成和验收 | 协议复杂，避免自研PHY；工艺绑定和费用是主要风险 |
| PCIe PHY | 工艺匹配第三方或foundry生态 PHY | 降为Gen2/减少lane | 必须由IP/foundry提供 | 高速模拟电路不可由普通RTL替代；决定封装和时序 |
| DMA | 保留自研DmaEngine，扩展64bit/错误处理 | 厂商PCIe DMA IP | 默认自研控制语义；PCIe controller/PHY仍购买 | 复用DMALD/DMAST；自研错误恢复工作量较大 |
| AXI fabric | 自研最小互连或合规第三方AXI fabric | 直接stream连接PCIe DMA | 内部协议由我们冻结 | AXI便于接TCM/CSR/PCIe桥；stream可省转换但软件/仲裁复杂 |
| PLL/POR/IO | 工艺/后端认可IP或标准单元 | 外部时钟+更低频 | 购买/工艺提供；我们定义时钟树和复位契约 | 模拟IP必须有工艺模型和lock/测试策略 |
| Debug | 自研管理寄存器/JTAG桥/UART TX/RX/4 KiB trace RAM | 标准Debug Module、更大 trace RAM、片上管理核 | 默认自研最小功能；TAP/IO按DFT规范集成 | 首版功能可控；不能只留JTAG引脚不做访问逻辑 |

### 3.4 ITCM/DTCM SRAM 方案

我们必须用目标 28 nm 工艺的 SRAM compiler 生成 hard macro，而不是把 FPGA BRAM 直接当 ASIC SRAM，也不是用综合出的寄存器阵列替代生产 SRAM。

| 存储器 | 当前 FPGA/RTL | 我们的默认 ASIC 方案 | 备选及取舍 |
|---|---|---|---|
| ITCM | 32 KiB、32 bit、逻辑1RW、1-cycle模型 | 由工艺 compiler 生成 1RW、32 bit数据、byte mask、32 KiB组织；上层 wrapper保留有效握手 | 多个较小1RW宏拼接，面积/译码增加但catalog更容易匹配 |
| DTCM | 512 KiB、128 bit行、两个逻辑读写端口；FPGA为多bank BRAM | 首先询价真正 2RW、128 bit宏或可拼接组织；用真实宏时序决定bank结构 | 多个1RW bank + 仲裁；面积可能更省但同bank不同地址也会冲突，Matrix/Vector/DMA需要停顿 |
| 宏视图 | FPGA inference/行为模型 | `.v/.sv`模型、`.lib/.db`、`.lef`、`.gds/.oas`、CDL/LVS、BIST/repair说明 | 缺任何视图都不能进入后端签核 |
| 数据可靠性 | 当前无统一ECC规格 | v0.2默认先做范围/协议错误检测；ECC作为可靠性决策项 | parity面积较小但不能纠正；SECDED增强可靠性但有RMW/面积/时序成本 |

宏模型像一个可实例化的 Verilog/IP 模块，用于 RTL/门级仿真；真实晶体管和版图通过 `.gds/.lef/.lib` 等视图参与 ASIC 流程。FPGA 继续使用 BRAM/URAM，但通过共同 wrapper 验证地址、mask、valid、延迟和冲突语义，不把 BRAM 物理时序当 ASIC 时序。

### 3.5 PCIe Controller/PHY 和 AXI 的关系

PCIe Controller 负责配置空间、TLP（Transaction Layer Packet，事务层报文）、BAR、Completion、MSI/MSI-X、链路状态和协议事务；PCIe PHY（Physical Layer，物理层）负责 SerDes（Serializer/Deserializer，串并/并串转换）电气收发、均衡、参考时钟和 lane。它们不是普通 AXI 模块，必须购买与目标工艺匹配的第三方/工艺生态 IP。

PCIe 还需要专用的高速 IO pad/SerDes pad。普通 UART/GPIO pad 不能直接连接 PCIe lane。PCIe 物理连接的完整层次是：

```text
PCIe Endpoint Controller（数字协议）
             │ controller ↔ PHY 数字接口
             ▼
PCIe PHY（PCS/PMA、SerDes、均衡、时钟恢复）
             │
             ▼
专用 PCIe 高速 pad/ESD/终端（由 PHY IP 或工艺 IO 生态提供）
             │ die pad / bump
             ▼
package substrate → BGA ball → PCB differential pair → PCIe connector
```

这里的“pad”仍属于 NPU die；“bump/ball”属于 die 与封装/PCB 之间的连接结构。通常由 foundry/IO library 提供普通数字 IO pad，由 PCIe PHY IP 厂商或 foundry 高速 IO 生态提供与 PHY 匹配的专用 pad。我们负责提出 lane 数、参考时钟、PERST、供电、ESD、封装和 PCB 约束，并确认供应商提交的 pad、bump、ball map 和模型与系统规格一致；不能在后端最后阶段用普通 GPIO pad 替换 PCIe 专用 pad。

我们的默认连接是：

```text
第三方 PCIe Controller/PHY
          │ controller user interface
          ▼
PCIe-AXI inbound/outbound bridge
          │
          ├─ AXI slave → BAR0 CSR / BAR1 TCM窗口
          └─ AXI master ← 自研DMA → Host DRAM PCIe请求
```

AXI 不是 PCIe 的物理必需品，但在我们的默认架构中保留它，因为现有 NPU 的 CSR、TCM 和 DMA 已围绕 AXI/AXI-like 握手组织。直接把 Core 连到 PCIe 会让每个模块理解 TLP、completion、ordering、timeout 和复位，增加验证边界；只有选择厂商 stream DMA 并同时冻结新的 stream/descriptor 语义时，才采用直接 stream 路线。

### 3.6 DMA 默认和备选

**默认：保留自研 DmaEngine，作为 AXI Master 接到 PCIe outbound bridge。**我们修改它以支持 64 bit Host 地址、描述符、4 KiB 分段、RRESP/BRESP/ID/RLAST 校验、超时、任务失败和安全恢复。Core 的 DMALD/DMAST 继续表达本地搬运意图，Host 地址由 descriptor/CSR 组合提供。

#### 3.6.1 Host DRAM → PCIe → AXI → DTCM

这是默认的 Host-to-Device（H2D）加载路径，例如加载权重、输入激活和程序：

```text
Host CPU/DRAM
     │ ① Driver写入descriptor：host_addr/local_addr/length/flags/tag
     ▼
PCIe Root Complex
     │ ② PCIe Memory Read TLP（NPU发起 outbound read）
     ▼
NPU PCIe PHY（专用高速pad）
     ▼
第三方 PCIe Endpoint Controller
     │ ③ completion data / status
     ▼
PCIe→AXI Outbound Bridge
     │ ④ AXI Master：AR/R，检查RRESP/RID/RLAST
     ▼
自研 DmaEngine
     │ ⑤ local write request
     ▼
AXI fabric / DTCM仲裁器
     │ ⑥ 检查范围、权限、对齐和bank冲突
     ▼
DTCM SRAM macro
     │ ⑦ 完成计数、错误记录或任务FAILED
     ▼
completion CSR + MSI/轮询
```

其中 PCIe Controller 负责把 outbound memory read 转成 PCIe 事务并接收 completion；AXI bridge 把返回数据转换为内部 AXI 写入；DmaEngine 负责 descriptor、地址和长度检查、DTCM 写入及错误恢复。

#### 3.6.2 DTCM → AXI → PCIe → Host DRAM

这是默认的 Device-to-Host（D2H）结果回写路径：

```text
DTCM SRAM macro
     │ ① DmaEngine发起local read
     ▼
AXI fabric / DTCM仲裁器
     │ ② AXI read response，检查读数据有效性
     ▼
自研 DmaEngine
     │ ③ AXI Master：AW/W，按4 KiB和burst限制分段
     ▼
PCIe→AXI Outbound Bridge
     │ ④ 产生 PCIe Memory Write TLP
     ▼
第三方 PCIe Endpoint Controller
     ▼
NPU PCIe PHY（专用高速pad）
     │ ⑤ PCIe lane
     ▼
PCIe Root Complex → Host DRAM
     │ ⑥ completion record写入后才报告任务完成
     ▼
MSI/轮询 → Linux driver → XJ-RT
```

Posted write 的 PCIe/AXI 完成语义必须由所选 IP 明确。`AXI BRESP=OKAY` 不能自动证明 Host 软件已经观察到数据；我们用 completion record、内存可见性屏障和驱动协议确定结果可见时刻。

#### 3.6.3 BAR 入站控制和调试路径

BAR 路径与大块 DMA 数据路径不同。BAR 是 Host 映射设备寄存器/窗口的入口，默认只用于控制、状态、doorbell 和受控 TCM/debug 访问：

```text
Host CPU执行 readl/writel
          │
          ▼
PCIe Root Complex
          │ PCIe Configuration / Memory Write/Read TLP
          ▼
NPU PCIe PHY（专用高速pad）
          ▼
第三方 Endpoint Controller
          │ 解析 BAR 命中、completion、byte enable
          ▼
PCIe→AXI Inbound Bridge
          │ AXI slave
          ├──────────────► BAR0 CSR / CHIP_ID / STATUS / FAULT / DOORBELL
          └──────────────► BAR1 受控 TCM / Debug窗口
                                  │
                                  ▼
                         AXI fabric → CSR/ITCM/DTCM
```

BAR 访问不需要把 Host 每个数据字先交给自研 DMA；它由 PCIe Controller/bridge 转成 AXI 从端访问。大块模型和激活数据仍走 DMA，避免 BAR 小事务造成严重性能损失。JTAG/UART 是独立的低速调试入口，不依赖 BAR 在链路掉线时仍能读取本地诊断状态。

**备选：采用供应商 PCIe DMA IP。**此时我们不把两个 DMA Controller 串联；厂商 DMA 直接发起 PCIe 读写，并通过 AXI-MM 或 AXI-Stream 把数据交给 DTCM 目标适配器。我们保留一个薄的 command/descriptor adapter，将现有指令映射到供应商 DMA 的队列和完成语义。

| 方案 | 优点 | 缺点 |
|---|---|---|
| 自研DMA+AXI bridge | 复用现有指令、DTCM接口和软件概念，内部可控 | 我们承担PCIe错误、超时、迟到响应和生命周期处理 |
| 厂商DMA IP | PCIe吞吐/协议和主机驱动示例可能更成熟 | 描述符、完成、错误和AXI接口需适配；IP许可、工艺和驱动绑定 |
| 直接PCIe到Core | 中间层少，理论延迟短 | Core被迫承担PCIe协议语义，难以复用现有AXI和验证环境；不作为默认 |

## 4. 后端设计、封装和板卡需求

### 4.1 我们要解决的问题

前端 RTL 正确并不能生成可制造芯片。后端必须把 RTL、SRAM、PCIe PHY、PLL、IO、DFT 和封装/板卡约束放进同一 PDK 和签核流程，并证明 600 MHz、供电、热和高速接口可实现。

### 4.2 工艺和 PDK

我们选择“华虹体系 28 nm”作为方向（项目沟通中也可能写作“华宏”；正式文件统一以合同中的 fab/平台名称为准），但 v0.2 不把“与 TSMC 28 nm 兼容”当作工艺确认。v1.0 必须写出：实际 fab/平台、器件类型、金属层、IO 电压、PDK release、标准单元库、SRAM compiler、PLL/IO/PCIe/DDR IP 的精确版本、MPW资格和交期。

PDK 是工艺数据和设计规则/模型的集合，包括层定义、DRC/LVS/ERC deck、晶体管/互连模型、标准单元/IO/宏的使用约束等。综合、布局布线、STA、DRC/LVS 是使用 PDK 的不同 EDA 工作阶段；Vivado 只能验证 FPGA 目标，不能替代 ASIC PDK、ASIC 综合或物理签核。

**PLL（Phase-Locked Loop，锁相环）**是把参考时钟经过倍频、分频和反馈锁定，生成芯片内部所需时钟的模拟/混合信号 IP。它不是普通 RTL 逻辑：需要工艺模型、输入参考频率、输出频率范围、jitter、lock 检测、bypass 和供电/复位时序。我们要求工艺或 IP 供应商提供 PLL hard macro、仿真模型、时序/噪声约束和测试模式；我们负责定义核心 600 MHz、PCIe 参考/用户时钟和安全启动时钟之间的关系。

### 4.3 ASIC 后端默认流程

ASIC 后端不是一个单独工具的一键操作，而是一组使用 PDK、标准单元、SRAM/PHY/IO hard macro 和约束文件的实现与签核步骤。下表给出每一步的英文全称、中文含义、解决的问题和我们要取得的证据；具体工具由设计服务商在 foundry 认可清单中选择。

| 阶段/英文全称 | 中文全称与作用 | 我们要求的默认产物 | 主要放行问题 |
|---|---|---|---|
| PDK/IP integration | 工艺设计套件/IP集成：安装规则、模型、标准单元和 hard macro | PDK/IP版本 manifest、授权、库/宏/PHY模型、构建脚本、版本校验 | 工艺、金属层、电压和所有 hard IP 是否一致；是否有未交付 black box |
| Logic synthesis（逻辑综合） | 将 RTL 映射为目标工艺标准单元网表 | gate-level netlist、面积、时序初估、功耗初估、综合日志 | latch、未驱动端口、未约束时钟、综合 black box、意外面积膨胀 |
| Design-for-Test insertion（DFT，可测试性设计插入） | 加入 scan、测试压缩、测试时钟、SRAM MBIST/BISR 接口 | DFT 网表、scan chain、测试模式、ATPG/MBIST配置和覆盖报告 | DFT 是否破坏功能时序；生产是否能控制/观察每个宏和关键逻辑 |
| Floorplanning（芯片平面规划） | 决定 die/core 尺寸、宏、IO、PHY、电源区域和通道位置 | floorplan、宏位置、利用率、IO/pad ring、power grid 初稿 | SRAM/PHY/PLL/IO 是否放得下；宏间通道、供电和热是否可行 |
| Placement（标准单元布局） | 把综合后的标准单元放到版图位置并优化拥塞/时序 | placement database、拥塞图、扇出/缓冲报告、早期 STA | 高拥塞、长线、过大扇出、关键 Matrix/Vector/DTCM 路径 |
| CTS, Clock Tree Synthesis（时钟树综合） | 用 buffer/inverter 将时钟分配到寄存器，控制 skew 和 latency | clock tree、skew/latency、gating、时钟 DRC、测试时钟报告 | 600 MHz 下 skew/jitter/脉宽；功能、scan、MBIST 时钟是否都可控 |
| Routing（信号/电源布线） | 连接标准单元、宏、IO 和电源网络 | routed database、DRC预检查、天线/拥塞/电源网络报告 | 断线、短路、拥塞、天线、关键线长和电源完整性 |
| Parasitic extraction（寄生参数提取） | 从最终布线提取 RC、电容耦合和延迟影响 | SPEF/寄生数据库、提取设置和版本 | STA/SI是否使用最终几何；RC角是否完整 |
| STA, Static Timing Analysis（静态时序分析） | 不运行程序，而是对所有约束路径计算 setup/hold 等时序 | MMMC 时序报告、约束审计、例外清单、关键路径 | 600 MHz、接口时钟、recovery/removal、pulse width、transition/fanout |
| MMMC, Multi-Mode Multi-Corner（多模式多角） | 组合功能、scan、MBIST、bypass等模式与 PVT/RC 角 | 每个模式/角的时序和电气报告 | 是否漏掉真实模式；是否用错误 false/multicycle 让报告变绿 |
| PVT, Process-Voltage-Temperature（工艺-电压-温度） | 工艺快慢、电源变化、温度变化的组合工作角 | foundry规定的 setup/hold/功耗角清单 | 最慢 setup、最快 hold、温度反转和寿命电压条件是否覆盖 |
| Logic/equivalence checking（逻辑/等价检查） | 比较 RTL、综合网表、DFT/ECO 后网表的功能一致性 | 等价报告、分区/例外说明、ECO证据 | 流水重定时、DFT、宏 wrapper、ECO 是否引入功能差异 |
| GLS, Gate-Level Simulation（门级仿真） | 用门级网表及 SDF 延迟检查启动、复位、关键事务和 X传播 | 带真实宏模型的定点门级回归、SDF日志 | reset/PLL/错误恢复/关键路径是否出现未解释 X 或时序违例 |
| DRC, Design Rule Checking（设计规则检查） | 检查版图几何是否满足线宽、间距、层、密度、天线等制造规则 | foundry deck 版本、DRC报告、waiver | 最终版图不得有未批准阻塞违规 |
| LVS, Layout Versus Schematic（版图与原理图/网表一致性） | 从版图提取连接，与参考网表比较 | LVS报告、CDL/网表版本、宏 black-box说明 | pad、电源、SRAM/PHY连接是否与最终网表一致 |
| ERC, Electrical Rule Checking（电气规则检查） | 检查电源短接、悬空、驱动冲突、well/substrate和电气连接 | ERC报告、ESD/电源/IO规则检查 | 电源域、IO状态、ESD和测试模式是否安全 |
| IR-drop analysis（电源压降分析） | 计算电源网络在静态/动态电流下的电压下降 | 静态/动态 IR 报告、活动文件、PDN模型 | 最差压降是否破坏标准单元、SRAM、PLL、PHY或IO工作 |
| EM, Electromigration（电迁移） | 检查金属和过孔电流密度导致的长期可靠性 | 电流密度/温度/寿命报告 | 电源网、时钟网、PCIe/PHY供电和高活动局部金属是否超限 |
| SI/PI, Signal/Power Integrity（信号/电源完整性） | 联合分析 die、封装、PCB 的高速波形和供电噪声 | PCIe/DDR通道、封装寄生、IBIS/S参数、PDN报告 | Gen3 lane、参考时钟、复位和电源在封装/板卡全链路可用 |
| Thermal analysis（热分析） | 根据功耗、封装和散热器估算结温 | 热阻/热仿真、功耗场景、散热边界 | 结温、热点和频率/寿命目标是否满足 |
| Tapeout（投片数据发布） | 将最终制造数据库交给 foundry/MPW | GDS/OASIS、最终网表、SDC、宏/库 manifest、pattern、checksum、签字 | 数据版本唯一、DRC/LVS/STA/DFT/IP授权和接收格式全部闭合 |

常见工具分工是：Synopsys Design Compiler/Fusion Compiler 或 Cadence Genus 做综合，Synopsys ICC2 或 Cadence Innovus 做布局布线，Synopsys PrimeTime 或 Cadence Tempus 做 STA，Synopsys/Cadence 的功耗工具做 power/IR/EM，Siemens Calibre 或 foundry认可的 PVS 做 DRC/LVS。工具名称不是固定要求；**PDK 和 foundry signoff deck 决定哪些版本可用于最终签核**。Vivado 仍只用于 Xilinx FPGA 的综合、实现和时序。

### 4.4 600 MHz 目标如何验证

600 MHz 的周期是 `1 / 600 MHz = 1.6667 ns`。一个寄存器到寄存器路径大致需要满足：

```text
Tclk ≥ Tcq + Tlogic + Twire + Tsetup + Tuncertainty
```

其中 `Tcq` 是发送寄存器 clock-to-Q 延迟，`Tlogic` 是组合逻辑延迟，`Twire` 是布局布线后的互连延迟，`Tsetup` 是接收寄存器建立时间，`Tuncertainty` 包括 jitter、skew、OCV 等时钟不确定性。hold、recovery/removal、最小脉宽和 max transition/fanout 也必须分别满足；不能只看 setup slack。

我们按以下八步验证：

1. **冻结时钟和工作条件。**定义 core_clk=600 MHz、PCIe user clock、管理时钟、PLL参考时钟、占空比、输入输出延迟、负载、电压、温度、jitter 和测试模式。功能、scan、MBIST、PLL bypass、reset release 各自建立 clock group 和约束。
2. **接入真实工艺库。**使用目标28 nm标准单元、SRAM macro `.lib/.db`、PLL/PHY/IO timing model 及 foundry 规定的 PVT/RC 角。black-box SRAM 或典型角模型只能做结构检查，不能用于600 MHz承诺。
3. **早期综合和路径预算。**运行 synthesis + early STA，输出面积、关键路径、扇出、逻辑层级、SRAM访问时间和功耗初估。将 1.6667 ns 按模块分解预算，例如 Matrix/Vector算术、DTCM mux/仲裁、DMA控制、CSR/AXI；预算是分析工具，不是随意给每块平均分配。
4. **真实宏和物理尺寸 floorplan。**放入 SRAM、PCIe PHY、PLL、IO ring、电源区域和 DFT 结构，检查宏到计算阵列的线长、通道和拥塞。PCIe PHY/PLL属于硬宏，不能用一组 RTL寄存器估算其物理占用。
5. **关键路径微架构优化。**若算术链、PE反馈、大 mux、全局广播或 DTCM bank选择超时，进行合法流水化、分层译码、bank重排、局部寄存和扇出复制。增加流水级必须同时修改 valid、tag、scoreboard、hazard、完成周期、性能计数和参考模型；Matrix累加反馈和Vector浮点归约不能只插寄存器而不重新证明数值/依赖关系。
6. **CTS、route 和寄生提取。**完成 Clock Tree Synthesis、placement、routing 和 SPEF/RC extraction；重新分析真实 clock skew、wire delay、耦合、IR drop 对时序的影响。placement前的逻辑延迟通过不代表布线后通过。
7. **Post-route MMMC signoff。**在所有规定功能/测试模式和 PVT/RC 角下检查 setup/hold、recovery/removal、pulse width、max transition、max fanout、clock gating、宏接口时序和 IO 时序。所有 false-path/multicycle 必须有 RTL/协议依据和评审记录；不能删掉有效路径让600 MHz报告变绿。
8. **硅上频率表征。**CP/FT后选多颗样片，在规定电压、温度、负载和工作负载下逐步提高频率，记录正确性、功耗、结温和错误状态，形成 silicon frequency table。典型角、单颗样片或 PCIe link-up 不能替代最差工作条件下的产品结论。

如果早期真实宏 STA 不能达到600 MHz，我们按以下顺序处理：先确认约束/库/宏模型正确，再优化关键路径和 floorplan；之后比较更快或不同端口 SRAM、拆 bank、降低非核心域频率、增加多周期协议或明确产品降频档位。**不能用“FPGA 100 MHz能跑”证明 ASIC 600 MHz，也不能仅把 FPGA 时钟约束改成600 MHz证明任何结论。**

FPGA 的100 MHz价值是验证功能、握手、错误注入、软件、长时间压力和流水周期数。ASIC新增流水级后，FPGA必须验证新的 valid/tag/stall/完成周期和数值结果，但不要求 ZCU102物理运行600 MHz。宏内部最小周期不能靠宏输出端多加一拍让宏变快；若宏本身不满足1.6667 ns，需要换宏、拆 bank、改变端口组织或让该存储域使用较低时钟并定义正确的 CDC/握手。

### 4.5 IO pad、封装和板卡

IO pad 是 die 上连接外部信号的 IO cell/pad 结构，包含驱动/接收、ESD、电压域、slew/drive strength、上下拉和可选复用；它属于 die，不是 BGA 球。BGA ball 是封装与 PCB 的导体连接，一个普通单端 ball 通常承载一个电气节点；高速差分 lane 由 TX/RX 两根信号组成，不是“一球固定一位”。

#### 4.5.1 IO pad 由谁提供、我们负责什么

**普通 IO pad 通常由 foundry 的 IO library 或其授权 IP 供应商提供。**该 pad 不是我们用 RTL 综合出来的逻辑；它包含工艺相关的晶体管、ESD 保护、电源/地连接和版图结构，需要匹配 PDK、IO 电压和可靠性规则。PCIe/SerDes 的高速 pad 通常由 PCIe PHY IP 厂商或 foundry 的高速 IO 生态提供，可能已经包含在 PHY hard macro 中，也可能作为独立 pad/ESD/termination 套件交付。不能把 PCIe lane 接到普通 GPIO pad 上。

我们负责定义和批准：

- 每个外部信号的功能、方向、复位默认态、复用、速率和电压域；
- PCIe lane、参考时钟、PERST、UART TX/RX、JTAG、GPIO 的数量和板卡连接；
- 功耗、ESD/测试模式、调试开放/锁定策略、pad ring/bump 预算和封装约束；
- foundry/IO/PHY 供应商提供的 pad 型号、`.lib/.lef/.gds`、ESD/可靠性说明、IBIS/S参数及集成指南是否满足要求。

后端/IO 厂商负责根据这些要求选择和实例化具体 pad、布局 pad ring/电源环、连接 bump/ball，并完成 IO 时序、电气、ESD、LVS/DRC 和封装协同。也就是说，**pad 的晶体管实现由工艺/IO供应商提供，但 pad 的功能需求、选型确认和最终系统连接属于我们总体设计的一部分。**

物理层次应理解为：

```text
RTL port → IO cell/pad（属于die）→ die pad/bump
        → package bond wire/FC bump → BGA ball
        → PCB trace/connector → Host或调试设备
```

#### 4.5.2 按接口区分 pad 类型

| 接口 | pad来源与实现 | 我们必须确认 |
|---|---|---|
| UART TX/RX、GPIO | foundry普通数字 IO library/授权IO IP | 电压、输入阈值、drive/slew、上电状态、ESD、是否需要USB-UART电平转换 |
| JTAG | foundry数字IO + DFT/测试复用要求 | TCK速度、TMS/TDI/TDO方向、TRST、测试模式、量产锁定和边界扫描复用 |
| PCIe Gen3 | PCIe PHY hard macro配套专用高速 pad/SerDes IO；不是普通GPIO pad | lane数、refclk、PERST、PHY电源、终端/参考电阻、封装寄生、IBIS/S参数和PCIe通道仿真 |
| PLL参考/输出 | PLL IP相关时钟输入/内部节点，使用专用时钟/模拟 IO要求 | reference clock、jitter、lock/bypass、模拟电源、隔离和测试观测 |
| DDR（若未来选择B路线） | DDR PHY配套byte-lane/时钟/地址命令IO | DQS、ODT、fly-by/拓扑、pin规则、训练和封装/PCB SI |

我们的首版 Host-DRAM-only 默认路线不需要 DDR pad；如果改为板载 DRAM，必须重新生成完整 pad/bump/ball/PCB 信号表，不能只在规格书中增加“DDR x32”而不修改 die 和封装。

我们的默认信号预算为：

| 接口 | 默认信号 | 说明 |
|---|---:|---|
| PCIe Gen3 x4 | 16 个高速信号（4 lane×TX/RX差分） | 另需参考时钟、PERST、专用电源/地和IP要求的测试信号 |
| UART | 2 个信号：TX/RX | TX 自动日志；RX 接收诊断/查询/受控控制命令；不承担主数据面 |
| JTAG | TCK/TMS/TDI/TDO，TRST可选 | TAP和实际内部访问逻辑分别验收 |
| GPIO | 4 个默认候选 | ready/fault/heartbeat/trigger可复用，不宣称每个都永久独立 |
| DDR | 默认0个 | 因为默认使用Host DRAM；B路线启用后按DDR PHY/器件组织重新生成完整pin表 |

FCBGA 是 v0.2 的默认评估封装，适合我们对供电、高速通道和热的目标；wire-bond BGA 是成本备选，只有在封装/PCB完整 SI/PI、PCIe Gen3 x4 和电源测试通过后才能替代。封装厂负责具体 substrate、bump/bond、ball map实施和模型交付，我们负责批准接口、功耗、热、尺寸、成本和板卡可布性。

板卡必须预留 PCIe 连接器、参考时钟和复位、JTAG、UART 到 USB-UART 的路径、GPIO/触发测点、关键电源电流测量、散热器和安全上电流程。没有这些通道，芯片即使功能正确也难以完成回片诊断。

### 4.6 责任边界

| 事项 | 我们负责 | 供应商负责 |
|---|---|---|
| 产品和接口 | lane、带宽、BAR、DMA、调试、功耗、环境、软件ABI | 按需求给出可实现选项和限制 |
| PDK/IP | 选择、授权、版本和集成验收 | 提供匹配视图、模型、约束、勘误和支持 |
| RTL/前端 | 计算、DMA适配、错误、CSR、管理和验证 | 可提供集成/审查服务 |
| 后端 | 批准约束、宏/IO/封装取舍、签核放行 | 综合、PNR、DFT、STA、DRC/LVS/IR/EM和发布数据 |
| 封装 | 需求、接口/电源/热/成本边界和最终选择 | 结构、基板、键合/凸点、寄生、SI/PI/热和装配 |
| 板卡 | 设备需求、测试流程和软件配合 | 具体原理图、layout、生产、板级SI/PI和bring-up支持 |

## 5. RTL 详细设计规格

### 5.0 RTL 模块清单与接口责任

下表是我们将规格落到 RTL 的第一层分解。文件名对应当前 XiangJiang 源码或新增模块建议名；新增模块在实现前要加入代码仓库和验证矩阵。

| 模块 | 当前/建议 RTL 文件 | 解决的问题 | 默认接口/责任 | 主要验证 |
|---|---|---|---|---|
| 顶层SoC | `NpuTop.scala`、新增 `XjSoCTop` | 把时钟、复位、PCIe、管理和计算模块组织成可封装 die | PCIe/管理/调试/DFT边界；不直接暴露 FPGA 专用 PS 端口 | reset/link/全系统 |
| 标量控制核 | `Core.scala`、`Control.scala`、`Decode.scala` | 解释程序、发起 Matrix/Vector/DMA 命令 | 保持现有32 bit ISA子集；明确非法 opcode、PC和任务完成 | ISA/scoreboard/formal |
| Matrix | `Matmul.scala`、`SystolicArray.scala`、`PE.scala` | INT8 主干矩阵计算 | 16×16阵列、INT32累加、DTCM专用访问端口 | bit-exact/边界/关键路径 |
| Vector | `VectorUnit.scala`、`Bf16.scala`、`Activation.scala` | BF16/FP32辅助算子和归约 | 8 lane、尾行、数值语义、DTCM访问端口 | 数值/特殊值/归约顺序 |
| ITCM wrapper | `Sram.scala`、新增 `ItcmMacroWrapper` | 取指和程序装载 | 1RW、32 bit、byte mask、真实宏延迟 | macro/FPGA/门级 |
| DTCM wrapper | `Dtcm.scala`、新增 `DtcmMacroWrapper` | 计算工作集和结果共享 | 512 KiB、128 bit、2逻辑端口；bank/仲裁/冲突 | 并发/冲突/ECC/MBIST |
| AXI管理从端 | `AxiMemSlave.scala`、新增 `AxiMgmtSlave` | BAR/AXI访问 CSR、TCM和调试窗口 | 32 bit data、地址/权限/burst/LAST/response检查 | AXI VIP/负向访问 |
| DMA Engine | `Dma.scala`、新增 `DmaDescriptor` | Host DRAM 与 local memory 搬运 | 64 bit Host地址、descriptor、4 KiB、错误/超时/恢复 | DMA VIP/错误注入 |
| PCIe适配 | 新增 `PcieAxiBridge`、`PcieIrqAdapter` | 把第三方 PCIe IP 接入内部 AXI、BAR、MSI | inbound/outbound、MPS/MRRS、复位、IP错误映射 | PCIe VIP/Endpoint FPGA |
| 错误管理 | 新增 `ErrorManager` | 汇总并保存跨模块 fault | fault code/source/task/address/PC、sticky/overflow/clear | 故障注入/恢复 |
| 任务/队列 | 新增 `CommandQueue`、`CompletionQueue` | 把 runtime 提交转换为硬件任务 | owner/valid/doorbell/completion 顺序 | 多任务/异常生命周期 |
| 调试桥 | 新增 `JtagDebugBridge`、`DebugQuiesce` | 回片后独立读取状态/TCM | JTAG TAP 后端总线、quiesce、权限 | JTAG脚本/挂死dump |
| UART/GPIO | 新增 `UartDebug`、`DebugGpio` | 无 Host/PCIe 时保留诊断路径 | UART TX/RX、TX/RX FIFO、命令解析、GPIO 状态/触发复用 | FIFO/断连/非法命令/权限 |
| Trace RAM | 新增 `DebugTraceRam` | 保存 fault 前的事件历史 | 4 KiB目标、循环写入、触发冻结、JTAG/UART/PCIe读取 | 事件顺序/冻结/overflow/读取 |
| 时钟复位 | 新增 `ClockResetManager` | PLL/POR/域复位/安全时钟 | 工艺IP、CDC/RDC、局部/全局 reset | CDC/RDC/复位压力 |
| DFT连接 | 后端/DFT wrapper | scan、MBIST、可选repair | test mode、测试时钟、宏BIST访问 | ATPG/MBIST/coverage |

### 5.1 顶层和时钟复位

**解决的问题：**当前 `NpuTop` 只有单时钟、active-low AXI reset，不能表达 PCIe、管理、测试和未来多时钟域。

**默认实现：**保留 NPU 核心时钟域；增加 PCIe user clock、管理/CSR时钟域和必要的异步 FIFO/握手；PLL/POR/ICG 使用工艺认可 IP/单元。每个域有同步复位释放，PLL lock 失败进入安全状态。Core reset、DMA reset、PCIe reset 和全芯片 reset 的影响矩阵必须写入 RTL/软件 ABI。

**备选：**所有逻辑同一时钟，减少 CDC；代价是 PCIe/管理时钟必须迁就 600 MHz，功耗和实现风险更高，不作为默认。

### 5.2 Matrix 模块

**解决的问题：**为 INT8 GEMM/卷积主干提供高吞吐乘加，并复用现有 16×16 阵列。

**默认接口和行为：**Matrix 从 DTCM 读取 tile，执行 16×16 INT8 乘法和 INT32 累加，结果写回 DTCM；保持现有指令/队列语义。必须定义 tile 边界、部分 tile、累加初值、地址对齐、矩阵端口冲突、stall 和完成条件。

**备选：**扩大到 32×32 或增加多个 Matrix 核。优点是峰值接近 1 TOPS 的路径更现实；缺点是 SRAM端口、面积、功耗、带宽和验证规模显著增加。v0.2 不采用，作为下一版架构。

**验收：**bit-exact 参考模型、全零/最大正负数/累加回绕、部分 tile、DTCM 冲突、DMA 同时访问、流水重定时前后结果一致。

### 5.3 Vector 模块

**解决的问题：**完成量化、归一化、激活、转换和归约等 Matrix 不能高效覆盖的算子。

**默认接口和行为：**8 路 BF16 输入，必要处以 FP32 中间结果，按现有 Vector 指令执行；DTCM 行为 128 bit，支持 valid/ready、部分尾行和与 Matrix/DMA 的仲裁。

**备选：**扩大 lane 数或添加专用激活硬件。优点是 Qwen 辅助算子更快；缺点是 FP32 逻辑、验证和功耗增加。v0.2 保持8路，先用软件 profile 决定下一版。

**验收：**BF16特殊值、舍入/归约顺序、NaN/Inf、尾行、源目的重叠规则、长组合路径和 600 MHz 关键路径。

### 5.4 ITCM/DTCM 和访问仲裁

**解决的问题：**FPGA BRAM 行为不能直接代表 ASIC SRAM，且当前 DTCM 端口冲突规则不足以覆盖 1RW 宏。

**默认实现：**共同 memory wrapper 定义端口请求、响应延迟、mask、权限、冲突和错误；backend 选择 FPGA BRAM、仿真宏模型或 ASIC SRAM macro。DTCM 访问者为 Matrix、Vector/标量、DMA 和受控 debug；仲裁必须可停顿并保证请求不丢失。

**备选：**若 compiler 提供真正 2RW 宏，保留两逻辑端口；若只有1RW，按 bank 仲裁并允许 Matrix/Vector stall。不得用“同一行冲突”代替“同bank不同行冲突”。

**验收：**边界地址、非法窗口、并发读写、同址 read/write、byte mask、未初始化、MBIST后 reload、ECC/RMW（若选）、不同 backend 对照。

### 5.5 AXI 从接口和内部 fabric

**解决的问题：**当前 S_AXI 对 burst/size/strobe/LAST/地址窗口的检查不完整，错误可能固定返回 OKAY。

**默认实现：**保留一个 32 bit AXI4/AXI-Lite兼容管理从接口，支持的 burst/size/alignment/ID/outstanding 写入接口规范；对不支持或非法访问返回约定的 SLVERR/DECERR，不产生副作用。完整译码分离 ITCM、DTCM、CSR 和保留区域，禁止 ITCM 绕回、CSR 低位别名和非法长度截断。

**备选：**BAR0 直接接一个第三方 AXI bridge；优点是减少自研协议转换，缺点是地址译码和错误语义受 IP 约束。无论哪种方案，CSR/错误路径不得被长时间 TCM 等待锁死。

### 5.6 DMA 模块

**解决的问题：**Host DRAM 与 DTCM 间数据量大，且当前 DMA 未完整检查 64 bit 地址、RRESP/BRESP、ID、LAST、超时和失败状态。

**默认 descriptor：**

```text
host_addr[63:0]   // Host DMA/IOVA，不是用户虚拟地址
local_addr[31:0]  // ITCM/DTCM窗口内偏移
length[31:0]      // bytes，按实现限制和16 B对齐检查
flags[31:0]       // direction, interrupt, end, permissions, version
tag[31:0]         // software task/segment association
```

DMA 在发起前检查地址加法进位、地址空间、权限、16 B 对齐、长度、4 KiB 分段和最大 burst；接收端检查 RRESP/BRESP、返回 ID、RLAST 和实际 beat 数。错误数据不得提交给 DTCM，任务不得从 FAILED 伪装成 SUCCESS。超时后停止新请求，安全排空/隔离已握手事务；驱动在设备 quiesce 前不能释放 DMA 映射。

**备选：**供应商 PCIe DMA IP 直接输出 AXI-MM/AXI-Stream；我们只实现 descriptor/completion adapter。优点是协议成熟，缺点是硬件/驱动语义变化和许可成本增加。

### 5.7 PCIe 管理、BAR 和中断

**解决的问题：**PCIe 不只是把 AXI 接到物理线，还需要配置空间、BAR、TLP、completion、链路复位和中断语义。

**默认：**第三方 Gen3 Endpoint Controller + PHY；x4；BAR0 为 64 KiB 管理 CSR，BAR1 为 1 MiB 受控 TCM/debug窗口，command/completion ring 位于 Host DRAM，BAR2 不作为强制需求。默认目标 MPS 256、MRRS 512，实际按 IP/Host 协商并允许降低。MSI 单向量为默认通知，轮询始终保留。

**备选：**x1、更大的 BAR、MSI-X、多队列。x1省面积/pin但减少带宽；MSI-X和多队列适合并发但增加驱动和验证复杂度；不能因“别人通常用BAR2”就固定BAR编号。

**错误和复位：**记录 PCIe link-down、completion timeout、UR/CA/poison 等 IP 可见事件；状态先于 MSI 写入 CSR。PERST、hot reset、软件复位和重新枚举的行为必须分别定义；不支持的 FLR 等能力不能广告。

### 5.8 CSR、错误码和恢复

**解决的问题：**错误发生后，Host、UART、JTAG 和 GPIO 必须看到同一份可定位状态。

默认 CSR（BAR0 相对地址）：

| 偏移 | 名称 | 访问 | 默认语义 |
|---:|---|---|---|
| 0x0000 | CHIP_ID | RO | 产品/修订号 |
| 0x0004 | ABI_VERSION | RO | 驱动兼容性检查 |
| 0x0008 | CAPABILITIES | RO | lane、DMA、SRAM、debug、ECC能力 |
| 0x000C | DEVICE_STATUS | RO | ready/busy/fault/quiesced/link |
| 0x0010 | CONTROL | RW | start/quiesce/局部复位命令 |
| 0x0020 | IRQ_STATUS | RO | sticky pending事件 |
| 0x0024 | IRQ_ENABLE | RW | 事件屏蔽 |
| 0x0028 | IRQ_ACK | W1C | 软件确认事件 |
| 0x0040 | TASK_ID | RW | 当前任务号 |
| 0x0044 | TASK_STATUS | RO | IDLE/RUNNING/SUCCESS/FAILED/ABORTED |
| 0x0100 | FAULT_VALID | RO | 首错快照有效/溢出 |
| 0x0104 | FAULT_CODE | RO | 版本化错误码 |
| 0x0108 | FAULT_SOURCE | RO | Core/DMA/AXI/PCIe/SRAM等来源 |
| 0x010C | FAULT_TASK_ID | RO | 发生错误的任务 |
| 0x0110/14 | FAULT_ADDR_LO/HI | RO | 64 bit Host/local地址 |
| 0x0118 | FAULT_PC | RO | 可用时记录指令位置 |
| 0x011C | FAULT_RAW | RO | 原始响应/附加状态 |
| 0x0120 | FAULT_COUNT | RO | 饱和计数 |
| 0x0124 | FAULT_ACK | W1C | 清首错快照 |

错误码默认分类如下：

| 错误码 | 含义 | 默认动作 |
|---|---|---|
| E001 | 非法命令/descriptor版本 | 拒绝提交，任务FAILED |
| E002 | 地址未对齐/范围/加法溢出 | 不发请求，任务FAILED |
| E003 | AXI size/burst/LAST/strobe非法 | 完成协议收尾，记录AXI错误 |
| E004 | DMA RRESP/BRESP/ID/RLAST错误 | 不写入无效结果，隔离DMA |
| E005 | DMA/PCIe completion timeout | 停止新请求，记录超时，等待恢复 |
| E006 | PCIe link/reset/UR/CA/poison | 依赖任务FAILED，保留状态 |
| E007 | SRAM/ECC/BIST错误 | 按纠正/不可纠正策略处理 |
| E008 | Core/Matrix/Vector非法访问或指令 | 停止依赖执行，记录PC/来源 |
| E009 | DDR训练/存储错误 | 仅B路线；设备进入不可用或重训练状态 |
| E00A | 复位/电源/时钟监视告警 | 进入安全状态，保留可读诊断 |

错误状态按“首错冻结 + 累计计数 + overflow + 明确清除”实现。UART 是日志输出通道，不是错误存储；PCIe MSI 是通知通道，不是错误数据载体；JTAG 是独立读取/控制通道，是否能读 TCM 取决于我们是否实现调试总线和 quiesce。

### 5.9 调试模块

**默认：**JTAG TAP连接自研调试桥，可读取 CHIP_ID、CSR、fault snapshot 和在 quiesce 后的 ITCM/DTCM；UART 做成 TX+RX 双向接口：TX 由事件 FIFO/发送状态机驱动，RX 由接收器、RX FIFO 和低速命令解析器驱动；GPIO 输出 `ready/fault/heartbeat/trigger` 的复用状态。

UART RX 的默认命令范围是读取版本/状态/错误快照、读取指定范围的 TCM、请求 quiesce、清除已确认错误和触发受控局部复位。RX 不允许在未授权模式下执行任意物理地址读写、改变 PCIe 配置或绕过任务生命周期；命令必须有帧头、长度、命令号、序号、payload、校验和和返回码。TX/RX 都有 FIFO，UART 未连接或主机不读取时不能阻塞 NPU 数据面。调试/量产锁定由 JTAG unlock、测试 strap 或 eFuse 生命周期状态控制。

**备选：**将 trace RAM 扩大到 8–16 KiB，或采用标准 RISC-V Debug Module。它们提升可诊断性/工具兼容性，但增加 SRAM、逻辑、验证、权限和量产锁定工作。普通 JTAG TAP 本身不能自动访问 NPU 内存，也不能因为留了四根 JTAG 线就宣称“支持 GDB”。

### 5.10 trace RAM 与标准 RISC-V Debug 的含义

**trace RAM 是什么。** trace RAM 是芯片内部一块专门保存调试事件的 SRAM，通常按循环缓冲区工作：新事件到来时覆盖最旧事件，发生 fault 时冻结或保留一段上下文。它可以记录如下信息：

| 记录内容 | 示例 |
|---|---|
| 时间/序号 | cycle counter、event sequence |
| 执行状态 | task ID、PC、Matrix/Vector FSM state、指令号 |
| 数据搬运 | DMA descriptor tag、地址、长度、方向、完成状态 |
| 错误 | fault code、source、AXI/PCIe response、timeout |
| 队列/复位 | doorbell、queue full/empty、quiesce、reset transition |

trace RAM 不是 UART FIFO，也不是普通程序数据 SRAM。UART 适合把少量结果逐条发出去，trace RAM 适合在错误发生前保留一串历史事件；回片后通过 JTAG、PCIe debug window 或 UART 分段读取。它的代价是额外 SRAM 宏、写入 mux、触发/冻结逻辑、读取协议和安全设计。我们的 v0.2 默认加入 **4 KiB 目标容量的小型 trace RAM**，记录宽度、条目数量和是否使用独立 SRAM macro 在 early PPA 后冻结；如果面积/时序不接受，可降为1 KiB或由批准的版本变更移除，但必须重新评审回片诊断风险。

**标准 RISC-V Debug 是什么。** RISC-V Debug Specification 定义了一套标准调试架构，典型组成是：

```text
外部调试器/GDB
        │ JTAG 或其他 Debug Transport
        ▼
Debug Transport Module (DTM/TAP)
        ▼
Debug Module (DM)
        ├─ halt/resume/reset/hart 状态
        ├─ 访问 GPR/CSR/内存的 abstract commands
        └─ program buffer / debug exception 机制
```

它的目标是让 OpenOCD、GDB 等标准工具以统一方式停止和恢复一个或多个 RISC-V hart，读写寄存器和内存。它不是 UART，也不是 PCIe DMA；它需要芯片实现标准的 DTM/DM 行为和相应的核调试支持。

我们当前只有 RV32I 子集和自定义指令，并没有现成的标准 RISC-V Debug Module、完整特权架构或已验证的 GDB 调试语义。因此，**标准 RISC-V Debug 不能因为使用了 RISC-V 风格指令就自动成立**。v0.2 首版默认采用面向 NPU 的自研 JTAG 调试桥，直接提供 CSR/TCM/fault/quiesce 访问；未来如果多核化、引入标准 RISC-V 控制核或需要 OpenOCD/GDB 生态，再评估标准 DTM+DM。两种方案可以共用 JTAG 引脚，但内部协议、工具和验收完全不同。

## 6. 验证和签核方法

### 6.1 我们要解决的问题

原型 FPGA 使用 BRAM、PS AXI 和约100 MHz，流片版本使用 SRAM hard macro、PCIe PHY 和 ASIC 后端。我们必须证明两者在功能契约上一致，同时承认它们在物理实现上不一致。

### 6.2 四层验证体系

| 层次 | 默认环境 | 证明内容 | 不证明的内容 |
|---|---|---|---|
| RTL模块 | Scala/Verilog仿真、断言、formal | Matrix/Vector/AXI/DMA/CSR/错误语义 | ASIC布线、PHY电气 |
| 子系统 | AXI/PCIe/DDR VIP、真实macro模型、参考模型 | BAR、DMA、复位、中断、存储和错误恢复 | 封装SI/PI、良率 |
| FPGA | ZCU102 + PS AXI；另加PCIe Endpoint板 | 软件、协议、压力、故障流程 | ASIC SRAM、ASIC PHY、600 MHz |
| ASIC签核/硅 | 综合、等价、STA、DRC/LVS、IR/EM、CP/FT、回片 | 制造可行、频率、电源、封装和实硅功能 | 所有现场异常的绝对保证 |

### 6.3 FPGA 验证路线

我们继续用 ZCU102 验证计算、AXI、DMA、TCM、CSR、错误注入和 PS 软件；其 PS 侧 PCIe 连接不能直接证明 NPU Gen3 Endpoint。我们另外准备有 PL 高速收发器、参考时钟、PERST 和 Host Endpoint 连接能力的 FPGA 平台，用于验证 PCIe BAR、DMA、MSI、IOMMU开关、掉链和驱动恢复。

ZCU102 上的第一批工作是：

1. 增加 fault CSR 和 TASK_STATUS，由 PS 通过现有 AXI 读取并串口打印。
2. 注入 DMA 越界、RRESP/BRESP错误、超时、4 KiB边界和复位场景。
3. 把 SRAM wrapper 接到 FPGA BRAM，并记录与 ASIC macro模型不同的延迟/冲突行为。
4. 用同一软件 ABI 运行 Matrix/Vector 单测和 Qwen 算子小规模回归。
5. 在 Endpoint FPGA 上接 Linux Host，完成真实 BAR/DMA/MSI 驱动闭环。

当前仓库的回归入口保留为 `cd XiangJiang && mill xnpu.test`；DTCM 专项可使用 `XNPU_BACKEND=verilator mill xnpu.test.testOnly '*DtcmDualPortSpec'`。这些命令只是建立 v0.2 的功能基线，执行结果必须记录 commit、参数、工具版本和日志，不能直接当作 ASIC 通过。

### 6.4 2B Qwen prefill 验证

我们建立三份结果：Host 参考实现、RTL/FPGA结果、ASIC结果。每个算子保存输入、量化/打包版本、输出、误差容限、DTCM tile划分和搬运统计。验证顺序是：

```text
算子 bit-exact/误差 → tile kernel → layer → prefill 主干图
→ PCIe load/compute/store → runtime 端到端 → 频率/带宽/功耗表征
```

若某算子未被 Matrix/Vector/标量核覆盖，软件必须明确走 Host fallback，并在性能报告中单独计时；不能把 fallback 隐藏在“硬件支持2B Qwen”的总标题下。

### 6.5 600 MHz 验证矩阵

| 阶段 | 工具/输入 | 我们接受的结论 |
|---|---|---|
| FPGA | Vivado timing、资源、长时压力 | 功能和握手稳定；不能推出 ASIC 频率 |
| ASIC early synthesis | 真实28 nm库、macro `.lib`、初始SDC | 识别关键路径和面积风险；不是最终签核 |
| placement/CTS | 真实宏/PHY尺寸、时钟树 | 识别拥塞、扇出和物理路径风险 |
| post-route | SPEF/RC、MMMC/PVT、setup/hold/电气 | 只有所有适用角和模式通过，600 MHz才可标为签核通过 |
| silicon | 频率扫描、压温、工作负载 | 形成产品频率表；若低于目标，必须有正式降级决定 |

### 6.6 后端和制造签核

投片前我们必须拿到：

- RTL→综合网表→最终网表的等价/差异证明。
- 功能、scan、MBIST、PLL bypass 等模式的完整约束和 STA。
- DRC、LVS、ERC、antenna、density/fill/DFM 无未批准阻塞违规。
- 静态/动态功耗、IR drop、EM、封装/PCB SI/PI、热分析。
- scan/ATPG、SRAM MBIST、可选BISR、CP/FT pattern和测试限值。
- 最终 GDS/OASIS、netlist、SDC、宏/IP manifest、pin/ball map、checksum 和签字。

### 6.7 “一次成功”的可执行准则

我们不使用“覆盖率100%”或“FPGA跑通”作为一次成功保证。放行依据是：需求—实现—验证—缺陷—版本可追溯；所有适用需求有有效证据；关键错误路径注入通过；真实宏/PHY/PDK已接入；后端签核无阻塞项；封装和板卡连接一致；保留回片调试和重投预算。

## 7. 参数与接口查阅表

### 7.1 核心参数

| 参数 | v0.2默认值 | 状态 |
|---|---|---|
| 工艺 | 华虹体系 28 nm，实际 fab/platform TBD | 方向已定，待厂商确认 |
| NPU die | 单 die；die 内无 DRAM/eDRAM | 默认冻结 |
| Core | 单核；32 bit地址/指令/GPR；现有 ISA 子集 | 基线，需补语义 |
| Matrix | 16×16 INT8，INT32累加，256 MAC/cycle | 基线 |
| Vector | 8 lane BF16，FP32中间路径 | 基线 |
| ITCM | 32 KiB，32 bit，1RW，byte mask | 逻辑冻结，宏TBD |
| DTCM | 512 KiB，128 bit行，两个逻辑端口 | 逻辑冻结，2RW/1RW宏TBD |
| 核心时钟 | 600 MHz目标，1.6667 ns | 需ASIC签核 |
| PCIe | Gen3 Endpoint x4 | 默认，x1备选 |
| Host DMA | 64 bit地址，16 B对齐，4 KiB分段 | 需RTL实现 |
| Host大容量存储 | Host DRAM | 默认 |
| BAR0 | 64 KiB CSR | 默认 |
| BAR1 | 1 MiB受控 TCM/debug窗口 | 默认；权限需冻结 |
| 中断 | 单 MSI | 默认；MSI-X备选 |
| 调试 | JTAG bridge、UART TX/RX、4 GPIO候选、4 KiB trace RAM目标 | 默认候选，需pad/安全决策；标准RISC-V Debug为增强项 |
| 软件 | Host Linux 6.x driver + XJ-RT inference engine + Qwen mapper | 默认 |
| 功耗/温度/面积 | TBD | G1必须冻结 |

### 7.2 PCIe/AXI 方向

| 接口 | 方向（相对NPU） | 默认宽度/属性 | 用途 |
|---|---|---|---|
| PCIe lanes | 双向高速 | x4，Gen3 | Host Root Complex ↔ NPU Endpoint |
| PCIe refclk/PERST | Host/板卡输入 | 依IP，默认100 MHz refclk候选 | 链路/复位 |
| S_AXI 管理从端 | Host/bridge → NPU | 32 bit data，32 bit addr，ID=4 | CSR/TCM窗口；需完整检查 |
| M_AXI DMA主端 | NPU → bridge/Host | 128 bit data，Host addr 64 bit目标 | Host DRAM搬运 |
| 内部 AXI/AXI-like | master/slave按模块 | 由 fabric 规范冻结 | CSR、TCM、DMA、PCIe适配 |

M_AXI 128 bit 是内部一次 16 byte 搬运宽度，不等于 128 个封装引脚；PCIe x4 的物理 lane 数也不由 AXI 数据宽度决定。

### 7.3 DMA、任务和地址空间

| 地址空间 | 默认范围/含义 | 谁产生 |
|---|---|---|
| CPU virtual address | Host进程虚拟地址 | 应用；不能直接给硬件 |
| Host DMA/IOVA | 64 bit，Linux DMA API/IOMMU返回 | 驱动 |
| Device local | 32 bit ITCM/DTCM/CSR偏移 | NPU descriptor/CSR |
| BAR physical window | PCIe枚举后由OS分配 | 驱动 |

默认禁止用户进程直接把虚拟地址写入 NPU。驱动负责 DMA map/unmap、缓存可见性、buffer 生命周期、IOMMU 开关和设备失效处理。command queue 的 owner、descriptor valid/complete、doorbell 写入顺序和 completion record 必须与 software ABI 同时冻结。

### 7.4 时钟、复位和调试

| 信号/域 | 默认方向 | 默认定义 |
|---|---|---|
| core_clk | 输入/PLL输出 | 目标600 MHz；由PLL生成或测试bypass |
| pcie_user_clk | IP输出/域内 | 由PCIe IP定义，和core通过CDC连接 |
| mgmt_clk | 输入/派生 | CSR/debug/UART域，频率TBD |
| POR | 输入/工艺IP | 上电保持安全，释放顺序由IP定义 |
| local reset | 内部 | Core/DMA/PCIe可分别复位，影响矩阵必需 |
| JTAG TCK/TMS/TDI/TDO | 外部 | TAP + 实际调试桥；量产锁定策略 |
| UART TX/RX | 外部 | TX自动日志、RX低速诊断命令；UART不承担主数据面 |
| GPIO[3:0] | 外部 | 状态/触发复用；上电默认安全 |

### 7.5 版本和兼容性

任何改变地址、descriptor、指令、数值、错误码、reset或中断语义的变更，都必须递增 ABI/硬件版本并重跑相关验证。软件先读取 `CHIP_ID/ABI_VERSION/CAPABILITIES`，不匹配时拒绝加载任务；不能默默按旧地址访问新芯片。

## 8. 发布、实施和待冻结事项

### 8.1 版本发布标准

v0.2 可作为内部架构评审和厂商询价基线。进入 v1.0 前，我们必须冻结：

1. 华虹实际工艺/PDK/标准单元/SRAM/PCIe/PLL/IO版本及授权。
2. Gen3 x4 或 x1、BAR、MSI、DMA职责、descriptor和Host软件ABI。
3. ITCM/DTCM真实宏端口、延迟、bank、ECC/repair和MBIST方案。
4. 2B Qwen prefill 的算子覆盖、量化格式、序列长度、性能/功耗/温度验收值。
5. 600 MHz的PVT/RC/模式、功耗和降级频率表。
6. pad、ball、封装结构、PCB、供电、散热、CP/FT、JTAG/UART/GPIO策略。

### 8.2 我们的实施顺序

```text
冻结目标与软件工作负载
  → 工艺/IP/宏询价和PPA预估
  → CSR/地址/DMA/错误/时钟接口冻结
  → AXI/访存检查和fault CSR（先在ZCU102验证）
  → SRAM wrapper与真实macro模型
  → PCIe Endpoint FPGA + Linux驱动
  → Qwen算子/端到端软件回归
  → ASIC综合/DFT/floorplan/600MHz早期STA
  → 封装/板卡/SI/PI/热协同
  → post-route signoff/CP-FT/tapeout
  → 回片bring-up/频压温/应用验收
```

### 8.3 当前最先关闭的事项

| 优先级 | 我们要关闭的事项 | 输出 |
|---|---|---|
| P0 | 确认华虹实际 fab/PDK/IP 可行性 | 工艺/IP匹配矩阵和书面回复 |
| P0 | 锁定 Host-only 默认路线和 PCIe x4 候选 | 系统地址图、带宽模型、成本对照 |
| P0 | 取得 ITCM/DTCM SRAM compiler catalog和模型 | 宏选择、端口/延迟/面积/时序报告 |
| P0 | 明确 2B Qwen prefill 算子和量化规格 | 软件栈、参考模型、验收工作负载 |
| P1 | 增加 DMA/AXI 检查、fault CSR、任务失败状态 | ZCU102故障注入和PS日志 |
| P1 | 选择第三方 PCIe Controller/PHY 和 Endpoint FPGA平台 | IP交付清单、驱动闭环、原型报告 |
| P1 | 建立真实宏 early STA 和 600 MHz 关键路径报告 | PPA、流水线/宏/bank调整决定 |
| P1 | 完成封装/IO/板卡预评估 | pad/ball/PCB/SI/PI/热联合报告 |

## 附录 A：历史资料与配套文件

- [v0.1总体设计与流片规格](XiangJiang_28nm_总体设计与流片规格_v0.1.md)：保留完整的 R001–R070 需求、厂商交付、验证和预算细节，作为追溯资料；与本文冲突时以本文和后续批准变更为准。
- [实施与关闭清单](实施与关闭清单.csv)：100项任务和依赖。
- [验证与签核矩阵](验证与签核矩阵.csv)：V01–V24 验证组。
- [选型决策表](选型决策表.csv)：D01–D22 选型记录。
- [厂商需求与交付清单](厂商需求与交付清单.md)：RFI/RFQ 输入，不代表已经向任何厂商发出。
- [预算与报价模板](预算与报价模板.csv)：金额待正式报价，不把空白视为零元。

## 附录 B：术语

| 术语 | 本项目定义 |
|---|---|
| die | 切割后的硅片；我们的NPU逻辑、SRAM、PHY、IO均在die内 |
| package | 封装成品，可包含一个或多个die；首版为单NPU die |
| SRAM/scratchpad | SRAM是物理存储器；ITCM/DTCM是软件管理的scratchpad，不是cache |
| DRAM/eDRAM | DRAM通常是外部存储；eDRAM是需专用工艺/IP的嵌入式DRAM；首版均不采用 |
| controller/PHY | controller处理协议/事务；PHY处理高速电气收发；PCIe两者都需要 |
| BAR | Host映射到设备的PCIe地址窗口 |
| AXI Master/Slave | Master发起事务，Slave接收并响应；描述事务方向 |
| DMA | 硬件数据搬运；不等于PCIe，默认通过AXI和PCIe bridge连接 |
| IOMMU | Host侧地址转换和设备访问隔离；不要求NPU内置MMU |
| PDK | 工艺数据、模型、层定义、规则和库/宏使用环境 |
| STA/PNR/DRC/LVS | 静态时序分析/布局布线/设计规则检查/版图网表一致性检查 |
| DFT/ATPG/MBIST | 可测试性设计/逻辑测试pattern/片上SRAM测试 |

---

**发布声明：**本文可作为我们与后端、IP、封装、板卡和软件团队的统一评审基线；只有在“待冻结事项”形成书面决定并完成对应验证/签核后，才可升级为 v1.0 投片规格。
