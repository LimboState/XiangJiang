# XJ-NPU AXI 与 DMA 规范

- **架构基准：** `docs/XJ-NPU.md` 4.6、7.3、7.4
- **状态：** 实现基线。

## 1. S_AXI_MEM（AXI4-Full Slave）

窗口（偏移；Host 系统基地址由 SoC 集成决定）：

| 偏移 | 映射 |
| --- | --- |
| 0x000000–0x03FFFF | 256 KiB ITCM |
| 0x040000–0x0FFFFF | Reserved |
| 0x100000–0x1FFFFF | 1 MiB DTCM |

- 只支持 32-bit 数据（AXSIZE=2）、FIXED/INCR/WRAP（WRAP 长度 2/4/8/16）、
  单 beat INCR len≤15；burst 不跨 4 KiB。锁、AXSIZE>2、未对齐、越界、
  Reserved 窗口访问返回 AXI error（RESP=2），高地址位绝不截断后访问 SRAM。
- Host 仅在 NPU 空闲（`hostIdle`）且旧事务排空时访问；Busy 期间访问报 error。
- 写：AW 与 W 独立到达，WSTRB 按字节执行；非法 WSTRB/WLAST 使对应 beat 丢弃
  且 B 粘性报 error（AWLEN 决定排空长度，已接受的更早合法 beat 不回滚）。
- 读：R 按序、保 ID；同周期冲突（单端口 ITCM、同 row DTCM 读写）显式串行化。
- ID：AXI 响应回显请求 ID；outstanding：写 1 笔、读 1 笔。

## 2. M_AXI_EXT（AXI4-Full Master，DMA）

- 32-bit 地址 / 32-bit 数据，AXLEN ≤ 15、AXSIZE=2、INCR、4 KiB 对齐不跨页；
  ID 固定 0。同时最多一个 outstanding burst。
- 本地通路 512-bit/64 B；DMA 缓冲 64 B。单次读 burst 的有效数据 ≤ 64 B：
  超过 64 B 的读被拆分为多个 burst，且每个 burst 完整成功并验证后才发布
  到 DTCM；错误读 burst 不写 DTCM。
- MVOUT 必须等待 AW/W 全部接受并收到全部 B 响应且 RESP=0 才算完成。
  已接受的 AXI 事务在 Flush/故障/复位路径中必须排空，不得丢弃。
- 完成与错误：first-error cause/address、已确认完成字节数
  （bytes_completed）、partial_side_effect 记录于 Host DMA Status 寄存器。

## 3. DMA 命令语义

- 配置（committed，ROB Head 更新）：`DSETSIZE` row_bytes/rows；
  `DSETSTRIDE` external_stride/local_stride。执行中的 MVIN/MVOUT 使用派发时
  捕获的配置快照，不重读寄存器。
- 完整命令预检查（乘加用 >32-bit 中间值防回绕）：rows>0、row_bytes>0、
  基地址与行长度 4 字节对齐、多行 stride 4 字节对齐、local 全范围在 DTCM、
  external 全范围在 32-bit 总线地址空间。预检查通过前不发任何请求。
- MVIN/MVOUT 在 ROB Head 获得一次性授权并占据 Head 至完成；错误后停止新
  burst 但排空已接受事务；已完成的 DTCM/DDR 写入不回滚，不提供事务级回滚。
- 程序同步：`MBARRIER`/`MFENCE`（MemQ 顺序项）或 `FENCE`。
