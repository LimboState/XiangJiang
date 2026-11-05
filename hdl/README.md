# XiangJiang 手写 SystemVerilog：迁移进行中

**当前不是完整 NPU，不能替换顶层，不能宣称已完成流片签核。**
本目录不以生成 RTL 改名、黑盒或空壳模块代替尚未迁移的实现。

## 基准与规范

- 参考源码：`src/main/scala`；ISA：`docs/xj-isa.md`。
- 已阅读微架构 `docs/xj-npu.md`；文档中的旧实现说明与当前源码不完全一致。
- 从当前 Chisel 生成的完整 RTL：`generated/hdl-reference/`。
- 生成命令：`./mill xj.runMain top.Generate generated/hdl-reference`。
- `reference_manifest.json` 保存源码/生成 RTL SHA256 和 Git HEAD；生成目录未纳入
  手写 synthesis filelist。修改 Chisel 后需重新生成并重新运行对照测试。
- 遵循 `/home/free/Workbench/sv_skillz/sv_skillz.md` 和 `sv_preferences.md`。
  规范作者仓库为 sv_skillz，规则许可证为 CC BY 4.0；本目录注明使用规范来源。
- 使用 SystemVerilog `.sv`，不是纯 Verilog-2001；需支持 package、packed struct、
  enum、always_ff、always_comb 的工具。

## 当前交付范围

| Chisel 来源 | 手写文件 | 状态 |
|---|---|---|
| Parameters/Bundle | pkg/xj_pkg.sv | 默认配置所需类型与内部操作码；尚非完整 Bundle 集合 |
| DecodeUnit | backend/decode/decode_unit.sv | 已写，全部生成输出字段差分测试通过 |
| ScalarFunctionalUnit | backend/fu/scalar_completion.sv | 三段式 EMPTY/FULL FSM |
| ALUUnit | backend/fu/alu_unit.sv | 已写，逐周期差分测试通过 |
| BranchUnit | backend/fu/branch_unit.sv | 已写，逐周期差分测试通过 |
| Frontend | frontend/frontend.sv | 三段式生命周期 FSM；逐周期差分测试通过 |
| IssueUnit | backend/issue/issue_unit.sv | 已写，三种默认实例差分测试通过 |
| ControlRegisters | device/control_registers.sv | 读/写独立三段式 FSM；差分测试通过 |
| InstructionTcm | mem/instruction_tcm.sv | 可综合推断模板；定向测试通过，尚未宏映射 |
| DataTcm | mem/data_tcm.sv | 四 bank 推断模板；定向测试通过，尚未宏映射 |
| RenameStage/Rob | 尚未迁移 | 不在 filelist 中，无占位实现 |
| VectorFloat/Divider/VectorUnit | 尚未迁移 | 保持 Chisel 参考，不在手写 filelist 中 |
| SystolicPE/MatrixArray/MatrixUnit | 尚未迁移 | 后续保持真实 systolic、OS/WS 共享及舍入语义 |
| LSU/DmaEngine/MemorySubsystem | 尚未迁移 | 后续三段式迁移，须保留所有握手/排空优先级 |
| ScalarCore/XiangJiangNPU | 尚未迁移 | 尚无手写顶层集成/全芯片等价测试 |

`filelist.f` 只列出已经存在的手写 RTL。从工程根目录使用。
当前 package 固定默认 ROB tag=3+4 bit、sequence=16 bit、vector=256 bit。
并非支持全部 XJParams 参数组合的替代实现。

## FSM 与复位约定

所有已引入 FSM 都有三个独立部分：
1. always_ff 状态寄存器；
2. always_comb 下一状态逻辑；
3. always_comb 输出/数据通路控制逻辑。
负载寄存器更新另用 always_ff，不把算术和状态转移混写。

保留项目原有 **同步高有效 reset**，不擅自使用规范默认的异步低有效复位，
避免与参考行为不一致。ASIC 顶层复位同步器及测试复位策略仍须集成。
无状态组合模块、SRAM、发射站独立 valid 位不是编码 FSM，不人为引入多余状态。
存储阵列不复位；无有效数据时未初始化负载不可使用。
Frontend 采用生成顶层的固定 ready=1 请求接口，不能直接替代可反压的通用取指口。
ITCM 无效/写周期输出保持，参考输出在这些周期是 don't-care；只保证有效读周期。
DTCM 同行同时读写禁止，并用 synthesis 外断言检测；最终行为需与 SRAM 宏契约一致。

## 验证

进入 XJ conda 环境执行：

```bash
python hdl/tests/run_checks.py
python hdl/tests/check_decode.py
python hdl/tests/check_control.py
verilator --binary --timing --assert --top-module memory_tb \
  --Mdir build/hdl_checks/memory -f hdl/filelist.f hdl/tests/memory_tb.sv
build/hdl_checks/memory/Vmemory_tb
```

已实际执行：
- 手写模块逐顶层 `verilator --lint-only -Wall`；IssueUnit 另检查深度 1、vector、
  同周期 wakeup 变体。没有全局关闭 warning。
- ALU / Branch 各 12,000 周期随机差分（含全部内部 op 值、reset、flush、反压）。
- Frontend 20,000 周期随机差分（含重定向、重启、延迟响应、barrier）。
- IssueUnit / IssueUnit_1 / IssueUnit_2 各 20,000 周期逐输出字段差分。
- Decode：100,000 随机指令 + 131,072 opcode/funct3/funct7 组合；对照全部暴露字段。
- ControlRegisters 20,000 周期差分，包含独立 AW/W、响应反压、权限变化及 CSR 读取。
- 小容量 SRAM 的 byte mask、零 mask、四 bank 定向测试。

这些是仿真，不是形式等价证明。参考优化掉的无用字段、无效负载不强制比较；
测试断言对有效输出作比较。两个局部 UNUSED 警告豁免用于协议定义的不使用字段，
有对应注释。测试源码不进入 synthesis filelist。

## 流片前必须完成

1. 完成其余模块迁移及完整顶层连接，跑现有端到端程序回归。
2. 逐模块及顶层形式等价（含 reset、X、非法状态、反压、flush）。
3. 工艺库综合，真实 SDC 下多角 STA、CDC/RDC、无 latch/多驱动检查。
4. SRAM 宏替换、读写碰撞语义、MBIST/修复策略。
5. Scan/DFT、复位树/时钟树/ICG、低功耗及 UPF（若需要）。
6. P&R、功耗/IR/EM、DRC/LVS、门级/SDF 和最终签核。

未经上述步骤，不应使用“可直接流片”或“全设计已等价”的描述。
