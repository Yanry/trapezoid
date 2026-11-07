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

默认小规模 `EndToEndCycleSimulation` 仍使用 1-way cache，以控制 RTL 和 Verilator 编译规模；它不是 paper cache 的缩容等比例模型。本轮 small profile 额外启用每 bank 2 个 MSHR 和 2 个 refill channel，测试 driver 会按 channel 分发请求并按 channel 接收响应。`scaled-paper` 保留论文的 128 MAC/row、128-wide fiber、32-bit word 和 64 B cache line，但只实例化一个 PE row，并使用 4 clusters x 2 banks、32 KiB、direct-mapped cache。它用于在可承受资源下观察论文宽 PE row 的端到端行为，不等于 128-row/16 MB paper system。

系统级 `EndToEndSystemConfig.writeOutstandingDepth` 控制 C DMA 最多在飞的有序写请求数，默认是 16。当前响应接口按单一 AXI ID 的语义要求 write responses 保持请求顺序。这个参数不改变计算结果，只决定写回能否隐藏 response latency。

### 2.3 派生容量

- `distributionWidth = macLanes`；paper 配置为 128。
- `macLanesPerSubrow = macLanes / localBanks`；paper 配置为 32。
- `mrnReductionLevels = log2(distributionWidth)`；paper 配置为 7。
- `pePipelineStages = 3 + mrnReductionLevels + responseFifoDepth`；paper 参数值为 12。三个固定 stage 对应 compact path 的 descriptor、route 和 MAC；paper-width command 另外使用两级宽 descriptor elasticity。MRN 第 0 级直接捕获 MAC 输出，不再另设 ingress register。这个参数用于基线延迟和计数器容量，不等于 7.2 的首 command 到末 response 总周期。
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
descriptor FIFO
  -> MFIU / dense fast path
  -> metadata FIFO / control FIFO / A-B distribution
  -> multiplier lanes
  -> mode-specific coordinate ordering
  -> N-1-node augmented MRN reduction
  -> response FIFO
```

paper-width command 约 43 Kbit，SpinalHDL 1.7 无法把它压成一个超宽 FIFO RAM word，因此 descriptor FIFO 使用两个串联的 elastic register stage；较小配置继续使用 `StreamFifoLowLatency`。route metadata 与 A/B control 分别使用独立的 2-entry FIFO，控制准备和超宽 operand descriptor 不再共用一个 holding register。

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

TrIP 主路径保留独立的 A/B Benes fabric。MFIU 输出 `routeValid + sourceIndex` metadata。16 lane 及以下使用完整组合 Benes 控制生成；paper-width 路径分为三类：单一 source 使用动态 broadcast controls，compacted spread permutation 使用预计算 Benes controls，其它 repeated-source block multicast 和不规则 route 进入通用 metadata pipeline。

受限控制器先检查 broadcast 或 `sourceIndex == spread(outputLane)`，128 lane 时经过 `log2(128)=7` 个 ready/valid stage。通用路径由 descriptor FIFO 写入 `PERowBenesMetadata` FIFO，A/B metadata 同步进入两个 7-stage、II=1 token pipeline，结果再写入独立 A/B control FIFO。这样 repeated-source pattern 不再执行旧版约 263-cycle 的逐命令图着色；7.2 中 `MS x MS` 和 `MS x D` 的 cold prepare 已降到 18/9 cycle。

paper-width 通用 repeated-source/不规则 route 现在由 `TrapezoidBenesRouteControl.sv` 根据 MFIU metadata 生成完整的 128-lane、13-stage Benes control word，A/B 两棵 fabric 使用独立 control FIFO 接收结果；不再存在 direct-distribution bridge。当前 7-stage endpoint 已改为只在各级寄存 `routeValid + sourceIndex` metadata，末端根据第 7 级稳定 metadata 生成控制字，避免在每个 stage 复制完整 control word；A/B 两路可以 II=1 地接收 metadata，PE row 只有在 A/B 控制字都有效且 `routable` 时才消费 route。Verilator 已对这些 route 做了 `routable` 断言和端到端 checksum 验证。当前剩余差距是末端 `solveSubnet` 仍为组合式图遍历，尚未拆成论文意义上“每个 Benes level 各自执行一个 metadata step”的完全状态机；下一步应将末端 solver 替换为 level-local metadata FSM。PE row 内的 4-entry route cache 仍复用已准备的 controls/token，`endOfTile` 会恢复 cold state。

### 4.5 Reduction tree 和 MRN

这两个概念需要区分：

- **Reduction tree** 是结构：多级 radix-2 pipeline，把多个乘积合并成较少的部分和。
- **MRN** 是带矩阵坐标语义的 reduction policy：节点比较左右 entry 的 `(row,column)`，坐标相同才相加，否则通过 level 内路由保留两个结果。

PE row 在 MAC 后为不同 mode 先整理顺序：

- `DenseIp`/`TrIp` 使用 triplet coordinate 顺序；
- `TrGs` 做稳定的 valid compaction，并检查 column/coordinate 顺序；
- `TrGt` 对 local bank 的 head 做有限排序，以恢复按 row/column 的 segment 顺序。

之后输入显式的 ready/valid `DualModeMrnTree`。当前空间路径已从 segmented Hillis-Steele prefix scan 改成 `N-1` 个 augmented-tree arithmetic node。第 `l` 级包含 `N / 2^(l+1)` 个节点；每个节点接收两个已压紧、按坐标有序的子树列表，只比较左列表 tail 与右列表 head。坐标相同就用该节点归约一次并删除重复 head；坐标不同则由 level 内路由拼接两个列表。各级节点数之和为 `N/2 + N/4 + ... + 1 = N-1`。默认 16-lane PE row 从旧 prefix scan 的 49 个空间 cell 降为 15 个；paper 128-lane PE row 从 769 个降为 127 个。

2026-11-07 进一步删除了每个输出位置上的 `leftEntries(dynamicIndex)` / `rightEntries(dynamicIndex)` 宽选择树，以及用于寻找 left boundary 的局部平衡 tail selector。每条有效 subtree link 现在显式携带 `{count, head, tail}`；node 直接读取上一层左 child 的 `tail` 和右 child 的 `head`，归约后同步更新新的 head/tail metadata。metadata 接口不再在每一级都保留固定 `macLanes` 个槽位：入口为 `N` 条 link，第 0 级输出 `N/2`，之后依次为 `N/4 ... 1`，每一级的 `Vec` 长度恰好等于该级实际 subtree link 数。每个 arithmetic node 独立生成动态的 `leftCount`、`rightCount`、`reduceBoundary`、`rightShift` 和 `outputCount`。右 child 只经过固定距离为 `1,2,4,...` 的 level-local 2:1 switch stages，每级 switch 只读取 `rightShift` 的一个 bit；相等的 right head 在入口失效，其余 entries 被路由到 left list 之后。这样 boundary value/coordinate 沿规则 node-to-node link 逐级传递，不再从完整 child list 中重新搜索，也不再依赖综合器裁剪每一级无效的 metadata 槽位。

空间 node policy 也与数据比较分离。mapper/controller 选择的 `TrapezoidDataflow` 在 MRN 入口先转换成 `SpatialMrnProfile.IpReduction` 或 `OrderedMerge`。每一级 `SpatialMrnControlMemory` 按 global node ID 为每个物理 node 保存两个 profile word，并输出独立的 `SpatialMrnNodeConfig`。配置字段现在包括 `mode`、`reduceOnEqual`、`checkOrdered`、`subtreeBase/subtreeLength`、`routeMode/routeShift` 和 `temporalEnable/temporalOwner`。`SpatialMrnControlWrite` 提供串行 programming port；该端口已经从 `DualModeMrnTree` 提升到 `TrapezoidPERow`、`ModeSharedTileController` 和 `UnifiedEndToEndSystem`，因此系统控制面可以按 `profile + level + node` 写入 layer 配置。

control store 使用每物理 node、每 profile 一份的分布式配置寄存器，而不是一个带 `N-1` 个异步读口的集中 RAM；执行时所有 node 可同时读取自己的静态 word，编程时每拍更新一个地址。尚未写入的 word 通过 `programmedValid` 选择原来的 IP/ordered-merge 默认配置，因此现有测试和启动流程保持兼容。编程配置只能启停与该 node 固定物理范围一致的 subtree；`subtreeBase` 不匹配、`subtreeLength` 不是 0 或物理 capacity、route shift 越界、非法 route mode、以及 temporal owner 与物理端口不匹配都会汇入 response `overflow`。这建立了 mapper 配置和硬件拓扑之间的可检查边界，但还不能重新连接任意 child 或把任意 temporal port 动态接到任意 node。坐标是否相同仍必须由 comparator 在运行时判断，因为这是稀疏数据相关事件，不能由 mapper 静态决定。

树仍只有 `log2(macLanes)` 个 `DualModeMrnLevel`，paper 配置正好是 7 级；每级末端保留一个 elastic register，所以 command 可见 latency、II=1 和 ready/valid backpressure 边界不变。第 0 级直接接收 MAC-stage 输出，没有额外全宽 ingress register。最后一级得到压紧且每个坐标唯一的结果列表，再形成响应中的 `(row,column,value)`。8-lane 定向测试使用前 6 项同坐标、后 2 项另一坐标，验证同一 segment 能跨 2-lane/4-lane 子树边界归约为 `21`，第二个 segment 独立归约为 `15`。

IP operation 使用这棵 augmented tree 做坐标归约。TrGt/TrGs 当前命令内的有序 partial products 也经过同一棵树；Gustavson mode 会在每一级检查坐标没有逆序。MRN 从已有空间节点中选择四个节点，分别暴露 ready/valid temporal 端口，对应四个 local accumulator bank。paper 和默认 small 配置的四个端口都映射到 level 0 的不同节点；4-MAC 单元测试配置总共只有 3 个空间节点，其中 port 0/1 复用 level 0，port 2 复用 level 1，port 3 使用一个补充 temporal node。production 16/128-lane 配置不实例化补充 node。每个 temporal response 都经过独立的一级 elastic register，不再是 SRAM read 到 write 的零周期组合旁路。

共享 level 使用显式 temporal-priority 仲裁：只要该 level 存在 temporal request，就通过 `ready=false` 完整保持空间 payload，并允许同 level 的多个 bank request 并行进入各自 response register；temporal request 清空后继续空间流水。必须采用这一仲裁，因为 response FIFO 被空间流填满时，TrGS accumulator 可能正在等待 temporal RMW；若坚持“只有空间 input 无效时才服务 temporal”，两条 ready/valid 路径会形成环形死锁。

`ModeSharedTileController` 在 TrGT 拥有 PE row 时把 streaming ordered merge 接到 port 0；TrGS 拥有 PE row 时把四个 accumulator bank 分别接到四个 temporal port。TrGT 使用同一 comparator/adder/mux 比较旧 partial 与新 PE response；TrGS 从同步 local SRAM 取得 old partial 后，通过对应 MRN cell 完成 `old + delta`。SRAM response 遇到 spatial backpressure 时先锁存，直到 temporal request 真正握手，因此不会丢失单周期 read response。TrIP、DenseIp 和裸 PE array 明确 tie-off 这些端口。

TrGT temporal merge 已把 request decision 和 response commit 解耦：request 握手时推进 `newIndex/oldIndex` 和 `head + lookahead` 旧流，下一拍寄存 response 按序写 inactive SRAM。这样上一项提交与下一项发射可以同拍发生；直接回归验证了旧流和新流同时有效时连续 4 拍接受 4 个 merge decision，即 steady-state request II=1。`temporalOutstanding` 保证最后一个寄存 response 提交后才切换 ping-pong buffer，clear/abort 也会排空旧 response。

坐标 lookahead policy 已从 `StreamingGustavsonTileEngine` 移入独立的 `GustavsonRadixKMrnControl`。该 control 同时接收一个 old partial head 和四个 new stream heads，按 `(row,column)` 选择最小 new head，再通过统一的 `DualModeMrnPolicy` 生成 temporal request、selected source 以及握手限定的 left/right consume；controller 不再复制 `sameCoordinate/oldBeforeNew` comparator。直接单元测试覆盖了候选乱序时的最小 head 选择和同坐标 coalesce。

TrGT 的四个 banked B FIFO 现在把 subrow 身份保留到 PE row 空间 MRN 输出：`destinationBank` 在 TrGT mode 表示原始 B stream。`StreamingGustavsonTileEngine` 在四个 cursor 前分别增加一个跨 response 的 head FIFO；深度为 `max(8, 2 * outputLanes)`，每项只保存 `{row,column,value}`。PE response 握手时按 `destinationBank` 将一批结果并行分发到四个 FIFO，radix-4 control 始终读取四个 FIFO head；只有 temporal MRN request 真正握手并选择该 source 时才移动对应 read pointer。FIFO 空间检查参与 `peResponse.ready`，因此 response 在容量不足时保持原子 backpressure，不会部分入队。

array-level TrGT scheduler 进一步为每个 stationary-A source 保存独立的长期 B stream。cache request ID 编码 `groupId + sourceId + lineId`；每个 source 各有 request cursor、line cursor、line 内 element cursor 和完成位。每个物理 local bank 再划分为 K 个虚拟 source FIFO，使用同步 SRAM body 和独立 elastic head，并按最小 column 选择。四个物理 subrow 的 head 继续按 `(row,column)` 合并，同坐标 subrow 在同一 PE command 中归约。`sourceFinish` 逐 source 关闭短流，因此短 B row 不必等待长 sibling，也不会把下一个 A[k] 的 column 0 接到前一 B row 的末列后触发 epoch。

TrGT merge 期间可以提前接受后续 PE command；其 response 也可在当前 temporal merge 尚未 drain 时入队。这样四个 source head 的生命周期覆盖 tagged command batch，而不再局限于一个 `responseReg`。定向回归覆盖 16 条 dense source、短/长 sibling、跨 subrow 同坐标 reduction 和不均衡 3/5/1-source packed rows，并断言正常 array-level 路径不进入 order-regression drain。engine 仍保留容量 reservation drain，以及面向绕过 scheduler 的无序直接 client 的坐标回退 fallback；真正的 packed-group/tile 结束也仍需一次终止 drain。这些机制不再用于正常 B-row 切换。TrGS 仍保留 slot-valid、bank replay、SRAM read/write 和输出扫描 FSM。`DualModeMrnNode` 类只保留为单 cell 独立测试 wrapper。没有综合数据时不能把物理复用直接宣称为已验证的面积或功耗下降。

这不是一个为每个输出 column 配置的完整 Gustavson accumulator。跨命令、跨 tile 的保留和写回由 Gustavson 前端、local buffer 或系统级结果路径负责。

### 4.6 Buffer

buffer 分为三类：

1. **PE row response FIFO**：吸收 reduction 输出和下游 backpressure，深度由 `responseFifoDepth` 控制。
2. **`BankedLocalBuffer`**：四 bank 本地存储，用于按 K/subrow 组织驻留数据和 partial state；bank 选择与 local subrow 对齐，减少同周期访问冲突。
3. **Gustavson FIFO/accumulator**：保存待处理的 B 元素、row/column 坐标和跨命令部分和。TrGT 使用两个交替的有序流 buffer，并通过 temporal port 0 合并旧 entry 与 PE response；TrGS 的四个 SRAM bank 使用四个 temporal port 并行完成 RMW。旧流读端有 head/lookahead 两项队列和同步 SRAM 返回旁路，能够把一次 old read、一次 merge/write 和下一次 old read 重叠。旧的全关联 compare-all/shift-all 插入网络已不在 streaming 主路径。

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
- 每个 bank 有参数化 MSHR 数量，默认 2、paper profile 为 4；bank 完成同步 lookup 后即可接收下一请求，因此同 bank 可保留多个等待 refill/response 的 miss；
- refill channel 数量参数化，默认 1、paper profile 为 4；不同 channel 可在同周期从不同 bank 发出 refill，并在同周期接收不同 bank 的 response；
- cluster wrapper 另有 per-client ROB，跨 cluster 的 response 也按 client issue order 退休；
- data/tag storage 拆成 `bank x way` 独立同步 SRAM BlackBox，避免推断一块 `lineWidth x ways` 的超宽 memory；
- cluster 将多个 bank 组合起来，系统级模型再将多个 cluster 连接到 DMA 读路径。

cluster wrapper 会先用 global bank bits 选择 cluster，再删除 cluster index bits，将剩余地址交给 local cache 做 bank/set/tag 解码；refill 请求返回系统边界时再插回 cluster bits。这样每个 cluster 都能使用全部 128 sets，避免 cluster bits 固定后只访问部分 sets。

`ClusteredReadCache` 和 `UnifiedEndToEndSystem` 已把额外 refill channels 贯通到顶层 DMA 接口，channel 0 保持原接口兼容。当前仍缺少同地址 miss coalescing、同 set 冲突 replay、跨 channel QoS，以及论文真实 HBM/NoC 时序；每个 bank 的 tag/data lookup 仍是每 cycle 最多一个新请求。replacement state 仍使用 inferred memory。工程也没有 dirty writeback、cache coherence 或完整 AXI burst 控制。

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

TrGt 的 B 输入通过四个物理 subrow、每个 subrow 内 K 个虚拟 source FIFO temporally gather。每条 stationary-A/B-row stream 都有独立 close bit；所有 source 完成且 accepted/issued element 计数相等后，scheduler 发送一条显式空终止命令。终止命令与最后一批有效坐标分离，避免部分 subrow 尚未暴露 head 时过早关闭 temporal merge。

PE command/response 现在携带 4-bit `responseTag`。TrGT engine 内有 4-entry issue-order tag scoreboard，可在当前 temporal merge 进行时继续向共享 PE row 发命令，并按 tag 检查 response retirement。四个 radix-K source 的长期 head queue 已从 payload 寄存器数组改为独立同步 `Mem`，每个 queue 使用 SRAM body、读指针/写指针/count 和一个 elastic head register；response enqueue、同步 head refill 和 MRN consume 可以重叠。

每个已发 command 会为四个 temporal response source 各预留一个可能的 head slot。queue 接近容量时进入 drain epoch：停止发新 command，先退休所有 tagged response、排空当前有序 head stream 并提交 partial，再开始下一 epoch，避免“满 source 等待消费、空 source 等待 response”的环形死锁。正常 array-level 路径在发 PE command 前已经把每个长期 B source 的 head 按 `(row,column)` 合并，因此 B-row 边界不再触发坐标回退 epoch；engine 的回退检测只作为无序直接 client 的正确性 fallback。

当前 cached TrGT scheduler 已按 A-row occupancy 做 subrow packing。CSR command 携带 `aRowLength`，一行需要的 subrow 数为 `ceil(aRowLength / macLanesPerSubrow)`；短行可与后续行共享同一个 PE row 的四个 subrow，长行可连续占用多个 subrow。四个 subrow 填满、下一行放不下或 CSR 输入结束时关闭 packed tile。完成判断使用 packed tile 的 `assignedTiles/completedTiles`，不再把 CSR 非空行数误当成 TrGT tile 数。

TrGT adapter 现在有两个 group descriptor/context、两套 A load cursor 和两套独立 B reorder buffer。`IndexedGustavsonA` 与 `GustavsonStart` 都携带 `groupId`；当前 context 运行时可以写入 inactive context。每个 group 的 B buffer 容量为 `localBanks * localDepthPerBank` 条 cache line。收集 descriptor 时按 A 的实际 `lineCount` 分配可变长度 `lineBase`，不再为每个 A 固定只保留 `bPrefetchDepth` 个槽，因此 background group 可以依次预取所有有效 A 的后续 B windows。每个 `groupId/sourceId` 还独立保存 next-request、current-line、element cursor 和 emit-complete，执行侧在当前最小 output row 内轮转 source，不再把整条 B row 串行发完后才切换 source。

B-window data memory 现在通过 `readSync` 接口访问。refill 写入 valid bitmap 后，当前 line 仍需经过一个 local-buffer SRAM read cycle 才能进入 `EmitLine`；这使 TrGT 的端到端周期包含真实的同步 local-buffer 读延迟，而不是组合 `readAsync` 查表。

共享 cache request 端口使用 `prefetchIssueTurn` 做公平轮转：当 execute group 和 background group 都能发射时交错选择，只有一侧能发射时立即服务该侧；request ID 编码 `groupId + sourceId + lineId`，所以多个 source 和两个 group 的 response 都可乱序返回并写入正确 window。group 按 `Fill -> Ready/A-load -> Execute -> Drain` 交接。该轮转消除了固定 execute 优先可能造成的 background starvation，但端口仍然每周期最多接受一个请求，尚不能等价模拟论文中的多 memory channel 整组带宽。两套 A/B context 的 descriptor、cursor、valid 和完成位目前也仍由寄存器 Vec 表达，尚未绑定成论文式共享物理 SRAM。

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

2026-11-07 在逐级缩宽的 subtree `{count,head,tail}` link、按 global node ID 分布式保存的可编程 profile control store、规则 level-local switch stages 和 `N-1` augmented MRN，以及 array-level `groupId/sourceId` 长期 B stream、逐 source close、K 路虚拟 FIFO 和跨 subrow `(row,column)` 合并上重新完成真实 Verilator 验证。72 个 case（6 类 x 4 mode x 3 samples）的结果、write 数和 checksum 全部通过；小规模 RTL 使用 2 个 refill channel 和每 bank 2 个 MSHR。本轮 Unified RTL generation 为 24.08 秒，Verilator 冷编译 696.85 秒，72-case RTL 仿真 115.06 秒，完整命令 838 秒。这些主机时间不计入硬件周期。下面是本轮 3 个 sample 的平均周期结果：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 1050.0 | 730.0 | 414.0 | 350.0 |
| HS x MS | 1063.7 | 743.7 | 436.0 | 350.0 |
| HS x D | 1095.0 | 775.0 | 559.0 | 350.0 |
| MS x MS | 1470.7 | 1148.0 | 2129.0 | 1884.3 |
| MS x D | 1836.0 | 1500.0 | 3536.7 | 2420.3 |
| D x D | 1852.0 | 1916.0 | 8185.0 | 7328.0 |

这些数字来自同一个共享 `ModeSharedTileController`、同一套 PE row/local storage、cluster ROB、双 refill channel 和 cold-cache 模型。`HS x MS/D` 下 TrGS 最快，`MS x MS/D` 下 TrIP 最快，`D x D` 下 DenseIp 最快；这三组推荐方向与论文一致。`HS x HS` 仍是 TrGS 的 350 cycle 小于 TrGT 的 414 cycle，原因是 16x16、0.39% A 只有一个非零，TrGT 的 subrow packing 没有第二条短 row 可合并，却仍要完成 CSR 扫描、同步 local-buffer 读和 packed-group close。其 first C write 为 405 cycle，而 TrGS 为 109 cycle。

DenseIp、TrIp、TrGs 与上一轮一致。TrGT 现在不会因 packed group 内切换 B row 而进入坐标回退 epoch；`TrGT / MS x D / sample 0` 的定向仿真完整结束于 3496 cycle，未再出现中间版本卡在 15 个 issued B element 的永久等待。相对上一轮表格，严格的跨 source/subrow 有序 head barrier 使 HS/MS 场景增加 6 到 262 cycle，而 `D x D` 从 9313 降到 8185 cycle。这里的目标首先是消除 B-row 边界 drain 和保证长期 stream 正确性，不应把每个类别都预期为单调加速。TrGT 在较密场景仍慢于推荐 mode，主要原因变为 command 数、单 cache issue point、read DMA 活跃周期和 C writeback 流量，而不是 B-row column 回退。

完整双 group B-window、双 refill channel、cache 计数和最终 C checksum 仍全部通过。MRN control store 替换前后 72 个 case 的周期逐项一致，这是预期结果：benchmark 没有在计时区间内执行 control programming，未写 word 使用默认 profile，MRN 仍保持 4 个寄存 level、II=1 和相同 backpressure 边界。完整运行后又把 programming port 提升到 Unified 顶层；`ModeSharedTileControllerSpec` 和 `UnifiedEndToEndSystemSpec` 已覆盖最终端口连接。调试参数 `--sample=<index>` 和 `--timeout=<cycles>` 可在超时时报告 TrGS assigned/completed/allocated、各 row state、PE in-flight、pending result 和 SRAM merge 状态。本次 7.1 命令为 `sbt -batch 'Test/runMain trapezoid.EndToEndCycleSimulation --scale=small --samples=3'`。

### 7.2 `PaperSinglePERowCycleSimulation`

源文件：`src/test/scala/trapezoid/PaperSinglePERowCycleSimulation.scala`。

运行：

```bash
scripts/run-paper-single-perow.sh
```

入口现在会自动创建 workspace 内的兼容 `VERILATOR_ROOT` 并启动 16 GiB worker JVM，因此可直接运行 `sbt -batch 'Test/runMain trapezoid.PaperSinglePERowCycleSimulation'`；脚本仍可使用。筛选示例为 `scripts/run-paper-single-perow.sh --mode=TrIp --category='MS x D' --samples=1`。

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

真实 RTL 结果于 2026-11-07 在 127 个空间 arithmetic node、按 `128/64/32/16/8/4/2/1` 实际 link 数逐级缩宽的 subtree metadata、254 个分布式 profile control word（127 node x 2 profile）、规则 level-local switch stages、四个寄存 temporal 复用端口、temporal-priority 仲裁和无额外 ingress 的 7-level `DualModeMrnTree` 上重新验证，全部 24 个 mode/category case 的 checksum、command/response 数、output coordinate 数和 useful MAC 数一致。旧 prefix scan 在相同 128-lane 配置需要 769 个空间 cell；新 augmented tree 使用 `64+32+16+8+4+2+1=127` 个。array-level `groupId/sourceId` scheduler、radix-4 TrGT control 和跨 response head FIFO 位于裸 `TrapezoidPERow` 外层，因此 7.2 仍只验证 PE row 空间 MRN，不经过本轮新增的长期 temporal source merge。结果如下，单元为 cycle：

| Category | DenseIp | TrIp | TrGt | TrGs |
| --- | ---: | ---: | ---: | ---: |
| HS x HS | 141 | 14 | 14 | 14 |
| HS x MS | 141 | 34 | 39 | 15 |
| HS x D | 141 | 52 | 141 | 21 |
| MS x MS | 141 | 38 | 195 | 65 |
| MS x D | 141 | 54 | 909 | 221 |
| D x D | 141 | 148 | 4109 | 1037 |

这里的 DenseIp 141 cycle 主要由 128 个逐列 command 加 descriptor/control、PE row pipeline 和 drain 构成。TrGT/TrGS 仍主要由当前命令拆分数量决定。`HS x HS` 只有一个 effectual MAC，直接走 broadcast controls，因此三个 sparse mode 都是 14 cycle、TrIP 为 0 个 prepare cycle。

受限 Benes pipeline 的 broadcast/spread pattern 保持固定准备周期：`HS x MS` 为 14 prepare cycle，`HS x D` 和 `D x D` 均为 7。本轮所有 24 个周期与上一轮一致；这是预期结果，因为 7.2 不实例化 array-level TrGT scheduler 或 `GustavsonTileEngine`。长期 source merge 由 7.1 和定向 scheduler RTL 回归覆盖，不能从 7.2 周期不变推断它没有生效。

`MS x MS` 和 `MS x D` 的旧 fallback 已消失。当前 `MS x MS` 为 38 cycle、18 prepare，`MS x D` 为 54/9。prepare 大于单次 7-stage 深度，是因为一个 workload 中存在多个 cold route metadata，且 counter 统计 command head 等待 control FIFO 的实际周期；它不再表现为 `263 x commandCount`。所有 24 个 case 的 checksum、command/response 数和 useful MAC 数一致。

`HS x HS` 中 TrIP、TrGT、TrGS 都是 14 cycle，并不表示论文的 mode selector 应把该类别改选 TrIP。这个 single-row workload 只有一个有效乘法，三个 sparse mode 都退化成一条 command 加相同 PE pipeline；阵列级 subrow packing、cache traffic 和跨 row load balance 仍不在 7.2 边界内。

测试还输出 `controlPrepareCycles`、`route`、`MAC`、`MRN`、`response` 五个 counter，以及 `commandCount`、`responseCount`、`usefulMacs`、`laneSlots` 和 checksum。`controlPrepareCycles` 表示 cold route 阻塞；其余四个是各 stage 持有 valid payload 的 occupancy delta，不是互斥阶段延迟，也不包含 CSR/DMA/cache/C write。完整端到端周期仍以 7.1 的最终 C write response 为准。

本轮外层 paper-width Spinal transform/RTL generation 为 319.05 秒，仿真 top elaboration/generation 为 0.99 秒，Verilator 编译 1074.07 秒，RTL 仿真 126.84 秒，完整命令实际耗时 1524 秒。运行时间不属于表中的硬件 cycle。生成的 RTL 中正好有 7 个串联的 `DualModeMrnLevel`，没有 tree ingress register，也没有 production 补充 `DualModeMrnNode` 实例；paper 配置的四个 temporal port 都连接 level-0 不同 node。detailed table 中每个 workload 的 `mrnCycles` 仍等于有效 command occupancy，说明空间路径保持 II=1 和原有 backpressure 语义。24 个周期与上一轮逐项一致；本轮 benchmark 将 programming port 保持无效，因此使用默认 profile，programming latency 不计入 command-to-response 周期。独立 `DualModeMrnNodeSpec` 已验证对一个指定 profile/level/node 写入后只有对应配置 word 更新。

相对上一轮 306.60/928.11/122.10 秒，分布式双 profile control store 增加了宿主机 RTL/Verilator 编译时间。它避免了集中 `Mem` 在 paper-width 下产生 127 个异步读口，但主机编译耗时不能直接等价为硬件面积或功耗；需要目标工艺综合比较配置寄存器、decoder 和被删除的动态选择逻辑。7.2 不经过 `GustavsonTileEngine` 的跨 response temporal merge。

### 7.3 Full paper array 的状态

旧版 `EndToEndCycleSimulation --scale=paper` 曾在超宽 cache memory inference 阶段失败。当前 cache 已拆成 `bank x way` data/tag SRAM，解决了原先 `lineWidth x ways` 单块 memory 的结构问题，但本轮只按要求重新验证了 7.1 小规模完整系统和 7.2 paper 单 PE row，尚未重新完成 128-row、16 MB cache 的全量 Verilator 编译与仿真。因此当前仍没有可信的 full paper array 周期，不能把 RTL 生成成功或旧失败记录当作结果。

## 8. 为什么当前趋势与论文不符

当前结果可以用来验证功能和 RTL 数据流，但不能直接当作论文性能复现。主要原因如下。

### 8.1 阵列规模不同

小规模端到端测试是 4 rows x 16 MAC；论文单 row 测试是 1 row x 128 MAC；论文的阵列级结果通常还依赖 128-row 并行。单 row 无法表现 row-level load balance、跨 row 广播、全局结果合并和阵列级 cache 并发。

### 8.2 workload 不同

当前 single-row 测试使用可控的合成 128x128 矩阵，并且只有一个 output row 有效。论文趋势通常来自真实矩阵、实际 fiber 长度分布、tile 划分和不同 row 的不均衡。相同平均 density 不代表相同 effectual MAC 数、命令数或 cache miss 数。

### 8.3 mode 的命令拆分还不是论文完整 scheduler

当前测试为了覆盖全部组合，使用明确的、可验证的 command grouping：DenseIp 一列一命令，TrGt 按 B column 和 A bank 分组，TrGs 按 A nonzero 和 B line 分组，TrIp 使用当前动态 packing。论文中的 scheduler 还会联合考虑 subrow、buffer occupancy、跨 row 负载和 memory availability。paper 单-row TrIP 已能在 tile 内缓存并复用 route controls，所有 cold route 都进入固定深度 metadata pipeline；当前 repeated-source/不规则 pattern 也已经由通用 Benes 每级控制驱动，而不是 direct-distribution bridge。命令拆分和物理分发结构与论文不同时，周期和面积排序仍会不同。

### 8.4 计时边界不同

`EndToEndCycleSimulation` 包含 DMA/cache/C write；`PaperSinglePERowCycleSimulation` 只包含 PE row command-to-response。一个 mode 在计算层更快，不代表在 cache miss、DMA 等待或写回拥塞后仍然更快。两张表不能直接比较，也不能直接与论文的 kernel-only 或 normalized throughput 图比较。

当前端到端写回支持默认 16 个有序 outstanding requests，消除了逐结果等待 response 的串行瓶颈。cache/DMA 读接口已经可以配置多个 refill stream；本轮 7.1 small profile 使用 2 个 channel，paper profile 配置为 4 个 channel，但仍不是论文 2 TB/s HBM 的 burst、bank timing 和竞争模型。C write 仍是一个有序 request stream。

### 8.5 memory system 和 NoC 仍是模型

paper profile 已配置为论文的 4 clusters、128 banks、16-way、64 B line 和 16 MB 总容量，但替换策略采用当前 RTL 的 round-robin，DMA 仍是固定 latency 模型，也尚未实现论文平台的真实 2 TB/s HBM、32x32 cluster crossbar 时序、NoC 竞争和 multicast routing。因此 TrGt/TrGs 的 memory advantage 或 DenseIp 的带宽瓶颈仍不一定呈现为论文中的比例。

### 8.6 hardware implementation 仍有结构差异

TrIP 已有两级 descriptor elastic FIFO、独立 route metadata FIFO、A/B control FIFO 和 7-stage/II=1 metadata pipeline，旧的约 263-cycle graph-coloring fallback 已删除。broadcast、compacted spread、repeated-source block multicast 和不规则 route 都驱动同一棵 Benes fabric；paper-width 通用控制由 `TrapezoidBenesRouteControl.sv` 生成并输出 `routable`。7.2 的 `MS x MS`、`MS x D` 和 `D x D` 均通过真实 Benes path 完成功能验证。当前 7 个 stage 只寄存 `routeValid + sourceIndex` metadata，完整 control word 只在末端生成，避免了宽控制字在每级复制；但图遍历 task 仍在末端组合完成，尚未拆成论文意义上的逐级、每周期一个 metadata step 的状态机。因此控制器面积、关键路径和功耗还不能视为论文 PPA 结果。

论文 TrGT 把不同 A rows 映射到一个 PE row 的四个 subrows。当前 scheduler 已实现 occupancy-aware 多行 packing、长行多 subrow 占用、两个 group descriptor/A context，以及按实际 line count 分配的完整 B-window group buffer。cache request/response 使用 `groupId + sourceId + lineId`，每条长期 B stream 有独立 cursor 和完成位；每个 local bank 内又有 K 个 SRAM-backed virtual FIFO head。B-line payload 仍通过 inferred `Mem.readSync` 建模一拍 local-buffer 读，valid bitmap、descriptor、cursor 和 outstanding 状态仍是寄存器控制面。background group 可以连续预取所有有效 A 的 B lines，但当前单 cache request 端口仍只有一个 issue point，尚未达到论文多通道 steady-state 带宽；这些 inferred memory 也尚未绑定目标工艺的同步 SRAM BlackBox。

PE row 已使用精确 `log2(macLanes)` 深度、总计 `macLanes-1` 个空间 arithmetic node 的 `DualModeMrnTree`，没有额外全宽 ingress register；每个 node 包含 coordinate comparator、门控 adder 和结果选择，level 末端有 elastic register。TrGT ordered merge 使用 port 0；TrGS 四 bank SRAM RMW 使用四个 temporal port，shared 主路径不再为 old+delta 复制独立 adder。temporal response 现在有真实 elastic register，level 仲裁可以在空间 pipeline 被 response backpressure 填满时暂停空间 payload 并完成 temporal RMW，避免环形死锁；无 owner 时还会 drain clear 前遗留的 temporal response。

仍有差异：augmented tree 已用逐 node `reduce/rightShift/outputCount` control、固定距离 level-local 2:1 switch stages，以及显式 `{count,head,tail}` subtree links 替代宽动态 Vec 索引和 balanced tail selector；metadata `Vec` 也已经按每级实际 node-link 数缩小。`SpatialMrnControlMemory` 现在是可写的双 profile 分布式 control store，mapper-facing programming port 已提升到 Unified 顶层，可以配置 node mode、物理 subtree 启停、局部 route shift 和 temporal owner，并检查配置与物理 topology 是否一致。但仍没有软件可见的 CSR address map、layer descriptor 到写序列的 loader 或 mapper 生成器；command 仍只携带两个 profile ID。subtree 配置只能选择固定物理 slice，route 只能覆盖本 node 的 right-child shift，temporal owner 也只能选择该 node 已静态连接的端口，不能任意重构 node-to-node topology。分布式寄存器保证所有 node 并行读，但 paper-width 有 254 个 control word；是否应改成紧凑 backing SRAM 加 active shadow register，需要综合后决定。

虽然 arithmetic node 已从 `O(N log N)` 降为严格 `N-1`，增加的 head/tail metadata register 和 control decoder 是否小于删除的 selector/mux、整体面积和关键路径是否改善，仍需综合确认。TrGS 的 slot 管理、bank replay、SRAM 控制和输出扫描保留在外围 FSM，standalone legacy 配置也仍保留本地 accumulator arithmetic；当前 temporal 端口是从已有 tree node 中静态选取，并非论文 RTL 的已知逐级 merge-control 实现。TrGT 已能让 request decision 与上一拍 response commit 重叠；radix-4 control 统一产生 selected source、MRN request 和 consume，四个 response source 前是同步 SRAM + elastic head queue，4-entry response-tag scoreboard 允许多 command 在途，PE response enqueue 可以与 temporal merge 并行。array scheduler 的长期 B stream identity 已消除正常 packed-group 内的 B-row order epoch，但物理 subrow 的全局 `(row,column)` head barrier 仍是组合比较网络，group 终止仍需显式 drain，且只有一个 cache issue point。queue 使用 inferred synchronous `Mem`，尚未绑定目标工艺 SRAM macro；TrGS slot/replay 也尚未统一到同一套长期 merge-control。MRN 复用的真实面积、关键路径和动态功耗仍需综合与 activity-based power analysis。

cache cluster 已有 client credit、cache 内 request ordering、跨 cluster client ROB、每 bank 多个 MSHR（small 为 2、paper 为 4）、多个 refill channels（small 为 2、paper 为 4）以及 bank/way SRAM。它仍缺少同地址 miss coalescing、同 set 冲突 replay、真实 HBM channel mapping 和论文内存网络时序。

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

1. 将 TrGT descriptor/cursor/valid/completion 控制面、K 路 virtual FIFO 和现有 B group `readSync` memory 映射到目标工艺的同步 SRAM/elastic queue BlackBox；增加多 request issue，使 background group 的整组预取能真正与 execute group steady-state 重叠。
2. 将当前已能工作的 paper-width 组合式 Benes route solver 进一步拆成真正逐级的 metadata/control pipeline，使图遍历本身也满足 II=1，并对每级 switch control 做综合面积、时序和功耗比较。
3. 在已经贯穿 Unified 顶层的 MRN programming port 上实现软件可见 CSR address map、busy/error/status、layer descriptor loader 和 mapper 配置生成器；增加配置 legality/readback 测试，并决定采用当前分布式 active words，还是采用紧凑 backing SRAM 加逐 layer shadow load。随后综合比较旧 769-cell prefix scan、新 127-node tree 在 control store、head/tail metadata register、switch mux、面积、关键路径和 switching activity 上的净变化。
4. 在已完成长期 `groupId/sourceId` B stream、四端口 MRN 算术复用、同步 SRAM/elastic head queue 和 tagged multi-outstanding 的基础上，把当前组合式跨 subrow head barrier 流水化，并评估是否能让相邻 packed group 在不破坏 output-row 顺序时共享 temporal partial 生命周期。再把 TrGS 的 slot/replay/output 控制统一到同一套长期 merge-control，并统一 standalone 与 mode-shared 路径。
5. 在现有多 MSHR/多 refill channel 上增加同地址 miss coalescing、同 set/bank replay、channel QoS 和真实 DMA burst/channel mapping。
6. 将 cache replacement、Gustavson partial buffers 等剩余 inferred memory 也绑定为目标工艺/FPGA memory macro，验证端口数、读延迟、write mask、冲突与初始化语义。
7. 为 7.2 增加可选的连续 warm-tile 模式，与当前默认 cold-tile 结果并列输出，量化 control cache 在真实 tile sequence 中的命中收益。
8. 导入论文相同矩阵，或至少使用相同 row/fiber length histogram、tile mapping 和 mode selection，而不只匹配平均 density。
9. 扩展 array scheduler 的 row balancing、buffer occupancy 和 memory availability 联合决策；当前动态 B packing 只依据所有活动 row 的最大 effectual-MAC 数。
10. 继续细分 setup、memory、route-control、MAC/MRN、merge 和 writeback；PE counter 是 stage occupancy，不是互斥阶段时长。
11. 在 SRAM BlackBox/层次化 Verilator 基础上再次尝试 128-row full array，并对齐 32x32 cluster crossbar、2 TB/s HBM、DMA burst 和 NoC/multicast 竞争。若比较面积和功耗，还需固定目标工艺、时钟约束、综合脚本和 switching activity。

在这些条件全部满足之前，当前结果最适合表述为“真实 RTL 对当前实现的数据流和计时边界进行验证”，而不是“复现论文性能曲线”。
