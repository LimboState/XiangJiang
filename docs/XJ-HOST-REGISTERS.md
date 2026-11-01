# XJ-NPU Host 寄存器映射（S_AXI_CTRL，AXI4-Lite）

- **架构基准：** `docs/XJ-NPU.md` 7.2
- **状态：** V2 基线功能实现；Debug Halt/Resume/Step 与 IRQ 为建议项，不实现
  （对应 capability 为 0，保留地址返回 0）。

32-bit 数据、4 字节对齐访问；未对齐或越界地址返回 AXI error（RESP=2）。
AW/W 独立到达均支持；WSTRB 不覆盖 bit0 的命令不被触发。

| 偏移 | 名称 | 访问 | 说明 |
| --- | --- | --- | --- |
| 0x00 | Control | W1 | bit0=Start、bit1=Ack（事件确认）；Busy 时 Start、Busy 时 Ack 均报 error |
| 0x04 | Status | RO | bit0=Busy、bit1=Done、bit2=Fault |
| 0x08 | Fault PC | RO | 故障指令 PC |
| 0x0C | Fault Instruction | RO | 原始 32-bit 指令 |
| 0x10 | Fault Cause | RO | 5-bit cause（见 `FaultCode`） |
| 0x14 | Fault Address | RO | 相关地址 |
| 0x18 | ITCM Size | RO | 262144 |
| 0x1C | DTCM Size | RO | 1048576 |
| 0x20 | ROB Entries | RO | 实现参数 |
| 0x24 | Scalar Result Slots | RO | 实现参数 |
| 0x28 | MemQ Entries | RO | 实现参数 |
| 0x2C | Issue Entries | RO | 实现参数 |
| 0x30 | Architecture Version | RO | 1 |
| 0x34 | DMA Status | RO | bit5=partial、bits4:0=cause |
| 0x38 | DMA Error Address | RO | first error address |
| 0x3C | DMA Bytes Completed[31:0] | RO | 低 32 位 |
| 0x40 | DMA Bytes Completed[63:32] | RO | 高 32 位 |
| 0x100–0x17C | Debug X Registers | RO | x0–x31（仅运行期可读视图；调试窗口未实现） |

- 只读寄存器写入被忽略（WSTRB 有效的写返回 error）；命令寄存器（0x00）
  的写成功返回 RESP=0。
- Start：仅当 `!Busy && !Done && !Fault && hostIdle`；Ack：仅当 `!Busy`。
  Ack 清除 Done/Fault 与故障现场，然后可再次 Start。
- 执行入口固定为 Core PC 0（ITCM 0x0）；入口 PC 可配置寄存器未实现。
- 程序结束协议：仅 `HALT`（0x0000000B）置 Done；ECALL/EBREAK 产生精确事件
  （cause 17/16），不隐含 Done。
- Reset 期间或有在途 AXI 事务时不允许 Start（hostIdle 门控）。

## 调试边界（未实现）

Halt/Resume/Step 与架构状态调试窗口属于 `docs/XJ-NPU.md` 5.3 的建议项，
当前实现不提供，不对外声明 capability；故障处理状态机保证排空完成与
故障锁存是两个不同状态（`busy` 在排空期间保持，`fault` 在故障锁存时置位）。
