# XJ-NPU CSR Map

- **架构基准：** `docs/XJ-NPU.md` 5.4
- **状态：** 实现基线。未知地址读/写与只读 CSR 写入 → Illegal Instruction
  （cause=18 `illegalCsr`）。CSR 指令在 ROB Head 执行读改写，旧值写 rd，
  新状态在提交点发布；改变执行上下文的 CSR 写入是派发屏障。

| 地址 | 名称 | 复位 | 读 | 写 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 0x001 | `fflags` | 0 | 是 | 是 | 隐式 FP 异常在提交点按程序顺序 OR 合并 |
| 0x002 | `frm` | 0 | 是 | WARL 0..4（5..7 写为 0） | FP 派发快照；写入是屏障 |
| 0x003 | `fcsr` | 0 | 是 | 是 | = {frm, fflags}，frm 按 WARL |
| 0x008 | `vstart` | 0 | 是 | 是 | 写入是屏障；vset* 提交时清零 |
| 0x009 | `vxsat` | 0 | 是 | 是（bit0） | 当前无饱和指令，恒 0 |
| 0x00A | `vxrm` | 0 | 是 | WARL 0..3 | 当前指令集不消费 |
| 0x00F | `vcsr` | 0 | 是 | 是 | = {vxrm, vxsat} |
| 0xC20 | `vl` | 0 | 是 | 只读 | vset* 提交时发布 |
| 0xC21 | `vtype` | 0 | 是 | 只读 | bit31=vill；vset* 提交时发布 |
| 0xC22 | `vlenb` | 16 | 是 | 只读 | VLEN/8 |
| 0x7C0 | `xjarch` | 1 | 是 | 只读 | 架构版本 |
| 0x7C1 | `xjcap` | 3 | 是 | 只读 | capability 位图：bit0=标量基线, bit1=向量选集 |
| 0x7C2 | `xjitcm` | 262144 | 是 | 只读 | ITCM 字节数 |
| 0x7C3 | `xjdtcm` | 1048576 | 是 | 只读 | DTCM 字节数 |

只读 CSR（0xC20/0xC21/0xC22/0x7C0–0x7C3）的任何写入（含 CSRRS/CSRRC 非零
源）报 Illegal Instruction；写入失败不产生任何副作用。

`CSRRW/CSRRS/CSRRC` 与立即形式 `CSRRWI/CSRRSI/CSRRCI` 均实现。`CSRRS/CSRRC`
以 rs1=x0（立即形式 uimm=0）只读。

派发屏障集合：`frm`、`fcsr`、`vstart`、`vxrm`、`vcsr` 的写入，以及所有
vset* 指令（提交点统一发布 vl/vtype/vstart）。
