# Vivado IP 导出与 ZCU102 集成

器件规格、BRAM/DSP 预算与映射建议见 [ZCU102 资源规划](ZCU102_Resource_Plan.md)。

## 1. 生成

```bash
./mill xj.runMain top.Elaborate
# 可选：输出目录、实际 AXI 时钟频率（Hz）
./mill xj.runMain top.Elaborate generated/vivado 99990005
```

默认输出 `generated/vivado/`，默认频率 100 MHz。`top.Generate` 仍用于裸核心/仿真导出，不改变其端口或地址契约。

```text
 generated/vivado/
 ├── XiangJiangAXI.sv       # Vivado IP 封装顶层，带 Xilinx interface 属性
 ├── synthesis.f           # 综合文件列表；路径相对于该目录
 ├── package_ip.tcl        # Vivado batch 打包脚本
 ├── resource-policy.txt   # 实际添加的资源属性与目标声明
 └── rtl/
     ├── XiangJiangNPU.sv
     ├── ...               # 全部子模块及 RAM 实现
     ├── filelist.f        # 原始生成 manifest
     └── xiangjiang-config.json
```

`synthesis.f` 从原始 manifest 派生，不使用通配符导入旧文件；不包含 verification bind/assert
层。必须导入完整文件列表，不能只导入 `XiangJiangNPU.sv`。

### ZCU102 资源属性

Elaborate 在独立导出目录中对 manifest 内的 RTL 定向添加属性：

| 对象 | 默认属性 |
| --- | --- |
| ITCM Memory 数组 | `ram_style="block"` |
| 当前 4×4 KiB DTCM Memory 数组 | `ram_style="block"` |
| BF16 PE 的 8×8 有效数乘法临时结果 | `use_dsp="yes"` |
| INT8 PE 的乘法临时结果、accumulator | `use_dsp="yes"` |
| BF16 FP32 accumulator、VectorUnit、tile buffers、控制逻辑 | 不添加属性，自动综合 |

可选第三、第四个位置参数用于 A/B 综合比较（前两个参数必须提供）：

```bash
# 默认：ITCM BRAM、DTCM BRAM、PE 乘法优先 DSP
./mill xj.runMain top.Elaborate generated/vivado 100000000 block yes
# 对照实验：DTCM 改用 LUTRAM
./mill xj.runMain top.Elaborate generated/vivado-lutram 100000000 distributed yes
# PE 乘法/INT8 累加不使用 DSP（比较 LUT 与时序）
./mill xj.runMain top.Elaborate generated/vivado-lut 100000000 distributed no
```

输出 `resource-policy.txt` 记录策略与每项匹配声明。处理器严格检查模块唯一性、存储器
几何结构和目标乘法赋值；目标缺失、重复或 firtool 格式变化会直接报错，需要人工更新
策略及测试。全部检查通过后才写回属性。每次导出先重新生成裸 RTL，不重复叠加属性。
这里只添加综合属性，不更改容量、数值语义、流水延迟或时序约束；普通 Generate 不受影响。
属性不是资源实例化承诺，尤其过程内临时变量上的 DSP 属性是否被 Vivado 采用，以及
INT8 MAC 是否完整合并进 DSP，都必须检查综合日志/层级资源报告。若未采用，应改用
专用 FPGA wrapper/原语，而不是假定已经消耗规划中的 768 个 DSP。

## 2. 打包

在已配置 Vivado 环境的终端执行：

```bash
vivado -mode batch -source generated/vivado/package_ip.tcl
# 自定义 FPGA 型号和输出 IP repository（可选参数必须按顺序提供）
vivado -mode batch -source generated/vivado/package_ip.tcl \
  -tclargs xczu9eg-ffvb1156-2-e /absolute/path/to/ip_repo
```

默认器件为 ZCU102 的 `xczu9eg-ffvb1156-2-e`，默认 IP repo 是导出目录下的 `ip_repo`。
脚本创建临时 Vivado 工程、导入综合文件、设置顶层 `XiangJiangAXI`、推断三个 AXI 接口、
配置 Slave aperture 和 Master address space，并执行 `ipx::check_integrity` 后保存。
它只打包 IP，不执行综合、布局布线或生成 bitstream；必须检查 Vivado 日志中的错误和警告。
脚本中的临时工程使用 `create_project -force`，不要将自己的工程放进其 `vivado_project` 目录。

在目标工程的 **Settings → IP → Repository** 添加上述 repo，刷新 IP Catalog，添加
`XiangJiang NPU`。也可用 GUI Create and Package New IP 导入 `synthesis.f` 中的全部文件，
将 `XiangJiangAXI` 设为顶层，并按下表配置 AXI memory map/address space。

## 3. 封装边界

| 接口 | 方向 | 协议 | 数据宽度 | 地址宽度 | 地址空间 |
| --- | --- | --- | --- | --- | --- |
| S_AXI_CTRL | Slave | AXI4-Lite | 32 | 16 | 64 KiB aligned aperture |
| S_AXI_MEM | Slave | AXI4 | 32 | 20 | 1 MiB aligned aperture |
| M_AXI_DDR | Master | AXI4 | 32 | 32 | 4 GiB address space，实际只映射可达 DDR |

Full AXI ID 默认为 4 bit。Master/Slave 方向及每个通道的属性由封装显式标注。
所有接口使用同一个 `ACLK`，`ARESETN` 为低有效，内部转换为核心的高有效同步复位。
频率属性必须与 BD 实际提供的频率一致；导出时的频率参数仅声明 metadata，不产生时钟，
也不证明 RTL 能满足该频率。封装不包含 CDC 或 DDR PHY/controller。
状态、retire 和宽寄存器调试输出不导出为 IP 引脚，软件通过 CSR 轮询；本版本不增加 IRQ。

## 4. Host 地址转换（与裸核心不同）

Slave 地址端口缩窄为 aperture 内的偏移位，必须将 CTRL 基址按 64 KiB、MEM 基址按
1 MiB 对齐。上游 AXI 互连负责全局地址译码/宽度适配，封装显式零扩展 Control 地址，
并重排 Memory 的地址位，不依赖互连自动执行减基址。

| PS 可见窗口偏移 | 裸核心看到的地址 | 内容 |
| --- | --- | --- |
| CTRL + 0x0000…0xFFFF | 0x0000…0xFFFF | CSR，未定义/未对齐地址返回 SLVERR |
| MEM + 0x00000…0x7FFFF | 0x00000000…0x0007FFFF | 512 KiB ITCM |
| MEM + 0x80000…0xFFFFF | 0x10000000…0x1007FFFF | DTCM aperture |

当前默认 DTCM 仅 16 KiB，所以 MEM + `0x80000…0x83FFF` 有效；其余 DTCM aperture
返回 SLVERR，不回绕到有效 SRAM。burst 必须留在同一个合法 SRAM 区间及 4 KiB 页内。
运行中 Host 不得访问 ITCM/DTCM；Control CSR 仍可访问。

示例 Address Editor 配置（应确认不与其他外设冲突）：

```text
CTRL_BASE = 0xA0000000, range = 64 KiB
MEM_BASE  = 0xA1000000, range = 1 MiB
ITCM_BASE = MEM_BASE
DTCM_BASE = MEM_BASE + 0x80000
```

NPU 指令中的 DTCM 地址仍然是零基偏移，不能传入 PS 的 MMIO 地址。
DMA Master 的 DDR 地址原样输出，绝不使用上述 Host aperture 转换。

## 5. ZCU102 Block Design

```text
PS M_AXI_HPM0_FPD → HOST SmartConnect（1 SI / 2 MI）
                       ├─→ S_AXI_CTRL
                       └─→ S_AXI_MEM

M_AXI_DDR → DMA SmartConnect（1 SI / 1 MI）→ PS S_AXI_HP0_FPD → PS DDR
```

SmartConnect/Interconnect 负责总线宽度、协议及 ID 适配。
不需要再加 AXI DMA、AXI BRAM Controller 或 MIG：DMA/TCM 在 NPU 内，DDR 控制器位于 PS。

- PS `pl_clk0` 同时连接 NPU `ACLK`、两组互连、PS HPM/HP 的 AXI clock 输入及
  Processor System Reset 的 `slowest_sync_clk`。
- 若 PS `pl_resetn0` 直接连接 `ext_reset_in`，配置该输入为低有效。
- 直接使用 PS clock 时令 `dcm_locked=1`；使用 Clock Wizard 时接其 `locked`。
- `peripheral_aresetn` 连接 NPU `ARESETN`，互连及其接口复位也应正确连接。
- 不得在尚有 DMA 响应时只复位 NPU，必须协调互连/DDR 路径恢复。
- 在 NPU Master 的地址空间映射 `HP0_DDR_LOW`，DMA buffer 应位于该端口可达的低 DDR，
  例如低于 2 GiB；32-bit 地址不代表整个 0…4 GiB 都是 DDR。
- 当前 DMA `AxCACHE=0`，不保证 CPU cache coherence。裸机执行 clean/invalidate；Linux
  使用 DMA API 与正确的 DMA 地址范围，不能将用户虚拟地址直接传给 NPU。

## 6. 检查与冒烟测试

1. `validate_bd_design`，检查时钟关联、地址范围和接口宽度转换。
2. 读取 CTRL + `0x30` → 1，`0x18` → 0x80000，`0x1C` → 0x4000。
3. 空闲时验证 ITCM/DTCM 写读，再加载最小标量程序；START 前等待写响应完成。
4. STATUS bit0=BUSY、bit1=DONE、bit2=FAULT；CONTROL bit0=START、bit1=ACK。
5. 验证 MVIN/MVOUT、错误状态、ACK/restart，最后运行矩阵程序。
6. 综合后检查 RAM 推断与资源利用；布局布线后检查 setup/hold 和未约束路径。

RAM 源由 Chisel 生成，Vivado 是否正确推断 BRAM 必须查看综合报告，不能仅凭接口校验通过
就认为可以上板。BF16 PE 已采用两级乘加流水，但 FP32 累加和其他通路仍需实际时序验证。
