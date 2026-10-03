# 本地短读长序列从头组装服务

一个完全本地运行的短读长（FASTQ 语义）从头组装 Web 服务：创建任务、提交带质量值的
读段批次、触发基于 de Bruijn 图的组装、查询连续序列（contig）与统计指标。
服务仅依赖 JDK 17 与本地文件系统，不使用数据库、消息队列或任何外部服务。

## 构建与启动

前置条件：JDK 17、Maven 3.8+（依赖在首次构建时从 Maven 中央仓库下载，之后可离线构建）。

```bash
# 完整构建并运行全部自动化测试
mvn clean test

# 构建可执行 jar（Spring Boot fat jar）
mvn package -DskipTests

# 真实启动服务（默认端口 8080，数据目录 ./data/tasks）
java -jar target/genome-assembly-java-1.0-SNAPSHOT.jar

# 自定义端口与数据目录
GENOME_ASSEMBLY_PORT=9090 \
GENOME_ASSEMBLY_DATA_DIR=/var/lib/genome-assembly/tasks \
java -jar target/genome-assembly-java-1.0-SNAPSHOT.jar
```

启动后访问 `GET http://localhost:8080/health`，返回 `{"status":"UP"}` 即表示服务可用。
服务以 Spring Boot 内嵌 Tomcat 持续运行；启动时自动扫描数据目录并恢复全部历史任务。

## 接口一览

所有接口路径前缀为 `/api/tasks`，请求与响应均为 `application/json`。

| 方法 | 路径 | 说明 | 成功状态码 |
| --- | --- | --- | --- |
| POST | `/api/tasks` | 创建任务，请求体可为空或只给部分参数 | 201 |
| GET | `/api/tasks` | 列出全部任务的状态快照 | 200 |
| GET | `/api/tasks/{taskId}` | 查询单个任务快照 | 200 |
| POST | `/api/tasks/{taskId}/batches` | 提交一个读段批次（幂等） | 201 首次 / 200 重复 |
| POST | `/api/tasks/{taskId}/assemble` | 触发组装 | 200 |
| GET | `/api/tasks/{taskId}/result` | 查询组装结果 | 200 |
| GET | `/health` | 健康检查 | 200 |

### 创建任务

请求体（所有字段可省略，省略时取默认值；字段结构与 `AssemblyConfig` 完全一致）：

```json
{
  "kmerSize": 21,
  "minCoverage": 2,
  "maxTipLength": 42,
  "qualityTrimThreshold": 20,
  "minReadLength": 21
}
```

响应 `TaskSnapshot`：

```json
{
  "taskId": "5f8d1d5e-...",
  "state": "CREATED",
  "readCount": 0,
  "batchCount": 0,
  "config": { "kmerSize": 21, "minCoverage": 2, "maxTipLength": 42,
              "qualityTrimThreshold": 20, "minReadLength": 21 },
  "stats": { "contigCount": 0, "totalLength": 0, "longestLength": 0, "n50": 0 },
  "error": null
}
```

### 提交批次

```json
{
  "batchId": "batch-001",
  "reads": [
    { "id": "read-1", "bases": "ACGT...ACGT", "qualities": "IIII...IIII" }
  ]
}
```

- `bases` 只接受明确碱基 `A/C/G/T`（大小写不敏感），长度 1..1,000,000；含 `N` 等
  模糊碱基返回 400 并指明出错位置。
- `qualities` 使用 Phred+33（Sanger/Illumina 1.8）ASCII 编码，允许 `!`(33)..`~`(126)；
  提供时长度必须与 `bases` 一致；省略时该读段不做质量门控。
- 同一 `batchId` 重复提交只计一次：首次返回 **201** 且 `duplicate=false`，
  重复返回 **200** 且 `duplicate=true`，两者可明确区分，既不静默重复也不静默丢弃。

回执示例：

```json
{ "taskId": "...", "batchId": "batch-001", "duplicate": false,
  "snapshot": { "...": "提交后的任务快照" } }
```

### 触发组装与查询结果

`POST /api/tasks/{taskId}/assemble` 同步完成组装并返回快照，`state` 为 `COMPLETED`
（异常输入导致失败时为 `FAILED` 并给出 `error`）。

`GET /api/tasks/{taskId}/result` 在未完成时返回 409 `not_completed`；完成后返回：

```json
{
  "config": { "...": "本任务使用的参数" },
  "readCount": 1234,
  "batchCount": 3,
  "contigs": [
    { "id": "contig_000001", "sequence": "ACGT...", "length": 432,
      "meanCoverage": 28.71 }
  ],
  "stats": { "contigCount": 2, "totalLength": 650, "longestLength": 432, "n50": 432 }
}
```

新批次在组装完成后到达时，旧结果失效，任务状态回到 `CREATED`，需要再次触发组装；
再次组装始终基于全部已受理批次，结果与一次性提交完全一致。

## 可配置参数

| 参数 | 含义 | 取值范围 | 默认值 |
| --- | --- | --- | --- |
| `kmerSize` | de Bruijn 图的 k-mer 长度（边长度），参与组装的短片段长度由该参数决定，代码中不写死 | 3..31 | 21 |
| `minCoverage` | k-mer/(k+1)-mer 保留的最低出现次数；低于该值视为低频测序错误并剔除 | 1..1,000,000 | 2 |
| `maxTipLength` | 悬挂末端分支（tip）的最大长度（边数），不超过该长度的无入度/无出度线性末端被移除；0 表示不移除 | 0..1,000,000 | 42（=2k） |
| `qualityTrimThreshold` | Phred 质量阈值：先从读段两端修剪低于阈值的碱基，随后任何包含低质量碱基的 k-mer 都不参与计数；0 表示关闭质量门控 | 0..93 | 0 |
| `minReadLength` | 修剪后仍短于该长度的读段被丢弃 | `kmerSize`..1,000,000 | 21 |

服务端配置项（`application.properties`，可用同名环境变量覆盖）：

| 属性 | 环境变量 | 默认值 |
| --- | --- | --- |
| `server.port` | `GENOME_ASSEMBLY_PORT` | 8080 |
| `genome-assembly.data-directory` | `GENOME_ASSEMBLY_DATA_DIR` | `data/tasks` |

## 组装算法与质量值的使用

1. **预处理**：读段两端按 `qualityTrimThreshold` 修剪；修剪后短于 `minReadLength`
   的读段丢弃。
2. **质量门控计数**：窗口扫描全部 k-mer 与 (k+1)-mer；只要窗口内含低于阈值的碱基，
   该 mer 就不计数。低质量错误碱基因此无法形成受信路径，而高质量碱基正常参与，
   低质量与高质量碱基被明确区别对待。未提供质量串或阈值为 0 时不做质量门控。
3. **规范 mer**：计数采用正链/反向互补中编码较小的 canonical mer，读段方向不影响结果。
4. **低频纠错**：出现次数低于 `minCoverage` 的节点与边直接剔除，高频真实路径保留。
5. **建图**：以长度 k-1 的 mer 为节点、长度 k 的 mer 为边构建双向对称 de Bruijn 图，
   边权为该 (k+1)-mer 的观测次数；删边同步删除反向互补镜像边。
6. **图简化**：迭代执行「去悬挂末端（tip clipping）」与「简单重复分支合并
   （bubble popping）」——同一分叉点出发、在汇合点收敛的简单路径只保留平均覆盖度
   最高的一条（并列时序列字典序较小者优先），覆盖度足够的真实线性连通性不受损。
7. **contig 提取**：从分叉点/端点出发沿唯一入度出度合并线性链；按 canonical 序列去重
   （一条 contig 与其反向互补只输出一次），按长度降序、序列升序排序，
   id 依次为 `contig_000001`……，`meanCoverage` 为链上边权算术平均并四舍五入到两位小数。

## 一致性、确定性与恢复语义

- **确定性**：相同输入与相同参数的组装结果逐字节一致。计数使用 canonical mer；
  建图、简化、遍历、排序全部使用有序集合与确定的并列裁决规则；id 按最终顺序生成。
  批次提交顺序、读段方向、并发调度顺序均不改变最终输出。
- **增量一致**：后续批次在已有计数上累加；任意中间结论只用于查询，最终结论等价于
  把全部批次一次性提交后组装。
- **并发一致**：每个任务独立加锁，任务之间无共享可变状态；查询返回的是锁内构造的
  不可变快照，任何时刻读到的 `readCount == 已受理读段总数`、批次/计数始终自洽，
  并发提交不丢失、不重复、不错乱。
- **崩溃恢复（WAL）**：每个批次先写入临时文件并 `fsync`，再原子 rename 发布并
  fsync 目录；只有发布成功才对客户端算受理。启动时删除残留 `.tmp`，扫描
  `batches/` 目录按序号重放，以 `batchId` 去重。因此在「受理之后、组装之前」
  任意时刻 `kill -9`，重启后已受理序列既不重复也不遗漏；组装本身是纯派生计算，
  不产生额外持久化状态。
- **幂等**：`batchId` 是去重键，首次受理返回 201，重复提交返回 200 + `duplicate=true`。

## 统计指标口径

- `contigCount`：去重排序后输出的 contig 条数。
- `totalLength`：全部 contig 长度之和。
- `longestLength`：单条 contig 的最大长度；无 contig 时为 0。
- `n50`：将 contig 按长度降序累加，使累计长度首次达到或超过
  `ceil(totalLength / 2)` 时那条 contig 的长度；无 contig 时为 0。

## 错误响应

统一 JSON 结构 `{ "status": <code>, "error": <code>, "message": <reason>,
"timestamp": <iso-8601> }`：

| 场景 | HTTP | error |
| --- | --- | --- |
| 请求体缺失/不是合法 JSON | 400 | `malformed_json` |
| 参数越界、非法碱基、质量串长度不符、空批次、批次/读段 id 非法或重复 | 400 | `invalid_request` |
| 任务标识不存在 | 404 | `task_not_found` |
| 未完成时查询结果、组装进行中再次提交/组装 | 409 | `not_completed` / `invalid_state` |
| 其他未预期错误 | 500 | `internal_error` |

所有拒绝都不改变服务与其他任务的状态，拒绝后服务继续可用。

## 容量边界

- k-mer/(k+1)-mer 计数使用开放寻址的原始类型 `long->int` 哈希表（无装箱、无链表对象），
  内存随 distinct mer 数量线性增长且有界；单批次读段数量上限 10,000,000，
  单读段长度上限 1,000,000，计数饱和到 `Integer.MAX_VALUE` 防止超高覆盖溢出。
- 自动化测试包含覆盖深度约 120x、单批数百条读段的场景，要求在合理时间内完成且
  contig 全部为参考序列（或其反向互补）的子串、统计指标正确。

## 已执行的验证

以下命令与场景均已在本地真实执行（结果以实际运行为准）：

```bash
mvn clean test
```

覆盖场景（均为真实链路调用，空响应或状态错乱即判失败）：

- 真实 Tomcat 启动与 `/health` 探测（`TaskControllerTest` 使用 RANDOM_PORT 发真实 HTTP 请求）。
- **真实进程级验证**：`ProcessRecoveryTest` 先用 Maven 构建可执行 fat jar，
  再以 `java -jar` 启动进程、提交批次、`destroyForcibly()`（SIGKILL 语义）强杀，
  重新以相同命令启动，校验重放不重不漏，并比对「中断后增量追加」与
  「从未中断一次性提交」的统计结果完全一致。
- 端到端：创建任务 → 提交带质量批次（高/低覆盖、1%/5%/8% 错误率）→ 组装 → 查询结果，
  断言状态码、contig、`totalLength/longestLength/n50` 全部合法。
- 组装正确性：contig 不得包含参考序列中不存在的连续片段；覆盖度足够时还原 ≥85% 主干。
- 质量参与：全低质量读段不产生任何受信 k-mer，高质量重复读段正常组装；
  低频高质量伪装错误由 `minCoverage` 剔除。
- 乱序提交、增量追加与一次性提交逐字节等价；重复组装结果相同。
- 图简化后继续追加批次仍还原主干；悬挂末端与简单重复分支被移除。
- 多任务并发互不干扰；同任务并发提交 + 并发查询不丢失、不重复、快照一致。
- 重复批次返回可区分的 201/200 与 `duplicate` 标志。
- 非法参数、非法碱基、空批次、格式错误 JSON、不存在任务均返回可区分错误码，
  且服务在拒绝后保持可用。
- k-mer 长度可配置（非写死），高覆盖大容量批次在限定时间内完成。
