# 100 MHz 推理实现

## 目标与验证边界

目标器件 xczu9eg-ffvb1156-2，目标时钟 100 MHz（10 ns）。这是实际 RTL
流水化/迭代实现，不添加 false-path 或 multicycle 约束来掩盖运算路径。
**功能测试和 RTL 导出不能证明时序收敛，最终须以集成设计的 post-route 报告签核。**

原报告周期 10.001 ns、WNS -57.801 ns、数据路径 67.553 ns、379 级逻辑。
报告网络标注 unplaced，不能当作实际布线结果。最差路径穿过 Host start/flush、
九路 completion 仲裁、ROB wakeup、浮点 Issue 和完整组合浮点运算。
此前以 67.8/10 推算的多周期数不能直接用于本版本：现在逻辑已重新分段。

## RTL 修改

- `ScalarCore.scala`：completion 最老优先仲裁改为平衡锦标赛树，各节点携带
  valid/index/seq，去掉九级串联动态多路选择器；保留老指令优先，不引入饥饿。
- `IssueUnit.scala`：wakeup 先更新保留站寄存器，下一拍才参与选择；保留 dispatch
  同拍捕获广播，避免丢依赖。去掉写回→唤醒→选择→执行的同拍穿透。
- `FunctionalUnit.scala`：MLU 输入、乘积、结果寄存；DVU 32 步恢复除法，商/余数
  共享数据通路，保留除零、signed overflow 语义。复杂 FU 不依赖 resp.ready
  组合透传 req.ready；结果反压时保持，flush 取消在途任务。
- `VectorFloat.scala`：加减的对阶、加减、规格化、下溢移位分段；乘法的归一化输入、
  乘积、规格化、下溢移位分段。符号、舍入模式、特殊值和 flags 与数据同步延迟。
  原组合参考函数保留在源码用于测试；被裁剪的运算不再从顶层实例化。
- `VectorUnit.scala`：FP 元素等待流水结果；归约保留顺序累加，不改变求和顺序。
  修正请求接收时使用旧 uop 初始化归约及末尾 inactive 元素覆盖归约结果的问题。
- `MatrixUnit.scala`：BF16 乘积先舍入、再精确拓宽到 FP32 的语义不变；乘积与
  累加器都切流水。反馈累加每五拍接收一项，避免直接给递归累加器加寄存器后
  丢失依赖。INT8 保持原每拍推进。MEW 同样等待 BF16 乘法流水。
- `LSU.scala`：补齐 FSW 的 fsrc2/fsrc3 广播锁存，避免较慢 FP 生产者导致 Store 永久等待。
- `FloatCore` 的 FCVT.S.W/WU 改为读取 X 源；修正 FMUL.S 的 Inf×0 结果为 NaN。

## 周期与吞吐率

延迟口径：请求被接受的周期到 `resp.valid`，不含排队、写回反压、提交。

| 单元/操作 | 延迟 | 接收间隔/备注 |
| --- | ---: | --- |
| ALU/Branch | 1 | 无反压时 1；未改变 |
| MUL/MULH/MULHSU/MULHU | 3 | 保守单任务，最小间隔 4 拍 |
| DIV/DIVU/REM/REMU | 34 | 输入 1 + 迭代 32 + 收尾 1，最小间隔 35 拍 |
| 保留标量浮点 | 6 | 输入 1 + 四级内部流水 + 输出 1，最小间隔 7 拍 |
| 一般向量整数 | 17 | 输入 1 + 遍历 16 元素；原结构不变 |
| 向量 FP/归约 | 81 | 输入 1 + 16×5；含 inactive/tail 扫描 |
| INT8 矩阵 MMUL/MMACC | 22 compute clocks | 不含 prepare、staging、访存、提交 |
| BF16 矩阵 MMUL/MMACC | 116 compute clocks | 每 5 拍推进，最后两相位用于排空 |
| BF16 MEW/MTRANS | 320 elementwise clocks | 当前统一 64×5 保守节拍 |

流水深度不等于整个单元 II=1：目前 MLU/FloatCore 使用单任务控制，优先时序和正确性。
BF16 矩阵 compute 时间从 23 增至 116 拍，**这是明显性能取舍，不应宣称等吞吐优化**。
若后续需要恢复 BF16 吞吐，可优化浮点加法器以减小反馈延迟，或交错多个独立 tile；
不能简单并行部分和再相加，因为那会改变原有舍入顺序。

## 指令裁剪与二进制迁移

以下机器指令现在精确报告 Illegal Instruction，而不是悄悄转换为近似结果：

- 标量 `FDIV.S`、`FSQRT.S`、`FMADD.S/FMSUB.S/FNMSUB.S/FNMADD.S`。
- 向量浮点 DIV、SQRT、融合 MAC：内部编号 28/29/30/31/78/79/80/81/82。
  部分编号原本就无可达机器编码，现在连 FU 算术硬件也不再实例化。
- `MMUL/MMACC/MEW` 等矩阵指令、整数 M 扩展全部保留。

旧程序必须重新编译/改写。本项目没有自动 trap emulation，也没有交付完整的软件
数学库。通用 `-march=rv32imf` 编译器可能生成被移除操作，不能仅靠 `-ffp-contract=off`
保证兼容：还需自定义 lowering/软件例程，并检查最终反汇编。

为了让被移除的向量融合 MAC 能用基础指令替换，补充原来编码不可达的 FP32 减/乘：

| 操作 | opcode | funct3 | bit25 |
| --- | --- | --- | --- |
| VFSUB32.vv / .vf | 0x7B | 4 | 0 / 1 |
| VFMUL32.vv / .vf | 0x7B | 5 | 0 / 1 |

其余字段沿用自定义向量格式：`vd=11:7, vs1/fs1=19:15, vs2=24:20,
 unmasked=26, maskReg=31:27`。不与矩阵 funct3≤3 冲突。

### 软件组合原则

- **非融合乘加**：`t=a*b; d=t+c`。可以降低为 FMUL+FADD 或 VFMUL32+VFADD32。
  必须为乘积使用临时目标，保留旧累加值和别名输入。mask/tail 配置保持一致。
  **两次舍入不等价于 FMA 的单次舍入**；需模型精度验证。精确 FMA 只能用经过验证的
  软件精确例程，不能宣称两条基本浮点指令逐位等价。
- **除法近似**：对有限非零、已归一化输入，用查表/多项式取得倒数初值，
  `r = r * (2 - x*r)` 迭代，再算 `a*r`。必须控制初值误差、缩放、溢出和次正规数。
- **RMSNorm/LayerNorm**：对有限正数用 rsqrt 初值，
  `r = r * (1.5 - 0.5*x*r*r)` 迭代；归一化直接乘 r，无需先 sqrt 再除法。
  `sqrt(x)≈x*r` 仅在正确处理域、零和特殊值后适用。
- Softmax 的 max-subtraction、指数近似、求和和最后乘倒数仍可用基本操作组合。
  倒数和 rsqrt 对推理很重要，裁剪的是昂贵的**精确硬件指令**，不是删除模型功能。

上述为 lowering 指导，不是误差上界承诺。模型/数据类型/输入范围不同，迭代次数和
异常处理必须单独验证；需要 IEEE 正确舍入或精确 fflags 时使用完整软件数学例程。

## Vivado 导出与签核

```bash
./mill -j 3 xj.test
./mill xj.runMain top.Elaborate generated/vivado 100000000
vivado -mode batch -source generated/vivado/package_ip.tcl
```

IP 包中的 FREQ_HZ 元数据不是时钟发生器或 create_clock 约束。重新打包/升级 BD IP，
确认综合使用新 `synthesis.f`，配置 PS PL_CLK0 或 Clock Wizard 为 100 MHz，重新运行
synthesis → opt → place → phys_opt → route。不要叠加第二个同源时钟定义。

打开 **routed** checkpoint 后执行：

```tcl
source scripts/check_timing_100mhz.tcl
```

验收要求：所有有效 setup/hold slack≥0，TNS/THS=0，pulse-width 无违例，无未约束
功能路径、无未布线网，复核 methodology 和原报告的 8 个 no-clock pins。
脚本输出 clocks、setup/hold 前 100 路径、timing summary、utilization、route status
和 methodology；脚本通过不替代其余报告的人工审查或板级测试。

若仍失败，提供新的 post-route top 100 **不同端点**路径，优先查看 rename/ROB 查询、
LSU 地址检查、FP 对阶和转换、矩阵 Store 转换等剩余路径；不要只重复看同一个
completion 寄存器的三个 bit，也不要用全模块 false-path/multicycle 掩盖问题。
