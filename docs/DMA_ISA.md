# XiangJiang DMA ISA 与 AXI Master


## 1. 接口与实现范围

- 顶层 `io.ddr` 是 **AXI4 Master**：32-bit 地址、32-bit 数据、`XJParams.axiIdBits` 位 ID。
  原 `io.memory` 仍是主机访问 ITCM/DTCM 的 AXI4 Slave；两者不可混接。
- DDR 控制器、PHY、AXI interconnect、地址映射和 CPU cache coherency 由集成系统提供。
  外部地址直接发往 AXI，不添加主机 DTCM 窗口基址，不执行地址翻译或缓存维护。
- DTCM 端为 512-bit 读写请求和 64-bit byte mask，地址是零基 DTCM 偏移。
  DMA/矩阵共用宽端口适配器，与 scalar/vector word 端口仲裁；响应握手后才算完成。
- Mvin/Mvout 各有一项命令 FIFO 和独立的 64 B 数据缓冲；共享非并发调度器及行地址/burst 进度状态。
  全局只接受一个未完成命令，且只允许一个 outstanding AXI burst。下一命令必须等完成记录被消费。
  这是基线串行实现，不宣称双向同时执行、跨 bank 峰值带宽或多 outstanding 吞吐。
- `dmaMaxBurstBeats` 可配置为 1–256，默认 16。实际 burst 进一步受当前 DTCM 64 B 行、外部
  4 KiB 页和软件行剩余长度限制，因此当前实现最多 16 个 32-bit beat，不要求长度为二次幂。
- 只发普通 `INCR`、4 B beat，ID=0，`LOCK/CACHE/PROT/QOS=0`，写入 `WSTRB=0xF`。
  不发 exclusive、FIXED、WRAP 或窄字节事务。AXI ID 只在上一命令所有响应排空后复用。

## 2. 指令编码

使用 R-type，`opcode=0x2B`（custom-1）、`funct3=011`、`rd=0`。
该 funct3 不选择矩阵 unit；矩阵仍只有 unit=0/1/2。所有未列出的 funct7 或非零 rd 非法。
不占用 custom-0，不改变精确 HALT 字 `0x0000000B`，不与向量编码重叠。

| 指令 | funct7 | rs1 | rs2 | 语义 |
| --- | --- | --- | --- | --- |
| `DSETSIZE rsLen, rsRows` | `0x10` | 每行字节数 | 行数 | 更新 rowBytes、rows |
| `DSETSTRIDE rsExtStride, rsLocalStride` | `0x11` | DDR 行首间距（字节） | DTCM 行首间距（字节） | 更新两侧 stride |
| `MVIN rsExt, rsLocal` | `0x12` | DDR 首地址 | DTCM 首地址 | DDR → DTCM |
| `MVOUT rsExt, rsLocal` | `0x13` | DDR 首地址 | DTCM 首地址 | DTCM → DDR |

所有输入均是完整 UINT32，均无寄存器结果；`rs1/rs2=x0` 按普通零源处理。
机器字计算：`(funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (3 << 12) | 0x2B`。

配置寄存器复位为零；HALT、START、ACK 和故障清理不清配置。软件宜显式配置每次任务。
两个配置指令均为控制屏障：阻止年轻派发，在 ROB 队首、源操作数就绪后更新，并在成功提交后
从 PC+4 恢复。实现复用 MemQ 保存两个捕获的 scalar operand，配置指令本身不访问 SRAM/DDR。
参数合法性在 MVIN/MVOUT 启动前统一检查，配置为零本身不产生故障。

## 3. 二维传输与预检查

第 r 行（`0 <= r < rows`）：

```text
DDR   [external + r*externalStride, external + r*externalStride + rowBytes)
DTCM  [local    + r*localStride,    local    + r*localStride    + rowBytes)
```

- `rowBytes > 0`、`rows > 0`。零长度/零行数报配置故障，不作为 NOP。
- 两侧首地址、rowBytes 必须 4 B 对齐；rows>1 时两侧 stride 也必须 4 B 对齐。
  不要求 64 B 对齐，可搬运一行中的部分 word，byte mask 保留其他 SRAM 字节。
- stride 是非负**行首间距**，不是行间空洞；允许零 stride 和行间重叠，严格按行序执行。
  单行时 stride 忽略。紧密二维布局取 stride=rowBytes。
- 用 66-bit 算术检查 `base+(rows-1)*stride+rowBytes`；外部上界不得超过 `2^32`，
  本地上界不得超过 DTCM_BYTES。不允许加法/乘法溢出后回绕。整个命令预检查通过前不发任何请求。
- 不检查系统 DDR 容量/访问权限，这由外部 AXI 从设备通过 RRESP/BRESP 报错。

## 4. 顺序、背压及完成

MVIN/MVOUT 在派发时同时预留 ROB 和 MemQ，捕获两地址源的值或完整生产者标签。
配置屏障保证任务使用的 shape 不会被后续配置覆盖；引擎接受命令时复制全部字段。
不得在执行阶段重读架构寄存器。只有 MemQ 队首且 ROB 队首授权后才向引擎提交命令。
整个二维命令占用同一 active MemQ 项，年轻 scalar/vector/matrix/DMA 访存不得穿越。
独立计算可以执行并缓冲，但不得越过 DMA 提交。

- MVIN：发 AR 前预留整个 burst 的数据空间；收齐并检查所有 R 后，才以 mask 写入 DTCM；
  等待 SRAM 写响应后计入已完成字节数。发生 R 错误的整个 burst 不写 DTCM。
- MVOUT：先读 DTCM 并缓冲，然后发 AW、全部 W，最后等 B；成功 B 后才计入已完成字节数。
  AW 被接受后，即使外部响应将报错，也必须发送完整的 W 序列。
- AR/AW/W 及本地请求在 VALID 且未 READY 时保持稳定。命令和完成记录支持背压。
- FENCE、MFENCE、MBARRIER、HALT、控制转移和故障清理均经 MemQ 的 empty/quiescent 检查，
  其中显式包含 DMA busy，不仅检查队列 valid。HALT 不能在等待 B 时宣布 DONE。
- 每个任务的 ROB tag 留在 MemQ 队首，完成通过既有 CDB 带该 tag 回传；AXI ID 不替代 ROB tag。

## 5. 故障与可观测状态

新增本地 fault cause（不是 RISC-V mcause）：

| cause | 含义 | FAULT_ADDR |
| --- | --- | --- |
| 12 | DMA 参数/对齐/全命令范围错误 | 本地错误优先报本地首地址，否则外部首地址 |
| 13 | DMA 读错误 | DDR RRESP 错误的 beat 地址，或本地读地址 |
| 14 | DMA 写错误 | DDR BRESP 错误的 burst 首地址，或本地写地址 |
| 15 | DMA AXI 协议错误 | RID/RLAST 错误的 beat 地址，或 BID 错误的 burst 首地址 |

任意非 OKAY 的 RRESP/BRESP 均报错，包括不应出现的 EXOKAY。
同一读 beat 同时协议/响应错误时协议错误优先；之后保留第一个错误及其地址。

错误后不再发新 burst；已接受 AR 的响应继续排空，已接受 AW 的 W/B 继续完成。
ARLEN 是最少排空 beat 数；早到 RLAST 记录协议错误但不提前释放，预期最后一拍无 RLAST
时继续丢弃响应直到收到 RLAST。对永久不返回数据、缺失响应或严重违约导致无法排空的外设，
硬件不会伪造成功/超时取消，BUSY 保持，必须由系统级总线恢复处理。全局复位须与互连/DDR
事务复位协同；不能仅复位 NPU 后让旧响应进入新的命令。

完全排空后，失败命令向 ROB 报异常；MemQ 阻止后续请求，等待正常故障清理。
FAULT_PC/FAULT_INST 指向 MVIN/MVOUT。故障指令不提交，年轻寄存器/存储不提交，但 DMA
已经完成的写入不会回滚。ACK 后从 PC=0 重新启动，不支持从失败 burst 自动续传。

新增 AXI-Lite 只读 CSR：

| 偏移 | 内容 |
| --- | --- |
| `0x34` | bit[3:0] DMA first cause；bit4 PARTIAL；其余零 |
| `0x38` | DMA first error address |
| `0x3C` | DMA 已确认成功字节数低 32 bit |
| `0x40` | DMA 已确认成功字节数高 32 bit |

相同结构也由顶层 `io.dmaStatus` 输出。状态在新命令进入活动记录时清零，完成/ACK 后保留直到
下一命令或复位。PARTIAL 仅在故障时置位：此前已有成功 DTCM 写，或已接受 DDR AW（即可能有
不可回滚的外部副作用）。失败的 DDR burst 不计入成功字节数，即使部分/全部 W 可能已写入。
成功命令 PARTIAL=0。读取运行中的两个计数半字不是原子快照；软件应在 BUSY=0 后读取。

## 6. 示例

```asm
ADDI       x1, x0, 128
ADDI       x2, x0, 2
DSETSIZE   x1, x2         # 两行，每行 128 B
ADDI       x3, x0, 256
DSETSTRIDE x3, x1         # DDR 行首间隔 256 B，DTCM 紧密 128 B
LUI        x10, 0x80000   # DDR = 0x80000000（示例，取决于 SoC 映射）
ADDI       x11, x0, 0     # DTCM = 0
MVIN       x10, x11
# 此后 scalar/vector/matrix 可以直接使用 DTCM 数据，无需轮询 DMA CSR
LUI        x10, 0x80001
MVOUT      x10, x11
FENCE
HALT
```

当前项目无汇编器扩展，可按 §2 公式使用 `.word`。

## 7. 实现与验证入口

- `src/main/scala/device/DmaEngine.scala`：命令/数据缓冲、预检查、burst、AXI 和错误排空。
- `src/main/scala/xiangjiang/mem/LSU.scala`：配置、源依赖、ROB-head 授权、整命令完成/故障。
- `src/main/scala/device/MemorySubsystem.scala`：宽端口仲裁和真实 DTCM 访问。
- `src/main/scala/top/XiangJiangNPU.scala`：DDR master 及 CSR 集成。
- `DmaEngineSpec`：双向随机传输、2D、非 64 B 对齐、4 KiB 边界、burst 上限 1/7/256、
  背压、全范围溢出、R/B/本地错误、RID/BID/RLAST、完成保持和后续命令。
- `DmaDecodeSpec` / `DmaOrderingSpec`：编码保留位、源依赖唤醒、完整 ROB tag 授权、命令/完成背压、
  年轻访存阻塞、独立 busy 检查及错误清理。
- `MemorySpec`：scalar/matrix/DMA 端口冲突、响应归属、写掩码、范围错误及 DMA 状态 CSR。
- `DmaSystemSpec`：主机加载程序、MVIN→LW/SW→MVOUT、地址版本捕获、等待 B 的 FENCE/HALT、
  精确故障、年轻写抑制、CSR 和 ACK/restart。

```bash
./mill xj.test.testOnly xiangjiang.DmaEngineSpec xiangjiang.DmaSystemSpec
./mill xj.test
./mill xj.runMain top.Generate generated
```
