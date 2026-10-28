# Trapezoid SpinalHDL 实现说明

本文档说明当前工程如何对应 Trapezoid 论文中的计算阵列，以及当前 RTL 仿真可以证明什么、不能证明什么。文档中的“周期”均指仿真时钟周期，不等同于论文图表中的归一化性能数字。

## 1. 实现范围和术语

当前工程包含两层：

1. **计算层**：以 `TrapezoidPERow` 为核心，完成多 fiber 交叉、操作数分发、乘法、带坐标的分段归约和结果输出。
2. **系统层**：提供 TrIP/Gustavson 前端、tile/matrix 控制器、banked cache、DMA/CSR 描述符边界和最终 C 写响应。

论文没有公开完整的 ISA、DMA 协议、cache 一致性协议和所有调度器细节。因此，当前工程是“论文数据流和 PE row 结构的可综合实现 + 明确的系统级仿真模型”，不是论文作者内部 RTL 的逐门复刻。

### Mode 与稀疏度

四个 mode 由调用方显式选择：

- `DenseIp`：稠密 inner-product 快速路径；
- `TrIp`：稀疏 inner-product；
- `TrGt`：Gustavson，A 驻留、按 B column 处理；
- `TrGs`：Gustavson，A 广播、按 B cache line 处理。

硬件不会根据稀疏度自动改 mode。论文中的 `HS * HS`、`HS * MS/D`、`MS * MS/D`、`D * D` 是调度策略的推荐场景，而不是硬件的输入限制。本工程测试会故意让所有 mode 运行所有类别，以便观察实现差异；这与论文只选择推荐 mode 比较的图表口径不同。

## 2. Config

### 2.1 `TrapezoidConfig`

配置定义在 `src/main/scala/trapezoid/TrapezoidConfig.scala`。主要参数如下：

| 参数 | 作用 | 当前 paper 配置 |
| --- | --- | ---: |
| `dataWidth` | 输入值和乘法操作数位宽 | 32 |
| `accumulatorWidth` | 乘法结果、部分和及最终和位宽 | 72 |
| `coordinateWidth` | 行列/K 坐标位宽 | 16 |
| `fiberWidth` | 一个 fiber 的最大坐标数 | 128 |
| `maxFibers` | 一个命令最多携带的 A/B fiber 数 | 4 |
| `macLanes` | 一个 PE row 的乘法 lane 数 | 128 |
| `cacheLineWords` | B cache line 或输入 line 的 word 数 | 16 |
| `outputLanes` | 一个响应可携带的结果槽数 | 16 |
| `peRows` | 阵列中的 PE row 数 | 128 |
| `localBanks` | 本地 buffer 的 bank 数 | 4 |
| `localWordsPerBank` | 每个 bank 每条 line 的 word 数 | 4 |
| `localDepthPerBank` | 每个 bank 的 line 深度 | 128 |
| `responseFifoDepth` | PE row 响应 FIFO 深度 | 2 |

当前 `TrapezoidConfig.paper` 是 32-bit 整数操作数和 72-bit 整数累加器配置，不是 IEEE FP32 浮点实现。论文规模的“规模”主要指 `128 PE rows x 128 MAC lanes`、128-wide fiber 和 16-word line；数据类型和数值格式仍需要单独与论文实验平台确认。

### 2.2 预定义配置和 benchmark 配置

`TrapezoidConfig.small` 适合快速生成 RTL，但 benchmark 可以为了缩短 Verilator 编译时间使用自己的配置。两者不要混淆：

| 用途 | 规模 | 关键配置 |
| --- | --- | --- |
| `TrapezoidConfig.small` | 通用小配置 | 4 rows，16 MAC/row，16-bit input |
| `EndToEndCycleSimulation` | 小规模完整系统 | 16x16 tile，4 rows，16 MAC/row，8-bit input，4 clusters x 2 banks |
| `EndToEndCycleSimulation --scale=scaled-paper` | 论文宽度单 row 端到端 | 128x128 matrix，1 row，128 MAC/row，32-bit input，4 clusters x 2 banks |
| `PaperSinglePERowCycleSimulation` | 论文规模单 row | 128x128 logical tile，1 row，128 MAC/row，`TrapezoidConfig.paper` |
| full paper array | 论文规模完整阵列 | 128 rows，128 MAC/row，当前生成/编译资源消耗很大 |

`CacheConfig.paper(...)` 和 paper-scale end-to-end profile 的 global cache 参数已对齐论文 Table I 的主要容量结构：4 clusters、每 cluster 32 banks、每 bank 128 sets x 16 ways、每 line 16 x 32-bit words（64 B）。总容量为：

```text
4 clusters x 32 banks x 128 sets x 16 ways x 64 B = 16 MB
```

默认小规模 `EndToEndCycleSimulation` 仍使用 1-way cache，以控制 RTL 和 Verilator 编译规模；它不是 paper cache 的缩容等比例模型。`scaled-paper` 保留论文的 128 MAC/row、128-wide fiber、32-bit word 和 64 B cache line，但只实例化一个 PE row，并使用 4 clusters x 2 banks、32 KiB、direct-mapped cache。它用于在可承受资源下观察论文宽 PE row 的端到端行为，不等于 128-row/16 MB paper system。

系统级 `EndToEndSystemConfig.writeOutstandingDepth` 控制 C DMA 最多在飞的有序写请求数，默认是 16。当前响应接口按单一 AXI ID 的语义要求 write responses 保持请求顺序。这个参数不改变计算结果，只决定写回能否隐藏 response latency。

### 2.3 派生容量

- `distributionWidth = macLanes`；paper 配置为 128。
- `macLanesPerSubrow = macLanes / localBanks`；paper 配置为 32。
- `mrnReductionLevels = log2(distributionWidth)`；paper 配置为 7。
- `pePipelineStages = 4 + mrnReductionLevels + responseFifoDepth`；paper 配置为 13。这个值是结构估计，不是端到端延迟。
- `maxFibers` 必须不超过 4，`macLanes` 必须至少为 `maxFibers^2`，并且能被 4 个 local bank 整除。
- `outputLanes` 不等于 MAC lane 数。paper 中它为 16，用于输出槽和 cache-line 结果；128 个 MAC 的中间乘积经过 MRN 后可能输出少于或等于 16 个 segment tail。

## 3. 总体架构

当前系统的逻辑路径如下：

```text
descriptor / CSR
      |
      +-- A/B fiber loader and DMA read requests
      |
      +-- TrIpMatrixController / GustavsonFrontends
                 |
                 +-- mode-specific command Stream
                              |
                       TrapezoidPERow array
                       (MFIU -> distribution -> MAC -> MRN)
                              |
               local buffer / Gustavson accumulator / response FIFO
                              |
                 result arbitration and C write DMA response
```

`UnifiedEndToEndSystem` 在系统级仿真中把描述符接收、CSR 状态、A/B 读 DMA、clustered cache、计算阵列、结果背压和最终 C 写响应串起来。它不是一个真实 AXI/HBM 控制器：DMA 和 cache 延迟是可配置的 RTL 模型，写回只验证 C 写响应边界和数据完整性。

默认 `Unified` profile 只实例化一个 `ModeSharedTileController`。TrIP、TrGT 和 TrGS 前端都把命令送入这一套共享阵列；每个物理 row 只有一个 `TrapezoidPERow` 和一套共享 local SRAM/FIFO storage。mode ownership 在 tile/row 边界切换，不能同时由两种 mode 占用同一 row。`IpOnly`、`TrGtOnly`、`TrGsOnly` profile 仅用于 paper-scale 编译资源受限时的专用测试，不代表统一芯片中复制了三套阵列。

### 3.1 两条前端路径

- `TrIpTileController` / `TrIpMatrixController` 处理 bitmask、compressed values 和多 A/B fiber 的 TrIP 数据流，并生成 `TripCommand`。
- `GustavsonFrontends` / `GustavsonTileEngine` 处理驻留 A 元素和流式 B 元素，并生成 `GustavsonCommand`，分别服务 `TrGt` 和 `TrGs`。

两个前端最终都通过 PE row 的统一命令/响应边界连接到计算层。前端负责 tile 划分、命令数量、缓存访问和跨命令累加；PE row 只负责当前命令能够看到的数据。

### 3.2 PE row 与完整阵列的关系

`GustavsonTileEngine`、`TrIpTileController` 和 `UnifiedEndToEndSystem` 不属于单个 `TrapezoidPERow`。它们是 PE row 上游的调度/封装层：

```text
matrix controller / Gustavson engine
       -> many TrapezoidPERow instances
       -> tile result / matrix writeback
```

因此，单 PE row 测试可以验证真实 `TrapezoidPERow`，但不能代表 128-row 阵列的 inter-row load balance、NoC/广播、全局 cache 冲突和结果合并。

## 4. 重要硬件模块

### 4.1 PEROW：`TrapezoidPERow`

源文件：`src/main/scala/trapezoid/TrapezoidPERow.scala`。

PE row 的主要流水阶段为：

```text
command m2sPipe
  -> MFIU / dense fast path
  -> Benes A/B distribution
  -> multiplier lanes
  -> mode-specific coordinate ordering
  -> segmented MRN reduction
  -> response FIFO
```

输入命令通过 `valid/ready` 进入，输出通过 `valid/ready` 返回。响应包括结果坐标、累加值、结果数量、effectual MAC 数、动态列数和 overflow 状态。

`DenseIp` 且输入恰好是一个完整 A fiber 和一个完整 B fiber 时走固定 lane 的 dense fast path；其它情况走稀疏 TrIP 路径。动态 B-column packing 已移到 `TrIpTileController`：每个活动 PE row 先独立计算可容纳的 B-column prefix，array scheduler 再取所有活动 row 的最小值，也就是由最大 effectual-MAC row 决定本轮全局列数。PE row 不再自行改变 array-level packing 决策。`TrGt`、`TrGs` 使用相同乘法阵列，但采用不同的 A/B 驻留和跨命令 merge 方式。

当前 PE row 没有独立的 clear 端口。跨 workload 测试通过完整 drain 响应来形成边界；完整系统中的 tile 控制器负责在正确的 tile/row 边界开始新的累加。

### 4.2 MFIU：`MultiFiberIntersection`

源文件：`src/main/scala/trapezoid/MultiFiberIntersection.scala`。

MFIU 面向最多 4 个 A fiber 和 4 个 B fiber：

1. 对每一对 A/B bitmask 做 coordinate intersection；
2. 为每个匹配位置生成 effectual multiplication candidate；
3. 计算该值在 compressed values 中的索引；
4. 生成 A/B fiber 号、K coordinate、value index 和 distribution source index；
5. 通过稳定 compaction 将候选送入连续 MAC lane；
6. 独立报告完整 effectual MAC 数和 `overflow`。

候选数量超过一条 PE row 的容量时，当前 PE row 不静默截断结果，而是让上游减少列数或拆分命令。MFIU 的交叉/压缩是功能上的 sparse intersection；它不等于 cache，也不负责跨命令的 C 累加。

### 4.3 Multiplier

当前没有单独名为 `Multiplier` 的顶层 Scala 组件。乘法器阵列在 `TrapezoidPERow` 的 MAC stage 中生成：每个 lane 读取一个 A distribution source 和一个 B distribution source，只有 route valid 时进行乘法，否则送入零值。

因此，乘法器的数量由 `macLanes` 决定。paper 配置每个 PE row 有 128 个并行乘法 lane。乘法结果先扩展到 `accumulatorWidth`，再进入带坐标的 reduction network。

### 4.4 Distribution network

PE row 中有独立的 A、B 两个 `OperandDistributionNetwork`。MFIU 输出的是“哪个 source 参与哪个乘法”的索引；distribution network 把 source value 多播到对应 MAC lane。

TrIP 主路径使用独立的 A/B Benes fabric。MFIU 输出 `routeValid + sourceIndex` metadata；控制生成器对合法多源 multicast pattern 做递归二分着色并产生每一级 2x2 switch controls。16 lane 及以下使用组合控制生成；paper 的 128 lane 使用带 ready/valid 的多周期精确求解器，并在 PE row 内增加 4-entry route-control cache。第一次出现的 route pattern 仍执行冷启动求解，重复 pattern 直接复用已生成的 A/B switch controls，因此 steady-state 不再支付 `263 x commandCount`。最后一条 `endOfTile` command 完成 route 后清空 cache，使下一 tile 从 cold route state 开始，同时保留 tile 内复用。`controlPrepareCycles` 只统计冷 route 阻塞周期。控制结果带 `routable`，PE row 对所有有效 TrIP route 执行断言；旧 radix-2 fallback 选择树已删除。

小/中规模配置使用低延迟 command/control FIFO；128-lane payload 太宽，当前受 SpinalHDL 1.7 超宽 FIFO elaboration 限制，只使用一个 elastic holding stage。它已经把重复 route 的吞吐改善到接近 command stream，但还不是论文目标的“metadata-only FIFO + 约 7 级、II=1 冷 route control pipeline”。冷 pattern 的准备时间、缓存替换和宽 command holding 仍会影响周期与面积。

### 4.5 Reduction tree 和 MRN

这两个概念需要区分：

- **Reduction tree** 是结构：多级 radix-2 pipeline，把多个乘积合并成较少的部分和。
- **MRN** 是带矩阵坐标语义的 reduction policy：只有 `segmentStart` 表示同一输出 segment 的起点时才相加，segment 变化时开启新的部分和。

PE row 在 MAC 后为不同 mode 先整理顺序：

- `DenseIp`/`TrIp` 使用 triplet coordinate 顺序；
- `TrGs` 做稳定的 valid compaction，并检查 column/coordinate 顺序；
- `TrGt` 对 local bank 的 head 做有限排序，以恢复按 row/column 的 segment 顺序。

之后输入 ready/valid MRN pipeline。每一层都带 `valid`、坐标和 `segmentStart`，相邻两个有效值只有属于同一 segment 才相加；任一级或 response FIFO 背压都会沿 Stream 反压返回。最后一级提取各 segment tail，形成响应中的 `(row, column, value)`。TrIP/DenseIp 使用这一棵 segmented reduction。

工程新增了单个 ready/valid `DualModeMrnNode`：IP operation 对同坐标输入做 reduction；Gustavson operation 对旧 partial stream 和新 product stream 做 compare/merge，相同坐标相加，否则输出坐标较小的一项并分别给出 consume 信号。`StreamingGustavsonTileEngine` 的 merge 主路径已使用这个 node。当前 PE 内 radix-2 segmented tree 尚未逐节点替换为该双模式 node，TrGS 的 spatial accumulator/外围控制也仍然独立，因此还不是论文中一棵 MRN 在两种 operation 间切换的完整复刻。

这不是一个为每个输出 column 配置的完整 Gustavson accumulator。跨命令、跨 tile 的保留和写回由 Gustavson 前端、local buffer 或系统级结果路径负责。

### 4.6 Buffer

buffer 分为三类：

1. **PE row response FIFO**：吸收 reduction 输出和下游 backpressure，深度由 `responseFifoDepth` 控制。
2. **`BankedLocalBuffer`**：四 bank 本地存储，用于按 K/subrow 组织驻留数据和 partial state；bank 选择与 local subrow 对齐，减少同周期访问冲突。
3. **Gustavson FIFO/accumulator**：保存待处理的 B 元素、row/column 坐标和跨命令部分和。`GustavsonTileEngine` 使用两个交替的有序流 buffer，每个 buffer 按 `localBanks` 拆分；单个双模式 MRN node 每次合并一个旧 entry 或 PE response entry。旧的全关联 compare-all/shift-all 插入网络已不在 streaming 主路径。

`BankedLocalBuffer` 已替换为带 byte mask、同步读延迟的 SRAM BlackBox。它的 bank/line 结构影响 TrGt/TrGs 的访问和命令分组，但它不是 global cache，也不负责完整矩阵的 DMA refill。BlackBox 当前提供可仿真行为模型；映射到具体 ASIC SRAM compiler 或 FPGA BRAM/URAM 仍需后端约束。

### 4.7 Cache

源文件主要为 `BankedReadCache.scala` 和相关 clustered cache 封装。

当前 cache 是只读、banked、可参数化 set-associative 的 RTL 模型：

- 地址按 cache line 切分；
- bank 选择和 set 选择分开；
- `ways=1` 时为 direct-mapped，paper profile 使用 16-way；
- lookup 并行比较一个 set 中的所有 tag；
- refill 优先选择 invalid way，set 填满后采用 per-set round-robin replacement；
- miss 产生 refill 请求；
- refill 完成后响应等待中的读请求；
- 每个 client 有独立 outstanding credit，默认 4、paper profile 为 8；
- cache 内 request ID 进入 per-client issue-order FIFO，bank/refill 可乱序完成，response FIFO 按该 client 的发出顺序退休；
- 每个 bank 有一个独立 miss context/MSHR，不同 bank 的 miss 可同时在飞；
- cluster wrapper 另有 per-client ROB，跨 cluster 的 response 也按 client issue order 退休；
- data/tag storage 拆成 `bank x way` 独立同步 SRAM BlackBox，避免推断一块 `lineWidth x ways` 的超宽 memory；
- cluster 将多个 bank 组合起来，系统级模型再将多个 cluster 连接到 DMA 读路径。

cluster wrapper 会先用 global bank bits 选择 cluster，再删除 cluster index bits，将剩余地址交给 local cache 做 bank/set/tag 解码；refill 请求返回系统边界时再插回 cluster bits。这样每个 cluster 都能使用全部 128 sets，避免 cluster bits 固定后只访问部分 sets。

当前每个 bank 仍只有一个 miss context，不是“每 bank 多 MSHR”；外部 refill 也仍是单 Stream 仲裁，不是多个并行 refill channel。replacement state 仍使用 inferred memory。工程还没有完整 dirty writeback、论文 replacement policy 复刻、真实 HBM/AXI 协议、NoC 路由和 cache coherence。C 写路径是系统级测试中的最终响应边界，不能理解为已经实现了论文硬件的完整内存子系统。

## 5. 四种 mode 的 dataflow

四种 mode 共享“命令接收—乘法—坐标归约—响应”主干，区别在于输入组织、操作数驻留、命令拆分和跨命令状态。

### 5.1 `DenseIp`

```text
完整 A fiber + 完整 B fiber
        -> 固定 K lane 配对
        -> 128 个乘法 lane
        -> coordinate reduction
        -> C(row, column) response
```

当 A/B 都是完整 fiber 时，PE row 旁路 MFIU 和动态 sparse packing，直接把固定 K 位置送入乘法器。非完整输入仍可执行，但会走 sparse fallback。当前单 PE row benchmark 为避免 128-lane 输入 overflow，将 dense B columns 按每条命令一个 column 发送，所以 DenseIp 的命令数在 128x128 测试中为 128；这反映的是当前 command interface，不是理想 dense array 的吞吐上限。

### 5.2 `TrIp`

```text
多个 A fiber + 多个 B fiber
        -> MFIU pairwise intersection
        -> DynamicColumnPacker
        -> A/B distribution multicast
        -> MAC
        -> triplet coordinate MRN
        -> C partial/result
```

`DynamicColumnPacker` 逐列估算 effectual MAC 数，在不超过当前 `macLanes` 的前提下选择最大 B column 前缀。一个命令完成后，调度器继续发送剩余列。A/B 的 values 通过 source index 复用，避免每个 MAC lane 都存一份独立操作数。

### 5.3 `TrGt`

```text
A 的 K 非零元素驻留到 local subrow/bank
        -> B stream 按 column 进入
        -> B aIndex 选择匹配的 A
        -> MAC
        -> row/column coordinate MRN
        -> Gustavson partial C
```

`TrGt` 以 B column 为主要工作单位。B lane 中的 `aIndex` 选择对应 A nonzero，local bank 和 K 检查保证只使用匹配元素。由于一条命令的 A 侧通常按 local bank/subrow 分组，A 非零数增加时命令数和等待更明显。

TrGt 的 B 输入通过四个独立 subrow FIFO temporally gather。最终 B 元素被接收时，adapter 会同时发送 `finish`；scheduler 使用 accepted/issued element 计数区分“FIFO 真空”和“同步 SRAM 预取尚未返回”，从而把 `endOfTile` 附着在最后一条有效 temporal issue 上，避免额外的空终止命令。

当前 cached TrGT scheduler 已按 A-row occupancy 做 subrow packing。CSR command 携带 `aRowLength`，一行需要的 subrow 数为 `ceil(aRowLength / macLanesPerSubrow)`；短行可与后续行共享同一个 PE row 的四个 subrow，长行可连续占用多个 subrow。四个 subrow 填满、下一行放不下或 CSR 输入结束时关闭 packed tile。完成判断使用 packed tile 的 `assignedTiles/completedTiles`，不再把 CSR 非空行数误当成 TrGT tile 数。

TrGT adapter 现在有两个 group descriptor/context 和两套独立 B reorder window。当前 group 执行时可以继续接收下一 group 的 A metadata，并为下一 ready group 的第一个有效 A 提前发出 B-line request。共享 cache 端口优先服务 execute group，当前 group 暂时没有 request 时才服务 background group。每组独立保存 `prefetchAIndex`、line count、next request、outstanding、occupancy、valid/data slots；request ID 编码 `groupId + lineId`，response 可在两个 group 之间乱序返回。group 仍按 `Fill -> Ready -> LoadA/Execute -> Drain` 顺序交接。

这比上一版进一步形成了真实跨 group miss overlap，但仍不是完整论文 ping-pong：background 目前只预取下一 group 第一个 A 对应的有限深度 B window，下一 group 的 A 尚未并行写入另一套 scheduler-local A SRAM，后续 A/B windows 也要等该 group 获得 execute ownership。完整 steady-state 仍需要两套可切换的 A/B local context 或等价的 bank ownership。

### 5.4 `TrGs`

```text
A 的一个驻留 K/nonzero 广播
        -> B cache line（最多 cacheLineWords 个元素）
        -> 每个 B element 与 A 广播值相乘
        -> column coordinate stable compaction
        -> MRN
        -> Gustavson partial C
```

`TrGs` 的关键是 A stationary、B spatial。当前实现要求这类命令的 B `aIndex` 与驻留 A 匹配，并按 column 顺序整理输出。B line 中的多个 column 可以在同一命令中并行，因此在 A 稀疏、B 较密的场景中，单 PE row 的命令数通常比 TrGt 少。

### 5.5 论文推荐场景和当前测试口径

| 输入稀疏度 | 论文常见推荐方向 | 当前工程允许 |
| --- | --- | --- |
| `HS * HS` | `TrGt` | 四种 mode 全部运行 |
| `HS * MS/D` | 通常 `TrGs` | 四种 mode 全部运行 |
| `MS * MS/D` | `TrIp` 或 Gustavson | 四种 mode 全部运行 |
| `D * D` | `DenseIp` | 四种 mode 全部运行 |

这张表只能说明调度选择，不能推导周期排序。周期还受 tile 尺寸、命令边界、缓存 miss、FIFO 背压、PE row 数和 workload 分布影响。

## 6. 系统级模块如何串联

### 6.1 TrIP 路径

`TrIpMatrixController` 读取 descriptor 后生成 tile，`TrIpTileController` 维护 tile 内的 K/row/column 进度，必要时通过 `TrIpMatrixCacheAdapter` 访问 A/B 数据。每个命令进入一个或多个 `TrapezoidPERow`，响应再按 tile 坐标汇总并写回 C。

### 6.2 Gustavson 路径

`GustavsonFrontends` 负责从 CSR/压缩输入构造 A resident 和 B stream。`GustavsonTileEngine` 将其分发到 `TrGt` 或 `TrGs` 的命令队列，并处理 local buffer、partial result 和 output ordering。PE row 只看到已经编码好的 Gustavson command，不直接解析完整 CSR 矩阵。

CSR walker 首先读取 `rowPtr(0)`，随后每次只读取下一个 row pointer；上一行的 `rowEnd` 直接作为下一行的 `rowStart`。这样不会把同一个 `rowPtr(i)` 重复读取为“上一行 end”和“下一行 start”，空行仍被正确跳过，但不再产生多余 cache hit 和控制周期。

### 6.3 Unified end-to-end

`UnifiedEndToEndSystem` 的仿真边界是：

```text
descriptor accepted
  -> CSR/control setup
  -> A/B DMA read and cache refill
  -> command issue
  -> PE row/array computation
  -> response backpressure and result accumulation
  -> final C write request/response
```

`ResultWritebackDma` 在结果和外部 DMA 之间包含 request FIFO 与 completion FIFO。它最多保留 `writeOutstandingDepth` 个有序 outstanding write，可以连续接收并发出 C records；系统只有在所有已接受结果都收到 write response 后才完成。此边界比只测 PE row 更完整，但其中 DMA/cache 延迟仍是本工程的可配置模型，不是论文平台的实测内存时序。

## 7. 两个 RTL 周期测试

### 7.1 `EndToEndCycleSimulation`

源文件：`src/test/scala/trapezoid/UnifiedEndToEndCycleSimulation.scala`。

运行：

```bash
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation'
```

默认小规模测试使用 16x16 logical matrix、4 rows x 16 MAC、4 clusters x 2 cache banks，并运行 6 个稀疏度类别、4 个 mode、3 个样本，共 72 个 workload case。命令行可以筛选 scale、mode、category 和 sample：

```bash
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=small'
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=scaled-paper --mode=TrGt --category=HSxHS --samples=1'
sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=paper --mode=TrGt --category=HSxHS --samples=1'
```

`scaled-paper` 和 `paper` 必须一次只选一个 mode，以便生成 mode-specialized RTL。`scaled-paper` 使用一个真实 128-MAC paper PE row 和缩放 cache；`paper` 才是 128 PE rows 与完整 paper cache。两者首次 Verilator 编译都明显慢于 `small`。

计时起点和终点是：

```text
first descriptor accepted
        ... complete RTL dataflow ...
final C write response consumed
```

因此它包含：

- descriptor/CSR 接收；
- A/B 读 DMA 请求和固定模型延迟；
- clustered cache hit/miss/refill；
- tile/controller 发命令；
- PE row/array 计算；
- response FIFO 和 result backpressure；
- C write request 及最终 write response。

它不包含：

- Verilator 编译时间；
- 软件生成矩阵和 software reference 的时间；
- 真实 AXI/HBM/DDR 传输；
- 论文原始 FPGA/ASIC 的频率、NoC 和功耗；
- 128-row full paper array 的已完成周期结果，除非确实完成对应仿真。

默认小规模 RTL 仿真于 2026-10-28 使用上述命令重新验证，72 个 case 的结果、write 数和 checksum 全部通过。统一系统 Verilator 编译约 501 秒，72-case 仿真约 217 秒；这些主机时间不计入硬件周期。汇总周期如下，单元为 cycle，数值是每类 3 个 sample 的平均值：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 1160.0 | 808.0 | 401.0 | 350.0 |
| HS x MS | 1173.7 | 821.7 | 503.0 | 350.0 |
| HS x D | 1205.0 | 853.0 | 1016.0 | 350.0 |
| MS x MS | 1580.7 | 1226.0 | 2730.0 | 1897.7 |
| MS x D | 1946.0 | 1578.0 | 24087.7 | 2518.3 |
| D x D | 1962.0 | 2026.0 | 58353.0 | 8192.0 |

这些数字来自同一个共享 `ModeSharedTileController`、同一套 PE row/local storage、cluster ROB 和 cold-cache 模型。`HS x MS/D` 下 TrGS 最快，`MS x MS/D` 下 TrIP 最快，`D x D` 下 DenseIp 最快；这三组推荐方向与论文一致。`HS x HS` 仍是 TrGS 的 350 cycle 小于 TrGT 的 401 cycle，原因是 16x16、0.39% A 只有一个非零，TrGT 的 subrow packing 没有第二条短 row 可合并，却仍要完成 CSR 扫描和 packed-group close。其 first C write 为 392 cycle，而 TrGS 为 110 cycle。

本轮加入双 group 独立 B reorder window 后，7.1 周期与上一版一致。定向 RTL 测试已经证明下一 group 的首个 B window 可以在当前 group 停留于 execute/drain 阶段时完成 request/refill，但当前 16x16 benchmark 的 packed-group 长度和调度没有形成可观察的额外重叠收益。TrGT 在 `MS x D` 和 `D x D` 中仍分别为 24087.7 和 58353 cycle，主要瓶颈仍是单 node ordered merge、按 column/local-bank 的大量命令，以及后续 A/B window 未跨 group 全重叠。

### 7.2 `PaperSinglePERowCycleSimulation`

源文件：`src/test/scala/trapezoid/PaperSinglePERowCycleSimulation.scala`。

运行：

```bash
scripts/run-paper-single-perow.sh
```

脚本设置当前 Verilator 发行版进行层次化 build 所需的兼容 `VERILATOR_ROOT`，然后执行 `--mode=all --samples=1`。也可传入筛选参数，例如 `scripts/run-paper-single-perow.sh --mode=TrIp --category='MS x D' --samples=1`。

这个测试使用真实 Verilator RTL，但只实例化一个 `TrapezoidPERow`：

- `TrapezoidConfig.paper.copy(peRows = 1)`；
- logical tile 为 128x128；
- 只有 output row 0 有效，其它 A rows 为零；
- A/B 仍按四种 density category 构造；
- command 和 response 可以并行流动，不能把每条 command 都强制等待 response；
- 计时从第一条 command 被 PE row 接收，到最后一条 response 被消费；
- 不包含 Unified system 的 CSR、DMA、global cache 和 C write response。
- RTL 还暴露四个 lifetime stage counters：`routeCycles`、`macCycles`、`mrnCycles`、`responseCycles`。测试在每个 workload 前取稳定采样点，表中显示的是该 case 的 counter delta，而不是跨 workload 的累计值。

命令拆分体现当前四种 dataflow 的差异：

- `DenseIp`：为了避免一条 dense command 超过 128 MAC capacity，当前按 B column 逐条发送；
- `TrIp`：按不超过 128 effectual MAC 的动态 column prefix 打包；
- `TrGs`：一个 A nonzero 配合最多一个 B cache line；
- `TrGt`：一个 B column 配合 local bank 分组的 A K indices。

真实 RTL 结果于 2026-10-28 使用上述命令重新验证，全部 24 个 mode/category case 的 checksum 一致。结果如下，单元为 cycle：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 141 | 276 | 14 | 14 |
| HS x MS | 141 | 544 | 39 | 15 |
| HS x D | 141 | 307 | 141 | 21 |
| MS x MS | 141 | 544 | 195 | 65 |
| MS x D | 141 | 307 | 909 | 221 |
| D x D | 141 | 403 | 4109 | 1037 |

这里的 DenseIp 141 cycle 主要由 128 个逐列 command 加 PE row pipeline/drain 构成。TrGT/TrGS 仍主要由当前命令拆分数量决定。TrIP 的 4-entry route-control cache 改变了原先 `263 x commandCount` 的趋势：冷 route pattern 先执行一次精确求解，后续相同 pattern 直接复用 controls。例如 `HS x HS` 的 1 条命令产生 262 个 `controlPrepareCycles`；`HS x MS` 的 7 条命令产生 524 个 prepare cycle，而不是约 1841 个。

现在每个 workload 的最后一条 `endOfTile` command 会清空 route-control cache，因此下一 workload 不需要对 SRAM BlackBox 或整个 PE row 执行中途 reset，也能得到独立 cold-tile 结果。`HS x D` 和 `MS x D` 都由旧的 warm-cache 45 cycle 变为 307 cycle，其中 262 cycle 是冷 control prepare，32 cycle 是 route/MAC/MRN command stream 加 pipeline drain。`D x D` 为 403 cycle，即 262 cycle prepare 加 128-command stream 和 drain。

测试还输出 `controlPrepareCycles`、`route`、`MAC`、`MRN`、`response` 五个 counter，以及 `commandCount`、`responseCount`、`usefulMacs`、`laneSlots` 和 checksum。`controlPrepareCycles` 表示 cold route 阻塞；其余四个是各 stage 持有 valid payload 的 occupancy delta，不是互斥阶段延迟，也不包含 CSR/DMA/cache/C write。完整端到端周期仍以 7.1 的最终 C write response 为准。

本次成功的全量层次化构建中，paper kernel 生成约 252 秒，Verilator 编译约 664 秒，24-case RTL 仿真约 61 秒。运行时间不属于表中的硬件 cycle。

### 7.3 Full paper array 的状态

旧版 `EndToEndCycleSimulation --scale=paper` 曾在超宽 cache memory inference 阶段失败。当前 cache 已拆成 `bank x way` data/tag SRAM，解决了原先 `lineWidth x ways` 单块 memory 的结构问题，但本轮只按要求重新验证了 7.1 小规模完整系统和 7.2 paper 单 PE row，尚未重新完成 128-row、16 MB cache 的全量 Verilator 编译与仿真。因此当前仍没有可信的 full paper array 周期，不能把 RTL 生成成功或旧失败记录当作结果。

## 8. 为什么当前趋势与论文不符

当前结果可以用来验证功能和 RTL 数据流，但不能直接当作论文性能复现。主要原因如下。

### 8.1 阵列规模不同

小规模端到端测试是 4 rows x 16 MAC；论文单 row 测试是 1 row x 128 MAC；论文的阵列级结果通常还依赖 128-row 并行。单 row 无法表现 row-level load balance、跨 row 广播、全局结果合并和阵列级 cache 并发。

### 8.2 workload 不同

当前 single-row 测试使用可控的合成 128x128 矩阵，并且只有一个 output row 有效。论文趋势通常来自真实矩阵、实际 fiber 长度分布、tile 划分和不同 row 的不均衡。相同平均 density 不代表相同 effectual MAC 数、命令数或 cache miss 数。

### 8.3 mode 的命令拆分还不是论文完整 scheduler

当前测试为了覆盖全部组合，使用明确的、可验证的 command grouping：DenseIp 一列一命令，TrGt 按 B column 和 A bank 分组，TrGs 按 A nonzero 和 B line 分组，TrIp 使用当前动态 packing。论文中的 scheduler 还会联合考虑 subrow、buffer occupancy、跨 row 负载和 memory availability。paper 单-row TrIP 已能在 tile 内缓存并复用 route controls，tile 边界会恢复 cold state；但冷 pattern 仍执行多周期 solver。命令拆分和控制准备方式与论文不同时，周期排序仍会不同。

### 8.4 计时边界不同

`EndToEndCycleSimulation` 包含 DMA/cache/C write；`PaperSinglePERowCycleSimulation` 只包含 PE row command-to-response。一个 mode 在计算层更快，不代表在 cache miss、DMA 等待或写回拥塞后仍然更快。两张表不能直接比较，也不能直接与论文的 kernel-only 或 normalized throughput 图比较。

当前端到端写回支持默认 16 个有序 outstanding requests，消除了逐结果等待 response 的串行瓶颈。它仍然只有一个 request Stream，最多每 cycle 发一个 cache line，也没有模拟多个 HBM channel；因此它比旧模型合理，但仍不是论文 2 TB/s HBM 的完整带宽模型。

### 8.5 memory system 和 NoC 仍是模型

paper profile 已配置为论文的 4 clusters、128 banks、16-way、64 B line 和 16 MB 总容量，但替换策略采用当前 RTL 的 round-robin，DMA 仍是固定 latency 模型，也尚未实现论文平台的真实 2 TB/s HBM、32x32 cluster crossbar 时序、NoC 竞争和 multicast routing。因此 TrGt/TrGs 的 memory advantage 或 DenseIp 的带宽瓶颈仍不一定呈现为论文中的比例。

### 8.6 hardware implementation 仍有结构差异

TrIP 主路径已对当前 MFIU 合法 pattern 生成完整 A/B Benes controls，`routable` 失败会断言，旧 fallback 选择树已删除。4-entry route-control cache 让重复 pattern 不再逐命令阻塞约 263 cycle，并新增 `controlPrepareCycles`。仍有差距：冷 pattern 仍由工程自定义的多周期递归 solver 生成；paper 宽度还没有 metadata-only 多 entry FIFO、约 7-stage/II=1 的冷 control pipeline，也未用论文版门级结构、布线约束和目标频率综合验证。

论文 TrGT 把不同 A rows 映射到一个 PE row 的四个 subrows。当前 scheduler 已实现 occupancy-aware 多行 packing、长行多 subrow 占用、两个 group descriptor/A context，以及每组独立 B reorder window。background group 可以在当前 group 执行期间预取第一个 A 的有限 B window；仍缺少下一 group A 的并行 local-SRAM load、后续 B windows 连续 refill，以及与 streaming merge 的整组 steady-state 重叠。

`StreamingGustavsonTileEngine` 已使用双 buffer、banked storage 和单个 ready/valid 双模式 MRN node 做 ordered merge，旧全关联插入 accumulator 不再位于 streaming 主路径。`BankedLocalBuffer` 和 cache data/tag 已绑定可仿真的同步 SRAM BlackBox。剩余差距是 PE 内 segmented tree 和 TrGS spatial path 还未统一到同一组双模式 MRN nodes；SRAM BlackBox 也尚未映射目标工艺 macro 并验证真实端口、读延迟和功耗。

cache cluster 已有 client credit、cache 内 request ordering、跨 cluster client ROB、每 bank 一个 miss context，以及 bank/way SRAM。它仍缺少每 bank 多 MSHR、多个并行 refill channel、冲突 replay 和论文内存网络时序，因此 cache-level memory parallelism 仍低于目标。

### 8.7 数值和验证对象不同

当前 paper config 使用整数 32-bit input 和 72-bit accumulator，论文硬件的具体数值格式、FP/INT 数据路径和综合约束需要逐项对齐。当前测试验证的是 C 结果、坐标、overflow 和 checksum，不是论文 ASIC/FPGA 的 PPA。

### 8.8 当前测试运行了论文不一定会选择的 mode

例如 `D * D` 下测试表仍然运行 TrGt/TrGs；它们会因为大量无效 command、bank 分组和 Gustavson bookkeeping 变慢。这能说明 mode 的适用边界，但不能作为论文推荐策略失效的证据。若要复现论文趋势，应只比较每一类的推荐 mode，并使用相同矩阵和相同阵列规模。

## 9. 如何正确解读结果

建议把结果分成三层：

1. **功能正确性**：所有 mode 的 output coordinates、values、overflow、command/response 数和 checksum 是否正确。
2. **PE row 微架构周期**：使用 `PaperSinglePERowCycleSimulation`，观察 packing、有效 MAC、MRN 和 response backpressure。
3. **系统端到端周期**：使用 `EndToEndCycleSimulation`，观察 descriptor、DMA、cache、阵列计算和 C write response 的总和。

在表格中，最顶层周期是测试打印的 `cycles`，不是 `usefulMacs`、`laneSlots` 或某个理论公式。可以按以下方式理解：

```text
cycles = first boundary acceptance -> last boundary response consumption
utilization = usefulMacs / laneSlots
```

`usefulMacs` 和 `laneSlots` 是解释指标，不是额外的周期。端到端测试的 boundary 是 descriptor 到 C write response；单 PE row 测试的 boundary 是 command 到 response。

## 10. 后续要实现论文级趋势，需要继续对齐的项目

按优先级建议：

1. 在现有双 B reorder window 基础上，为下一 group 增加独立 A local-SRAM context，并让 background refill 从首个 A window 扩展到整组连续 A/B windows；group 获得 execute ownership 时应直接进入 ready state，而不是重新执行 A load/refill。
2. 将冷 Benes control generation 改为真正的受限-pattern metadata pipeline：MFIU compaction 同时生成各级 switch metadata，约 7 stage、II=1；增加 metadata FIFO/control FIFO，并保留 `routable` assertion 和 `controlPrepareCycles`，最终删除冷路径上的通用图着色 solver。
3. 将 PE 内 segmented reduction、TrGS spatial accumulation 和 Gustavson ordered merge 统一到可配置的双模式 MRN node/tree，测量 backpressure 下的 II、面积和关键路径，并避免当前单 node merge 对 dense Gustavson workload 的串行化。
4. 将每 bank 一个 miss context 扩展为真正的多 MSHR，增加 cluster-level refill channel arbitration/reorder 与多个外部 refill streams；为相同 set/bank 冲突加入 replay。
5. 将 cache replacement、Gustavson partial buffers 等剩余 inferred memory 也绑定为目标工艺/FPGA memory macro，验证端口数、读延迟、write mask、冲突与初始化语义。
6. 为 7.2 增加可选的连续 warm-tile 模式，与当前默认 cold-tile 结果并列输出，量化 control cache 在真实 tile sequence 中的命中收益。
7. 导入论文相同矩阵，或至少使用相同 row/fiber length histogram、tile mapping 和 mode selection，而不只匹配平均 density。
8. 扩展 array scheduler 的 row balancing、buffer occupancy 和 memory availability 联合决策；当前动态 B packing 只依据所有活动 row 的最大 effectual-MAC 数。
9. 继续细分 setup、memory、route-control、MAC/MRN、merge 和 writeback；PE counter 是 stage occupancy，不是互斥阶段时长。
10. 在 SRAM BlackBox/层次化 Verilator 基础上再次尝试 128-row full array，并对齐 32x32 cluster crossbar、2 TB/s HBM、DMA burst 和 NoC/multicast 竞争。若比较面积和功耗，还需固定目标工艺、时钟约束、综合脚本和 switching activity。

在这些条件全部满足之前，当前结果最适合表述为“真实 RTL 对当前实现的数据流和计时边界进行验证”，而不是“复现论文性能曲线”。
