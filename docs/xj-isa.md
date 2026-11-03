# XJ-ISA 指令集规范

|项目|定义|
|---|---|
|ISA Profile|XJ-NPU-1.0|
|指令长度|固定 32 bit，`IALIGN=32`|
|标量宽度|XLEN=32|
|字节序|小端|
|目标指令数|85|
|当前实现|仅实现 33 条 Scalar 指令，见 [xj-npu.md](xj-npu.md) 第 13 节|

本文只定义软件可见的机器编码、架构状态、操作数、运算结果、存储布局和故障语义。流水线、ROB、RAT、FU、MemQ、TCM、AXI 和实现状态由 [xj-npu.md](xj-npu.md) 定义。

XJ-ISA 借用部分 RISC-V RV32I/RVV 命名和编码风格，但不是完整 RV32I、RV32IMF 或 RVV 实现，也不声明标准兼容性。未在本文列出的编码均为非法指令。

## 1. ISA 组成与架构状态

### 1.1. 指令组成

|执行域|数量|组成|
|---|---:|---|
|Scalar|33|21 条整数计算、8 条分支/跳转、`LW`、`SW`、`FENCE`、`HALT`|
|Vector|38|36 条寄存器计算、`VLD`、`VST`|
|Matrix|10|配置、tile 搬运、计算和同步|
|Core DMA|4|二维尺寸/步长配置、`MVIN`、`MVOUT`|
|合计|85|—|

### 1.2. 架构状态

|状态|定义|
|---|---|
|PC|32 bit；指令地址必须 4 B 对齐|
|Scalar Register File|`x0–x31`，每个 32 bit；`x0` 恒为零|
|Vector Register File|`v0–v31`，每个 256 bit，固定为 8 个 32-bit lane|
|Matrix Unit|`u0/u1/u2` 各自具有已提交 A、B、ACC、有效位和 mode|
|Core DMA 配置|`row_bytes`、`rows`、`external_stride`、`local_stride` 及有效位|
|浮点控制|固定 RNE；无 `frm/fcsr`，无浮点异常标志|

普通运行从 `PC=0` 开始。`HALT` 正常结束当前运行；故障通过 Host 状态报告，不进入指令级 Trap。ACK 和后续 START 保留已提交寄存器、Matrix 状态、DMA 配置和 SRAM；复位重新初始化架构寄存器及控制状态，但不保证清除 ITCM/DTCM。

### 1.3. 记号

- `u32(v)`：保留 `v` 的低 32 bit。
- `s32(v)`：将 32-bit 位模式解释为二进制补码有符号整数。
- `sext32(v)`：符号扩展到 32 bit。
- `{a,b}`：位拼接。
- 所有地址和步长均以 byte 为单位。

## 2. 通用机器编码

### 2.1. RV32 基本格式

|格式|`inst[31:25]`|`inst[24:20]`|`inst[19:15]`|`inst[14:12]`|`inst[11:7]`|`inst[6:0]`|
|---|---|---|---|---|---|---|
|R|`funct7`|`rs2`|`rs1`|`funct3`|`rd`|`opcode`|
|I|`imm[11:5]`|`imm[4:0]`|`rs1`|`funct3`|`rd`|`opcode`|
|S|`imm[11:5]`|`rs2`|`rs1`|`funct3`|`imm[4:0]`|`opcode`|
|B|`{imm[12],imm[10:5]}`|`rs2`|`rs1`|`funct3`|`{imm[4:1],imm[11]}`|`opcode`|
|U|`imm[31:25]`|`imm[24:20]`|`imm[19:15]`|`imm[14:12]`|`rd`|`opcode`|
|J|`{imm[20],imm[10:5]}`|`{imm[4:1],imm[11]}`|`imm[19:15]`|`imm[14:12]`|`rd`|`opcode`|

|立即数|执行值|
|---|---|
|I|`sext32(inst[31:20])`|
|S|`sext32({inst[31:25],inst[11:7]})`|
|B|`sext32({inst[31],inst[7],inst[30:25],inst[11:8],0})`|
|U|`{inst[31:12],12'b0}`|
|J|`sext32({inst[31],inst[19:12],inst[20],inst[30:21],0})`|

B/J 偏移的隐式低位零只保证 2 B 粒度，不保证目标满足 4 B 对齐。

### 2.2. Opcode 空间

|空间|Opcode|用途|
|---|---:|---|
|RV32 OP/OP-IMM 等|标准主 opcode|Scalar RV32I 子集|
|XJ-V|`0x57`|36 条 Vector 寄存器计算指令|
|`custom-0`|`0x0B`|`HALT`、`VLD/VST`、Core DMA|
|`custom-1`|`0x2B`|`MSETMODE/MLOAD/MSTORE`|
|`custom-2`|`0x5B`|Matrix compute|
|`custom-3`|`0x7B`|Matrix 同步与屏障|

`custom-0` 的分配如下：

|`funct3`|定义|
|---:|---|
|`000`|仅完整机器字 `0x0000000B` 为 `HALT`|
|`001`|`VLD`|
|`010`|`VST`|
|`011`|`DSETSIZE`|
|`100`|`DSETSTRIDE`|
|`101`|`MVIN`|
|`110`|`MVOUT`|
|`111`|保留，非法|

## 3. Scalar ISA（33 条）

### 3.1. 寄存器整数运算（10 条）

全部使用 `opcode=0x33`。

|指令|`funct3`|`funct7`|结果|
|---|---:|---:|---|
|`ADD rd,rs1,rs2`|`000`|`0000000`|`u32(x[rs1]+x[rs2])`|
|`SUB rd,rs1,rs2`|`000`|`0100000`|`u32(x[rs1]-x[rs2])`|
|`AND rd,rs1,rs2`|`111`|`0000000`|按位与|
|`OR rd,rs1,rs2`|`110`|`0000000`|按位或|
|`XOR rd,rs1,rs2`|`100`|`0000000`|按位异或|
|`SLL rd,rs1,rs2`|`001`|`0000000`|`u32(x[rs1] << x[rs2][4:0])`|
|`SRL rd,rs1,rs2`|`101`|`0000000`|逻辑右移 `x[rs2][4:0]`|
|`SRA rd,rs1,rs2`|`101`|`0100000`|算术右移 `x[rs2][4:0]`|
|`SLT rd,rs1,rs2`|`010`|`0000000`|`s32(x[rs1]) < s32(x[rs2]) ? 1 : 0`|
|`SLTU rd,rs1,rs2`|`011`|`0000000`|无符号小于则 1，否则 0|

其他 `funct7` 均非法，不实现 M 扩展编码。

### 3.2. 立即数和 PC 相对运算（11 条）

I 型整数操作使用 `opcode=0x13`；普通立即数先符号扩展。

|指令|`funct3`|上部限制|结果|
|---|---:|---|---|
|`ADDI rd,rs1,imm12`|`000`|—|`u32(x[rs1]+sext32(imm12))`|
|`ANDI rd,rs1,imm12`|`111`|—|按位与|
|`ORI rd,rs1,imm12`|`110`|—|按位或|
|`XORI rd,rs1,imm12`|`100`|—|按位异或|
|`SLLI rd,rs1,shamt`|`001`|`inst[31:25]=0000000`|左移 `shamt`|
|`SRLI rd,rs1,shamt`|`101`|`inst[31:25]=0000000`|逻辑右移 `shamt`|
|`SRAI rd,rs1,shamt`|`101`|`inst[31:25]=0100000`|算术右移 `shamt`|
|`SLTI rd,rs1,imm12`|`010`|—|有符号小于则 1|
|`SLTIU rd,rs1,imm12`|`011`|—|立即数符号扩展后按无符号比较|
|`LUI rd,imm20`|U，`opcode=0x37`|—|`{imm20,12'b0}`|
|`AUIPC rd,imm20`|U，`opcode=0x17`|—|`u32(PC+{imm20,12'b0})`|

### 3.3. Scalar 访存（2 条）

|指令|编码|语义|
|---|---|---|
|`LW rd,imm12(rs1)`|I，`opcode=0x03, funct3=010`|从 `EA` 加载小端 32-bit 字|
|`SW rs2,imm12(rs1)`|S，`opcode=0x23, funct3=010`|向 `EA` 存储已捕获的 `x[rs2]`|

```text
EA = u32(x[rs1] + sext32(imm12))
```

EA 必须 4 B 对齐，完整 4 B 必须位于零基 DTCM `[0x00000000,0x00100000)`。同时未对齐和越界时优先报告未对齐。`LW x0` 仍须执行访问和故障检查，只丢弃寄存器结果。

### 3.4. 分支与跳转（8 条）

条件分支使用 B 型 `opcode=0x63`。

|指令|`funct3`|条件|
|---|---:|---|
|`BEQ rs1,rs2,offset`|`000`|位模式相等|
|`BNE rs1,rs2,offset`|`001`|位模式不等|
|`BLT rs1,rs2,offset`|`100`|有符号小于|
|`BGE rs1,rs2,offset`|`101`|有符号大于等于|
|`BLTU rs1,rs2,offset`|`110`|无符号小于|
|`BGEU rs1,rs2,offset`|`111`|无符号大于等于|

|指令|编码|目标|链接值|
|---|---|---|---|
|`JAL rd,offset`|J，`opcode=0x6F`|`u32(PC+offset)`|`u32(PC+4)`|
|`JALR rd,imm12(rs1)`|I，`opcode=0x67, funct3=000`|`u32(x[rs1]+imm) & 0xFFFFFFFE`|`u32(PC+4)`|

本设计无分支预测。只检查实际选中的下一 PC：它必须 4 B 对齐且位于 ITCM `[0x00000000,0x00040000)`。目标错误归属于当前控制转移指令，并禁止链接值提交。`JALR rd=rs1` 合法，目标使用旧源值。

### 3.5. 屏障与终止（2 条）

|指令|合法编码|语义|
|---|---|---|
|`FENCE pred,succ`|`opcode=0x0F, funct3=000`|保守全局屏障，忽略字段细分并排空所有更早工作|
|`HALT`|仅 `0x0000000B`|排空更早工作后正常结束当前运行|

`FENCE` 不进行 cache maintenance，也不实现 `FENCE.I`。`HALT` 不是复位、Trap、`WFI` 或 `EBREAK`。

### 3.6. 常用伪指令

|伪指令|展开|
|---|---|
|`NOP`|`ADDI x0,x0,0`|
|`MV rd,rs`|`ADDI rd,rs,0`|
|`NOT rd,rs`|`XORI rd,rs,-1`|
|`NEG rd,rs`|`SUB rd,x0,rs`|
|`J offset`|`JAL x0,offset`|
|`JR rs`|`JALR x0,0(rs)`|
|`RET`|`JALR x0,0(x1)`|

## 4. Vector ISA（38 条）

### 4.1. 数据组织与掩码

- Vector Register File 固定为 32×256 bit，不设置 `vl/vtype`。
- 每个寄存器包含 8 个 32-bit lane。
- INT32/FP32 使用完整 lane；BF16 使用低 16 bit，规范结果将高 16 bit 清零。
- `.vx` 的 Scalar 源来自 `x[rs1]`：整数使用全部 32 bit，BF16 只读取低 16 bit。
- `vm=1` 表示全部 lane 启用；`vm=0` 使用派发时捕获的旧 `v0[7:0]`，汇编可写 `,v0.t`。
- 被关闭的普通目的 lane 保留完整旧 32-bit 位模式；归约跳过关闭 lane。
- `VLD/VST` 是无掩码的 32 B 全寄存器访问。

比较指令产生规范掩码：启用 lane 的真假结果写入 `vd[i]`，关闭 lane 保留旧 `vd[i]`，`vd[255:8]` 清零。若 `vd=v0`，执行掩码仍使用旧 `v0`。

### 4.2. XJ-V 编码

```text
31       26 25 24      20 19      15 14   12 11       7 6       0
┌──────────┬──┬──────────┬──────────┬───────┬──────────┬─────────┐
│  funct6  │vm│   vs2    │vs1/rs1/u5│funct3 │    vd    │  0x57   │
└──────────┴──┴──────────┴──────────┴───────┴──────────┴─────────┘
```

|`funct3`|形式|字段解释|
|---:|---|---|
|`000`|`.vv`|`op.vv vd,vs2,vs1`|
|`100`|`.vx`|`op.vx vd,vs2,rs1`|
|`011`|`.vi`|`op.vi vd,vs2,uimm5`，`uimm5=inst[19:15]`|
|`010`|一元 `.v`|`vs2` 为源，`inst[19:15]=0`|
|`001`|归约 `.vs`|`vs2` 为数据，`vs1[0]` 为 FP32 初值|

二元运算固定按 `op(vs2,vs1/scalar)` 计算，减法、除法和比较不得交换源。

|`funct6`|操作|合法形式|`funct6`|操作|合法形式|
|---:|---|---|---:|---|---|
|`0x00`|`vadd`|`.vv/.vx`|`0x12`|`vfwcvtbf16.f.f.v`|一元 `.v`|
|`0x01`|`vsub`|`.vv/.vx`|`0x13`|`vfncvtbf16.f.f.w`|一元 `.v`|
|`0x02`|`vmul`|`.vv/.vx`|`0x14`|`vfcvt.f.x.v`|一元 `.v`|
|`0x03`|`vmax`|`.vv/.vx`|`0x15`|`vfcvt.x.f.v`|一元 `.v`|
|`0x04`|`vmin`|`.vv/.vx`|`0x16`|`vslideup`|`.vx/.vi`|
|`0x05`|`vand`|`.vv/.vx`|`0x17`|`vslidedown`|`.vx/.vi`|
|`0x06`|`vor`|`.vv/.vx`|`0x18`|`vrgather`|`.vv`|
|`0x07`|`vxor`|`.vv/.vx`|`0x19`|`vfredsum`|`.vs`|
|`0x08`|`vsll`|`.vv/.vx`|`0x1A`|`vfredmax`|`.vs`|
|`0x09`|`vsrl`|`.vv/.vx`|`0x1B`|`vmseq`|`.vv/.vx`|
|`0x0A`|`vsra`|`.vv/.vx`|`0x1C`|`vmslt`|`.vv/.vx`|
|`0x0B`|`vfadd`|`.vv/.vx`|`0x1D`|`vmsle`|`.vv/.vx`|
|`0x0C`|`vfsub`|`.vv/.vx`|`0x1E`|`vmsltu`|`.vv/.vx`|
|`0x0D`|`vfmul`|`.vv/.vx`|`0x1F`|`vmsleu`|`.vv/.vx`|
|`0x0E`|`vfdiv`|`.vv/.vx`|`0x20`|`vmfeq`|`.vv/.vx`|
|`0x0F`|`vfmacc`|`.vv/.vx`|`0x21`|`vmflt`|`.vv/.vx`|
|`0x10`|`vfmax`|`.vv/.vx`|`0x22`|`vmfle`|`.vv/.vx`|
|`0x11`|`vfmin`|`.vv/.vx`|`0x23`|`vmerge.vvm`|`.vv` 且 `vm=0`|

未列出的 `funct6/funct3` 组合和非零保留字段均非法。

### 4.3. INT32 运算

|指令|逐 lane 语义|
|---|---|
|`vadd/vsub`|加减后保留低 32 bit|
|`vmul`|乘积保留低 32 bit|
|`vmax/vmin`|有符号 INT32 最大/最小值|
|`vand/vor/vxor`|按位逻辑|
|`vsll/vsrl/vsra`|移位量取第二操作数低 5 bit|

整数运算不饱和、不产生溢出故障，lane 之间不传递进位。

### 4.4. BF16 与转换

BF16 和 FP32 遵循 IEEE 754 二进制格式，固定 RNE，支持无穷、带符号零和次正规数，不采用 FTZ/DAZ。算术 NaN 规范化为 BF16 `0x7FC0` 或 FP32 `0x7FC00000`；浮点特殊值不产生故障或异常标志。

|指令|语义|
|---|---|
|`vfadd/vfsub/vfmul/vfdiv`|按无限精度结果一次舍入到 BF16|
|`vfmacc`|`vs2×vs1/scalar+old vd`，最终只舍入一次|
|`vfmax/vfmin`|一侧 NaN 时返回数值侧；均为 NaN 时返回规范 NaN|
|`vfwcvtbf16.f.f.v`|BF16 位模式左移 16 bit，精确扩宽并保留 NaN payload|
|`vfncvtbf16.f.f.w`|FP32→BF16，RNE；NaN 规范化|
|`vfcvt.f.x.v`|signed INT32→FP32，RNE|
|`vfcvt.x.f.v`|FP32→signed INT32，RNE；NaN/正溢出取 `INT32_MAX`，负溢出取 `INT32_MIN`|

补充规则：

- 有限非零数除以 ±0 得到带符号无穷；`0/0` 和 `∞/∞` 得到规范 NaN。
- `max(+0,-0)=+0`，`min(+0,-0)=-0`。
- `vmfeq/vmflt/vmfle` 任一操作数为 NaN 时均返回假；`+0==-0`。
- XJ-NPU-1.0 不包含 `Vexp/Vrcp/Vrsqrt`。

### 4.5. 置换与归约

|指令|语义|
|---|---|
|`vslideup`|距离 `d`；`i>=d` 时写 `vs2[i-d]`，否则写零|
|`vslidedown`|距离 `d`；`i+d<8` 时写 `vs2[i+d]`，否则写零|
|`vrgather.vv`|索引按 UINT32 解释；0–7 选择源 lane，其他值返回零|
|`vmerge.vvm`|旧 `v0[i]=1` 选择 `vs1[i]`，否则选择 `vs2[i]`|

`.vi` 距离为零扩展 `uimm5`，`.vx` 距离使用完整 UINT32；距离大于等于 8 时启用 lane 全部写零。源在执行前捕获，允许目的与源重叠。

`vfredsum.vs` 和 `vfredmax.vs` 将 `vs2` 解释为 FP32，以 `vs1[0]` 为初值，按 lane 0→7 的固定顺序处理启用元素。每一步均产生 FP32 RNE 结果，不允许树形重排。结果写入 `vd[0]`，`vd[7:1]` 保留旧值；无启用 lane 时 `vd[0]=vs1[0]`。

### 4.6. Vector 访存（2 条）

|指令|编码|语义|
|---|---|---|
|`VLD vd,imm12(rs1)`|I，`opcode=0x0B, funct3=001`|加载连续 32 B|
|`VST vs3,imm12(rs1)`|S，`opcode=0x0B, funct3=010`|存储连续 32 B|

`VST` 使用 `vs3=inst[24:20]`，立即数仍按 S 型拼接。EA 按 32-bit Scalar 加法回绕后检查，必须 32 B 对齐，完整 32 B 必须位于 DTCM；未对齐优先于越界。lane `i` 对应 `EA+4*i…EA+4*i+3`。VLD 只有在完整 32 B 读取成功后才更新 `vd`；VST 按程序顺序产生存储器副作用。

## 5. Matrix ISA（10 条）

### 5.1. Matrix 状态与 tile 布局

|Unit|输入|内部 ACC|数据流|
|---|---|---|---|
|`u0`|BF16 A/B|8×8 FP32|固定 OS|
|`u1`|signed INT8 A/B|8×8 INT32|OS/WS|
|`u2`|signed INT8 A/B|8×8 INT32|OS/WS|

|对象|DTCM 大小|对齐|布局|
|---|---:|---:|---|
|u0 A/B|各 128 B|64 B|64×BF16，row-major|
|u0 ACC load/store|128 B|64 B|外部 BF16；加载扩宽、存储 RNE 窄化|
|u1/u2 A/B|各 64 B|64 B|64×signed INT8，row-major|
|u1/u2 ACC load/store|256 B|64 B|64×INT32，小端、row-major|

只支持完整 8×8 tile，不支持行列掩码。边界 tile 由软件补零。复位时 mode=OS，A/B/ACC 数据清零且有效位清零；ACK 和后续 START 保留已提交状态。

### 5.2. `custom-1`：配置和 tile 搬运

使用 R 型字段，`funct3=unit`；`unit=000/001/010` 对应 `u0/u1/u2`，其他值非法。

|指令|`funct7`|字段约束|语义|
|---|---:|---|---|
|`MSETMODE unit,mode`|`0000000`|`rs1=rd=0`；`rs2=0/1`|设置 OS/WS|
|`MLOAD unit,A,rsA`|`0000100`|`rs1=rsA, rs2=rd=0`|加载 A|
|`MLOAD unit,B,rsA`|`0000101`|同上|加载 B|
|`MLOAD unit,ACC,rsA`|`0000110`|同上|加载 ACC|
|`MSTORE unit,rsC`|`0001000`|`rs1=rsC, rs2=rd=0`|存储已提交 ACC|

`MSETMODE` 的 mode 是编码立即数：0=OS，1=WS。u0 只接受 OS；u1/u2 接受 OS/WS。其他 mode 编码非法。

MLOAD 只有在完整 tile 读取成功后才原子替换目标 A/B/ACC，失败时保留旧状态。MSTORE 要求 ACC 有效，并在地址和完整 tile 通过预检查后写出结果。

### 5.3. `custom-2`：Matrix compute

`opcode=0x5B`，`funct3=unit`，`rs1=rs2=rd=0`。

|指令|`funct7`|输入要求|结果|
|---|---:|---|---|
|`MMUL unit`|`0000000`|A/B 有效|从正零开始计算 `ACC=A×B`|
|`MMACC unit`|`0000001`|A/B/ACC 有效|`ACC=ACC+A×B`|
|`MEW unit`|`0000010`|A/B 有效|`ACC=A⊙B`|
|`MTRANS unit`|`0000011`|A 有效|`ACC=Aᵀ`，不读取 B|

u0 矩阵乘法按 `k=0…7` 顺序执行：

```text
acc = RNE_FP32(acc + exact(BF16(A) × BF16(B)))
```

u1/u2 将 signed INT8 乘积符号扩展到 INT32，每次累加按模 2^32 回绕。MEW/MTRANS 在 u0 中产生 FP32 ACC，在 u1/u2 中产生 INT32 ACC。OS/WS 只改变内部数据移动，不改变架构结果。

### 5.4. `custom-3`：同步

除参数字段、`funct3` 和 `opcode=0x7B` 外，其余位必须为零。

|指令|`funct3`|参数|语义|
|---|---:|---|---|
|`MWAIT unit`|`000`|`unit=inst[31:30]`|等待指定 MU 的任务和完成记录清空|
|`MFENCE`|`001`|无|与 Scalar `FENCE` 同义的全局屏障|
|`MBARRIER id`|`010`|`id=inst[31:27]`|等待所有更早 Matrix/DMA 工作|

`unit=3` 非法。`id` 只保留软件同步命名空间，不增加硬件并发。

### 5.5. Matrix 地址和状态故障

所有 tile 地址必须 64 B 对齐，完整 64/128/256 B 范围必须位于 DTCM。范围计算使用至少 33 bit 中间值，禁止地址回绕；未对齐优先于越界。

非法 unit、mode、buffer 或保留字段产生非法指令。编码合法但缺少所需 A/B/ACC 有效位时产生 Matrix 状态故障。

## 6. Core DMA ISA（4 条）

四条指令均使用 R 型 `opcode=0x0B, funct7=0000000, rd=x0`。

|指令|`funct3`|`rs1`|`rs2`|语义|
|---|---:|---|---|---|
|`DSETSIZE rsRowBytes,rsRows`|`011`|`row_bytes`|`rows`|设置二维尺寸|
|`DSETSTRIDE rsExtStride,rsLocalStride`|`100`|`external_stride`|`local_stride`|设置二维步长|
|`MVIN rsExternal,rsLocal`|`101`|外部首地址|DTCM 首地址|外部→DTCM|
|`MVOUT rsExternal,rsLocal`|`110`|外部首地址|DTCM 首地址|DTCM→外部|

`MVIN/MVOUT` 的 `rs1` 始终是外部首地址，`rs2` 始终是 DTCM 首地址；助记符只决定数据方向。

`DSETSIZE` 要求 `row_bytes>0`、`rows>0` 且 `row_bytes` 4 B 对齐。`DSETSTRIDE` 要求两个 stride 为 4 B 整数倍，允许为零。两条配置按程序顺序生效；合法执行后更新状态并置对应有效位，发生故障时保留旧配置。

MVIN/MVOUT 要求两个配置有效、两个 base 4 B 对齐，并在产生任何访问前完成全命令预检查：

```text
last_row         = rows - 1
ext_last_byte    = external_base + last_row × external_stride + row_bytes - 1
local_last_byte  = local_base    + last_row × local_stride    + row_bytes - 1
```

计算使用 65 bit 或等价带进位检查。`ext_last_byte<2^32`，`local_last_byte<1 MiB`。行按 `0…rows-1` 顺序执行，允许零 stride 和行间重叠；重叠 MVIN 的后执行行覆盖先执行行。

每行拆成不跨 4 KiB、长度不超过 256 B 的 AXI burst，最多 4 个同方向 burst 在途。发生错误后停止发出新的 burst，并排空已接受事务；已成功写入的数据不回滚。AXI ID/LAST 破坏导致响应无法归属时进入 `BUS_POISONED`，只能平台复位。

## 7. 故障与运行终止

### 7.1. 故障码

这些故障码是 XJ-NPU 本地 Host 接口约定，不是 privileged RISC-V `mcause`。

|码|名称|`FAULT_ADDR`|
|---:|---|---|
|0|无故障|0|
|1|非法指令|原始机器字|
|2|取指/控制目标未对齐|故障地址|
|3|取指/控制目标越界|故障地址|
|4|Scalar load 未对齐|EA|
|5|Scalar load 访问故障|EA|
|6|Scalar store 未对齐|EA|
|7|Scalar store 访问故障|EA|
|8|Vector load 未对齐|EA|
|9|Vector load 访问故障|EA|
|10|Vector store 未对齐|EA|
|11|Vector store 访问故障|EA|
|12|Matrix tile 未对齐|tile base|
|13|Matrix tile 访问故障|tile base|
|14|Matrix 运行时状态故障|0|
|15|DMA 配置或地址故障|非法值或失败地址|
|16|DMA 外部读响应错误|失败 burst 地址|
|17|DMA 外部写响应错误|失败 burst 地址|
|18|DMA/AXI 协议失配|相关地址或 0|

`FAULT_DETAIL[31:24]` 为域（1=Vector、2=Matrix、3=DMA），`[23:16]` 为域内原因或 AXI RESP，`[15:0]` 为 lane、unit/buffer 或 DMA row 索引。

### 7.2. 故障处理

普通故障在精确指令边界处理：

1. 锁存 `FAULT_PC/INST/CAUSE/ADDR/DETAIL`，停止启动年轻工作。
2. 故障指令不产生正常成功结果，年轻指令的结果不可见。
3. 更老的架构状态以及已经发生的 Store/MSTORE/DMA 部分副作用保留，不执行回滚。
4. 安全排空已接受的存储器事务后清除 BUSY 并置 FAULT。
5. Host ACK 后可从 PC=0 再次 START。

`BUS_POISONED` 是例外：保持 BUSY/owner，不接受 ACK、START 或 Host SRAM 访问，只能平台复位。

## 8. 指令清单

|执行域|指令|
|---|---|
|Scalar 计算|`ADD SUB AND OR XOR SLL SRL SRA SLT SLTU ADDI ANDI ORI XORI SLLI SRLI SRAI SLTI SLTIU LUI AUIPC`|
|Scalar 访存/控制|`LW SW BEQ BNE BLT BGE BLTU BGEU JAL JALR FENCE HALT`|
|Vector INT|`vadd vsub vmul vmax vmin vand vor vxor vsll vsrl vsra`|
|Vector BF16|`vfadd vfsub vfmul vfdiv vfmacc vfmax vfmin`|
|Vector 转换|`vfwcvtbf16.f.f.v vfncvtbf16.f.f.w vfcvt.f.x.v vfcvt.x.f.v`|
|Vector 置换/归约|`vslideup vslidedown vrgather vfredsum vfredmax`|
|Vector 比较/选择|`vmseq vmslt vmsle vmsltu vmsleu vmfeq vmflt vmfle vmerge.vvm`|
|Vector 访存|`VLD VST`|
|Matrix|`MSETMODE MLOAD MSTORE MMUL MMACC MEW MTRANS MWAIT MBARRIER MFENCE`|
|Core DMA|`DSETSIZE DSETSTRIDE MVIN MVOUT`|

## 9. 示例

```asm
# Vector BF16 FMA
vfmacc.vx v8, v4, x1

# BF16 Matrix tile
MSETMODE u0, OS
MLOAD    u0, A, x10
MLOAD    u0, B, x11
MMUL     u0
MSTORE   u0, x12

# 二维 DMA：外部 x20 -> DTCM x21
DSETSIZE   x1, x2
DSETSTRIDE x3, x4
MVIN       x20, x21
FENCE
HALT
```
