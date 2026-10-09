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
```

输出位于 `generated-cached-gustavson/`。

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

当前 accumulator 是寄存器式坐标 CAM 和确定性最小坐标选择网络，适合功能验证和小规模配置。TrGT subrow/FIFO 已由独立前端实现；后续仍需把 FIFO 和 accumulator 映射到本地 SRAM，并将最小坐标选择网络替换为可流水化 merge tree。

### 2.11 TrGT/TrGS 前端与多 PE row 阵列

按照论文 Fig. 15--17 的数据移动方式，新增了：

- `TrGtSubrowScheduler`：将一个 PE row 划分为 4 个逻辑 subrow，每个 subrow 配置独立 B FIFO，每次最多从每个 FIFO 发出一个元素；
- `GustavsonBFifo`：使用同步 `Mem` 推断本地 SRAM bank，支持预取 head、独立 Stream 背压、occupancy、满队列停顿和环回；
- `TrGsSpatialEngine`：把一个 A 非零值广播到 cache-line 内所有有效 B lane，并保留 B 的列坐标用于空间分发和结果累加；
- `GustavsonArray`：为每个 PE row 复制独立的 Gustavson accumulator、命令/结果 Stream 和状态接口；
- `GustavsonRowScheduler`：把共享命令流按 tile 分配给空闲 PE row，同一 tile 固定在同一 row，最终命令送出后可重叠执行下一个 tile，并将共享结果端口锁定到当前 tile 的 `endOfTile`。

TrGT 前端暴露 accepted/issued B-element 计数，便于检查 FIFO 停顿和利用率。调用方应在最后一个 B 元素完成 Stream 握手后拉高 `finish`；前端会先排空四个 FIFO，再发送 `endOfTile`。多 PE row 阵列允许每行独立选择 `TrGt` 或 `TrGs` 并独立背压；共享调度器采用 first-free 分配，不根据 density 自动改变 mode，同一 tile 内也允许逐命令显式切换 `TrGt`/`TrGs`。

这些模块已经实现论文数据移动方式的功能等价路径。TrGT FIFO 已使用四个同步 memory bank，但由于 B 元素包含 `aIndex/k/column/value`，其 typed word 宽度不同于当前结果 buffer；两种用途尚未统一成同一个可切换的 `BankedLocalBuffer` 实例。TrGS 当前通过带坐标的组合适配完成分发，尚未实现流水化物理分发网络。

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
- adapter 串行请求整段 B row，并把每条 cache line 转换成带背压的 `PERowCommand`；
- 最后一条 cache line 继承调用方的 `endOfTile`，因此同一输出 tile 可以连续累加多段 B row；
- 对零长度、超配置长度、地址空间越界和非 Gustavson mode 进行显式拒绝，不产生 cache 请求；
- cache error 或 request ID 不匹配会置位 `memoryError`；若 tile 已打开，还会发送空的结束命令释放 PE row，避免调度器永久停在半开状态；
- 暴露 requested/emitted line、cache hit/miss/refill、PE row 分配和完成 tile 等状态计数。

`CachedGustavsonSystem` 使用 cache client 0，并集成 `GustavsonRowScheduler`、`BankedReadCache` 和外部 refill Stream。相同 valid density 的 B row 可以由调用方分别选择 `TrGt` 或 `TrGs`；adapter 不统计 density，也不会修改 mode。当前地址仍由软件提供固定宽度 B-row 区域，CSR row pointer 解析和 DMA 尚未实现。

## 3. 当前达到的效果

### 3.1 功能验证

当前共有 32 项自动测试，执行方式为：

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
- Gustavson B-row 非法 mode、地址越界和 refill error 拒绝，以及 cache error 后自动关闭半开 tile。

当前测试结果为 32 项全部通过。

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

### 3.3 当前适用范围

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

它目前还不是可以直接连接 HBM 并独立执行完整矩阵乘法的加速器系统。

## 4. 当前限制

### 4.1 调度器覆盖范围

`TrIpTileController` 已完成 bitmask/IP tile 和跨 K 累加，`TrIpMatrixController` 已完成描述符驱动的 M/N/K 外层循环，cached wrapper 也能从固定跨度的 bitmask fiber 区自动取数，但尚未实现：

- 从内存中的可变长 CSR/CSC 指针和 index 数组自动生成 fiber；
- 从矩阵描述符生成 Gustavson tile，并进行代价感知的多 PE row 负载均衡；
- 面向软件的寄存器组、描述符队列或指令译码器。

当前设计刻意不实现根据 density 自动选择 mode。未来即使增加启发式推荐器，也必须保留软件显式覆盖 mode 的能力。

### 4.2 缺少全局 cache 和 DMA

当前已实现一个参数化 multi-client、multi-bank direct-mapped 读 cache，以及每 bank 一个独立 miss 的共享 refill 协议。TrIP 矩阵前端和 Gustavson B-row 前端都已经与实际 cache、外部 refill 端口封装成完整读系统，但仍未实现论文中的 4 个 cache cluster、完整 32 x 32 cluster 互连、写 cache/dirty eviction、HBM 请求和 DMA。通用顶层的每 PE row 本地 buffer 仍由外部端口控制；TrIP tile controller 使用独立的四 bank 结果 buffer 完成自动 scatter 和有序读回。后续需要把结果写回映射到统一存储层次。

### 4.3 Gustavson 存储和并行调度仍待完成

当前 TrGT/TrGS 已能在任意 lane density 下完成命令内归约、跨命令累加、有序 flush、SRAM subrow FIFO 调度、多 PE row 分配和共享结果仲裁，但尚未完成：

- 将 TrGT typed FIFO bank 与 TrIP 结果 buffer 统一成可按 mode 切换的同一组物理 SRAM；
- 将寄存器式坐标 CAM 替换为可扩展的本地 buffer/merge tree；
- 在 first-free 分配之上增加 row 成本估计、公平仲裁和描述符驱动的负载均衡；
- 从 CSR row pointer 自动生成 B-row cache 区间，以及 DMA/HBM refill 流程。

因此当前已经是可验证的多 PE row、cache/refill 驱动 Gustavson 功能系统，但还不是论文规模的 CSR/DMA/SRAM 实现。

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

### 阶段 2：完成 TrIP 调度器（tile、跨 K 累加与外层 M/N/K 循环已完成）

1. 增加 tile 配置和 A fiber 装载状态；
2. 汇总所有 PE row 的动态列容量；
3. 自动选择本周期发送的 B column 数；
4. 实现 overflow 命令拆分或重放；
5. 将 PE row 结果自动 scatter 到本地 buffer；
6. 从本地 buffer 有序读取并写回输出 tile；
7. 通过同步读改写跨多个 K tile 累加同一个 C tile。

当前已经达到 tile controller 验收标准：测试平台只需逐个装载 K tile 并发送 `start`，控制器会自动完成列规划、必要的 A-group 拆分、PE 命令生成、跨 K 累加和最终有序读回。

矩阵控制器进一步接收一次全局描述符并自动推进 M/N/K tile，软件不再逐 tile 发送 `start`。cached wrapper 已把当前流式 fiber 装载口连接到固定跨度的 cache fiber 记录，并完成运行时基地址范围检查，使软件不再逐 fiber 握手。阶段二剩余工作是解析可变长 CSR/CSC 描述符并连接 DMA。

### 阶段 3：完成 TrGT/TrGS 执行引擎（功能前端与多 PE row 阵列已完成）

1. 把 PE row 划分为最多 4 个可独立调度的 subrow；
2. 使用本地 buffer bank 实现 B-row FIFO；
3. 实现跨周期、跨 cache line 的部分和累加；
4. 实现带坐标比较器的有序 merge tree；
5. 增加 row 完成、flush 和有序写回协议；
6. 为 TrGS 实现 A value 广播和按 N 坐标的 B 分发。

验收标准：TrGT 和 TrGS 可以消费完整 CSR row，并直接输出完整、有序的 C row。

六项均已完成可仿真的功能版本：第 2 项使用四个同步 `Mem` bank 推断 SRAM，但尚未与 TrIP 结果 buffer 复用同一物理实例。TrGS 已具有广播/列坐标分发路径，多个命令可持续累加相同坐标，`endOfTile` 后按坐标排序并背压输出；共享调度器可让多个 PE row 重叠执行，并保证一个 tile 的命令与输出边界不被拆散。固定宽度 B-row 区域已经通过统一 cache 完成 miss/refill/hit，后续重点是解析 CSR row pointer、统一物理 SRAM 和实现流水化 merge tree。

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
3. 将当前组合路由逐步替换为流水化前缀网络和 Benes network；
4. 将归约链改为平衡、可切分的流水化 reduction tree；
5. 再尝试生成和综合 128 x 128 paper 配置；
6. 建立吞吐、利用率、面积、频率和存储流量统计。

验收标准：获得可复现的综合报告和周期级性能数据，再与论文结果进行定量比较。

## 6. 建议的近期任务

下一轮建议实现简单 DMA/AXI refill，并增加 CSR/CSC row/column pointer walker，使软件不再直接提供固定宽度 fiber/B-row 地址。并行工作是把 TrGT typed FIFO 与 TrIP 结果 buffer 统一到可按 mode 切换的物理 `BankedLocalBuffer`，并为 cache/Gustavson 仲裁器增加 round-robin 公平策略。

所有后续控制器都应继续遵守本文的 mode 与稀疏度原则：density 可以参与性能推荐，但硬件接口必须允许调用方在任意 sparsity level 下显式选择任何 mode。
