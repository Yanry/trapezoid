# Trapezoid SpinalHDL 实现说明

## 1. 项目目标

本项目参考论文 *Trapezoid: A Versatile Accelerator for Dense and Sparse Matrix Multiplications*，在 `src/main/scala` 中实现可参数化的 SpinalHDL 计算阵列。

当前版本重点实现论文中的 PE row 计算数据通路、稀疏交叉与路由、归约逻辑以及 PE row 本地存储。由于论文没有公开完整的指令格式、DMA 协议和 cache 控制协议，当前顶层以命令/响应 Stream 和本地存储端口作为调度器边界。

### Mode 与稀疏度原则

论文根据典型使用场景推荐 `DenseIp`、`TrIp`、`TrGt` 和 `TrGs` 对应不同的 sparsity level，但本实现不把该对应关系做成硬件限制：

- mode 始终由调用方显式指定；
- 硬件不会根据 density 自动替换或拒绝 mode；
- `DenseIp` 和 `TrIp` 都可以处理从全零到全稠密的 bitmask fiber；
- `TrGt` 和 `TrGs` 都可以接收任意数量的有效 Gustavson lane；
- 后续调度器可以把 density 作为性能选择依据，但不能改变各 mode 的功能可用性。

新增的 `TrIpTileController` 服务于 bitmask/IP 输入格式，因此支持 `DenseIp` 和 `TrIp`。`TrGt` 和 `TrGs` 继续通过通用 PE row 命令接口使用，后续实现其 tile 调度器时也必须保持与 density 解耦。

## 2. 当前已经完成的内容

### 2.1 参数化硬件配置

`TrapezoidConfig` 可以配置：

- 数据位宽和累加器位宽；
- fiber 的坐标范围和每次最多处理的 fiber 数；
- 每个 PE row 的 MAC lane 数；
- cache line 输入宽度和输出 lane 数；
- PE row 数量；
- 本地 buffer 的 bank 数和深度。

工程提供两套预定义配置：

- `TrapezoidConfig.small`：默认 4 个 PE row，每行 16 个 MAC，用于快速生成和仿真；
- `TrapezoidConfig.paper`：128 个 PE row，每行 128 个 MAC、16-word cache line，对应论文 Table I 的主要阵列尺寸。

### 2.2 统一的数据类型和流接口

已经定义以下硬件接口：

- bitmask 格式的压缩 fiber；
- TrIP 的多 A row、多 B column 输入；
- TrGT/TrGS 使用的驻留 A 非零元素和流式 B 元素；
- 带 `valid/ready` 背压的 PE row 命令和响应；
- 带 `(row, column)` 坐标的矩阵结果；
- effectual MAC 数量、输出数量、溢出状态和动态列数；
- 四 bank 本地 buffer 读写接口。

### 2.3 Multi-Fiber Intersection Unit

`MultiFiberIntersection` 对应论文 Fig. 11 和 Fig. 12 的核心功能：

1. 对最多 4 个 A fiber 和 4 个 B fiber 做两两 bitmask 交叉；
2. 找出所有 effectual computation；
3. 使用前缀计数将稀疏匹配压缩到连续 MAC lane；
4. 计算匹配元素在压缩 values 数组中的索引；
5. 生成 A/B fiber、坐标以及压缩值索引等路由信息；
6. 当 effectual computation 超过 MAC lane 数时产生 `overflow`。

当前实现完成了论文 MFIU 和 shift-unit 的等价逻辑功能。路由逻辑由 SpinalHDL 组合选择网络生成，还没有进一步映射成论文采用的 Benes network 物理结构。

### 2.4 动态 B 列打包

`DynamicColumnPacker` 会计算不同 B column 前缀产生的 effectual computation 数，并选择不会超过 PE row MAC 数量的最大列数。

PE row 响应会返回：

- `selectedColumns`：当前输入最多可以安全处理的 B column 数；
- `effectualMacs`：全部输入实际产生的有效乘法数量；
- `overflow`：当前命令是否超过 PE row 容量。

如果发生溢出，当前命令不会静默丢弃部分积，而是返回零个结果，由上层调度器减少 B column 数后重新发送。

### 2.5 PE row 计算数据通路

`TrapezoidPERow` 目前支持四个 mode：

| Mode | 论文推荐场景 | 当前实现行为 |
| --- | --- | --- |
| `DenseIp` | D x D | 使用全有效 bitmask，通过 IP 路径完成乘法和按输出坐标归约 |
| `TrIp` | MS x MS、MS x D | 多 fiber 交叉、压缩路由、并行乘法和按 A-row/B-column 归约 |
| `TrGt` | HS x HS | 根据 `aIndex` 路由 B 元素，并合并相同输出坐标的部分积 |
| `TrGs` | HS x MS、HS x D | 复用 Gustavson 路径，在多个 N 坐标上并行产生和归约部分积 |

表中的场景仅表示论文的数据流选择建议，不是接口约束。测试已经覆盖在非推荐 density 下显式运行这些 mode。

IP/TrIP 路径可以在一次命令中输出多个 `(row, column, value)`。Gustavson 路径可以在一个 cache-line 命令内合并相同坐标的部分积。

响应使用寄存器保存，并正确处理 `valid/ready` 背压；下游停顿时不会覆盖尚未消费的结果。

### 2.6 四 bank 本地 buffer

每个 PE row 实际例化一个 `BankedLocalBuffer`，具有：

- 4 个相互独立的 bank；
- 每个 bank 每周期一次掩码写；
- 每个 bank 每周期一次同步读；
- 读响应有效信号；
- 可配置的 bank 深度。

它可以作为 TrIP 输出 scatter buffer 或 TrGT 的 B-row FIFO。通用 `Trapezoid` 顶层仍开放本地 buffer 端口；`TrIpTileController` 已经自动将 PE 输出 scatter 到四 bank buffer，并按 row-major 顺序读回。

### 2.7 顶层阵列和 RTL 生成

`Trapezoid` 顶层按配置复制 PE row 和本地 buffer。每个 PE row 可以独立接受命令、产生响应和进行背压。

生成默认规模 Verilog：

```bash
sbt -batch 'runMain trapezoid.GenerateTrapezoid'
```

输出位于 `generated/`，当前包括：

- `Trapezoid.v`
- `TrapezoidPERow.v`
- `MultiFiberIntersection.v`
- `DynamicColumnPacker.v`
- `BankedLocalBuffer.v`

论文规模的生成入口为：

```bash
sbt -batch 'runMain trapezoid.GeneratePaperTrapezoid'
```

该配置规模很大，生成和后续综合需要较高的内存与运行时间。目前主要验证的是 small 配置，尚未完成 paper 配置的完整 elaboration 和综合评估。

生成带阶段二调度器的 bitmask/IP tile controller：

```bash
sbt -batch 'runMain trapezoid.GenerateTrIpTileController'
```

输出位于 `generated-tile/`。

### 2.8 矩阵级参考模型

`trapezoid.model.MatrixReference` 提供纯 Scala 正确性基线，包括：

- 有符号整数 dense matrix multiplication；
- dense row/column 与 bitmask fiber 的转换；
- dense matrix 与 CSR/CSC 的双向转换；
- 四种 mode 的统一数学语义入口。

参考模型明确规定四种 mode 都计算相同的矩阵乘法结果，sparsity 只影响调度效率，不影响正确性或 mode 是否可用。

### 2.9 TrIP tile 调度器

`TrIpTileController` 已完成阶段二的 tile 级闭环：

1. 通过带索引的 Stream 装载 A row fiber 和 B column fiber；
2. 通过 `start` 配置实际 row/column 数量和显式 mode；
3. 同时计算所有活动 PE row 的 B-column 容量并取全局最小值；
4. 当一个 B column 仍可能溢出时，自动缩小 A fiber group 并重放同一 B 前缀；
5. 原子地向所有 PE row 分发命令，避免部分 PE 提前接受批次；
6. 收集所有 PE 响应，并逐项 scatter 到控制器内的四 bank 结果 buffer；
7. 按 A/B 的载入索引以 row-major 顺序读回非零交叉结果；
8. 支持输出背压、稳定的 `endOfTile` 和保持型 `done`；
9. `clear` 在空闲时清除装载状态，在 busy 时执行同步 abort；若命令已经送入 PE，会先排空未决响应再回到空闲，避免污染下一次 tile；
10. 拒绝不属于 IP 路径的 mode 或超出 tile 容量的 row/column 数，并通过 `unsupportedMode` 报告配置错误；
11. 暴露当前 A offset、group size、B base 和规划列数，便于调试和性能计数。

本控制器处理一个 K 范围不超过 `fiberWidth` 的 tile。更大的 K 仍需要上层 K-tile 累加器。
同一 tile 内有效 A row tag 和 B column tag 必须分别唯一，控制器使用 tag 将 PE 结果映射回载入槽位。

## 3. 当前达到的效果

### 3.1 功能验证

当前共有 9 项自动测试，执行方式为：

```bash
sbt -batch test
```

已验证：

- TrIP 对多个压缩 fiber 的坐标交叉；
- 压缩 values 索引和 MAC lane 路由；
- 同一输出元素的多个部分积归约；
- 动态 B column 选择和 effectual MAC 统计；
- 超出 MAC 容量时的 overflow 行为；
- Gustavson 路径中相同 `(row, column)` 部分积的合并；
- DynamicColumnPacker 和四 bank 本地 buffer 的独立 elaboration；
- bitmask fiber、CSR 和 CSC 在多种 density 下的随机 round-trip；
- 四种 mode 在 0%、低、中、高和 100% density 下与统一参考结果一致；
- `DenseIp` 在稀疏输入上运行，以及 `TrIp` 在全稠密输入上运行；
- `TrGt` 和 `TrGs` 在不同有效 lane density 下显式运行；
- 完整 tile 的加载、全局列规划、A-group 拆分、scatter 和 row-major 读回；
- 非满 tile、全零 tile、负数输入和随机输出背压；
- busy 状态下的同步 abort 和未决 PE 响应排空。

当前测试结果为 9 项全部通过。

### 3.2 RTL 检查

默认配置已经完成 SpinalHDL elaboration，并成功生成 Verilog。生成结果通过：

```bash
verilator --lint-only --Wall -Wno-fatal generated/*.v
verilator --lint-only --Wall -Wno-fatal generated-tile/*.v
```

当前两个 RTL 目录都没有 lint error。剩余 warning 主要来自 SpinalHDL 生成的枚举调试字符串、未消费的调试/诊断信号和部分保留接口，不影响功能。

### 3.3 当前适用范围

当前版本适合：

- 验证论文四种数据流的 PE row 级计算和路由思路；
- 进行小规模稀疏矩阵输入的 RTL 仿真；
- 直接输入一个 bitmask/IP tile 并得到有序矩阵结果；
- 研究 MFIU、动态列打包和 merge/reduction 行为；
- 作为后续调度器、cache 层次和 FPGA/ASIC 综合工作的基础。

它目前还不是可以直接连接 HBM 并独立执行完整矩阵乘法的加速器系统。

## 4. 当前限制

### 4.1 调度器覆盖范围

`TrIpTileController` 已完成单个 bitmask/IP tile 的调度，但尚未实现：

- 跨多个 M/K/N tile 的全矩阵循环；
- K 超过 `fiberWidth` 时的跨 tile 部分和累加；
- TrGT 和 TrGS 的矩阵级 tile controller；
- 面向软件的寄存器组、描述符队列或指令译码器。

当前设计刻意不实现根据 density 自动选择 mode。未来即使增加启发式推荐器，也必须保留软件显式覆盖 mode 的能力。

### 4.2 缺少全局 cache 和 DMA

论文中的 4 个 cache cluster、每 cluster 32 bank、32 x 32 互连、HBM 请求和 cache refill 尚未实现。通用顶层的每 PE row 本地 buffer 仍由外部端口控制；TrIP tile controller 使用独立的四 bank 结果 buffer 完成自动 scatter 和有序读回。后续接入全局存储层次时，需要把该功能映射到分布式 PE buffer 和 cache 写回路径。

### 4.3 Gustavson 只完成命令内归约

当前 TrGT/TrGS 可以在任意 lane density 下合并单次输入中相同坐标的部分积，但尚未完成：

- 跨多个周期或多个 cache line 的 C 元素持续累加；
- TrGT 的 4 个独立 PE subrow/FIFO 调度；
- 按 N 坐标排序的完整 merge tree；
- 输出 row 完成后的有序写回；
- B row 缓存未命中和 refill 流程。

因此当前 TrGT/TrGS 是可验证的核心 datapath，而不是完整的矩阵级 Gustavson 执行引擎。

### 4.4 算术格式尚未达到论文 FP32 配置

当前 MAC 使用可参数化的有符号整数数据通路。`paper` 配置设置了 32-bit 输入和较宽累加器，但并不是 IEEE-754 FP32 运算。

要和论文的 32 TFLOP/s 配置进行严格对比，需要接入可综合 FP32 multiplier/adder/FMA，并定义 NaN、Inf、舍入和异常处理策略。

### 4.5 尚未获得性能和面积结论

目前完成的是功能 RTL 和小配置验证，尚未执行：

- paper 配置的完整 RTL 生成；
- FPGA place-and-route；
- ASIC synthesis；
- 时序、面积和功耗分析；
- 与论文 TPU、SIGMA、Flexagon 基线的周期级性能比较。

因此目前不能声称达到论文报告的 1 GHz、32 TFLOP/s 或性能/面积结果。

## 5. 下一步工作建议

建议按照以下顺序继续，先闭合功能，再进行规模和性能优化。

### 阶段 1：建立矩阵级正确性基线（已完成）

1. 实现纯 Scala 参考模型，支持 dense、bitmask sparse 和 CSR/CSC 输入；
2. 增加随机矩阵测试，把硬件输出与软件矩阵乘法逐元素比较；
3. 为 `DenseIp`、`TrIp`、`TrGt`、`TrGs` 分别建立端到端测试；
4. 增加负数、空 fiber、全满 fiber、输出碰撞和连续背压测试；
5. 使用随机 `valid/ready` 停顿检查 Stream 协议。

已完成参考模型、格式转换、随机 density 覆盖、四 mode 语义测试、负数/空输入以及 Stream 背压测试。当前验收测试全部通过。

### 阶段 2：完成 TrIP 调度器（已完成单 K tile）

1. 增加 tile 配置和 A fiber 装载状态；
2. 汇总所有 PE row 的动态列容量；
3. 自动选择本周期发送的 B column 数；
4. 实现 overflow 命令拆分或重放；
5. 将 PE row 结果自动 scatter 到本地 buffer；
6. 从本地 buffer 有序读取并写回输出 tile。

当前已经达到该验收标准：测试平台只装载 A/B fiber 并发送一次 `start`，控制器会自动完成列规划、必要的 A-group 拆分、PE 命令生成、结果 scatter 和有序读回。

阶段二仍有一个扩展项：增加 K-tile 累加器，使 K 大于 `fiberWidth` 的矩阵也能由同一控制器连续执行。

### 阶段 3：完成 TrGT/TrGS 执行引擎

1. 把 PE row 划分为最多 4 个可独立调度的 subrow；
2. 使用本地 buffer bank 实现 B-row FIFO；
3. 实现跨周期、跨 cache line 的部分和累加；
4. 实现带坐标比较器的有序 merge tree；
5. 增加 row 完成、flush 和有序写回协议；
6. 为 TrGS 实现 A value 广播和按 N 坐标的 B 分发。

验收标准：TrGT 和 TrGS 可以消费完整 CSR row，并直接输出完整、有序的 C row。

### 阶段 4：实现多级存储系统

1. 定义统一的 cache request/response 协议；
2. 实现参数化 cache bank 和地址映射；
3. 实现 PE row 到 cache bank 的仲裁与互连；
4. 实现论文中的 cluster 组织；
5. 增加 miss queue、refill 和写回；
6. 提供简单 DMA 或 AXI 接口连接外部存储器。

验收标准：四种数据流都只通过统一存储接口访问矩阵数据，并能在 bank 冲突和 cache miss 下正确停顿。

### 阶段 5：替换为目标算术单元

根据最终目标选择：

- FPGA 原型：使用厂商 DSP/FMA IP；
- ASIC：接入综合库支持的 FP32 multiplier/adder/FMA；
- 仅研究稀疏数据流：保留整数或定点运算，同时在报告中明确与论文 FP32 的区别。

替换后需要重新确定流水级数、累加器格式和 PE row 的响应延迟。

### 阶段 6：规模化和 PPA 优化

1. 先对 1、4、16 个 PE row 做综合；
2. 检查 MFIU 前缀逻辑和路由网络的关键路径；
3. 将当前组合路由逐步替换为流水化前缀网络和 Benes network；
4. 将归约链改为平衡、可切分的流水化 reduction tree；
5. 再尝试生成和综合 128 x 128 paper 配置；
6. 建立吞吐、利用率、面积、频率和存储流量统计。

验收标准：获得可复现的综合报告和周期级性能数据，再与论文结果进行定量比较。

## 6. 建议的近期任务

下一轮建议进入阶段三，优先实现 TrGT/TrGS 共用的跨周期坐标累加与有序 merge，再分别补充 TrGT subrow/FIFO 调度和 TrGS 的 A 广播/B 空间分发。与此同时可以给现有 `TrIpTileController` 增加 K-tile 累加，并随后补齐外层 M/N tile 循环，形成第一个支持任意 M/K/N 尺寸的完整矩阵执行路径。

所有后续控制器都应继续遵守本文的 mode 与稀疏度原则：density 可以参与性能推荐，但硬件接口必须允许调用方在任意 sparsity level 下显式选择任何 mode。
