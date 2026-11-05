# XiangJiang 独立手写 SystemVerilog NPU

本目录包含可独立编译、仿真和实例化的完整默认配置 NPU，顶层为
`top/xiangjiang_npu.sv` 中的 `xiangjiang_npu`。综合文件列表只包含手写 RTL，
没有生成 Chisel RTL、黑盒、空壳模块或外部算术 IP 依赖。

实现依据当前 `src/main/scala` 的 Chisel 设计，而不是文档中已过时的
“仅实现 33 条 Scalar 指令”说明。实现 Scalar、Vector、Matrix、Core DMA
全部执行域。源码使用 SystemVerilog `.sv`，需要支持 package、packed struct、
数组端口、enum、`always_ff` 和 `always_comb`；不是 Verilog-2001。

**完整 RTL 集成不等于流片签核或形式等价证明。** 默认配置已通过模块和整机仿真对照；
尚未进行工艺库综合、物理实现、STA、CDC/RDC、DFT 或形式签核。

## 独立使用

可以将整个 `hdl/` 复制到新工程，无需复制 Scala、Mill 或 `generated/`。
从复制目录运行：

```bash
verilator --lint-only -Wall --top-module xiangjiang_npu -f standalone.f
verilator --lint-only -Wall -DSYNTHESIS --top-module xiangjiang_npu -f standalone.f
python check_standalone.py
```

`check_standalone.py` 将仅有 `standalone.f` 所列 RTL 的副本复制到临时目录，执行
两种 lint，再使用独立 AXI Host/外部内存模型装载并运行程序。它不读取生成 RTL，
不依赖 Chisel、Scala、Java 或本仓库其他目录。需要 Python 3、Verilator、C++
工具链和 make；已验证工具版本为 Verilator 5.052。`--lint-only` 可跳过仿真编译。

从原仓库根目录使用 `hdl/filelist.f`；从 `hdl/` 内使用 `standalone.f`。两份列表
顺序一致，package 在使用它的模块之前。测试代码不属于综合列表。

## 默认配置与接口

|项目|配置|
|---|---|
|时钟与复位|单一 `clk`；同步高有效 `reset`|
|顶层|`xiangjiang_npu`|
|Scalar/Vector ARF|32×32 bit / 32×256 bit|
|ROB / Scalar result / Vector result|8 / 6 / 4 entries|
|Issue / MemQ|每站 4 entries / 4 entries|
|身份与年龄|tag = 3-bit slot + 4-bit generation；16-bit seq|
|Matrix|1×BF16 8×8 OS + 2×signed INT8 8×8 OS/WS，共 192 PE|
|ITCM|256 KiB，65536×32-bit 同步单端口|
|DTCM|1 MiB，4×4096×512-bit 同步 1R1W bank|
|AXI|32-bit 地址/数据，4-bit ID；Control 为 AXI4-Lite|

顶层协议：
- `control_*`：AXI4-Lite Slave，独立 AW/W、B 和 AR/R 握手。`*_prot` 被忽略，与 Chisel 一致。
- `memory_*`：Host AXI4 Slave，用于装载和回读 ITCM/DTCM。
- `external_*`：Core DMA 的 AXI4 Manager；必须连接真实外部内存或合规模型。
- `status`：BUSY/DONE/FAULT、故障记录及 DMA 状态。
- `retire_valid/retire`：已提交指令记录；`debug_regs/debug_v_regs`：已提交寄存器。
- AXI channel payload 使用 `pkg/xj_pkg.sv` 的 packed struct；ready/valid 分开。
  外部工程可通过 `.memory_aw(aw_payload)` 等命名连接，并按 struct 字段接标准 AXI 信号。

Host 地址窗：ITCM `[0,0x40000)`，DTCM `[0x10000000,0x10100000)`。
Core 内部 DTCM 地址从零开始。Host 的基本使用流程：
1. 复位后通过 `memory_*` 装载程序和数据，并等待写响应。
2. 向 Control `0x00` 写 `1` 启动，程序从 PC=0 开始。
3. 等待 `status.done` 或 `status.fault`，此时普通运行已排空。
4. 空闲时读取 SRAM、寄存器或故障 CSR；向 Control `0x00` 写 `2` ACK。
5. ACK/再次 START 保留已提交 X/V、Matrix、DMA 配置和 SRAM。

`BUS_POISONED` 保持 BUSY 并拒绝新命令；ACK 不能解除，必须平台复位。
START 使用与 Chisel 一致的一拍 `start_pending`，该拍已占用 BUSY/Host SRAM 权限。
完整机器编码和 MMIO 布局见原仓库 `docs/xj-isa.md`、`docs/xj-npu.md`；
复制到其他工程时应同时保留这两份软件接口文档。

当前顶层验证范围是 **默认 XJParams**，不是所有 Chisel 参数组合。
部分底层模块有局部参数，但不能仅修改 tag 常量或 SRAM 深度就宣称整个 NPU 已适配。

## 架构对应

|Chisel 来源|手写实现|
|---|---|
|Parameters/Bundle/AXI|`pkg/xj_pkg.sv`|
|Frontend|`frontend/frontend.sv`|
|DecodeUnit/RenameStage/Rob|`backend/decode`、`backend/rename`、`backend/rob`|
|IssueUnit|`backend/issue/issue_unit.sv`；9 个站，全部禁用同周期 wakeup 发射|
|ALUUnit/BranchUnit|`backend/fu/alu_unit.sv`、`branch_unit.sv`、`scalar_completion.sv`|
|VectorUnit|`backend/fu/vector_unit.sv`；按 G_GROUP 实例化 5 组，折叠 8 lane|
|VectorFloat|`fp_parts`、`fp_round`、`fp_sum_pipeline`、`fp_arithmetic`、`fp_compare`、`fp_convert`|
|BFloatDivider|`backend/fu/bfloat_divider.sv`；逐位恢复除法，无组合除法器|
|SystolicPE/MatrixArray|`systolic_pe_bf16/int8`、`matrix_array_bf16/int8`|
|MatrixUnit|`backend/fu/matrix_unit.sv`；owner 匹配后发布 staging|
|LSU|`mem/lsu.sv`；独立最老就绪 AGU、统一顺序 MemQ|
|DmaEngine|`mem/dma_engine.sv`；二维地址、完整 burst 缓冲、错误排空|
|MemorySubsystem/TCM|`device/memory_subsystem.sv`、`mem/instruction_tcm.sv`、`mem/data_tcm.sv`|
|ScalarCore|`scalar_core.sv`；派发/提交/异常、11 路完成仲裁及写回寄存级|
|XiangJiangNPU|`top/xiangjiang_npu.sv`；Control/Memory/Core 独立完整连接|

保留的关键微架构：
- 顺序单派发、独立 FU 乱序执行、ROB 顺序提交；先捕获源版本再建立目的 RAT 映射。
- 11 路完成响应通过平衡年龄比较树仲裁，同龄选择低序号源，随后进入写回寄存级。
- generation/seq 耗尽时先停分配、排空，再复用；不静默回绕。
- MemQ 同时至多一个活动操作；Store、Matrix、DMA 在 ROB Head 授权。
- DMA 与源码一致：一次处理一个至多 256 B 的 burst，不跨 4 KiB；不是增加并行度的改版。
- FP add/mul 保留四个内部寄存边界；FMA 保留六个内部寄存边界，乘积不提前舍入。
- BF16 阵列每 8 拍注入一个 k 项，INT8 使用单拍邻接链；OS/WS 共用每个 PE 的乘加路径。
- 没有矩阵点积/归约树替代路径；Vector 归约按 lane 0→7 顺序计算。
- 只移植当前 NPU 实例化的 VectorFloat 功能；未实例化且不属于 ISA 的 exp/rsqrt 等辅助函数不作为硬件加入。

## 状态机与 SRAM

遵循 `sv_skillz` 的 SystemVerilog 规范；来源为
`C:\Users\free\Workbench\sv_skillz`（规则许可证 CC BY 4.0）。
有编码状态的控制器均分开实现：状态寄存 `always_ff`、下一状态 `always_comb`、
输出/数据通路控制 `always_comb`，负载寄存更新另置 `always_ff`。
RS/RAT/ROB 的独立 valid 位、流水有效位不是另行编码的全局 FSM。

保留 Chisel 的同步高有效复位，不改为异步复位。架构寄存器/Matrix 状态按源码复位，
SRAM 不清零；读未初始化 SRAM 的数据没有保证。
ITCM 无效或写周期的读输出允许与生成 RTL 的 don't-care 不同，只比较有效读。
DTCM 同行读写由仲裁禁止，并在仿真断言检查；底层需要按目标工艺替换为 SRAM 宏。

Strict lint 不全局关闭 warning。局部 UNUSED 豁免仅用于共享 bundle 未消费字段、
特化后不用的端口和仅用于断言的观察信号，旁边注明原因。

## 仿真验证

在仓库根目录、已配置 Verilator 的环境下：

```bash
./mill xj.runMain top.Generate generated/hdl-reference
python hdl/tests/check_npu.py
python hdl/tests/regression.py
python hdl/check_standalone.py
```

`check_npu.py` 比较手写顶层与完整 Chisel 顶层，输入同一 AXI Host/外部内存激励，
逐周期比较全部生成顶层暴露的控制输出、有效 payload、退休记录、X/V ARF 和状态。
模块测试通过端口适配器接参考 RTL；适配器只生成连接与比较断言，不生成 DUT 逻辑。
`*_tb.sv` 中含 `REFERENCE/CHECKS` 标记的是测试模板，应通过对应 Python 脚本运行。

已执行的主要结果：

|验证|结果|
|---|---|
|顶层严格 lint|普通模式和 `-DSYNTHESIS` 均通过|
|完整 NPU 差分|15,759 cycles，60 个程序，567 retirements；DMA AR/AW 各 5 次|
|独立复制目录|无 src/generated 依赖，完成同一批 60 个程序的功能检查|
|Vector 5 组差分|各 120,000 cycles，含流水浮点、转换、除法、掩码、置换/归约|
|MatrixUnit 差分|20,048 cycles，1,092 tasks，覆盖 BF16/INT8 OS/WS、提交/冲刷/故障|
|DMA 差分|54,175 cycles，190 commands，含预检查、跨页拆分、反压和错误|
|LSU 差分|65,000 cycles，9,932 allocations，6,168 results，1,248 faults|
|MemorySubsystem 差分|35,770 cycles，AXI B/R 及 Scalar/Matrix 返回均数千次|
|Rename/ROB 差分|30,000 / 40,000 cycles|
|Decode|100,000 随机指令 + 131,072 编码组合|
|ALU/Branch/Frontend/Issue/Control|保留原有模块回归|

整机程序包含循环、分支源/目的重叠、代际耗尽、精确异常和重启，全部 36 类 Vector
寄存器运算编码及 VLD/VST，三套矩阵阵列的装载/计算/存储，二维 DMA、外部错误和 poison。
这不是穷举覆盖率报告。无效负载不要求对照；生成实例优化掉的字段不能凭此测试证明。
例如 Rename 差分只对照译码器实际使用的 `mask_reg=0`；MemorySubsystem 差分使用
Core 的全字/全 beat mask，底层 byte mask 另有 SRAM 测试。

`reference_manifest.json` 保存本次源码、文档与生成参考 RTL 的 SHA256；
`python hdl/tests/update_reference_manifest.py --check` 检查参考是否过期。

## 后续流片签核

仍须完成：逐模块/顶层形式等价，X/非法状态验证，工艺库综合与真实 SDC，
多角 STA、CDC/RDC，SRAM 宏/MBIST，Scan/DFT、时钟与复位树，P&R、IR/EM、DRC/LVS，
以及门级/SDF 回归。当前不提供工艺相关宏、SDC 或 DFT 实现，也不宣称时序收敛。
