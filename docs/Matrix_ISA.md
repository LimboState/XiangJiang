# XiangJiang Matrix ISA

本文定义固定 `16×16` 矩阵单元的指令。Scalar ISA、Vector ISA 以及统一的 ROB/MemQ 提交规则保持不变。

## 1. 硬件与数据约定

| 阵列 | 数据类型 | 数据流 | 累加与输出 |
| --- | --- | --- | --- |
| `u0` | BF16 | 固定 OS | BF16×BF16，FP32 累加；结果 RNE 转为 BF16 |
| `u1` | INT8 | OS/WS | 有符号 INT8×INT8，乘积扩展为 INT32；输出 INT32 |
| `u2` | INT8 | OS/WS | 有符号 INT8×INT8，乘积扩展为 INT32；输出 INT32 |

- 每条矩阵计算指令处理一个完整的 `16×16` tile；没有可编程 M/N/K，也没有矩阵寄存器堆。
- 每个 unit 有 A/B 输入 skew buffer 和内部累加器。A、B 从 DTCM 通过 `MLOAD` 装入，结果通过 `MSTORE` 写回 DTCM。
- tile 为行主序、紧密排列，不携带 stride。输入/输出地址由标量 `x` 寄存器提供。
- 矩阵任务按序进入 MemQ；只有 MemQ 队首且 ROB 队首授权后，load、compute 或 store 才能启动。当前策略最多一个 MemQ 项 active。

## 2. 指令一览

| 指令 | 功能 |
| --- | --- |
| `MSETMODE` | 设置 `u1/u2` 的 OS 或 WS 数据流模式 |
| `MLOAD` | 从 DTCM 将 A、B 或累加器 tile 装入矩阵单元 |
| `MMUL` | 使用已装入的 A/B：`ACC = A × B` |
| `MMACC` | 使用已装入的 A/B：`ACC = ACC + A × B` |
| `MEW` | 使用已装入的 A/B：`ACC = A ⊙ B` |
| `MTRANS` | 使用已装入的 A：`ACC = Aᵀ` |
| `MSTORE` | 将内部累加器/结果 tile 写回 DTCM |
| `MWAIT` | 等待指定阵列的当前任务完成 |
| `MBARRIER` | 等待此前矩阵/DMA 工作完成的局部屏障 |
| `MFENCE` | 矩阵显式屏障；与 Scalar `FENCE` 的矩阵覆盖语义等价 |

推荐顺序如下：

```asm
MSETMODE u1, WS
MLOAD    u1, A,   x10
MLOAD    u1, B,   x11
MMUL     u1
MWAIT    u1
MSTORE   u1, x12
MFENCE
```

## 3. RISC-V opcode 分配

Scalar ISA 已使用完整机器字 `0x0000000B` 作为 `HALT`，矩阵不使用 `custom-0`：

| 空间 | opcode | 用途 |
| --- | --- | --- |
| `custom-0` | `0x0B` | Scalar `HALT`，已占用 |
| `custom-1` | `0x2B` | `MSETMODE`、`MLOAD`、`MSTORE` |
| `custom-2` | `0x5B` | `MMUL`、`MMACC`、`MEW`、`MTRANS` |
| `custom-3` | `0x7B` | `MWAIT`、`MBARRIER`、`MFENCE` |

参考：[RISC-V opcode map](https://docs.riscv.org/reference/isa/unpriv/rv-32-64g.html)。自定义 opcode 不表示与标准 RISC-V 扩展兼容；未列出的编码必须作为非法指令处理。

### 3.1 数据移动与模式格式（`custom-1 = 0x2B`）

使用 R-type 字段：

```text
31        25 24    20 19    15 14  12 11        7 6       0
┌───────────┬────────┬────────┬──────┬───────────┬─────────┐
│  funct7   │  rs2   │  rs1   │unit  │    rd     │ 0x2B    │
└───────────┴────────┴────────┴──────┴───────────┴─────────┘
```

`unit=000/001/010` 分别选择 `u0/u1/u2`，`unit=011` 保留。

| 指令 | `funct7` | `funct3` | 寄存器字段 |
| --- | --- | --- | --- |
| `MSETMODE unit, mode` | `0000000` | `unit` | `rs2[0]=mode`，其余 `rs2` 位、`rs1`、`rd` 必须为 0 |
| `MLOAD unit, buffer, rsA` | `00001bb` | `unit` | `rs1=rsA`，`rs2=0`，`rd=0` |
| `MSTORE unit, rsC` | `0001000` | `unit` | `rs1=rsC`，`rs2=0`，`rd=0` |

`MLOAD` 的 `bb` 低两位选择 buffer：`00=A`、`01=B`、`10=ACC`，`11` 保留；因此对应 `funct7=0x04/0x05/0x06`。`MSTORE` 写出当前内部累加器/结果，不写标量寄存器。

`MSETMODE` 是控制屏障：只有更早任务完成并提交后，新模式才生效；活动任务期间不能切换模式。`u0` 只允许 `OS`，`u1/u2` 允许 `OS` 和 `WS`。复位后的模式为 `OS`。

### 3.2 计算格式（`custom-2 = 0x5B`）

计算指令也使用 R-type 字段；`rd` 字段在本扩展中表示 `rsC`，但计算指令不写标量寄存器：

```text
31        25 24    20 19    15 14  12 11        7 6       0
┌───────────┬────────┬────────┬──────┬───────────┬─────────┐
│  funct7   │  rsB   │  rsA   │unit  │  reserved │ 0x5B    │
└───────────┴────────┴────────┴──────┴───────────┴─────────┘
```

计算指令不需要地址寄存器：地址已由 `MLOAD/MSTORE` 提供，`rs1/rs2/rd` 必须为 0。`funct3[1:0]` 为 unit，`funct3[2]` 必须为 0。

| 指令 | `funct7` | 要求 |
| --- | --- | --- |
| `MMUL unit` | `0000000` | A、B 已装入；清零内部累加器后计算 `ACC=A×B` |
| `MMACC unit` | `0000001` | A、B 已装入；计算 `ACC=ACC+A×B` |
| `MEW unit` | `0000010` | A、B 已装入；计算 `ACC=A⊙B` |
| `MTRANS unit` | `0000011` | A 已装入，B 必须未使用；计算 `ACC=Aᵀ` |

`MLOAD.ACC` 可在 `MMACC` 前从 DTCM 读取已有 C tile 作为初始累加器。u0 的 ACC 以 FP32 保存，写回 u0 结果时 RNE 转 BF16；u1/u2 的 ACC 为 INT32。`MTRANS` 保持输入元素类型，`MEW` 按对应 unit 的结果格式写回。

### 3.3 同步格式（`custom-3 = 0x7B`）

同步指令的保留字段必须为零：

| 指令 | 固定字段 | 其他字段 |
| --- | --- | --- |
| `MWAIT unit` | `funct3=000`，`inst[31:30]=unit` | `inst[29:0]=0` |
| `MFENCE` | `funct3=001` | 除 opcode/funct3 外全部为 0 |
| `MBARRIER id` | `funct3=010`，`inst[31:27]=id` | `inst[26:0]=0` |

`unit=0/1/2` 合法，`unit=3` 非法；`id` 为 5-bit 软件标签。当前单 active MemQ 策略下，`MBARRIER` 等待所有更早矩阵/DMA 工作完成；不同 `id` 只保留软件同步命名空间，不增加硬件并发。

## 4. 数据移动与执行规则

1. `MLOAD.A/B` 将一个完整 `16×16` tile 写入对应 unit 的 A/B skew buffer；`MLOAD.ACC` 将 DTCM 中的 C tile 转换为内部累加格式。
2. `MLOAD`、计算和 `MSTORE` 均按序分配 ROB；有 DTCM 访问的 `MLOAD/MSTORE` 预留 MemQ 项。
3. 计算只能使用已完成的 A/B load 和当前 mode；不能在执行阶段重新读取标量寄存器或 DTCM 地址。
4. `MSTORE` 必须等待对应计算完成，并将整个结果 tile 写入 DTCM。已写入的字节在后续故障中不回滚。
5. 地址必须满足实现规定的 tile 对齐，完整 tile 必须落在 DTCM 范围内；检查必须先于 SRAM 索引截断。DTCM bank 冲突按既有内存互连规则处理。
6. 未对齐、越界、非法 unit/mode、错误 buffer 或非法 opcode 都产生矩阵故障；矩阵专用故障码尚未在 `sketchNPU.md` 中定义，不能冒用 Scalar ISA §8 的加载/存储故障码。
7. Scalar `FENCE` 已覆盖标量、向量、矩阵和 DMA；`MFENCE` 是矩阵程序的显式同义屏障，不是 ALU 空操作。

## 5. 示例

```asm
# x10/x11/x12 分别保存 A/B/C 的 DTCM tile 地址
MSETMODE u0, OS
MLOAD    u0, A, x10
MLOAD    u0, B, x11
MMUL     u0
MWAIT    u0
MSTORE   u0, x12

# INT8 weight-stationary
MSETMODE u1, WS
MLOAD    u1, A, x10
MLOAD    u1, B, x11
MMUL     u1
MWAIT    u1
MSTORE   u1, x12
MFENCE
```

## 6. 参考

- [Scalar_ISA.md](Scalar_ISA.md)：`custom-0=0x0B` 的 `HALT`、x 寄存器、ROB/MemQ 和 `FENCE` 约定。
- [Vector_ISA.md](Vector_ISA.md)：共享 `x` 寄存器、BF16 表示和向量与矩阵 BF16 精度边界。
- [sketchNPU.md](sketchNPU.md)：矩阵单元、A/B skew buffer、DTCM 和任务提交规则。
- [RISC-V opcode map](https://docs.riscv.org/reference/isa/unpriv/rv-32-64g.html)：`custom-0…custom-3` major opcode 保留规则。
- [Gemmini ISA](https://github.com/ucb-bar/gemmini)：公开的 RISC-V custom 矩阵加速器设计参考；本 ISA 不复用其指令语义。
