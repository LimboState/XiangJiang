# XiangJiang NPU 微架构规范

|项目|定义|
|---|---|
|架构|顺序单发射、乱序执行、ROB 顺序提交|
|ISA|[XJ-ISA 1.0](xj-isa.md)，目标共 85 条指令|
|标量|32×32-bit X Register，无独立 Scalar FP Register File|
|向量|32×256-bit V Register，8×32-bit lane|
|矩阵|1×BF16 8×8、2×INT8 8×8，共 192 PE|
|存储|256 KiB ITCM、1 MiB 四 bank DTCM|
|前端|32-bit 指令，无分支预测|
|存储顺序|统一 MemQ，同一时刻至多一个活动表项|
|当前 RTL|仅实现 33 条 Scalar 指令，见第 13 节|

本文定义 XJ-NPU 的流水线、状态结构、功能单元、存储层次、DMA、Host 接口和实现约束。软件可见的机器编码、操作数、数值和故障语义以 [xj-isa.md](xj-isa.md) 为准。

“目标设计”表示已确定的架构契约，不表示 Vector、Matrix 和 Core DMA 已在当前 RTL 中实现或验证。

## 1. 总体架构

XiangJiang NPU 是单指令流加速器。统一前端按程序顺序取指、译码、重命名和派发；后端允许彼此独立的 Scalar、Vector 和 Matrix 工作乱序执行；统一 ROB 只按程序顺序提交。

```text
Host CPU
  |-- AXI4-Lite Control
  |-- AXI4-Full  Memory
  |
  +--> ITCM --> Fetch --> Decode --> Rename / Dispatch
                                      |
                   +------------------+------------------+
                   |                  |                  |
                Scalar RS          Vector RS        Matrix Task
                   |                  |                  |
             Scalar FUs          Vector FUs         MU0/MU1/MU2
                   |                  |                  |
                   +---------- Completion Network -------+
                                      |
                              Result Slots / ROB
                                      |
                                Commit / Fault
                                      |
                              X/V ARF, Matrix State

Scalar LSU / Vector LSU / Matrix LSU / Core DMA
                         |
                    Unified MemQ
                         |
                    DTCM Arbiter
                         |
                 4 × 256 KiB DTCM

Core DMA <--> External AXI4 Manager <--> System Memory
```

### 1.1. 执行域

|执行域|职责|边界|
|---|---|---|
|Scalar|32-bit 整数、地址生成、分支和控制|不执行 Vector BF16，不绕过 ROB 提交|
|Vector|8-lane INT32/BF16/FP32 运算、置换、归约和 32 B 访存|Scalar 操作数来自 X Register；无独立 F Register|
|Matrix|三个 8×8 MU、tile 搬运和内部 ACC|不参与 X/V 重命名；无 Matrix RAT|
|Core DMA|外部存储器与 DTCM 的二维搬运|命令同步执行，不作为后台已退休任务|

### 1.2. 流水阶段

|阶段|职责|是否改变已提交架构状态|
|---|---|---|
|Fetch|从同步 ITCM 取指，绑定 PC 和 epoch|否|
|Decode|严格检查机器编码，生成源/目的/FU/访存元数据|否|
|Rename/Dispatch|捕获源版本，原子分配 ROB、结果槽、RS/MemQ|否|
|Issue|选择最老的就绪候选，并与目标 FU 握手|否|
|Execute|计算或执行多拍任务|否|
|Complete|写结果槽、记录异常、唤醒消费者|否|
|Commit|ROB Head 发布寄存器/Matrix/DMA 配置，或授权不可逆操作|是|
|Retire|提交事务完成后释放资源|不增加新副作用|

取指、派发和提交宽度均为每周期至多一条指令。不同 FU 可以在同周期各自发射一条就绪操作，因此“顺序单发射前端”不等于“后端每周期只能执行一个 FU”。

## 2. 前端与控制流

### 2.1. Fetch

ITCM 是 32-bit 单端口同步 SRAM：请求在周期 N 接受，指令在 N+1 返回。每个响应绑定请求 PC 和 epoch；重定向、故障或清空后，旧 epoch 响应必须丢弃。

在截断 SRAM index 前检查 PC：

- 4 B 对齐；
- 位于 `[0x00000000,0x00040000)`；
- 不允许地址回绕形成 ITCM 别名。

### 2.2. 无预测控制流

本设计没有方向预测器、BTB 或返回地址栈。识别 `BEQ/BNE/BLT/BGE/BLTU/BGEU/JAL/JALR` 后：

1. 当前控制转移按顺序分配 ROB 和控制记录。
2. 阻止更年轻指令继续派发，并使年轻预取响应失效。
3. 更老指令继续执行和提交。
4. 控制转移成为 ROB Head、源就绪且更早副作用排空后，由 S-BRU 解析。
5. 成功时提交链接值并重定向；失败时进入精确故障流程。

不需要分支 RAT checkpoint，因为未在未解析分支之后建立年轻架构工作。

### 2.3. 派发屏障

以下操作识别后阻止年轻派发，直到自身提交或故障：

- 分支和跳转；
- `FENCE/MFENCE/MBARRIER/MWAIT`；
- `MSETMODE`、DMA 配置；
- `HALT`；
- 需要全局排空的控制操作。

## 3. 重命名与依赖管理

### 3.1. 架构和推测状态

|状态|已提交副本|推测/版本化副本|
|---|---|---|
|X Register|X ARF|RAT-X + Scalar Result Slots|
|V Register|V ARF|RAT-V + 256-bit Vector Result Slots|
|Matrix|各 MU 已提交 A/B/ACC/mode|带 ROBTag 的 staging/working|
|DMA 配置|已提交 shape/stride 和有效位|ROB 中的配置 effect|
|存储器|DTCM/外部存储器|MemQ、任务记录和 FIFO|

X/V 使用独立 RAT；Matrix 不设置 RAT。已提交 ARF 保存精确状态，结果槽保存未提交版本。

### 3.2. 原子 Rename/Dispatch

派发必须先解析所有显式和隐式源，再更新目的映射。源包括：

- X/V 普通源；
- 旧 Vector 目的；
- 旧 `v0` 掩码；
- Store 数据；
- Matrix/DMA 地址和配置参数；
- `vfmacc` 旧目的。

源解析规则：

|情况|动作|
|---|---|
|`x0`|直接提供零|
|RAT 无映射|捕获 ARF 值|
|RAT 映射且结果已就绪|捕获结果槽值|
|RAT 映射但未就绪|保存完整生产者 tag 并登记消费者|

一条指令所需的 ROB、结果槽、RS/控制记录和 MemQ 必须原子分配。任一资源不足时整条指令等待，不得留下部分 RAT 更新或资源泄漏。

### 3.3. 标签与年龄

- `tag={ROB_slot,generation}` 标识动态指令和结果版本。
- `seq` 表示程序年龄，不能用循环 slot 直接比较年龄。
- FU、结果槽、MemQ、Matrix/DMA 任务和外部响应均携带完整 tag。
- 返回时必须验证 slot、generation 和 owner；不匹配的迟到响应丢弃。
- generation/seq 即将回绕时停止分配，排空所有可能返回的响应后再重置域。

### 3.4. RAT 和结果槽回收

提交旧生产者时，仅当 RAT 仍同时匹配其 tag 和结果槽编号才清除映射；较年轻的 WAW 映射必须保留。

结果槽只有在以下条件全部满足后才能回收：

- 生产者已经提交或故障清理；
- 所有已登记消费者已捕获结果或撤销登记；
- 恢复流程不再需要该值。

原型不允许同一周期释放并立即重新分配同一结果槽。

## 4. ROB、RS 与完成网络

### 4.1. ROB 表项

逻辑 ROB Entry 至少包含：

```text
identity: valid, slot, generation, seq, pc, inst
operation: domain, opcode, destination metadata
result: scalar/vector result slot reference
memory: MemQ/task reference, authorization, progress
matrix/dma effects: unit/config snapshot and publish state
completion: uop count/bitmap, completion-only event
fault: valid, cause, address, detail
```

每条成功派发的动态指令恰有一个 ROB Entry；多拍执行仍只对应一个架构指令。

### 4.2. 保留站

RS 保存：

- ROBTag 和 seq；
- 操作码、立即数和控制信息；
- 每个源的 `ready/value/tag`；
- 目的结果槽；
- 掩码和旧目的。

每个 FU 从自己的候选集中选择 `seq` 最小的就绪项。较老但源未就绪的操作不阻止其他 FU 发射较年轻的独立计算。

### 4.3. 完成网络

基线只有一条逻辑 CDB，每周期至多广播一个结果，最大宽度 256 bit。Scalar 结果使用低 32 bit。

```text
Completion = {
  tag, seq, destination_kind,
  scalar_data[31:0] or vector_data[255:0],
  exception_valid, cause, value,
  completion_only
}
```

多个 FU 同周期完成时，响应先进入各 FU 输出保持缓冲，再由完成网络选择最老响应。未获选择的响应保持稳定，不能覆盖或丢失。

广播同时：

- 写对应结果槽；
- 更新 ROB 完成/故障状态；
- 唤醒 RS、MemQ 和控制记录。

广播不直接写 ARF、RAT、DTCM 或已提交 Matrix 状态。

### 4.4. 提交

只有 ROB Head 可以提交。普通寄存器结果在提交时写 ARF；Matrix working 和 DMA 配置在提交时发布；Store、MSTORE 和 DMA 写入则在 Head 获得启动授权，并在实际事务完成后退休。

“授权”不等于“成功完成”。若必须等到操作完成才授权，将形成提交和执行互相等待的死锁。

## 5. 功能单元

### 5.1. FU 组成

|执行资源|数量|职责|
|---|---:|---|
|S-ALU|1|Scalar 算术、逻辑、比较、移位、LUI/AUIPC|
|S-BRU|1|分支条件、目标、链接值和目标检查|
|S-AGU|1|LW/SW 地址生成、对齐和范围预检查|
|V-IALU|1 组|8-lane INT32 算术、逻辑、移位和比较|
|V-IMUL|1 组|8-lane INT32 乘法|
|V-BFPU|1 组|BF16 算术/FMA/比较和四类转换|
|V-BFDIV|1|迭代式 BF16 除法|
|V-PERM/RED|1|slide、gather、merge 和固定顺序归约|
|Scalar/Vector LSU|1 个共享顺序接口|LW/SW 和 VLD/VST|
|MU0|1|BF16 8×8 Matrix Unit，FP32 ACC|
|MU1/MU2|各 1|signed INT8 8×8 Matrix Unit，INT32 ACC|
|Matrix LSU|1|MLOAD/MSTORE tile 搬运|
|DMA Engine|1|二维地址生成、burst 拆分和数据搬运|

`FENCE/HALT/MSETMODE/MWAIT/MBARRIER/MFENCE` 由控制记录和 ROB Head 处理，不发送到虚构的普通 ALU。

### 5.2. FU 接口原则

- FU 只使用派发/发射时捕获的源，不重新读取 ARF。
- 请求和响应携带完整 ROBTag。
- ISA 不暴露固定执行延迟；`latency` 和启动间隔 `II` 是实现参数。
- 发射前必须确保输出保持寄存器或 FIFO 有空间。
- 响应受阻时 tag、data、fault 必须稳定。
- Vector FU 只广播完整 256-bit 结果，不暴露部分 lane ready。
- 被掩码关闭的 lane 不启动可能产生特殊结果的运算。

### 5.3. Scalar 路径

S-ALU 接收两个 32-bit 源、立即数、PC 和 ROBTag。当前 Scalar RTL 的 ALU 为单周期、`II=1`，但这不是软件可见保证。

S-BRU 独立于 S-ALU，只在控制转移成为 ROB Head 时执行。它输出所选下一 PC、链接值、目标故障和重定向信息。

S-AGU 与 LSU 分离。地址就绪不等于访存完成；SW 数据必须使用派发时捕获的版本。

### 5.4. Vector 路径

Vector 数据通路逻辑宽度为 256 bit。物理实现可以采用 8 路并行 lane，也可以分时执行，但必须保持单条 Vector 指令结果原子性。

- V-IALU 和 V-IMUL 分离，避免乘法路径限制逻辑操作时序。
- V-BFPU 统一实现分类、规格化和 RNE；不同操作不得依赖不同 IP 的默认特殊值行为。
- V-BFDIV 可逐 lane 迭代，但一个请求占用单元直到全部启用 lane 完成。
- V-PERM/RED 使用 8×8 选择网络或等价结构；归约顺序必须符合 XJ-ISA。

## 6. Matrix 子系统

### 6.1. 状态划分

每个 MU 包含：

- 已提交 A/B 输入；
- 已提交 ACC 和 mode；
- 输入有效位；
- 带 owner ROBTag 的 staging/working；
- 8×8 PE 阵列和任务控制器。

```text
Committed A/B/ACC/mode
          |
          +-- snapshot --> Working(owner ROBTag)
          |                    |
          +<---- Commit publish+

DTCM --> Matrix LSU --> Staging(owner ROBTag) --> Commit publish
```

### 6.2. Matrix 指令生命周期

|指令|执行方式|
|---|---|
|MLOAD|ROB Head + MemQ Head 启动；完整 tile 进入 staging；正常提交时发布|
|MMUL/MMACC/MEW/MTRANS|ROB Head 启动；快照已提交状态；working 完成后提交 ACC|
|MSTORE|ROB Head + MemQ Head 授权；快照已提交 ACC；逐拍写 DTCM|
|MSETMODE|ROB Head 等待目标 MU 空闲后更新 mode|
|MWAIT|观察指定 MU 的任务和完成记录|
|MBARRIER/MFENCE|在 ROB Head 检查全局排空条件|

每个 MU 同时至多拥有一个 working task。三个阵列物理独立，但由于 Matrix writer 只在 ROB Head 启动，基线不承诺三个完整 Matrix task 并发。

### 6.3. 精确状态

- MLOAD 失败时丢弃 staging，不改变已提交状态。
- Matrix compute 发生状态故障时丢弃 working。
- compute 正常完成且 owner tag 匹配后，只有提交才发布 ACC。
- MSTORE 是不可逆多拍写；已被 SRAM 接受的字节不回滚。
- Flush 可丢弃未发布 staging/working，但不能取消已接受的 DTCM 请求。

数值和 tile 布局由 XJ-ISA 第 5 节定义。

## 7. 存储层次

### 7.1. ITCM

|属性|配置|
|---|---|
|容量|256 KiB|
|组织|65536×32-bit 单端口同步 SRAM|
|核心地址|`[0x00000000,0x00040000)`|
|时序|请求 N 接受，N+1 返回|
|复位|不清 SRAM|

Core 运行时独占 ITCM；Host 只在 Core idle 时装载或回读。

### 7.2. DTCM

|属性|配置|
|---|---|
|总容量|1 MiB|
|Bank|4×256 KiB|
|每 bank|4096×512 bit，1R1W|
|交织|64 B；`bank=addr[7:6]`|
|行索引|`addr[19:8]`|
|行内 byte|`addr[5:0]`|
|写使能|64-bit byte mask|
|核心地址|`[0x00000000,0x00100000)`|
|复位|不清 SRAM|

物理峰值读、写带宽各为 4×64 B/cycle，但实际吞吐受 MemQ、bank 冲突、适配器和仲裁限制。

同一 bank 的多个同向访问串行化；同一行读写也必须由仲裁器明确排序，不能依赖 SRAM macro 的未定义 read-during-write 行为。

### 7.3. 地址检查原则

- Scalar/Vector 有效地址按 XJ-ISA 的 32-bit 运算形成，再检查对齐和范围。
- Matrix/DMA 多拍范围使用扩展精度中间值，禁止回绕。
- 所有多拍访问在首个请求前完成完整范围预检查。
- 未对齐优先于范围错误。
- 检查必须发生在 SRAM index 截断之前。

## 8. MemQ 与存储顺序

### 8.1. 入队

以下指令在派发时按程序顺序预留 MemQ：

- `LW/SW`；
- `VLD/VST`；
- `MLOAD/MSTORE`；
- `MVIN/MVOUT`。

纯 Matrix compute 和无存储器副作用的配置操作不进入 MemQ。

### 8.2. 单活动表项

同一时刻至多一个 MemQ 表项处于活动状态。年轻表项不能绕过未就绪的队首；不实现：

- 地址推测；
- Load 绕过更老 Store；
- Store-to-Load forwarding；
- memory replay；
- 多个独立活动 Load。

DTCM Load 可以在到达 ROB Head 前执行，因为 DTCM 读取无设备副作用；Store、MLOAD/MSTORE 和 MVIN/MVOUT 必须在 ROB Head 获得授权。

### 8.3. 激活与完成

|操作|激活条件|完成条件|
|---|---|---|
|LW/VLD|MemQ Head、地址和返回槽就绪、预检查通过|数据完整返回并写结果槽|
|SW/VST|MemQ Head + ROB Head、数据就绪、预检查通过|全部字节达到 SRAM 完成点|
|MLOAD|MemQ Head + ROB Head、staging 可用|tile 完整返回；提交时发布|
|MSTORE|MemQ Head + ROB Head、ACC 有效|全部 tile 字节完成|
|MVIN|MemQ Head + ROB Head、全命令预检查/缓冲预留完成|外部读成功且 DTCM 写完成|
|MVOUT|MemQ Head + ROB Head、全命令预检查/发送资源完成|外部写全部收到成功响应|

## 9. Core DMA 与外部 AXI

### 9.1. DMA 结构

DMA 包含：

- 已提交 shape/stride 配置及有效位；
- MVIN 和 MVOUT 逻辑数据路径；
- 64-bit/65-bit 扩展地址预检查；
- 行地址生成器；
- 4 KiB/burst 拆分器；
- AXI ID 和响应跟踪；
- 数据 FIFO 和 DTCM 宽度转换。

两个方向不会同时激活。

### 9.2. Burst 约束

- 外部地址宽度固定 32 bit。
- 单 burst 不超过 256 B，且不得跨越 4 KiB。
- 最多 4 个同方向 burst 在途。
- 发出读请求前预留完整返回空间。
- 行严格按软件顺序执行；允许零 stride 和重叠行。
- MVIN 验证完整 burst 后按命令顺序写 DTCM。
- MVOUT 已接受 AW 后必须完成所有 W beat 并接收 B。

### 9.3. AXI 错误

普通 AXI 错误：停止发新 burst，排空已接受事务，记录首个错误和进度；此前成功写入的数据不回滚。

如果 ID、LAST 或响应归属被破坏，无法证明剩余响应的所有者，则进入 `BUS_POISONED`：

- 保持 BUSY 和 owner；
- 不接受 ACK、START 或 Host SRAM 访问；
- 不伪造安全排空；
- 只能平台复位恢复。

## 10. Host 接口与生命周期

### 10.1. 接口

|接口|作用|
|---|---|
|AXI4-Lite Control Slave|START/ACK、状态、故障和配置查询|
|AXI4-Full Memory Slave|Core idle 时装载/回读 ITCM/DTCM|
|AXI4 Manager|Core DMA 访问外部存储器|

Host SRAM 窗口：

|Host 地址|目标|
|---|---|
|`[0x00000000,0x00040000)`|ITCM|
|`[0x10000000,0x10100000)`|DTCM|
|其他|`SLVERR`|

Host 地址与 Core 零基本地地址不是同一命名空间。

### 10.2. 生命周期

```text
RESET -> IDLE
IDLE --START--> RUNNING
RUNNING --HALT/drain--> DONE --ACK--> IDLE
RUNNING --fault/drain--> FAULT --ACK--> IDLE
RUNNING --protocol poison--> BUS_POISONED --platform reset--> IDLE
```

- START 要求 Core 和 Host Memory 接口完全空闲。
- 每次 START 从 PC=0 开始。
- ACK 清终止/故障/进度，不清寄存器、Matrix 状态、DMA 配置和 SRAM。
- Core 运行时 Host 不能修改 ITCM/DTCM。
- Host 必须自行处理 CPU cache 与外部 DMA 的一致性。

### 10.3. Control MMIO

接口版本 1（当前 Scalar-only RTL）：

|偏移|名称|定义|
|---:|---|---|
|`0x00`|CONTROL|写 bit0 START、bit1 ACK|
|`0x04`|STATUS|bit0 BUSY、bit1 DONE、bit2 FAULT|
|`0x08`|FAULT_PC|故障指令 PC|
|`0x0C`|FAULT_INST|故障机器字|
|`0x10`|FAULT_CAUSE|XJ-ISA 本地故障码|
|`0x14`|FAULT_ADDR|故障地址/数据|
|`0x18`|ITCM_BYTES|ITCM 容量|
|`0x1C`|DTCM_BYTES|DTCM 容量|
|`0x20`|CONFIG_ROB|ROB 表项数|
|`0x24`|CONFIG_XRES|Scalar Result Slot 数|
|`0x28`|CONFIG_MEMQ|MemQ 表项数|
|`0x2C`|CONFIG_ISSUE|Scalar Issue 深度|
|`0x30`|VERSION|接口版本|
|`0x100+4*n`|XREG[n]|已提交 X Register，只读|

完整目标实现使用接口版本 2，并增加：

|偏移|名称|定义|
|---:|---|---|
|`0x34`|FAULT_DETAIL|域、域内原因和索引|
|`0x38`|DMA_ROW_BYTES|已提交 row_bytes|
|`0x3C`|DMA_ROWS|已提交 rows|
|`0x40`|DMA_EXT_STRIDE|已提交 external_stride|
|`0x44`|DMA_LOCAL_STRIDE|已提交 local_stride|
|`0x48`|DMA_BYTES_DONE|已确认成功字节数|
|`0x4C`|DMA_ROWS_DONE|完整成功行数|
|`0x50`|DMA_STATUS|ACTIVE/DIRECTION/ERROR/BUS_POISONED|

## 11. 精确故障与清空

故障由 FU、取指、LSU、Matrix 或 DMA 生成，先带 tag 写入对应 ROB Entry。只有故障指令成为 ROB Head 时才改变全局运行状态。

普通故障流程：

1. 锁存 `FAULT_PC/INST/CAUSE/ADDR/DETAIL`。
2. 停止取指、派发和新的 FU/存储器启动。
3. 排空已被 SRAM/AXI 接受的请求和 FU 输出缓冲。
4. 丢弃故障指令及更年轻的 RS、RAT、结果槽、ROB 和 Matrix working。
5. 保留已提交 ARF/Matrix/DMA 配置和已授权的部分存储器副作用。
6. 安全排空后清 BUSY、置 FAULT，等待 Host ACK。

纯计算 FU 可以在 Flush 后继续运行，并在出口因 tag 失配被丢弃；已经接受的 SRAM/AXI 事务不能被 Flush 取消。

## 12. 参数与正确性不变量

### 12.1. 固定配置

|参数|值|
|---|---|
|指令/PC|32 bit / 32 bit|
|取指/派发/提交|各 1 条/cycle 上限|
|X Register|32×32 bit|
|V Register|32×256 bit|
|Matrix|3×8×8 PE|
|ITCM|256 KiB|
|DTCM|1 MiB，4×256 KiB|
|CDB|1×256 bit/cycle|
|活动 MemQ|最多 1|
|分支预测|无|

### 12.2. 实现参数

|参数|约束|
|---|---|
|`N_ROB`|扩大窗口不改变单提交宽度|
|`N_RS[fu]`|每个已实现 FU 至少一个候选记录|
|`N_XRES/N_VRES`|分别保存 32/256-bit 结果|
|`N_MEMQ`|增加深度不增加活动表项数|
|`N_RESULT[fu]`|必须覆盖在途不可停顿结果|
|`G_tag/W_seq`|回绕前必须排空歧义|
|`L_fu/II_fu`|延迟和启动间隔分别配置|
|`W_AXI`|外部 AXI 数据宽度；必须支持 4 B 对齐窄事务|
|DMA FIFO/ID|覆盖最多 4 个在途 burst|

### 12.3. 正确性不变量

1. 每条成功派发的动态指令恰有一个 ROB Entry。
2. 全部显式和隐式源在目的 RAT 更新前绑定。
3. Complete 不直接更新已提交 ARF、Matrix 状态或存储器。
4. RAT 清除必须同时匹配 tag 和结果槽，不能破坏年轻映射。
5. 结果槽在全部登记消费者捕获前不得复用。
6. 返回响应必须验证完整 owner identity。
7. 不可逆 Core 写必须在 ROB Head 授权。
8. 同一时刻至多一个 MemQ 表项活动。
9. 所有请求在接受前必须预留返回容量。
10. 背压期间 tag/data/fault 必须保持稳定。
11. Flush/reset 不能取消已经接受的 SRAM/AXI 事务。
12. tag/seq 回绕前必须消除所有迟到响应歧义。
13. 声明的已实现能力不得大于已完成 RTL 和验证的集合。

## 13. 当前 Scalar-only RTL

当前 RTL 只实现 XJ-ISA 的 33 条 Scalar 指令，不包含 Vector、Matrix、Core DMA、外部 AXI Manager、cache、特权架构或分支预测器。

### 13.1. 模块组织

相对于 RTL 工程的 `src/main/scala`：

|模块|职责|
|---|---|
|`xiangjiang/Parameters.scala`、`Bundle.scala`|参数、ROBTag、操作数和完成记录|
|`xiangjiang/frontend/Frontend.scala`|同步 ITCM 请求、取指缓冲、屏障和重定向|
|`xiangjiang/backend/decode/DecodeUnit.scala`|Scalar 严格译码|
|`xiangjiang/backend/rename/RenameStage.scala`|X ARF、RAT、源捕获和旁路|
|`xiangjiang/backend/issue/IssueUnit.scala`|ALU/Branch RS 和最老就绪发射|
|`xiangjiang/backend/fu/FunctionalUnit.scala`|ALUUnit、BranchUnit|
|`xiangjiang/backend/rob/Rob.scala`|ROB 和 Scalar Result Pool|
|`xiangjiang/mem/LSU.scala`|有序 MemQ 和单活动访问|
|`xiangjiang/mem/TCM.scala`|ITCM/DTCM|
|`xiangjiang/ScalarCore.scala`|派发、CDB、提交和故障清理|
|`device/*`|Host AXI/控制寄存器/存储器适配器|
|`top/*`|平台集成和 RTL 生成|

### 13.2. 默认参数

|参数|当前默认值|
|---|---:|
|ROB|8|
|ALU/Branch RS|各 4|
|Scalar Result Slot|6|
|MemQ|4|
|generation/seq|4/16 bit|
|Host AXI address/data/ID|32/32/4 bit|

### 13.3. 构建与验证

在 RTL 仓库根目录：

```bash
./mill xj.test
./mill xj.runMain top.Generate generated
```

当前已记录的验证结果为 6 个测试套件、30 项仿真通过，覆盖 Scalar 译码/ALU/分支、ROB/结果槽、重命名冒险、迟到标签、回绕排空、有序 LSU、DTCM bank、Host AXI、精确故障、ACK 和重启。该结果只证明 Scalar-only 实现，不证明目标 Vector/Matrix/DMA 已实现。

## 14. 参考资料

- [XJ-ISA](xj-isa.md)
- [RISC-V Unprivileged ISA](https://docs.riscv.org/reference/isa/unpriv/)
- [RISC-V Vector Extension](https://docs.riscv.org/reference/isa/unpriv/v-st-ext)：仅用于命名和行为对照
- [Ibex Documentation](https://ibex-core.readthedocs.io/en/latest/)
- [BOOM Documentation](https://docs.boom-core.org/en/latest/)
