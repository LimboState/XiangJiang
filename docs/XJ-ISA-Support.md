# XiangJiang NPU 支持指令总表

本表以 [XJ-NPU 架构](XJ-NPU.md) 为目标，按当前 RTL 的机器译码与执行路径整理。
**每条指令一行，所有分类合并在同一张表中。**

- **已接入**：存在机器译码与执行/提交路径，不代表全部标准边界均已验证。
- **部分**：存在路径，但有已知编码、数值或状态语义偏差，限制见表中说明。
- 仅有内部 FU 分支、机器编码不可达的操作，以及未实现、已移除的指令，不列入支持清单。
- `xd/xs`、`fd/fs`、`vd/vs` 分别表示 X、F、V 寄存器；`vm` 为自定义 mask 寄存器，`u` 为矩阵单元编号 0/1/2。
- `op/f3/f7` 分别为 opcode、inst[14:12]、inst[31:25]；`b25` 为 inst[25]。编码字段用 `0x` 十六进制，VecOp 编号用十进制。
- 指令长 32 bit，PC 4 B 对齐；XLEN=FLEN=32，VLEN=DLEN=128，ELEN=32。普通数据访存访问 DTCM，外存访问经 DMA。
- 自定义向量助记符用于本文标识，不表示标准 RVV 二进制兼容或已有汇编器支持；`[,vm]` 表示可选 mask。

| 分类 | 指令 | 操作数 | 功能 | 编码 / 内部编号 | 当前支持状态与限制 |
| --- | --- | --- | --- | --- | --- |
| RV32I·算术 | `LUI` | `xd,imm20` | xd=imm20<<12 | op=0x37，U 型 | 已接入 |
| RV32I·算术 | `AUIPC` | `xd,imm20` | xd=PC+(imm20<<12) | op=0x17，U 型 | 已接入 |
| RV32I·算术 | `ADDI` | `xd,xs1,imm12` | 加符号扩展立即数 | op=0x13，f3=0x0 | 已接入；结果取低 32 位 |
| RV32I·比较 | `SLTI` | `xd,xs1,imm12` | 有符号小于，返回 0/1 | op=0x13，f3=0x2 | 已接入 |
| RV32I·比较 | `SLTIU` | `xd,xs1,imm12` | 立即数符号扩展后作无符号小于比较 | op=0x13，f3=0x3 | 已接入 |
| RV32I·逻辑 | `XORI` | `xd,xs1,imm12` | 按位异或 | op=0x13，f3=0x4 | 已接入 |
| RV32I·逻辑 | `ORI` | `xd,xs1,imm12` | 按位或 | op=0x13，f3=0x6 | 已接入 |
| RV32I·逻辑 | `ANDI` | `xd,xs1,imm12` | 按位与 | op=0x13，f3=0x7 | 已接入 |
| RV32I·移位 | `SLLI` | `xd,xs1,shamt` | 逻辑左移 | op=0x13，f3=0x1，f7=0x00 | 已接入；shamt 为 5 bit |
| RV32I·移位 | `SRLI` | `xd,xs1,shamt` | 逻辑右移 | op=0x13，f3=0x5，f7=0x00 | 已接入 |
| RV32I·移位 | `SRAI` | `xd,xs1,shamt` | 算术右移 | op=0x13，f3=0x5，f7=0x20 | 已接入 |
| RV32I·算术 | `ADD` | `xd,xs1,xs2` | 加法 | op=0x33，f3=0x0，f7=0x00 | 已接入；结果取低 32 位 |
| RV32I·算术 | `SUB` | `xd,xs1,xs2` | 减法 | op=0x33，f3=0x0，f7=0x20 | 已接入；结果取低 32 位 |
| RV32I·移位 | `SLL` | `xd,xs1,xs2` | 逻辑左移 | op=0x33，f3=0x1，f7=0x00 | 已接入；移位量 xs2[4:0] |
| RV32I·比较 | `SLT` | `xd,xs1,xs2` | 有符号小于 | op=0x33，f3=0x2，f7=0x00 | 已接入 |
| RV32I·比较 | `SLTU` | `xd,xs1,xs2` | 无符号小于 | op=0x33，f3=0x3，f7=0x00 | 已接入 |
| RV32I·逻辑 | `XOR` | `xd,xs1,xs2` | 按位异或 | op=0x33，f3=0x4，f7=0x00 | 已接入 |
| RV32I·移位 | `SRL` | `xd,xs1,xs2` | 逻辑右移 | op=0x33，f3=0x5，f7=0x00 | 已接入；移位量 xs2[4:0] |
| RV32I·移位 | `SRA` | `xd,xs1,xs2` | 算术右移 | op=0x33，f3=0x5，f7=0x20 | 已接入；移位量 xs2[4:0] |
| RV32I·逻辑 | `OR` | `xd,xs1,xs2` | 按位或 | op=0x33，f3=0x6，f7=0x00 | 已接入 |
| RV32I·逻辑 | `AND` | `xd,xs1,xs2` | 按位与 | op=0x33，f3=0x7，f7=0x00 | 已接入 |
| RV32I·访存 | `LW` | `xd,off(xs1)` | DTCM 读 32 bit | op=0x03，f3=0x2 | 已接入；4 B 对齐，写 x0 仍执行访问与异常检查 |
| RV32I·访存 | `SW` | `xs2,off(xs1)` | DTCM 写 32 bit | op=0x23，f3=0x2 | 已接入；4 B 对齐，MemQ Head+ROB Head 授权 |
| RV32I·分支 | `BEQ` | `xs1,xs2,off` | 相等时 PC+=off | op=0x63，f3=0x0 | 已接入；检查选中的下一 PC |
| RV32I·分支 | `BNE` | `xs1,xs2,off` | 不等时 PC+=off | op=0x63，f3=0x1 | 已接入 |
| RV32I·分支 | `BLT` | `xs1,xs2,off` | 有符号小于时跳转 | op=0x63，f3=0x4 | 已接入 |
| RV32I·分支 | `BGE` | `xs1,xs2,off` | 有符号大于等于时跳转 | op=0x63，f3=0x5 | 已接入 |
| RV32I·分支 | `BLTU` | `xs1,xs2,off` | 无符号小于时跳转 | op=0x63，f3=0x6 | 已接入 |
| RV32I·分支 | `BGEU` | `xs1,xs2,off` | 无符号大于等于时跳转 | op=0x63，f3=0x7 | 已接入 |
| RV32I·跳转 | `JAL` | `xd,off` | xd=PC+4；PC+=off | op=0x6F | 已接入 |
| RV32I·跳转 | `JALR` | `xd,off(xs1)` | xd=PC+4；PC=(xs1+off)&~1 | op=0x67，f3=0x0 | 已接入；选中目标需 4 B 对齐且在 ITCM 内 |
| RV32I·屏障 | `FENCE` | `pred,succ` | 全局访存排序 | op=0x0F，f3=0x0 | 已接入；保守完整屏障，忽略细分域 |
| RV32I·环境 | `ECALL` | 无 | Host 服务事件 | 精确字 0x00000073 | 已接入；本地 cause=17，不置 Done，服务协议待冻结 |
| RV32I·环境 | `EBREAK` | 无 | Host 断点事件 | 精确字 0x00100073 | 已接入；本地 cause=16，不隐含标准 Debug Module |
| M·乘法 | `MUL` | `xd,xs1,xs2` | 乘积低 32 bit | op=0x33，f3=0x0，f7=0x01 | 已接入 MLU |
| M·乘法 | `MULH` | `xd,xs1,xs2` | 有符号×有符号乘积高 32 bit | op=0x33，f3=0x1，f7=0x01 | 已接入 MLU |
| M·乘法 | `MULHSU` | `xd,xs1,xs2` | 有符号×无符号乘积高 32 bit | op=0x33，f3=0x2，f7=0x01 | 已接入 MLU |
| M·乘法 | `MULHU` | `xd,xs1,xs2` | 无符号×无符号乘积高 32 bit | op=0x33，f3=0x3，f7=0x01 | 已接入 MLU |
| M·除法 | `DIV` | `xd,xs1,xs2` | 有符号商，向零截断 | op=0x33，f3=0x4，f7=0x01 | 已接入；除零商为全 1，溢出返回被除数 |
| M·除法 | `DIVU` | `xd,xs1,xs2` | 无符号商 | op=0x33，f3=0x5，f7=0x01 | 已接入；除零商为全 1 |
| M·余数 | `REM` | `xd,xs1,xs2` | 有符号余数 | op=0x33，f3=0x6，f7=0x01 | 已接入；除零返回被除数，溢出返回 0 |
| M·余数 | `REMU` | `xd,xs1,xs2` | 无符号余数 | op=0x33，f3=0x7，f7=0x01 | 已接入；除零返回被除数 |
| Zbb·计数 | `CLZ` | `xd,xs1` | 前导零数 | op=0x13，f3=0x1，f7=0x30，rs2=0 | 部分；当前缺少零输入返回 32 的处理 |
| Zbb·计数 | `CTZ` | `xd,xs1` | 尾随零数 | op=0x13，f3=0x1，f7=0x30，rs2=1 | 部分；当前缺少零输入返回 32 的处理 |
| Zbb·计数 | `CPOP` | `xd,xs1` | 置 1 位数 | op=0x13，f3=0x1，f7=0x30，rs2=2 | 已接入 |
| Zbb·逻辑 | `ANDN` | `xd,xs1,xs2` | xs1 AND NOT xs2 | op=0x33，f3=0x7，f7=0x20 | 已接入 |
| Zbb·逻辑 | `ORN` | `xd,xs1,xs2` | xs1 OR NOT xs2 | op=0x33，f3=0x6，f7=0x20 | 已接入 |
| Zbb·逻辑 | `XNOR` | `xd,xs1,xs2` | NOT(xs1 XOR xs2) | op=0x33，f3=0x4，f7=0x20 | 已接入 |
| Zbb·最值 | `MIN` | `xd,xs1,xs2` | 有符号最小值 | op=0x33，f3=0x4，f7=0x05 | 已接入 |
| Zbb·最值 | `MINU` | `xd,xs1,xs2` | 无符号最小值 | op=0x33，f3=0x5，f7=0x05 | 已接入 |
| Zbb·最值 | `MAX` | `xd,xs1,xs2` | 有符号最大值 | op=0x33，f3=0x6，f7=0x05 | 已接入 |
| Zbb·最值 | `MAXU` | `xd,xs1,xs2` | 无符号最大值 | op=0x33，f3=0x7，f7=0x05 | 已接入 |
| Zbb·扩展 | `SEXT.B` | `xd,xs1` | 低 8 bit 符号扩展 | op=0x13，f3=0x1，f7=0x30，rs2=4 | 已接入 |
| Zbb·扩展 | `SEXT.H` | `xd,xs1` | 低 16 bit 符号扩展 | op=0x13，f3=0x1，f7=0x30，rs2=5 | 已接入 |
| Zbb·扩展 | `ZEXT.H` | `xd,xs1` | 低 16 bit 零扩展 | op=0x33，f3=0x4，f7=0x04，rs2=0 | 已接入 |
| Zbb·旋转 | `ROL` | `xd,xs1,xs2` | 循环左移 xs2[4:0] | op=0x33，f3=0x1，f7=0x30 | 部分；当前错误拒绝 rs2 编号 0/1/2/4/5 |
| Zbb·旋转 | `ROR` | `xd,xs1,xs2` | 循环右移 xs2[4:0] | op=0x33，f3=0x5，f7=0x30 | 已接入 |
| Zbb·旋转 | `RORI` | `xd,xs1,shamt` | 循环右移立即数 | op=0x13，f3=0x5，f7=0x30 | 部分；当前错误拒绝 shamt=24 |
| Zbb·字节 | `ORC.B` | `xd,xs1` | 非零字节变为 0xFF | op=0x13，f3=0x5，f7=0x14，rs2=7 | 已接入 |
| Zbb·字节 | `REV8` | `xd,xs1` | 反转 4 字节顺序 | op=0x13，f3=0x5，f7=0x34，rs2=24 | 已接入 |
| Zicsr | `CSRRW` | `xd,csr,xs1` | 返回 CSR 旧值并写 xs1 | op=0x73，f3=0x1 | 已接入；ROB Head 执行，提交发布 |
| Zicsr | `CSRRS` | `xd,csr,xs1` | 返回旧值并按 xs1 置位 | op=0x73，f3=0x2 | 已接入；xs1=x0 时只读 |
| Zicsr | `CSRRC` | `xd,csr,xs1` | 返回旧值并按 xs1 清位 | op=0x73，f3=0x3 | 已接入；xs1=x0 时只读 |
| Zicsr | `CSRRWI` | `xd,csr,uimm5` | 返回旧值并写零扩展立即数 | op=0x73，f3=0x5 | 已接入 |
| Zicsr | `CSRRSI` | `xd,csr,uimm5` | 返回旧值并按立即数置位 | op=0x73，f3=0x6 | 已接入；uimm5=0 时只读 |
| Zicsr | `CSRRCI` | `xd,csr,uimm5` | 返回旧值并按立即数清位 | op=0x73，f3=0x7 | 已接入；uimm5=0 时只读 |
| Zifencei | `FENCE.I` | 无 | 提交后刷新取指状态 | op=0x0F，f3=0x1；规范字 0x0000100F | 已接入 |
| F·访存 | `FLW` | `fd,off(xs1)` | DTCM 32-bit 位模式→F | op=0x07，f3=0x2 | 已接入 MemQ；4 B 对齐 |
| F·访存 | `FSW` | `fs2,off(xs1)` | F 位模式→DTCM | op=0x27，f3=0x2 | 已接入 MemQ；4 B 对齐，Head 授权 |
| F·算术 | `FADD.S` | `fd,fs1,fs2,rm` | FP32 加法 | op=0x53，f7=0x00，f3=rm | 部分；定向溢出与消去零符号待修正 |
| F·算术 | `FSUB.S` | `fd,fs1,fs2,rm` | FP32 减法 | op=0x53，f7=0x04，f3=rm | 部分；定向溢出与消去零符号待修正 |
| F·算术 | `FMUL.S` | `fd,fs1,fs2,rm` | FP32 乘法 | op=0x53，f7=0x08，f3=rm | 部分；Inf×0 结果选择、定向溢出待修正 |
| F·融合运算 | `FMADD.S` | `fd,fs1,fs2,fs3,rm` | fs1×fs2+fs3，单次舍入 | op=0x43，fmt=0，f3=rm | 部分；fmt 未校验、特殊值与定向溢出待修正 |
| F·融合运算 | `FMSUB.S` | `fd,fs1,fs2,fs3,rm` | fs1×fs2−fs3，单次舍入 | op=0x47，fmt=0，f3=rm | 部分；fmt 未校验、特殊值与定向溢出待修正 |
| F·融合运算 | `FNMSUB.S` | `fd,fs1,fs2,fs3,rm` | −fs1×fs2+fs3 | op=0x4B，fmt=0，f3=rm | 部分；当前先舍入再取负，定向舍入有偏差；fmt 未校验 |
| F·融合运算 | `FNMADD.S` | `fd,fs1,fs2,fs3,rm` | −fs1×fs2−fs3 | op=0x4F，fmt=0，f3=rm | 部分；当前先舍入再取负，定向舍入有偏差；fmt 未校验 |
| F·除法 | `FDIV.S` | `fd,fs1,fs2,rm` | FP32 除法 | op=0x53，f7=0x0C，f3=rm | 部分；定向溢出与 flags 边界待完善 |
| F·开方 | `FSQRT.S` | `fd,fs1,rm` | FP32 平方根 | op=0x53，f7=0x2C，rs2=0，f3=rm | 部分；−Inf 结果选择待修正 |
| F·比较 | `FEQ.S` | `xd,fs1,fs2` | 相等→1，否则 0 | op=0x53，f7=0x50，f3=0x2 | 已接入；sNaN 置 NV |
| F·比较 | `FLT.S` | `xd,fs1,fs2` | 小于→1，否则 0 | op=0x53，f7=0x50，f3=0x1 | 部分；qNaN 未按标准置 NV |
| F·比较 | `FLE.S` | `xd,fs1,fs2` | 小于等于→1，否则 0 | op=0x53，f7=0x50，f3=0x0 | 部分；qNaN 未按标准置 NV |
| F·转换 | `FCVT.W.S` | `xd,fs1,rm` | FP32→有符号 INT32 | op=0x53，f7=0x60，rs2=0，f3=rm | 部分；无效/NX/边界需修正 |
| F·转换 | `FCVT.WU.S` | `xd,fs1,rm` | FP32→无符号 UINT32 | op=0x53，f7=0x60，rs2=1，f3=rm | 部分；错误使用 2^31 无符号上界 |
| F·转换 | `FCVT.S.W` | `fd,xs1,rm` | 有符号 INT32→FP32 | op=0x53，f7=0x68，rs2=0，f3=rm | 部分；FU 当前误读 fsrc1 而非 src1，不能依赖目标转换结果 |
| F·转换 | `FCVT.S.WU` | `fd,xs1,rm` | 无符号 UINT32→FP32 | op=0x53，f7=0x68，rs2=1，f3=rm | 部分；FU 当前误读 fsrc1 而非 src1，不能依赖目标转换结果 |
| F·搬运 | `FMV.X.W` | `xd,fs1` | F→X 原始位模式 | op=0x53，f7=0x70，rs2=0，f3=0 | 已接入；译码漏 f3=0 限制，FCLASS.S 会被误接收 |
| F·搬运 | `FMV.W.X` | `fd,xs1` | X→F 原始位模式 | op=0x53，f7=0x78，rs2=0，f3=0 | 已接入；译码漏 f3=0 限制 |
| Vector·配置 | `VSETVLI` | `xd,xs1,vtypei` | 设置 vl/vtype，xd 返回 vl | op=0x57，f3=0x7，bit31=0；vtypei=inst[30:20] | 部分；提交清 vstart；xs1=x0 的标准 AVL 特例未实现 |
| Vector·配置 | `VSETIVLI` | `xd,uimm5,vtypei` | 立即 AVL，设置 vl/vtype | op=0x57，f3=0x7，bits31:30=11；vtypei=inst[29:20] | 已接入；受文末配置限制 |
| Vector·配置 | `VSETVL` | `xd,xs1,xs2` | 从两个 X 源读取 AVL/vtype | op=0x57，f3=0x7，bits31:25=0x40 | 部分；只检查 vtype 源低 11 位，AVL 特例未实现 |
| 自定义 Vector·整数 | `VADD.vv` | `vd,vs2,vs1[,vm]` | 逐元素加法 | op=0x57，f3=0x0，b25=0；VecOp=0 | 已接入；SEW 8/16/32 |
| 自定义 Vector·整数 | `VADD.vx` | `vd,vs2,xs1[,vm]` | 逐元素加标量 | op=0x2F，f3=0x0，b25=0；VecOp=1 | 已接入；SEW 8/16/32 |
| 自定义 Vector·整数 | `VMIN.vv` | `vd,vs2,vs1[,vm]` | 逐元素有符号最小值 | op=0x57，f3=0x1，b25=0；VecOp=8 | 已接入 |
| 自定义 Vector·整数 | `VMIN.vx` | `vd,vs2,xs1[,vm]` | 与标量取有符号最小值 | op=0x2F，f3=0x1，b25=0；VecOp=9 | 已接入 |
| 自定义 Vector·移位 | `VSLL.vv` | `vd,vs2,vs1[,vm]` | 逐元素逻辑左移 | op=0x57，f3=0x2，b25=0；VecOp=16 | 已接入 |
| 自定义 Vector·移位 | `VSLL.vx` | `vd,vs2,xs1[,vm]` | 按标量逻辑左移 | op=0x2F，f3=0x2，b25=0；VecOp=17 | 已接入 |
| 遗留 Vector·BF16 | `VFSUB.BF16.vv` | `vd,vs2,vs1[,vm]` | BF16 减法 | op=0x57，f3=0x3，b25=0；VecOp=24 | 遗留已接入；固定 8×16-bit，不按 vl 控制活动元素 |
| 遗留 Vector·BF16 | `VFSUB.BF16.vx` | `vd,vs2,xs1[,vm]` | BF16 减标量 | op=0x2F，f3=0x3，b25=0；VecOp=25 | 遗留已接入；X 源低 16 位作 BF16 |
| 遗留 Vector·BF16 | `VFMAX.BF16.vv` | `vd,vs2,vs1[,vm]` | BF16 最大值 | op=0x57，f3=0x4，b25=0；VecOp=32 | 遗留已接入；固定 8×16-bit |
| 遗留 Vector·BF16 | `VFMAX.BF16.vx` | `vd,vs2,xs1[,vm]` | BF16 与标量取最大值 | op=0x2F，f3=0x4，b25=0；VecOp=33 | 遗留已接入；固定 8×16-bit |
| 遗留 Vector·BF16 | `VFMIN.BF16.vv` | `vd,vs2,vs1[,vm]` | BF16 最小值 | op=0x2B，f3=0x4，b25=0；VecOp=34 | 遗留已接入；固定 8×16-bit |
| 自定义 Vector·比较 | `VMSLE.vv` | `vd,vs2,vs1[,vm]` | 有符号小于等于，写 mask 位 | op=0x57，f3=0x5，b25=0；VecOp=40 | 部分；被误归入固定 16-bit 遗留路径，忽略 vl |
| 自定义 Vector·比较 | `VMSLE.vx` | `vd,vs2,xs1[,vm]` | 有符号小于等于标量，写 mask 位 | op=0x2F，f3=0x5，b25=0；VecOp=41 | 部分；被误归入固定 16-bit 遗留路径，忽略 vl |
| 自定义 Vector·比较 | `VMSLTU.vx` | `vd,vs2,xs1[,vm]` | 无符号小于标量，写 mask 位 | op=0x2B，f3=0x5，b25=0；VecOp=43 | 部分；被误归入固定 16-bit 遗留路径，忽略 vl |
| 遗留 Vector·BF16 比较 | `VMFLT.BF16.vv` | `vd,vs2,vs1[,vm]` | BF16 小于，写 mask 位 | op=0x57，f3=0x6，b25=0；VecOp=48 | 遗留已接入；固定 8×16-bit |
| 遗留 Vector·BF16 比较 | `VMFLT.BF16.vx` | `vd,vs2,xs1[,vm]` | BF16 小于标量，写 mask 位 | op=0x2F，f3=0x6，b25=0；VecOp=49 | 遗留已接入；固定 8×16-bit |
| 自定义 Vector·重排 | `VSLIDEUP` | `vd,vs2,xs1[,vm]` | 上移元素 | op=0x2B，f3=0x6，b25=0；VecOp=52 | 部分；低端填 0，而非标准保留旧值 |
| 自定义 Vector·重排 | `VRGATHER` | `vd,vs2,vs1[,vm]` | 按 vs1 元素索引读取 vs2 | op=0x2F，f3=0x7，b25=0；VecOp=57 | 部分；≥16 索引先改为 0，越界语义待修正 |
| 自定义 Vector·访存 | `VLE32` | `vd,off5(xs1)[,vm]` | DTCM 连续读 32-bit 元素 | op=0x57，f3=0x0，b25=1；VecOp=64 | 已接入 MemQ；要求 SEW=32，off5 为 −16..15 字节 |
| 自定义 Vector·访存 | `VSE32` | `vd,off5(xs1)[,vm]` | vd 连续写 DTCM | op=0x2F，f3=0x0，b25=1；VecOp=65 | 已接入；SEW=32，MemQ Head+ROB Head 授权 |
| 自定义 Vector·FP32 | `VFADD32.vv` | `vd,vs2,vs1[,vm]` | FP32 加法 | op=0x57，f3=0x1，b25=1；VecOp=72 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·FP32 | `VFADD32.vf` | `vd,vs2,fs1[,vm]` | FP32 加 F 标量 | op=0x2F，f3=0x1，b25=1；VecOp=73 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·FP32 | `VFMACC32.vv` | `vd,vs2,vs1[,vm]` | vd_old+vs2×vs1 | op=0x57，f3=0x2，b25=1；VecOp=80 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·FP32 | `VFMACC32.vf` | `vd,vs2,fs1[,vm]` | vd_old+vs2×fs1 | op=0x2F，f3=0x2，b25=1；VecOp=81 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·FP32 比较 | `VFEQ32.vv` | `vd,vs2,vs1[,vm]` | FP32 相等，写 mask 位 | op=0x57，f3=0x3，b25=1；VecOp=88 | 部分；要求 SEW=32，缺 fflags |
| 自定义 Vector·FP32 比较 | `VFEQ32.vf` | `vd,vs2,fs1[,vm]` | FP32 等于 F 标量，写 mask 位 | op=0x2F，f3=0x3，b25=1；VecOp=89 | 部分；要求 SEW=32，缺 fflags |
| 自定义 Vector·转换 | `VFCVT.I2F32` | `vd,vs2[,vm]` | INT32→FP32 | op=0x57，f3=0x4，b25=1；VecOp=96 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·转换 | `VFCVT.U2F32` | `vd,vs2[,vm]` | UINT32→FP32 | op=0x2F，f3=0x4，b25=1；VecOp=97 | 部分；要求 SEW=32，缺 frm/fflags |
| 自定义 Vector·归约 | `VFREDSUM32` | `xd,vs2[,vm]` | FP32 归约求和，返回 X 位模式 | op=0x2B，f3=0x4，b25=1；VecOp=98 | 部分；初值/末尾 inactive 元素合并需修正，缺 frm/fflags |
| Xxjmatrix·配置 | `MSETMODE` | `u,mode` | OS=0，WS=1 | op=0x2B，f3=u，f7=0；rs1=rd=0，inst[24:21]=0，inst[20]=mode | 部分；MU0 WS 拒绝，INT8 WS 只记录模式、未改变物理路由 |
| Xxjmatrix·装载 | `MLOAD.A` | `u,xs1` | DTCM tile→A staging→MASF | op=0x2B，f3=u，f7=0x04，rs2=rd=0 | 已接入；tile 至少 64 B 对齐，Head 提交发布 |
| Xxjmatrix·装载 | `MLOAD.B` | `u,xs1` | DTCM tile→B staging→MASF | op=0x2B，f3=u，f7=0x05，rs2=rd=0 | 已接入；tile 至少 64 B 对齐，Head 提交发布 |
| Xxjmatrix·装载 | `MLOAD.ACC` | `u,xs1` | DTCM tile→ACC staging→MASF | op=0x2B，f3=u，f7=0x06，rs2=rd=0 | 已接入；MU0 外部 BF16→内部 FP32，MU1/2 原样 INT32 |
| Xxjmatrix·计算 | `MMUL` | `u` | 清工作初值，ACC=A×B | op=0x5B，f3=u，f7=0x00，rs1=rs2=rd=0 | 已接入；结果 staging，Head 提交发布 |
| Xxjmatrix·计算 | `MMACC` | `u` | ACC=committed ACC+A×B | op=0x5B，f3=u，f7=0x01，rs1=rs2=rd=0 | 已接入；读取 committed A/B/ACC |
| Xxjmatrix·计算 | `MEW` | `u` | ACC[i,j]=A[i,j]×B[i,j] | op=0x5B，f3=u，f7=0x02，rs1=rs2=rd=0 | 已接入；BF16 乘积先舍入再拓宽，INT8 乘积符号扩展 |
| Xxjmatrix·重排 | `MTRANS` | `u` | ACC[i,j]=转换(A[j,i]) | op=0x5B，f3=u，f7=0x03，rs1=rs2=rd=0 | 已接入；BF16 拓宽或 INT8 符号扩展 |
| Xxjmatrix·存储 | `MSTORE` | `u,xs1` | committed ACC→DTCM tile | op=0x2B，f3=u，f7=0x08，rs2=rd=0 | 已接入；MemQ Head+ROB Head 授权；MU0 RNE 转 BF16 |
| Xxjmatrix·屏障 | `MBARRIER` | `id5` | 等待更老 Matrix/DMA 任务 | op=0x7B，f3=0x2，id=inst[31:27]，inst[26:15]=0，rd=0 | 已接入；id 当前不影响范围 |
| Xxjmatrix·屏障 | `MFENCE` | 无 | Matrix/DMA 访存完成与可见性排序 | 精确字 0x0000107B | 已接入 MemQ |
| Xxjdma·配置 | `DSETSIZE` | `xs1,xs2` | row_bytes=X[xs1]，rows=X[xs2] | op=0x2B，f3=0x3，f7=0x10，rd=0 | 已接入；配置屏障，在 ROB Head 经 MemQ 更新 |
| Xxjdma·配置 | `DSETSTRIDE` | `xs1,xs2` | external_stride=X[xs1]，local_stride=X[xs2] | op=0x2B，f3=0x3，f7=0x11，rd=0 | 已接入；配置屏障，在 ROB Head 经 MemQ 更新 |
| Xxjdma·传输 | `MVIN` | `xs1,xs2` | 外存 X[xs1]→DTCM X[xs2] | op=0x2B，f3=0x3，f7=0x12，rd=0 | 已接入；MemQ Head+ROB Head 授权，完成或错误排空后结束 |
| Xxjdma·传输 | `MVOUT` | `xs1,xs2` | DTCM X[xs2]→外存 X[xs1] | op=0x2B，f3=0x3，f7=0x13，rd=0 | 已接入；Head 授权，等待全部 B 响应 |
| 自定义·执行环境 | `HALT` | 无 | 顺序提交后停止运行并置 Done | 精确字 0x0000000B | 已接入；其他 custom-0 字不作为 HALT |

## 共用限制与说明

1. **支持口径**：表中包含已接入和部分实现的指令；“部分”不是完整支持或合规承诺。
   `rv32im_zicsr_zifencei_zbb` 是架构目标，当前不能声明完整 RV32I、Zbb、F 或 Zve32f 兼容。
   LB/LH/LBU/LHU/SB/SH 尚未实现；标准 F 的 FSGNJ/FMIN/FMAX/FCLASS 等未选入。
   MWAIT 已移除。NOP/MV/RET 等伪指令、CSR 地址与 Host MMIO 操作不算独立机器指令。

2. **标量浮点**：rm=0/1/2/3/4 分别为 RNE/RTZ/RDN/RUP/RMM，rm=7 使用 frm 快照，
   rm=5/6 在带 rm 的指令中非法。候选 `fflags={NV,DZ,OF,UF,NX}` 提交时合并，不自动 trap。
   当前溢出统一选择 Infinity，特殊值及定向舍入仍有表列偏差；不能据有 rm/flags 通路
   就声明完整 IEEE/RISC-V 数值合规。R4 的 fmt=inst[26:25]；表内 fmt=0 是标准单精度编码要求。

3. **向量配置**：`VLMAX=LMUL×128/SEW`。当前有有效 VLMAX 表项的组合为：
   SEW=8 的 LMUL=1、1/2、1/4；SEW=16 的 LMUL=1、1/2；SEW=32 的 LMUL=1。
   SEW=8/LMUL=1/8、SEW=16/LMUL=1/4、SEW=32/LMUL=1/2 当前检查允许但 VLMAX 缺项，
   会得到 vl=0、vill=0，这是缺陷。LMUL>1 多寄存器展开未实现。不支持的其他组合置 vill；
   vill 或非零 vstart 下执行向量指令产生本地 cause=20/21，无可恢复 vstart 语义。
   vset* 在提交点更新 vl/vtype、清 vstart，xd=x0 仍更新配置。

4. **自定义向量编码**：`VecOp=64*b25+8*f3+group`，group 对 op=0x57/0x2F 分别为 0/1，
   对 op=0x2B 为 `2+(f3&3)` 且要求 f3≥4。0x57/f3=7 留给 vset*。
   inst[31:27]=vm，inst[26]=unmasked；inst[24:20]=vs2（访存时为 off5），
   inst[19:15]=vs1/xs1/fs1，inst[11:7]=vd/xd。group 与 f3 不独立，因此内部操作定义
   并不全可达。本表只列入 29 个可达 VecOp；VSUB、VMUL、VAND、VMERGE、VLE8/16、
   VSE8/16、VFDIV32、VFSQRT32 等仅有内部路径的操作不列为可执行指令。

5. **向量执行**：一般整数/FP32 路径保留 tail/mask 旧值；遗留 BF16 使用固定 8×16-bit，
   不按 vl 控制活动元素。当前 legacyBf16 范围误覆盖整数比较 36–45；SEW=8/16 取数
   也未统一截断到元素宽度。FP32 路径不消费 frm、不更新 fflags；归约初值/最终活动元素
   合并仍需修正。可选 mask 寄存器不是标准 RVV 固定 v0 协议。

6. **矩阵**：均为 8×8，MU0 A/B 与外部 ACC 为 BF16（每 tile 128 B），内部 ACC 为 FP32；
   MU1/2 A/B 为 Signed INT8（64 B），ACC 为 INT32（256 B）。tile 为 row-major，至少
   64 B 对齐，整个范围先预检查。BF16 每次乘积先 RNE 到 BF16 再精确拓宽参与 FP32 累加；
   INT8 乘积符号扩展，INT32 溢出回绕。矩阵不读 frm、不写 fflags，不直接写 X/F/V。
   MSETMODE 的 mode 占用 rs2 低位，不能同时要求整个 rs2=0；INT8 WS 当前仅记录模式。

7. **DMA**：所有参数为字节，rows/row_bytes 非零，基址/长度及多行 stride 按 4 B 对齐；
   整个命令先范围检查。一个 active command、一个 outstanding burst，32-bit 外存数据宽度、
   64 B 本地 buffer。已完成写入不回滚，错误需排空。当前 shape 直接取 LSU 的 dmaShape，
   依靠配置屏障与 MemQ 串行保持稳定，尚非每条 entry 独立保存派发快照。

8. **配套文档**：[CSR Map](XJ-CSR-MAP.md)、[Host 寄存器](XJ-HOST-REGISTERS.md)、
   [AXI/DMA 规范](XJ-AXI-DMA.md)。CSR 未知地址或写只读项报告本地 cause=18。
   Host Start/Ack、可选 Halt/Resume/Step 与 IRQ 不属于本表机器指令；HALT 指令不等于 Host HALT_REQ。

本次仅整理文档，不修改 RTL 或测试；支持状态来自源码静态核对，不是新增仿真或标准兼容认证。
