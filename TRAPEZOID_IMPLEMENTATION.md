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
| `PaperSinglePERowCycleSimulation` | 论文规模单 row | 128x128 logical tile，1 row，128 MAC/row，`TrapezoidConfig.paper` |
| full paper array | 论文规模完整阵列 | 128 rows，128 MAC/row，当前生成/编译资源消耗很大 |

`CacheConfig.paper(...)` 和 paper-scale end-to-end profile 的 global cache 参数已对齐论文 Table I 的主要容量结构：4 clusters、每 cluster 32 banks、每 bank 128 sets x 16 ways、每 line 16 x 32-bit words（64 B）。总容量为：

```text
4 clusters x 32 banks x 128 sets x 16 ways x 64 B = 16 MB
```

默认小规模 `EndToEndCycleSimulation` 仍使用 1-way cache，以控制 RTL 和 Verilator 编译规模；它不是 paper cache 的缩容等比例模型。

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
  -> DynamicColumnPacker
  -> A distribution + B distribution
  -> multiplier lanes
  -> mode-specific coordinate ordering
  -> segmented MRN reduction
  -> response FIFO
```

输入命令通过 `valid/ready` 进入，输出通过 `valid/ready` 返回。响应包括结果坐标、累加值、结果数量、effectual MAC 数、动态列数和 overflow 状态。

`DenseIp` 且输入恰好是一个完整 A fiber 和一个完整 B fiber 时走固定 lane 的 dense fast path；其它情况回退到稀疏 TrIP 路径。`TrIp` 始终经过稀疏交叉和动态 packing。`TrGt`、`TrGs` 使用相同的乘法和 MRN，但在乘法前采用不同的 A/B 驻留规则。

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

当前实际接入 PE row 的是显式 radix-2 分发树：扇入为 2，选择深度约为 `log2(macLanes)`，同一个 source 可以被多个 lane 复用。工程还包含独立的 `BenesDistributionFabric` 和 broadcast control generator，但通用 runtime Benes 控制尚未替换 PE row 中的 radix-2 路径。因此，当前实现的功能语义接近论文的 distribution network，物理拓扑和面积不能直接当作论文 Benes 网络面积。

### 4.5 Reduction tree 和 MRN

这两个概念需要区分：

- **Reduction tree** 是结构：多级 radix-2 pipeline，把多个乘积合并成较少的部分和。
- **MRN** 是带矩阵坐标语义的 reduction policy：只有 `segmentStart` 表示同一输出 segment 的起点时才相加，segment 变化时开启新的部分和。

PE row 在 MAC 后为不同 mode 先整理顺序：

- `DenseIp`/`TrIp` 使用 triplet coordinate 顺序；
- `TrGs` 做稳定的 valid compaction，并检查 column/coordinate 顺序；
- `TrGt` 对 local bank 的 head 做有限排序，以恢复按 row/column 的 segment 顺序。

之后输入 MRN pipeline。每一层都带 `valid`、坐标和 `segmentStart`，相邻两个有效值只有属于同一 segment 才相加。最后一级提取各 segment tail，形成响应中的 `(row, column, value)`。

这不是一个为每个输出 column 配置的完整 Gustavson accumulator。跨命令、跨 tile 的保留和写回由 Gustavson 前端、local buffer 或系统级结果路径负责。

### 4.6 Buffer

buffer 分为三类：

1. **PE row response FIFO**：吸收 reduction 输出和下游 backpressure，深度由 `responseFifoDepth` 控制。
2. **`BankedLocalBuffer`**：四 bank 本地存储，用于按 K/subrow 组织驻留数据和 partial state；bank 选择与 local subrow 对齐，减少同周期访问冲突。
3. **Gustavson FIFO/accumulator**：保存待处理的 B 元素、row/column 坐标和跨命令部分和。它不等同于 PE row 的固定 MAC pipeline。

local buffer 的 bank/line 结构影响 TrGt/TrGs 的访问和命令分组，但它不是 global cache；local buffer 也不负责完整矩阵的 DMA refill。

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
- cluster 将多个 bank 组合起来，系统级模型再将多个 cluster 连接到 DMA 读路径。

cluster wrapper 会先用 global bank bits 选择 cluster，再删除 cluster index bits，将剩余地址交给 local cache 做 bank/set/tag 解码；refill 请求返回系统边界时再插回 cluster bits。这样每个 cluster 都能使用全部 128 sets，避免 cluster bits 固定后只访问部分 sets。

当前没有完整的 dirty writeback、论文未明确的 replacement policy 复刻、真实 HBM/AXI 协议、NoC 路由和 cache coherence。C 写路径是系统级测试中的最终响应边界，不能理解为已经实现了论文硬件的完整内存子系统。

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

可通过 `-Dtrapezoid.bench.mode=TrIp` 等参数只运行一个 mode，也可以使用 `-Dtrapezoid.bench.scale=paper -Dtrapezoid.bench.mode=TrIp` 生成论文规模、单一 mode 的系统配置。默认小规模测试使用 16x16 logical matrix、4 rows x 16 MAC、4 clusters x 2 cache banks，并运行 6 个稀疏度类别、4 个 mode、3 个样本，共 72 个 workload case。

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

默认小规模 RTL 仿真已验证 72 个 case 的结果和 checksum。典型汇总周期如下，单元为 cycle，数值是该类别多个 sample 的平均值：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 1131.0 | 779.0 | 311.0 | 312.0 |
| HS x MS | 1144.7 | 792.7 | 311.0 | 312.0 |
| HS x D | 1176.0 | 824.0 | 311.0 | 312.0 |
| MS x MS | 1551.7 | 1197.0 | 1693.3 | 1656.3 |
| MS x D | 1917.0 | 1549.0 | 2893.7 | 2219.3 |
| D x D | 1933.0 | 1997.0 | 13979.0 | 7302.0 |

这些数字包含 clustered-cache 地址修复和 16-entry C write outstanding FIFO。现在关键推荐趋势为：`MS x MS`、`MS x D` 下 TrIP 最快，`D x D` 下 DenseIp 最快；HS 类仍由 Gustavson 路径领先。该趋势更接近论文的数据流选择，但数值仍是当前 16x16 缩比 RTL，不是论文性能数字。

旧实现中 `MS x D` 的 TrIP 为 2791.3 cycles，TrGS 为 2525.3 cycles。两者都有约 251 个非零 C writes，但旧 `ResultWritebackDma` 必须等待当前 write 的 6-cycle response，才能接受下一个结果。TrIP 约在 446 cycle 才开始输出，而 TrGS 能较早流式输出并把串行写延迟与计算重叠，因此旧模型人为偏向 TrGS。改成多 outstanding 后，TrIP 为 1549.0 cycles，低于 TrGS 的 2242.3 cycles；最终计时终点仍然是最后一个 C write response。

旧的 `HS x HS` 结果是 TrGt 357 cycles、TrGS 356 cycles。原因有两层：TrGt 的同步 local-buffer FIFO 在最后一个元素尚处于预取阶段时，会被误判为空并发出额外终止命令；共享 CSR walker 还会重复读取每个内部 row pointer。修复后，TrGt 的 first C write 从 114 降到 103 cycles，CSR cache hits 从 15 降到 0，最终 TrGt 为 311 cycles、TrGS 为 312 cycles。

这里的 1-cycle 差值不能解释为论文级加速比。默认 `16x16 @ 0.39%` 的 HS 矩阵实际只有一个非零，最终周期主要由 16 行 CSR 扫描决定。也因此 `HS x MS/D` 的总周期会掩盖 TrGS 更早产生结果的优势：first C write 分别是 TrGt 151/283 cycles，而 TrGS 是 109/146 cycles。论文趋势应在 128x128 paper profile、真实 HS 行长度分布和完整 subrow row-packing 下判断。

### 7.2 `PaperSinglePERowCycleSimulation`

源文件：`src/test/scala/trapezoid/PaperSinglePERowCycleSimulation.scala`。

运行：

```bash
sbt -batch 'Test/runMain trapezoid.PaperSinglePERowCycleSimulation'
```

这个测试使用真实 Verilator RTL，但只实例化一个 `TrapezoidPERow`：

- `TrapezoidConfig.paper.copy(peRows = 1)`；
- logical tile 为 128x128；
- 只有 output row 0 有效，其它 A rows 为零；
- A/B 仍按四种 density category 构造；
- command 和 response 可以并行流动，不能把每条 command 都强制等待 response；
- 计时从第一条 command 被 PE row 接收，到最后一条 response 被消费；
- 不包含 Unified system 的 CSR、DMA、global cache 和 C write response。

命令拆分体现当前四种 dataflow 的差异：

- `DenseIp`：为了避免一条 dense command 超过 128 MAC capacity，当前按 B column 逐条发送；
- `TrIp`：按不超过 128 effectual MAC 的动态 column prefix 打包；
- `TrGs`：一个 A nonzero 配合最多一个 B cache line；
- `TrGt`：一个 B column 配合 local bank 分组的 A K indices。

当前真实 RTL 结果如下，单元为 cycle：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 141 | 14 | 14 | 14 |
| HS x MS | 141 | 20 | 39 | 15 |
| HS x D | 141 | 45 | 141 | 21 |
| MS x MS | 141 | 20 | 195 | 65 |
| MS x D | 141 | 45 | 909 | 221 |
| D x D | 141 | 141 | 4109 | 1037 |

这里的 DenseIp 141 cycle 主要由 128 个逐列 command 加 PE row pipeline/drain 构成；其它 mode 的周期主要由命令数量、每条命令的有效 MAC 数、MRN pipeline 和 response backpressure 构成。测试还输出 `commandCount`、`responseCount`、`usefulMacs`、`laneSlots` 和 checksum，用于区分“周期变长”是工作量增加还是硬件利用率下降。

### 7.3 Full paper array 的状态

`PaperUnifiedEndToEndCycleSimulation` 可以生成 128-row 的 paper-scale RTL 结构，但完整 Verilator C++ 编译和仿真资源消耗很大。当前不能把“RTL 生成成功”或“编译启动”当成“已经得到完整 128-row end-to-end 周期”。论文规模的可信端到端数字需要实际完成该仿真，并记录同一计时边界。

## 8. 为什么当前趋势与论文不符

当前结果可以用来验证功能和 RTL 数据流，但不能直接当作论文性能复现。主要原因如下。

### 8.1 阵列规模不同

小规模端到端测试是 4 rows x 16 MAC；论文单 row 测试是 1 row x 128 MAC；论文的阵列级结果通常还依赖 128-row 并行。单 row 无法表现 row-level load balance、跨 row 广播、全局结果合并和阵列级 cache 并发。

### 8.2 workload 不同

当前 single-row 测试使用可控的合成 128x128 矩阵，并且只有一个 output row 有效。论文趋势通常来自真实矩阵、实际 fiber 长度分布、tile 划分和不同 row 的不均衡。相同平均 density 不代表相同 effectual MAC 数、命令数或 cache miss 数。

### 8.3 mode 的命令拆分还不是论文完整 scheduler

当前测试为了覆盖全部组合，使用明确的、可验证的 command grouping：DenseIp 一列一命令，TrGt 按 B column 和 A bank 分组，TrGs 按 A nonzero 和 B line 分组，TrIp 使用当前动态 packing。论文中的 scheduler 还会联合考虑 subrow、buffer occupancy、跨 row 负载和 memory availability。命令拆分不同，周期排序就会不同。

### 8.4 计时边界不同

`EndToEndCycleSimulation` 包含 DMA/cache/C write；`PaperSinglePERowCycleSimulation` 只包含 PE row command-to-response。一个 mode 在计算层更快，不代表在 cache miss、DMA 等待或写回拥塞后仍然更快。两张表不能直接比较，也不能直接与论文的 kernel-only 或 normalized throughput 图比较。

当前端到端写回支持默认 16 个有序 outstanding requests，消除了逐结果等待 response 的串行瓶颈。它仍然只有一个 request Stream，最多每 cycle 发一个 cache line，也没有模拟多个 HBM channel；因此它比旧模型合理，但仍不是论文 2 TB/s HBM 的完整带宽模型。

### 8.5 memory system 和 NoC 仍是模型

paper profile 已配置为论文的 4 clusters、128 banks、16-way、64 B line 和 16 MB 总容量，但替换策略采用当前 RTL 的 round-robin，DMA 仍是固定 latency 模型，也尚未实现论文平台的真实 2 TB/s HBM、32x32 cluster crossbar 时序、NoC 竞争和 multicast routing。因此 TrGt/TrGs 的 memory advantage 或 DenseIp 的带宽瓶颈仍不一定呈现为论文中的比例。

### 8.6 hardware implementation 仍有结构差异

当前 PE row 使用 radix-2 distribution tree，Benes 通用 runtime control 尚未接入；local buffer、Gustavson accumulator 和跨命令 partial state 也比论文完整实现简化。功能等价不代表面积、频率、功耗或拥塞等价。

论文 TrGT 把不同 A rows 映射到一个 PE row 的四个 subrows；当前 cached TrGT scheduler 仍以“一个完整 CSR row 分配一个 PE row”为主，subrow 主要用于拆分同一行的 A nonzeros。因此当前实现已经具备四路 temporal B FIFO，但尚未获得论文的 4x spatial-M 映射能力。这是 HS x HS 吞吐仍不能直接对齐论文的最大结构差异之一。

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

1. 将 TrGT scheduler 改为 occupancy-aware row packing，把不同 A rows 映射到四个 subrows，并正确处理长行占用多个 subrows 的情况。
2. 导入论文相同的矩阵或相同 fiber 统计，而不是只匹配四个 density 百分比。
3. 先完成 128-row full array 的实际 Verilator 编译和运行，再比较阵列级周期。
4. 继续细分并报告 setup、memory、compute、writeback；当前已经输出 first C write、DMA active、hit/miss 和总周期，但还没有独立的 compute-only counter。
5. 将 paper scheduler 的动态 B packing、row balancing、buffer occupancy 和 mode selection 规则直接编码到前端。
6. 保持已经对齐的 16 MB、128-bank、16-way、64 B cache 结构，并继续对齐论文的 32x32 cluster crossbar、2 TB/s HBM 带宽、miss latency、DMA burst 和 NoC/multicast 竞争。
7. 将 Benes distribution control、完整跨命令 Gustavson accumulator 和真实结果写回路径接入系统。
8. 如果要比较面积和功耗，使用固定工艺/FPGA、时钟约束、综合脚本和 switching activity；SpinalHDL/Verilator 仿真本身不能给出论文可比的面积功耗。

在这些条件全部满足之前，当前结果最适合表述为“真实 RTL 对当前实现的数据流和计时边界进行验证”，而不是“复现论文性能曲线”。
