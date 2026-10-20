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

`TrIpTileController` 服务于 bitmask/IP 输入格式，因此支持 `DenseIp` 和 `TrIp`；`GustavsonTileEngine` 同时支持 `TrGt` 和 `TrGs`。两个控制器都只检查输入格式是否匹配，不检查或推断 density。同一个多 K/多命令序列也允许调用方逐次显式选择兼容的 mode。

## 2. 当前已经完成的内容

### 2.1 参数化硬件配置

`TrapezoidConfig` 可以配置：

- 数据位宽和累加器位宽；
- fiber 的坐标范围和每次最多处理的 fiber 数；
- 每个 PE row 的 MAC lane 数；
- cache line 输入宽度和输出 lane 数；
- PE row 数量；
- 本地 buffer 的 bank 数和深度；
- 每个本地 buffer bank 的 line word 数；
- PE row 响应 FIFO 深度 `responseFifoDepth`。

工程提供两套预定义配置：

- `TrapezoidConfig.small`：默认 4 个 PE row，每行 16 个 MAC，用于快速生成和仿真；
- `TrapezoidConfig.paper`：128 个 PE row，每行 128 个 MAC、16-word cache line、16 个归约结果槽，对应论文 Table I 的主要阵列尺寸。16 个结果槽覆盖 TrIP 的 `4 x 4` 输出矩阵，也覆盖 TrGS 每周期最多 16 个 cache-line 元素；它不等于 128 个 MAC lane。Gustavson 整 tile 累加表容量与 response 宽度解耦，paper 默认仍为 128 项，可保存一条完整 C row。

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

当前实现完成了论文 MFIU 和 shift-unit 的等价逻辑功能。最多 `4 x 4 x fiberWidth` 个 pair-major 候选先并行形成，随后进入分层稳定 compaction tree：每个节点根据左子树有效数，通过多级 shift 将右侧紧凑流拼接到左侧，而不是让每个 MAC lane 比较全部候选的全交叉网络。该网络保持 fiber-pair 和 K-coordinate 顺序，并只保留前 `macLanes` 项；独立计数路径仍报告完整 effectual computation 数和 overflow。shift unit 使用的 A/B 压缩 value rank 按源 fiber 通过并行 prefix tree 预计算一次，由所有目标 lane 共享。当前 route metadata 压缩已经分层化，但 PE row 随后的 A/B operand 动态选择仍由 SpinalHDL 组合 mux 实现，尚未映射成论文使用的两套 128 x 128 Benes distribution network。

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
| `DenseIp` | D x D | 单 A/B fiber 且 bitmask 全有效时旁路 MFIU，按固定 K lane 完成点积；其他输入回退到通用 IP 路径 |
| `TrIp` | MS x MS、MS x D | 多 fiber 交叉、压缩路由、并行乘法和按 A-row/B-column 归约 |
| `TrGt` | HS x HS | 四个 temporal subrow 各自访问固定 A-register slice，每个 subrow 每周期最多接收一个 B 元素 |
| `TrGs` | HS x MS、HS x D | 广播 A0，在一个有序 cache line 的多个 N 坐标上并行产生和归约部分积 |

表中的场景仅表示论文的数据流选择建议，不是接口约束。测试已经覆盖在非推荐 density 下显式运行这些 mode。

IP/TrIP 路径可以在一次命令中输出多个 `(row, column, value)`。Gustavson 路径可以在一个 cache-line 命令内合并相同坐标的部分积。

PE row 已拆分为五个逻辑阶段：

1. `Input`：锁存完整命令，隔离阵列调度器与 MFIU；
2. `Route/Distribution`：执行 MFIU、动态列规划、A/B 值索引和 Gustavson `aIndex` 选择；
3. `MAC`：固定 lane 有符号乘法，并把无效 lane 清零；
4. `Reduce/Merge`：按输出坐标归约部分积；
5. `Output`：将完整响应写入可配置深度的弹性寄存器 FIFO。

前三个阶段之间使用支持同周期出入的弹性 `Stream` 流水寄存器。在无背压时，流水线填满后可以每周期接收一条命令；任一级停顿都会沿 `ready` 反压到命令入口。DenseIp 在恰好一个全有效 A fiber 和一个全有效 B fiber 时直接按固定 K lane 送入 MAC，并把 MFIU 和动态列规划器输入隔离为零；这保持论文 dense IP 的固定路由和低稀疏控制翻转。显式选择 DenseIp 但输入并非该形态时仍回退到通用路径，保持 mode 与 density 解耦的接口语义。四种 mode 在 MAC stage 前选择有效操作数，因此物理上只描述 `macLanes` 个共享乘法器；Gustavson 使用前 `cacheLineWords` 条 lane，其余 lane 由 operand isolation 保持为零，不再额外描述一套 Gustavson 乘法器。TrIP 利用 MFIU 输出按 fiber pair 连续排列的性质，通过共享的可切片 contiguous reduction tree 归约：每个树节点只比较并合并左右子树边界上的一个输出簇，而不是为最多 16 个候选输出分别复制一棵全宽加法树。这对应论文 MRN 的 reduction mode，并将加法器数量从 O(`maxFibers^2 * macLanes`) 降到 O(`macLanes`)。Gustavson 路径不再对整个 16-word cache line 构造全互比较排序器：TrGT 只对四个 subrow head 进行坐标排序，且每个 head 只能访问所属的 A-register slice；TrGS 固定广播 A0，并对已经按坐标排列的 cache-line 输入进行分层稳定压缩。之后两者复用对数深度 segmented prefix tree 合并相同坐标。跨 subrow 的 TrGT 路由或乱序的 TrGS cache line 都会置 overflow 并丢弃该响应。两条路径的组合加法深度均为 O(log2(lane 数))。默认响应 FIFO 深度为 2，并以 field-wise 弹性寄存器实现；下游停顿时会保持输出稳定，并允许前级继续运行到所有弹性存储占满。

这次流水化没有改变 `PERowCommand`/`PERowResponse` 外部协议，也没有根据 density 改写 mode。代价是 PE row 不再是一拍命令/响应模块，上层必须只依赖 Stream 握手，不能假定固定的一拍响应延迟。

### 2.6 四 bank 本地 buffer

每个 PE row 实际例化一个 `BankedLocalBuffer`，具有：

- 4 个相互独立的 bank；
- 每个 bank 每行 4 个 word，paper 配置为 128 行；
- 每个 bank 每周期一次 byte-masked 整行写；
- 每个 bank 每周期一次同步整行读；
- 读响应有效信号；
- 可配置的 bank 深度。

`TrIpTileController` 为每个 PE row 例化独立的四 bank buffer，使用 `A fiber/subrow -> bank`、`B column / 4 -> address`、`B column % 4 -> word` 的映射。一个 `PERowResponse` 的最多 `4 x 4` 个 TrIP 结果会组合匹配到四个 bank 的四个 word，并在一个 scatter 阶段并行掩码写入；跨 K tile 时则并行读出相关 bank line、逐 word 累加后写回。结果最终仍按 row-major 顺序从对应 PE buffer 读回。

Gustavson cache adapter 对两种 mode 使用不同分发方式。TrGT 在 cache line 内用游标跳过无效槽，并把每个有效 B 元素送到 `aIndex` 所属的 subrow lane；因此一条含 `E` 个有效元素的缓存行需要 `E` 次 PE 命令握手，末元素才结束该行或 tile。TrGS 则把一条有序缓存行中的全部有效元素在一次命令中空间展开，并统一引用广播的 A0。上述发射周期属于 cache-to-PE 分发阶段，后面还要叠加 PE 流水、tile accumulator 和结果写回周期。

该组织在 bank 数、word 数和深度上对应论文的 `4 banks x 4 words x 128 lines`。当前整数验证配置的 word 宽度是 `accumulatorWidth`，paper 配置仍为 72-bit 整数累加值；只有替换成 FP32 数据通路后，物理容量才严格等于论文的 8KB/PE row。TrGT 的四个 subrow FIFO 已通过一个 `BankedGustavsonBFifo` 共同使用单个四 bank `BankedLocalBuffer`，不再为四个 subrow 分别例化 typed `Mem`。当前 TrIP controller 和 TrGT frontend 仍是独立执行顶层，因此两者尚未通过 mode 所有权仲裁共享同一个物理实例。

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

该配置规模很大，生成和后续综合需要较高的内存与运行时间。paper 参数的单 PE row 已完成独立 elaboration；完整 128-row 阵列和 paper RTL benchmark 使用显式入口，不放入默认快速回归。

生成带阶段二调度器的 bitmask/IP tile controller：

```bash
sbt -batch 'runMain trapezoid.GenerateTrIpTileController'
```

输出位于 `generated-tile/`。

生成带外层 M/N/K 循环的矩阵描述符控制器：

```bash
sbt -batch 'runMain trapezoid.GenerateTrIpMatrixController'
```

输出位于 `generated-matrix/`。

生成自动从 cache 读取 A/B fiber 的矩阵控制器：

```bash
sbt -batch 'runMain trapezoid.GenerateTrIpCachedMatrixController'
```

输出位于 `generated-cached-matrix/`。

生成集成 banked cache、仅暴露 refill 端口的矩阵系统：

```bash
sbt -batch 'runMain trapezoid.GenerateTrIpCachedMatrixSystem'
```

输出位于 `generated-cached-system/`。

生成阶段四的多 bank 读 cache：

```bash
sbt -batch 'runMain trapezoid.GenerateBankedReadCache'
```

输出位于 `generated-cache/`。

生成阶段三 Gustavson 跨命令累加引擎：

```bash
sbt -batch 'runMain trapezoid.GenerateGustavsonTileEngine'
```

输出位于 `generated-gustavson/`。

阶段三还提供四个生成入口：

```bash
sbt -batch 'runMain trapezoid.GenerateTrGtSubrowScheduler'
sbt -batch 'runMain trapezoid.GenerateTrGsSpatialEngine'
sbt -batch 'runMain trapezoid.GenerateGustavsonArray'
sbt -batch 'runMain trapezoid.GenerateGustavsonRowScheduler'
```

分别输出到 `generated-trgt/`、`generated-trgs/`、`generated-gustavson-array/` 和 `generated-gustavson-scheduler/`。

生成集成 B-row adapter、banked cache 和多 PE-row 调度器的 Gustavson 系统：

```bash
sbt -batch 'runMain trapezoid.GenerateCachedGustavsonSystem'
sbt -batch 'runMain trapezoid.GenerateCachedTrGsSpatialSystem'
sbt -batch 'runMain trapezoid.GenerateClusteredCachedTrGsSpatialSystem'
```

分别输出位于 `generated-cached-gustavson/`、`generated-cached-trgs/` 和 `generated-clustered-cached-trgs/`。

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
4. 在发送一个 A fiber group 前复用列规划器顺序预检全部 B column；若任意单列可能溢出，则先缩小 group 并重新扫描，避免处理中途改变分组和重复累加；
5. 原子地向所有 PE row 分发命令，避免部分 PE 提前接受批次；
6. 收集所有 PE 响应，并让各 PE row 并行 scatter 到各自的四 bank 结果 buffer；
7. 使用 `firstKTile/lastKTile` 描述 K 序列，在四 bank buffer 中同步读改写并跨 K tile 累加；
8. 中间 K tile 只完成累加，最后一个 K tile 才按 row-major 顺序读回结果；
9. 支持输出背压、稳定的 `endOfTile`、保持型 `done` 和 `awaitingKTile` 状态；
10. `clear` 在空闲时清除装载状态，在 busy 时执行同步 abort；若命令已经送入 PE，会先排空未决响应再回到空闲，避免污染下一次 tile；
11. 拒绝不属于 IP 路径的 mode、超出容量的维度以及不一致的 K 序列，并通过 `unsupportedMode` 报告配置错误；
12. 暴露当前 A offset、group size、B base 和规划列数，便于调试和性能计数。

单个 K tile 的范围不超过 `fiberWidth`，但一个序列可以包含任意数量的 K tile。序列内 row/column 数量和 tag 布局必须保持一致；每次 K tile 可以独立选择 `DenseIp` 或 `TrIp`，不受 density 限制。

### 2.10 Gustavson tile 执行引擎

`GustavsonTileEngine` 在 `TrapezoidPERow` 外增加了跨命令执行闭环：

1. 接收任意数量的 `TrGt`/`TrGs` 命令，并允许同一 tile 内显式切换 mode；
2. 复用 PE row 完成单条 cache-line 命令内的乘法与坐标归约；
3. 使用参数化坐标 accumulator 合并跨周期、跨命令的同坐标部分和；
4. `endOfTile` 命令完成后按 `(row,column)` 递增顺序输出；
5. 输出支持 Stream 背压，最后一个结果带 `endOfTile`；
6. accumulator 容量不足时设置保持型 `overflow`，不会静默覆盖已有坐标；
7. 不属于 Gustavson 路径的 mode 通过 `unsupportedMode` 拒绝；
8. busy 状态下 `clear` 会排空已进入 PE 的响应后安全中止。

当前 accumulator 使用按 `(row,column)` 排序的紧凑寄存器数组。每个 PE response 结果依次与有效前缀比较：命中时累加，未命中时插入对应位置并平移后续项。这样只需 O(`accumulatorEntries`) 个坐标比较器，结束时可以直接每周期流出一个有序结果；此前每个输出都重新进行全表最小坐标选择的 O(`accumulatorEntries^2`) 网络已经删除。TrGT subrow/FIFO 已由独立前端实现；后续仍需把 FIFO 和 accumulator 映射到本地 SRAM，并把逐项插入升级为可流水化的双流 merge tree。

单个 paper 参数 Gustavson PE row 可以独立生成，便于在完整 128-row 阵列之前检查 RTL 规模和关键路径：

```bash
sbt -batch 'runMain trapezoid.GeneratePaperGustavsonTileEngine'
```

### 2.11 TrGT/TrGS 前端与多 PE row 阵列

按照论文 Fig. 15--17 的数据移动方式，新增了：

- `TrGtSubrowScheduler`：将一个 PE row 划分为 4 个逻辑 subrow，每个 subrow 配置独立 B FIFO，每次最多从每个 FIFO 发出一个元素；
- `BankedGustavsonBFifo`：四个 subrow queue 共同例化一个 `BankedLocalBuffer`，每个 queue 固定占用一个 bank，并支持同步预取 head、独立 Stream 背压、occupancy、满队列停顿和环回；
- `TrGsSpatialEngine`：把一个 A 非零值广播到 cache-line 内所有有效 B lane，并使用按输出列索引的 spatial accumulator；一个 PE response 的最多 16 个结果可以在同一周期写入不同列槽，连续 cache line 可以无空拍进入 PE 流水线；结束时可通过 `MatrixResultLine` 每拍并行排出最多 `cacheLineWords` 个有序结果；
- `TrGsSpatialArray` / `TrGsSpatialRowScheduler`：为每个物理 PE row 配置独立 spatial accumulator，按 tile first-free 分配 PE row；一个 tile 的输出流在共享端口保持锁定到 `endOfTile`，其他 PE row 可并行计算或等待输出；通过编译期参数在兼容的 scalar 结果流与 cache-line 宽结果流之间选择，不会同时实例化两套排空数据通路；
- `GustavsonArray`：为每个 PE row 复制独立的 Gustavson accumulator、命令/结果 Stream 和状态接口；
- `GustavsonRowScheduler`：把共享命令流按 tile 分配给空闲 PE row，同一 tile 固定在同一 row，最终命令送出后可重叠执行下一个 tile，并将共享结果端口锁定到当前 tile 的 `endOfTile`。

TrGT 前端暴露 accepted/issued B-element 计数，便于检查 FIFO 停顿和利用率。调用方应在最后一个 B 元素完成 Stream 握手后拉高 `finish`；前端会先排空四个 FIFO，再发送 `endOfTile`。多 PE row 阵列允许每行独立选择 `TrGt` 或 `TrGs` 并独立背压；共享调度器采用 first-free 分配，不根据 density 自动改变 mode，同一 tile 内也允许逐命令显式切换 `TrGt`/`TrGs`。

paper 参数的 TrGS 专用路径可以独立生成：

```bash
sbt -batch 'runMain trapezoid.GeneratePaperTrGsSpatialEngine'
sbt -batch 'runMain trapezoid.GeneratePaperTrGsSpatialRowScheduler'
```

这些模块已经实现论文数据移动方式的功能等价路径。paper 生成入口启用 16-result 宽写回：128 个 accumulator 槽被划分为 8 个对齐 line，全空 line 会被跳过，每个非空 line 使用一拍排空，满 C row 从 128 拍降为 8 拍。该结构避免了把任意 128 个槽全局压缩到 16 lane 所需的大型 compactor，更接近本地 bank/line 读取。TrGT 的四路 FIFO 已统一到一个四 bank `BankedLocalBuffer`，但该实例还没有与 TrIP controller 的结果 buffer 进行 mode 级共享。TrGS accumulator 现在只为每个 slot 保存 valid/value，整行共享一个 row 和对齐 column-block 基址，删除了 128 份重复 row/column 寄存器及其翻转；跨 row 或跨 column block 的输入会置 overflow。当前仍通过带坐标的组合适配完成分发，尚未替换为流水化物理分发网络。

当前 Gustavson 前端还增加了两项物理约束：

- `TrGtSubrowScheduler` 的 A register 按四个 temporal subrow 分组保存。A 在 tile 内保持驻留；tile 结束后下一次 `aLoad` 自动开启新的装载会话并失效旧的 A valid 位，避免跨 tile 复用陈旧值，同时不破坏同一 tile 内的 stationary-A 复用。
- `TrGsSpatialEngine` 的 accumulator slot 按 `column % localBanks` 交错映射到四个 bank，word 地址由剩余 column index 给出。PE response 先进入 pending mask，每个周期由四路 bank arbiter 各选择最多一个结果，因此无冲突时最多并行完成四次 accumulator 更新；落在同一 bank 的其余结果保留在 pending mask 中并在后续周期重放，不会丢失。`accumulatorCycles`、`accumulatorUpdates` 和 `bankReplayCycles` 分别记录 accumulator 活跃周期、实际更新数量和 bank 冲突带来的额外重放周期，可以直接用于 TrGS 周期分解。

### 2.12 矩阵描述符与外层 M/N/K 循环

`TrIpMatrixController` 在 tile controller 外增加了完整的外层循环边界：

1. 接收全局 row/column 数量、K tile 数量以及每个 K tile 的显式 mode；
2. 自动维护 `rowBase`、`columnBase` 和 `kTileIndex`，循环顺序为 K、N、M；
3. 计算边界 tile 的实际 row/column 数量；
4. 通过 `acceptingFibers` 和当前基址请求上游装载对应的 A row/B column fiber，fiber tag 使用全局 row/column 坐标；
5. 自动生成 `firstKTile/lastKTile` 并启动内部 `TrIpTileController`；
6. 保留每个输出 tile 的 `endOfTile`，并在整个描述符最后一个结果上额外产生 `endOfMatrix`；
7. 统计完成的输出 tile，拒绝越界、空维度、零 K tile 以及与 IP 输入格式不兼容的 mode。

描述符中的 mode 数组由软件逐 K tile 指定，可以在同一矩阵中切换 `DenseIp`/`TrIp`，且不检查 density。当前接口已经自动推进外层循环，但 fiber 数据仍由上游按当前基址流式提供；CSR/CSC 指针读取、cache 请求和 DMA 尚属于后续存储系统工作。

### 2.13 多 bank 读 cache 与 refill 协议

`BankedReadCache` 完成了阶段四的第一版统一读存储边界：

- 参数化 line word 位宽、每 line word 数、地址宽度、bank 数、每 bank set 数、client 数和 request ID；
- line address 的低位选择 bank、随后位选择 set、高位作为 direct-mapped tag；
- bank 数和 set 数均为 2 的幂，并且每个 bank 至少包含 2 个 set；
- 每个 bank 使用独立 data/tag `Mem`，并允许不同 bank 同周期接受请求；
- 同 bank 多 client 请求使用固定优先级仲裁并通过 Stream 背压串行化；
- 每个 client 最多一个 outstanding 请求，每个 bank 最多一个独立 miss；
- 共享 refill 请求端口携带 bank 和 line address，refill 响应可按 bank 乱序返回；
- hit、miss、refill error 和 request ID 都通过原 client 的响应 Stream 返回；
- refill error 不会安装 cache line，`clear` 会失效全部 tag 并取消未完成事务；
- 暴露 bank busy、client outstanding 以及 hit/miss/refill 计数器。

该 cache 不检查计算 mode 或 operand density，四种 mode 后续可以通过相同的 read request/refill 协议取数。当前版本刻意只实现读路径；cache 写入、dirty bit、eviction writeback、多个 miss/bank 的 MSHR、跨 cluster 互连和 DMA 尚未实现。

### 2.14 TrIP 矩阵 fiber-cache adapter

`FiberCacheReader`、`TrIpMatrixFiberLoader` 和 `TrIpCachedMatrixController` 已经把阶段二矩阵循环连接到阶段四 cache 读协议：

- 一个 fiber 可以跨越任意数量的 cache line，不要求 line 宽度大于完整 fiber；
- 固定记录从低位到高位依次保存 `valid`、mask、固定数量的有符号 value slot 和 tag；
- cache line 按低有效位优先的顺序读取，并通过 request ID 检查响应属于当前 line；
- A 使用 `(globalRow * maxKTiles + kTileIndex)` 布局，B 使用转置的 `(kTileIndex * maxMatrixColumns + globalColumn)` 布局；
- A/B reader 可以并行产生两个独立 cache client 请求，并分别对矩阵控制器的 `aLoad`/`bLoad` 施加背压；
- cache error 会把对应 fiber 标记为 invalid，并在矩阵顶层锁存 `memoryError`，不会把错误数据静默当作有效 fiber；
- descriptor 入口按实际 row/column/K 数计算 A/B 最末 cache line，越界时不发请求并置位 `addressError`；
- cached descriptor 仍保留每个 K tile 的显式 mode，adapter 不读取 density，也不会改变 mode。

`TrIpCachedMatrixSystem` 进一步把 A/B reader 分别连接到 `BankedReadCache` 的 client 0/1，未使用 client 保持空闲，并向外暴露共享 refill Stream、bank/client 状态和 hit/miss/refill 计数器。默认系统生成入口只配置实际使用的 2 个 client；独立 cache 生成入口仍保留论文式 32 client 配置。当前 adapter 使用固定跨度的 bitmask fiber 区，尚未解析内存中的可变长 CSR/CSC pointer/index 数组；系统已经闭合 cache 读路径，但 refill 端仍需由外部内存或后续 DMA 提供数据。

### 2.15 Gustavson B-row cache adapter

`GustavsonBRowCacheAdapter` 和 `CachedGustavsonSystem` 已经把阶段三执行路径接入阶段四统一 cache：

- 一条命令显式携带 `TrGt`/`TrGs`、驻留 A 非零值、`aIndex`、B-row 基址和 cache-line 数量；
- B 元素按低位到高位的 `valid`、有符号 value、column 固定记录打包，每条 cache line 可容纳的元素数量由实际 line 宽度计算；
- B 的 K 坐标来自当前 A 元素，未占用的 PE lane 自动置 invalid；
- adapter 按地址顺序请求整段 B row，并把每条 cache line 转换成带背压的 PE-row command；
- adapter 使用独立 request tracker 和单项 elastic line buffer；在一个 client 最多一个 outstanding 的约束下，下一条 cache 请求可以与上一条 line 向 PE row 的输出同周期握手，PE 背压时 cache 响应会稳定停在响应端口；
- 最后一条 cache line 继承调用方的 `endOfTile`，因此同一输出 tile 可以连续累加多段 B row；
- 对零长度、超配置长度、地址空间越界和非 Gustavson mode 进行显式拒绝，不产生 cache 请求；
- cache error 或 request ID 不匹配会置位 `memoryError`；若 tile 已打开，还会发送空的结束命令释放 PE row，避免调度器永久停在半开状态；
- 暴露 requested/emitted line、cache hit/miss/refill、PE row 分配和完成 tile 等状态计数。

`CachedGustavsonSystem` 使用 cache client 0，并集成 `GustavsonRowScheduler`、`BankedReadCache` 和外部 refill Stream。相同 valid density 的 B row 可以由调用方分别选择 `TrGt` 或 `TrGs`；adapter 不统计 density，也不会修改 mode。`CachedTrGsSpatialSystem` 是面向 TrGS 综合和周期实验的专用顶层：adapter 直接产生窄 `TrGsCommand`，不再构造 128-entry A 向量；cache line 送入 `TrGsSpatialRowScheduler`，非 TrGS 命令在发出 cache 请求前被拒绝。该顶层只实例化一套 TrGS PE-row 阵列，不会为了保留通用路径同时复制一套 TrGT 阵列。当前地址仍由软件提供固定宽度 B-row 区域，CSR row pointer 解析和 DMA 尚未实现。

`ClusteredCachedTrGsSpatialSystem` 进一步按 cache client 数量划分 TrGS cluster。每个 cluster 包含一个独立 B-row adapter 和 `peRows / clientCount` 条 PE row，总 PE row 数不变；输入 tile 从 first-free cluster 分配，并在该 tile 的所有 B-row command 完成前保持 cluster 归属。不同 cluster 可同时持有 cache outstanding request、等待不同 bank refill 或运行 PE row。各 cluster 内部先完成 PE-row 结果仲裁，顶层结果端口再锁定一个 cluster 直到 `endOfTile`，因此并行 tile 的结果不会交叉。生成入口启用 cache-line 宽写回，small 生成配置使用 2 个 cluster，每个 cluster 2 条 PE row；映射到论文的 32 个 cache client 和 128 条 PE row 时对应每 cluster 4 条 PE row。

## 3. 当前达到的效果

### 3.1 功能验证

当前共有 43 项自动测试，执行方式为：

```bash
sbt -batch test
```

已验证：

- TrIP 对多个压缩 fiber 的坐标交叉；
- MFIU 分层 compactor 在 100 组固定种子随机输入下保持 pair-major 路由、value rank、完整计数和 overflow；
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
- PE row 连续命令在流水线中保持顺序，输出停顿时响应内容保持稳定，恢复后不丢失后续响应；
- 完整 tile 的加载、全局列规划、A-group 拆分、scatter 和 row-major 读回；
- 非满 tile、全零 tile、负数输入和随机输出背压；
- 后段高密 B column 触发 A group 缩小时不会重放、重复累加已经完成的前段列；
- busy 状态下的同步 abort 和未决 PE 响应排空；
- 两个 K tile 的本地 buffer 读改写累加，且序列内可以切换 `TrIp`/`DenseIp`；
- TrGT/TrGS 跨命令同坐标累加、坐标排序 flush 和随机输出背压；
- Gustavson accumulator 容量不足时的显式 overflow；
- Gustavson 错误 mode 拒绝和 busy 状态安全 abort；
- TrGT 四路 subrow FIFO 的满队列背压、独立排空和有序结果合并；
- TrGS 对全满或稀疏 cache line 的 A 广播、B 列分发和跨命令累加；
- TrGT SRAM FIFO 的满队列背压、输出停顿、顺序保持和指针环回；
- 多 PE row Gustavson 阵列和共享 row scheduler 的结构 elaboration；
- 共享调度器的 tile-row 固定、双 row 重叠执行、结果仲裁、完成释放，以及同一 tile 内显式切换 `TrGt`/`TrGs`；
- 6 x 8 与 8 x 6 矩阵的 2 x 2 个 M/N tile、2 个 K tile 自动遍历；
- 矩阵描述符内逐 K tile 显式切换 `DenseIp`/`TrIp`、随机输出背压、tile/matrix 边界和参考模型比对；
- 非法矩阵描述符和不兼容 mode 的显式拒绝；
- 多 bank cache 的独立并行接收、同 bank 冲突背压、request ID 响应路由和跨 bank 乱序 refill；
- cache miss/refill、重复读取 hit、地址冲突替换、refill error 不安装以及 clear 全局失效；
- 跨多个 cache line 的 fiber 重组、输出背压、有符号 value 解码和 cache error 传播；
- cached matrix descriptor 的 A/B 地址生成、运行时地址越界拒绝，以及稀疏输入下显式 `DenseIp` 的端到端矩阵结果；
- 矩阵前端、banked cache 和 refill 的完整读链路，以及重复描述符从首次 miss/refill 转为 cache hit；
- Gustavson B row 的跨 cache-line 解码、负数、同坐标跨行累加和随机输出背压；
- 同一稀疏 B row 分别显式运行 `TrGt`/`TrGs`，并验证首次 miss/refill、重复读取 hit 和结果一致性；
- Gustavson B-row 非法 mode、地址越界和 refill error 拒绝，以及 cache error 后自动关闭半开 tile；
- TrGS adapter 在输出已缓存 line 的同周期发出下一条 cache 请求；
- 两个 TrGS cluster 使用独立 cache client，并行持有和执行不同 tile；
- TrGS 宽写回将分属首尾 line 的 6 个结果分成 `4 + 2` 两拍输出，跳过中间全空 line，并在背压期间保持整行稳定。

当前测试结果为 43 项全部通过。

### 3.2 RTL 检查

默认配置已经完成 SpinalHDL elaboration，并成功生成 Verilog。生成结果通过：

```bash
verilator --lint-only --Wall -Wno-fatal generated/*.v
verilator --lint-only --Wall -Wno-fatal generated-tile/*.v
verilator --lint-only --Wall -Wno-fatal generated-matrix/*.v
verilator --lint-only --Wall -Wno-fatal generated-cached-matrix/*.v
verilator --lint-only --Wall -Wno-fatal generated-cached-system/*.v
verilator --lint-only --Wall -Wno-fatal generated-cache/*.v
verilator --lint-only --Wall -Wno-fatal generated-gustavson/*.v
verilator --lint-only --Wall -Wno-fatal generated-trgt/*.v
verilator --lint-only --Wall -Wno-fatal generated-trgs/*.v
verilator --lint-only --Wall -Wno-fatal generated-gustavson-array/*.v
verilator --lint-only --Wall -Wno-fatal generated-gustavson-scheduler/*.v
verilator --lint-only --Wall -Wno-fatal generated-cached-gustavson/*.v
```

当前已生成的 RTL 目录都没有 lint error。剩余 warning 主要来自 SpinalHDL 生成的枚举调试字符串、未消费的调试/诊断信号和部分保留接口，不影响功能。

已对 paper 参数的单个 Gustavson/PE row 做独立 elaboration。当前论文配置保持 128 个输入 MAC lane、128-wide fiber 和 `4 x 4` fiber intersection，但将响应修正为 16 个结果 lane：MRN 每条命令最多生成 16 个 TrIP 输出，TrGS 每条 cache line 也最多提供 16 个输出。分层 MFIU compactor 将 `MultiFiberIntersection.v` 从旧结构约 `304.7 MB / 6,391,733` 行降到约 `17.7 MB / 205,127` 行，单行生成时间从约 68 秒降到 38 秒。该数字是生成 RTL 文本规模而非综合面积；完整 128-row 阵列的生成、lint 和综合仍属于后续规模化工作。

### 3.3 四种 mode 的论文规模周期模型

`DataflowLatencyBenchmark` 按论文的六类 workload 运行：`HS x HS`、`HS x MS`、`HS x D`、`MS x D`、`MS x MS` 和 `D x D`。运行命令为：

```bash
sbt -batch 'Test/runMain trapezoid.DataflowLatencyBenchmark'
```

模型固定使用：

- A、B 均为 `256 x 256`；
- 计算阵列为论文 Table I 的 `128 PE rows x 128 MACs`；
- 时钟按论文的 `1 GHz` 换算，因此 1 个 model cycle 等于 1 ns；
- `HS=0.5%`、`MS=20%`、`D=100%` 非零密度；
- 每类运行 3 个固定随机样本，A/B 都具有精确的非零元素数量；
- 每个样本实际执行一次矩阵乘，统计有效 MAC 数并生成结果 checksum；
- 每一类都显式计算全部四种 mode，不把论文的推荐 mode 做成接口限制。

论文只定义 `HS < 1%`、`MS = 1%~90%`，并没有规定每一类只能使用一个固定密度。这里的 `0.5%` 和 `20%` 是可重复测试所选的代表点，不是新的硬件限制。需要扫描其他密度时只需修改 workload 参数，四种 mode 仍然全部可用。

周期模型计算的是 compute issue cycle 下界：

- `DenseIp` 统计标准 IP 在 128 行、128 个 K-lane 上的 M/N/K wave；
- `TrIp` 逐个 128-wide K tile 建立 bitmask，并实际执行最多 4 条 A fiber、4 条 B fiber，以及每 PE row 不超过 128 个有效乘法的 MFIU 打包规则；
- `TrGt` 按 4 个 temporal subrow/PE row、每 subrow 每周期 1 个 MAC 计算最长 row work；
- `TrGs` 按 128 个 PE row、每行每周期 16 个空间 MAC 计算 row work。

当前固定种子的运行结果如下。单位为 cycle；在假设的 1 GHz 下，数值也等于 ns。

| 类别 | A 非零密度 | B 非零密度 | 论文推荐 mode | 有效 MAC | `DenseIp` | `TrIp` | `TrGt` | `TrGs` |
| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: | ---: |
| HS x HS | 0.50% | 0.50% | `TrGt` | 442.3 | 1024.0 | 128.0 | 9.0 | 2.0 |
| HS x MS | 0.50% | 20.00% | `TrGs` | 16766.3 | 1024.0 | 128.0 | 273.7 | 33.0 |
| HS x D | 0.50% | 100.00% | `TrGs` | 83968.0 | 1024.0 | 128.0 | 1280.0 | 154.7 |
| MS x D | 20.00% | 100.00% | `TrIp` | 3355392.0 | 1024.0 | 512.0 | 17834.7 | 2170.7 |
| MS x MS | 20.00% | 20.00% | `TrIp` | 671825.0 | 1024.0 | 128.7 | 3520.0 | 431.3 |
| D x D | 100.00% | 100.00% | `DenseIp` | 16777216.0 | 1024.0 | 2048.0 | 65536.0 | 8192.0 |

表中为 3 个样本的算术平均值。该结果没有包含 pipeline fill/drain、cache/HBM 访问、bank 冲突、NoC 争用、结果写回和背压，因此不是当前 RTL 的端到端延迟，也不能替代论文的完整 cycle-level system simulator。尤其是 `HS x HS` 中 compute-only 的 `TrGs` 数字更小，而论文推荐 `TrGt` 是因为它减少存储流量；加入完整存储层次之前不能用本表推翻或复现该结论。

普通工作站上的论文配置周期 benchmark 为：

```bash
sbt -batch 'Test/runMain trapezoid.DataflowLatencyBenchmark'
```

该测试直接使用 `TrapezoidConfig.paper` 的 `128 PE rows x 128 MACs`、128-wide fiber、4 条 A/B fiber 和 16-word cache line 参数，不使用 small 配置。它运行 `256 x 256` workload 和论文六类 A/B 稀疏度组合，输出完整阵列的 compute issue cycle；该结果是论文配置调度模型，不是 full-array Verilator 周期。

完整 paper RTL 实验入口保留为：

```bash
sbt -batch 'Test/runMain trapezoid.FullPaperRtlDataflowLatencyBenchmark'
```

该入口保持 `128 x 128` paper PE/MAC 配置，并通过 blackbox 只生成一次 paper PE-row 内核。TrIP controller 现把 128 个 PE row 封装为 packed 层次、只广播一份 B fiber，并把 output-valid 从逐 cell 寄存器改为逐行位图；paper 顶层 Verilog 已从约 767MB 降到约 23MB（约 37 万行）。但是在当前 30GiB 主机上，Verilator 5.022 translation 仍会被系统以 `SIGKILL(9)` 终止，因此该入口仍只适合更高内存机器，不应作为本机默认测试。`PaperRtlDataflowLatencyBenchmark` 作为兼容入口会打印说明并转到可运行的 paper cycle model，不再触发 OOM。

paper RTL 基准中 `DenseIp`/`TrIp` 周期相同，是因为当前两者共用同一个 `TrIpTileController` 调度和 PE 数据通路。`TrGt` 仍通过通用 `GustavsonRowScheduler` 的有序 merge accumulator 执行；`TrGs` 已切换到启用宽写回的 `TrGsSpatialRowScheduler`，使用 128 个 paper PE row、连续 cache-line 发射、按列空间累加和每拍最多 16 个结果的排空，因此两种 Gustavson mode 的 RTL 周期不再被同一个逐项 merge/输出状态机人为拉齐。完整 cache/HBM 流量以及写回端口、bank 和 NoC 争用仍未包含。

### 3.4 当前适用范围

当前版本适合：

- 验证论文四种数据流的 PE row 级计算和路由思路；
- 进行小规模稀疏矩阵输入的 RTL 仿真；
- 直接输入一个 bitmask/IP tile 并得到有序矩阵结果；
- 在配置容量内通过一个描述符自动遍历多个 M/N/K tile；
- 从固定跨度的 cache fiber 区自动装载 TrIP/DenseIp 矩阵 tile；
- 通过集成 banked cache 在 miss/refill 和后续 hit 下执行完整 TrIP/DenseIp 矩阵描述符；
- 验证多 client、多 bank 的 cache 请求、冲突背压和 refill 行为；
- 跨多个 K tile 累加 IP 结果，以及跨多个命令累加 Gustavson 结果；
- 验证 TrGT 四路时间 gather 和 TrGS cache-line 空间分发；
- 从固定宽度 B-row cache 区域运行完整的 TrGT/TrGS miss/refill/hit 数据通路；
- 研究 MFIU、动态列打包和 merge/reduction 行为；
- 作为后续调度器、cache 层次和 FPGA/ASIC 综合工作的基础。

### 3.5 Cache-to-result 端到端 RTL 周期仿真

`EndToEndCycleSimulation` 使用真实 Verilator RTL 对六类稀疏度组合和四种 mode 做完整交叉测试，共运行 `6 categories x 4 modes x 3 samples = 72` 次。每次运行都逐元素检查 RTL 输出，并从任务边界持续计数到最后结果或无结果 tile 的 `done`。该入口使用可在当前机器稳定运行的 `4 PE rows x 16 MACs` 缩比配置，同时包含冷 cache lookup、bank 仲裁、固定 12-cycle HBM refill、PE 流水、跨命令或跨 K tile 累加以及结果排空：

```bash
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation'
```

六类为 `HS x HS`、`HS x MS`、`HS x D`、`MS x MS`、`MS x D` 和 `D x D`。目标密度是 `HS=0.5%`、`MS=20%`、`D=100%`；受 `16 x 16` 离散粒度限制，实际精确非零率为 `0.39%`、`19.92%` 和 `100%`，且每个样本强制保留一个共同 K 坐标以避免 HS workload 全部退化为空乘法。四种 mode 都运行所有六类，不按 density 限制接口。

输出表包含总周期、首结果周期、输入接收完成周期、HBM active 周期、refill/hit/miss 数量、有效 MAC、结果数量和 checksum。DenseIp/TrIP 的起点是 cached matrix descriptor 握手；TrGT/TrGS 当前缺少矩阵描述符前端，因此起点是第一条 cached B-row command 握手。终点是最后一个结果完成握手且所有 output tile 完成。测试使用始终 ready 的结果接收端，因此没有人为加入输出背压。

当前固定种子的顶层 `Total cycles` 平均值如下。每个数字都来自 Verilator 时钟边沿计数，不使用解析 compute-cycle 公式：

| 类别 | A密度 | B密度 | 论文mode | `DenseIp` | `TrIp` | `TrGt` | `TrGs` |
| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: |
| HS x HS | 0.39% | 0.39% | `TrGt` | 1360.0 | 1360.0 | 100.0 | 100.0 |
| HS x MS | 0.39% | 19.92% | `TrGs` | 1381.0 | 1381.0 | 488.0 | 140.7 |
| HS x D | 0.39% | 100.00% | `TrGs` | 1405.0 | 1405.0 | 1555.0 | 283.0 |
| MS x MS | 19.92% | 19.92% | `TrIp` | 1724.0 | 1724.0 | 1237.0 | 550.7 |
| MS x D | 19.92% | 100.00% | `TrIp` | 1995.0 | 1995.0 | 5910.0 | 1534.3 |
| D x D | 100.00% | 100.00% | `DenseIp` | 2219.0 | 2283.0 | 28842.0 | 4439.0 |

表中小数来自三个整数周期样本的平均值。IP路径使用固定宽度 bitmask fiber，因此冷 cache 下不同稀疏度仍请求相同的64条输入cache line，低密度结果主要体现计算和输出排空差异；Gustavson路径使用压缩B row cache区域，refill数量会随B密度变化。TrGT逐个发送B元素，TrGS按cache line空间发送，因此B较密时TrGS明显更快。

这些数字是当前缩比 RTL 的 cache-to-result 顶层周期，而不是论文 `128 x 128` full-array 周期。Gustavson A/CSR pointer 读取、四 cluster cache、真实 HBM channel/queue、DMA 和最终 C 写回仍在边界之外；这些模块完成后，应保持同一个计时接口，将起点前移到统一矩阵描述符，将终点后移到最后一次 C 写响应。论文配置 full-array Verilator 在当前机器上仍会因生成规模和内存占用失败。

### 3.6 论文配置的稀疏度端到端周期仿真

`PaperEndToEndCycleSimulation` 使用 `TrapezoidConfig.paper` 和论文方法中的 `1 GHz`、`128 x 128 FP32 MAC`、4 个 cache cluster、每 cluster 32 banks/4 MB、总计 16 MB global cache、1 MB local buffer、64B cache line 和 2 TB/s HBM。测试覆盖 `HS x HS`、`HS x MS`、`HS x D`、`MS x MS`、`MS x D` 和 `D x D`，每一类都计算 `DenseIp`、`TrIp`、`TrGt` 和 `TrGs`，并同时标出论文推荐 mode：

```bash
sbt -batch 'Test/runMain trapezoid.PaperEndToEndCycleSimulation'
```

默认矩阵为 `256 x 256`，`HS=0.5%`、`MS=20%`、`D=100%`，每类使用 3 个固定随机样本。HS 输入按 CSR/CSC 的 FP32 value、32-bit coordinate 和 pointer 计算流量，MS 输入按 bitmask fiber 和 FP32 nonzero value 计算流量，D 输入按未压缩 FP32 计算流量；输出根据实际非零密度选择相同的格式规则。计时从矩阵描述符接受且 A/B 位于 HBM 开始，到 C 写回 HBM 结束，包含输入格式流量、global cache/HBM 服务周期、所选数据流的 issue 周期、五级 PE pipeline fill/drain 和输出写回周期。

固定种子的运行结果如下，均为每类 3 个样本的算术平均值；周期在 1 GHz 下也等于 ns。

| 类别 | A 密度 | B 密度 | 论文 mode | `DenseIp` | `TrIp` | `TrGt` | `TrGs` |
| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: |
| HS x HS | 0.50% | 0.50% | `TrGt` | 1031.0 | 135.0 | 17.7 | 11.0 |
| HS x MS | 0.50% | 20.00% | `TrGs` | 1062.0 | 166.0 | 287.3 | 71.0 |
| HS x D | 0.50% | 100.00% | `TrGs` | 1128.3 | 237.3 | 1469.7 | 264.3 |
| MS x MS | 20.00% | 20.00% | `TrIp` | 1164.0 | 269.7 | 3715.3 | 576.7 |
| MS x D | 20.00% | 100.00% | `TrIp` | 1160.0 | 705.3 | 17629.3 | 2280.0 |
| D x D | 100.00% | 100.00% | `DenseIp` | 1160.0 | 2184.0 | 65672.0 | 8328.0 |

主表为 `E2E streamed`：允许输入传输与执行重叠。程序还为全部 24 个 category/mode 组合输出详细表，其中 `E2E serialized` 将输入、计算和输出完全串行，是无重叠上界。论文没有公开固定 HBM command latency，因此模型使用论文给出的 2 TB/s 带宽，不额外虚构 first-line latency。当前实现是可重复的 paper-configuration cycle model，不是论文模拟器的逐地址复刻，也不是 full-chip Verilator：它尚未模拟 cache 地址级 bank conflict、NoC 仲裁、DMA command latency 和随机背压；full-chip Verilator 在当前机器上仍受 128-row RTL 翻译内存限制。

论文使用 128 个真实 workload，而不是统一的 `256 x 256` 随机矩阵；因此这里遵循的是论文硬件配置、稀疏度分类、存储格式和 mode 映射，不能把表中绝对周期与论文图中的 workload 周期直接逐项比较。要复现论文最终图表，还需要导入论文 Table III/IV 对应矩阵、执行 coordinate/occupancy tiling，并补齐上述逐地址争用模型。

它目前还不是可以直接连接 HBM 并独立执行完整矩阵乘法的加速器系统。

## 4. 当前限制

### 4.1 调度器覆盖范围

`TrIpTileController` 已完成 bitmask/IP tile 和跨 K 累加，`TrIpMatrixController` 已完成描述符驱动的 M/N/K 外层循环，cached wrapper 也能从固定跨度的 bitmask fiber 区自动取数，但尚未实现：

- 从内存中的可变长 CSR/CSC 指针和 index 数组自动生成 fiber；
- 从矩阵描述符生成 Gustavson tile，并进行代价感知的多 PE row 负载均衡；
- 面向软件的寄存器组、描述符队列或指令译码器。

当前设计刻意不实现根据 density 自动选择 mode。未来即使增加启发式推荐器，也必须保留软件显式覆盖 mode 的能力。

### 4.2 缺少全局 cache 和 DMA

当前已实现一个参数化 multi-client、multi-bank direct-mapped 读 cache，以及每 bank 一个独立 miss 的共享 refill 协议。TrIP 矩阵前端和 Gustavson B-row 前端都已经与实际 cache、外部 refill 端口封装成完整读系统，但仍未实现论文中的 4 个 cache cluster、完整 32 x 32 cluster 互连、写 cache/dirty eviction、HBM 请求和 DMA。通用顶层的每 PE row 本地 buffer 仍由外部端口控制；TrIP tile controller 已改为每个 PE row 独占一个四 bank 结果 buffer，完成并行 scatter 和有序读回。后续仍需把 TrIP 与 Gustavson 的两套存储封装统一成同一物理 SRAM。

### 4.3 Gustavson 存储和并行调度仍待完成

当前 TrGT/TrGS 已能在任意 lane density 下完成命令内归约、跨命令累加、有序 flush、SRAM subrow FIFO 调度、多 PE row 分配和共享结果仲裁，但尚未完成：

- 在统一执行顶层中增加 mode 所有权仲裁，让 TrGT 的 `BankedLocalBuffer` 与 TrIP 结果 buffer 共享同一个物理实例；
- 将当前有序寄存器 accumulator 替换为可扩展的本地 buffer/双流 merge tree；
- 在 first-free 分配之上增加 row 成本估计、公平仲裁和描述符驱动的负载均衡；
- 从 CSR row pointer 自动生成 B-row cache 区间，以及 DMA/HBM refill 流程。

因此当前已经是可验证的多 PE row、cache/refill 驱动 Gustavson 功能系统，但还不是论文规模的 CSR/DMA/SRAM 实现。

### 4.4 算术格式尚未达到论文 FP32 配置

当前 MAC 使用可参数化的有符号整数数据通路。`paper` 配置设置了 32-bit 输入和较宽累加器，但并不是 IEEE-754 FP32 运算。

要和论文的 32 TFLOP/s 配置进行严格对比，需要接入可综合 FP32 multiplier/adder/FMA，并定义 NaN、Inf、舍入和异常处理策略。

### 4.5 尚未获得实现后性能和面积结论

目前已经有 small 配置的逐周期 RTL 基准，以及 `256 x 256`、`128 x 128 MAC` 的 compute issue 周期模型，但尚未执行：

- paper 配置的完整 RTL 生成；
- FPGA place-and-route；
- ASIC synthesis；
- 时序、面积和功耗分析；
- 包含逐地址 cache/HBM/NoC/写回争用的论文规模端到端 RTL 或完整系统级周期模拟；
- 与论文 TPU、SIGMA、Flexagon 基线的完整周期级性能比较。

因此当前表格中的 1 GHz 只是论文时钟假设下的时间换算，不能声称本 RTL 已达到论文报告的 1 GHz、32 TFLOP/s 或性能/面积结果。

## 5. 下一步工作建议

建议按照以下顺序继续，先闭合功能，再进行规模和性能优化。

### 阶段 1：建立矩阵级正确性基线（已完成）

1. 实现纯 Scala 参考模型，支持 dense、bitmask sparse 和 CSR/CSC 输入；
2. 增加随机矩阵测试，把硬件输出与软件矩阵乘法逐元素比较；
3. 为 `DenseIp`、`TrIp`、`TrGt`、`TrGs` 分别建立端到端测试；
4. 增加负数、空 fiber、全满 fiber、输出碰撞和连续背压测试；
5. 使用随机 `valid/ready` 停顿检查 Stream 协议。

已完成参考模型、格式转换、随机 density 覆盖、四 mode 语义测试、负数/空输入以及 Stream 背压测试。当前验收测试全部通过。

### 阶段 2：完成 TrIP 调度器（tile、跨 K 累加与外层 M/N/K 循环已完成）

1. 增加 tile 配置和 A fiber 装载状态；
2. 汇总所有 PE row 的动态列容量；
3. 自动选择本周期发送的 B column 数；
4. 实现 overflow 命令拆分或重放；
5. 将 PE row 结果自动 scatter 到本地 buffer；
6. 从本地 buffer 有序读取并写回输出 tile；
7. 通过同步读改写跨多个 K tile 累加同一个 C tile。

当前已经达到 tile controller 验收标准：测试平台只需逐个装载 K tile 并发送 `start`，控制器会自动完成列规划、必要的 A-group 拆分、PE 命令生成、跨 K 累加和最终有序读回。

当显式选择 DenseIp 且声明范围内的 A/B fiber 都是全有效 bitmask 时，controller 直接固定为一个 A fiber 和一个 B column 的 128-lane 批次，跳过 TrIP 所需的逐列 preflight；`denseBypassActive` 用于周期测试确认该路径。存在任意稀疏 fiber 时仍走原有动态规划路径。

矩阵控制器进一步接收一次全局描述符并自动推进 M/N/K tile，软件不再逐 tile 发送 `start`。cached wrapper 已把当前流式 fiber 装载口连接到固定跨度的 cache fiber 记录，并完成运行时基地址范围检查，使软件不再逐 fiber 握手。阶段二剩余工作是解析可变长 CSR/CSC 描述符并连接 DMA。

### 阶段 3：完成 TrGT/TrGS 执行引擎（功能前端与多 PE row 阵列已完成）

1. 把 PE row 划分为最多 4 个可独立调度的 subrow；
2. 使用本地 buffer bank 实现 B-row FIFO；
3. 实现跨周期、跨 cache line 的部分和累加；
4. 实现带坐标比较器的有序 merge tree；
5. 增加 row 完成、flush 和有序写回协议；
6. 为 TrGS 实现 A value 广播和按 N 坐标的 B 分发。

验收标准：TrGT 和 TrGS 可以消费完整 CSR row，并直接输出完整、有序的 C row。

六项均已完成可仿真的功能版本：第 2 项使用四个同步 `Mem` bank 推断 SRAM，但尚未与 TrIP 结果 buffer 复用同一物理实例。TrGS 已具有广播/列坐标分发路径，多个命令可持续累加相同坐标；有序寄存器 accumulator 已删除 flush 阶段的全表最小选择网络，并能在 `endOfTile` 后每周期输出一个结果。共享调度器可让多个 PE row 重叠执行，并保证一个 tile 的命令与输出边界不被拆散。固定宽度 B-row 区域已经通过统一 cache 完成 miss/refill/hit，后续重点是解析 CSR row pointer、统一物理 SRAM 和将逐项插入替换为流水化双流 merge tree。

### 阶段 4：实现多级存储系统（统一读协议、bank 仲裁与 refill 已完成）

1. 定义统一的 cache request/response 协议；
2. 实现参数化 cache bank 和地址映射；
3. 实现 PE row 到 cache bank 的仲裁与互连；
4. 实现论文中的 cluster 组织；
5. 增加 miss queue、refill 和写回；
6. 提供简单 DMA 或 AXI 接口连接外部存储器。

验收标准：四种数据流都只通过统一存储接口访问矩阵数据，并能在 bank 冲突和 cache miss 下正确停顿。

当前完成了第 1、2 项的读路径、第 3 项的通用多 client bank 仲裁、第 5 项的单 miss/bank refill 功能版本，并已分别将 TrIP 矩阵前端和 Gustavson B-row 前端与实际 cache、refill 端口集成为系统顶层。下一步是增加 cluster 组织、写回/MSHR 和 DMA。

### 阶段 5：替换为目标算术单元

根据最终目标选择：

- FPGA 原型：使用厂商 DSP/FMA IP；
- ASIC：接入综合库支持的 FP32 multiplier/adder/FMA；
- 仅研究稀疏数据流：保留整数或定点运算，同时在报告中明确与论文 FP32 的区别。

替换后需要重新确定流水级数、累加器格式和 PE row 的响应延迟。

### 阶段 6：规模化和 PPA 优化

1. 先对 1、4、16 个 PE row 做综合；
2. 检查 MFIU 前缀逻辑和路由网络的关键路径；
3. 在现有分层 MFIU compactor、value-rank prefix tree 和 stage 边界基础上，将 A/B operand 动态 mux 替换为两套 Benes distribution network；
4. 当前 TrIP contiguous reduction tree 已按输出簇切片，Gustavson flush 也已改为有序连续输出；下一步将 Gustavson 的逐项寄存器插入替换为 SRAM 双流 merge，并根据综合关键路径增加可配置寄存器切分；
5. 再尝试生成和综合 128 x 128 paper 配置；
6. 建立吞吐、利用率、面积、频率和存储流量统计。

验收标准：获得可复现的综合报告和周期级性能数据，再与论文结果进行定量比较。

## 6. 建议的近期任务

PE row 方向下一轮应优先完成统一物理本地存储：把 TrGT typed FIFO、A register、TrIP 结果 scatter buffer 和 Gustavson partial-sum storage 映射到可按 mode 切换用途的同一组四 bank SRAM；随后用 16/32/64/128 lane 配置综合新的流水化 PE row，根据报告确定 MFIU、Benes 路由和 reduction tree 的附加寄存器位置。

系统方向仍需实现简单 DMA/AXI refill 和 CSR/CSC row/column pointer walker，使软件不再直接提供固定宽度 fiber/B-row 地址。cache/Gustavson 仲裁器也仍需增加 round-robin 公平策略。

所有后续控制器都应继续遵守本文的 mode 与稀疏度原则：density 可以参与性能推荐，但硬件接口必须允许调用方在任意 sparsity level 下显式选择任何 mode。
