# 本地短读长序列从头组装服务

本服务提供基于 de Bruijn 图的短读长（FASTQ 语义）从头组装能力。全程本地运行，不依赖任何外部服务。
支持多任务并发、批次幂等、中断恢复、增量组装与一致性快照查询。

## 1. 构建与启动

### 环境要求

- JDK 17
- Maven 3.9+

### 构建

```bash
mvn clean package
```

构建产物为可执行 fat jar：`target/genome-assembly-java-1.0-SNAPSHOT.jar`。

### 启动

```bash
# 方式一：java -jar（推荐）
java -jar target/genome-assembly-java-1.0-SNAPSHOT.jar

# 方式二：mvn spring-boot:run
mvn spring-boot:run
```

服务默认监听 `8080` 端口，数据目录为工作目录下的 `data/`。可通过系统属性覆盖：

```bash
java -Dserver.port=9090 -Dgenome.data-dir=/path/to/data \
     -jar target/genome-assembly-java-1.0-SNAPSHOT.jar
```

启动后访问 `http://localhost:8080/api/tasks` 返回任务列表（初始为空数组 `[]`）即表示服务可用。

## 2. REST 接口

所有接口路径前缀为 `/api/tasks`，请求与响应均为 JSON。

| 方法 | 路径 | 说明 | 成功状态码 |
|------|------|------|-----------|
| POST | `/api/tasks` | 创建组装任务 | 201 |
| GET | `/api/tasks` | 列出全部任务快照 | 200 |
| GET | `/api/tasks/{taskId}` | 查询单个任务快照 | 200 |
| POST | `/api/tasks/{taskId}/batches` | 提交批次 | 200 |
| POST | `/api/tasks/{taskId}/assemble` | 触发组装 | 200 |
| GET | `/api/tasks/{taskId}/result` | 获取组装结果 | 200 |

### 2.1 创建任务

`POST /api/tasks`

请求体为可选的组装参数 JSON（见第 3 节）。请求体为空或 `{}` 时使用全部默认值。

```bash
curl -X POST http://localhost:8080/api/tasks \
     -H 'Content-Type: application/json' \
     -d '{}'
```

响应：

```json
{
  "taskId": "a3f2b1c4-5d6e-7f89-abcd-ef0123456789",
  "state": "CREATED",
  "readCount": 0,
  "batchCount": 0,
  "config": { "kmerSize": 21, "minCoverage": 2, "maxTipLength": 42, "qualityTrimThreshold": 0, "minReadLength": 21 },
  "stats": { "contigCount": 0, "totalLength": 0, "longestLength": 0, "n50": 0 },
  "error": null
}
```

### 2.2 提交批次

`POST /api/tasks/{taskId}/batches`

请求体为批次对象：

```json
{
  "batchId": "batch-001",
  "reads": [
    { "id": "read-1", "bases": "ACGTACGTACGTACGTACGTACG", "qualities": "IIIIIIIIIIIIIIIIIIIIIII" }
  ]
}
```

字段说明：

- `batchId`：批次唯一标识，用于幂等去重。重复提交同一 `batchId` 返回 `409 Conflict`，不计入新数据。
- `reads`：读段列表，不能为空。
- `reads[].id`：读段标识，不能为空。
- `reads[].bases`：DNA 序列，仅允许 `A/C/G/T`（大小写均可）。
- `reads[].qualities`：Phred+33 质量串，可为 `null`（表示无质量信息，按高质量处理）；若提供，长度必须与 `bases` 一致。

成功响应返回更新后的任务快照（状态变为 `RUNNING`）。

### 2.3 触发组装

`POST /api/tasks/{taskId}/assemble`

无请求体。基于当前已受理的全部批次派生组装结果，返回状态为 `COMPLETED` 的任务快照。可重复调用，每次都基于最新数据重新派生。

### 2.4 获取组装结果

`GET /api/tasks/{taskId}/result`

任务未组装时返回 `409 Conflict`。已组装时返回：

```json
{
  "config": { "...": "..." },
  "readCount": 600,
  "batchCount": 3,
  "contigs": [
    { "id": "contig-1", "sequence": "ACGT...", "length": 1980, "meanCoverage": 27.3 }
  ],
  "stats": { "contigCount": 1, "totalLength": 1980, "longestLength": 1980, "n50": 1980 }
}
```

### 2.5 错误响应

所有错误均返回 JSON 错误体 `{"error": "原因描述"}`，不会出现空响应：

| 状态码 | 含义 | 触发场景 |
|--------|------|---------|
| 400 | Bad Request | 非法参数、非法碱基、空批次、质量串长度不匹配 |
| 404 | Not Found | 任务标识不存在 |
| 409 | Conflict | 重复提交批次、任务尚未组装即查询结果 |

## 3. 可配置参数

创建任务时通过 JSON 请求体传入，未提供的字段使用默认值。

| 参数 | 含义 | 取值范围 | 默认值 |
|------|------|---------|--------|
| `kmerSize` | k-mer 长度（de Bruijn 图边长度） | 3..31 的奇数 | 21 |
| `minCoverage` | k-mer 最低保留覆盖度，低于该值视为测序错误路径剔除 | >= 1 | 2 |
| `maxTipLength` | 悬挂末端分支最大移除长度（边数），超过则保留 | >= 0 | 42（2×k） |
| `qualityTrimThreshold` | 末端低质量修剪的 Phred 阈值，0 表示不修剪 | 0..93 | 0 |
| `minReadLength` | 参与组装的最短读长（修剪后），短于该值的读段被丢弃 | >= kmerSize | 等于 kmerSize |

质量值参与错误识别的方式：

1. 当 `qualityTrimThreshold > 0` 时，读段首尾质量低于阈值的碱基被修剪。
2. 每个 k-mer 窗口只有当其最低 Phred 质量达到下限时才被计数：`qualityTrimThreshold > 0` 时取该阈值，否则取内置下限 Q20。携带低质量碱基（通常是测序错误）的 k-mer 不进入图，低频错误路径因此被剔除。
3. `qualities` 为 `null` 时按高质量处理（不进行质量过滤）。

## 4. 一致性语义

- **确定性**：相同输入与相同参数重复组装，输出逐字节相同。contig 按长度降序、序列字典序排列，与内部 HashMap 迭代顺序无关。
- **顺序无关**：批次提交顺序不影响最终结果。引擎只维护 k-mer 覆盖度计数，图构建为派生计算。
- **增量一致性**：后续批次到达时在既有计数上累加，最终结论与一次性提交全部批次的结果完全一致。
- **快照一致性**：查询返回任务在锁内构造的不可变快照，不会读到半更新数据。并发提交与并发查询同时进行时，数据不丢失、不重复计入。

## 5. 恢复机制

- 每个批次在受理时原子落盘到 `<dataDir>/tasks/<taskId>/batches/` 目录（先写 `.tmp` 临时文件，再原子移动）。批次文件只增不改。
- 服务重启时扫描全部任务目录，重放批次文件重建引擎状态。
- 批次 `batchId` 去重保证：同一批次重复提交只计入一次，重启重放也不重复处理、不遗漏。
- 进程在批次受理后、组装完成前任意时刻被强制终止（`kill -9`），重启后已受理数据完整保留，重新组装的结论与中断前逐字节一致。

## 6. 统计指标口径

| 指标 | 含义 |
|------|------|
| `contigCount` | 组装得到的连续序列（contig）数量 |
| `totalLength` | 全部 contig 长度之和 |
| `longestLength` | 最长 contig 的长度 |
| `n50` | 将 contig 按长度降序排列，累计长度首次达到 `totalLength/2` 时对应的 contig 长度 |

每个 contig 的 `meanCoverage` 为该路径上全部 k-mer 覆盖度的算术平均值。

## 7. 验证步骤与结果

验证命令：

```bash
mvn clean test
```

测试覆盖以下场景（共 17 个用例，全部通过）：

- **DnaEncodingTest**：2-bit 编码往返、反向互补与字符串形式一致、反向互补对合性、歧义碱基检测。
- **KmerAssemblyEngineTest**：
  - 高覆盖（30x）低错误（0.5%）数据还原参考主干（总长 >= 95%），无参考中不存在的 contig；
  - 中覆盖（15x）高错误（3%）数据剔除低频错误路径，无新片段，主干 >= 90%；
  - 质量值区别对待：全部低质量读段不产生任何 contig，高质量版本正常组装；
  - 乱序提交与增量追加：组装结果逐字节相同，且与一次性提交完全一致；
  - `minReadLength` 等参数可配置；
  - 高覆盖（500x）大批量（2 万+ 读段）在 30 秒内完成，统计指标正确。
- **TaskStoreRecoveryTest**：批次原子落盘、重启重放、重复批次不重复计入，重放后组装结论与连续组装一致。
- **TaskConcurrencyTest**：4 个任务并发提交与组装互不干扰；同任务并发提交（含重复提交）与并发查询时快照始终自洽，最终计数不丢不重。
- **TaskControllerTest**（MockMvc 全链路）：创建任务、提交批次、触发组装、查询结果的完整流程；空批次、非法碱基、空白 batchId、质量串不匹配、任务不存在、未组装即查结果、非法参数等异常场景均返回可区分错误码，拒绝后服务保持可用。
- **ProcessRecoveryTest**：用真实 `java` 命令启动服务，提交批次后 `kill -9` 强杀，重启后数据不丢、结论逐字节一致；继续追加批次的结果与一次性提交完全一致；重复提交返回 409。

## 8. 项目结构

```
src/main/java/com/github/guoyyit/genomeassemblyjava/
├── Application.java              # 启动类
├── engine/
│   ├── DnaEncoding.java          # DNA 2-bit 编码与反向互补
│   └── KmerAssemblyEngine.java    # 增量式 de Bruijn 图引擎
├── model/                        # 不可变数据模型（record）
├── service/
│   ├── AssemblyTask.java          # 单任务（锁、校验、快照）
│   └── TaskManager.java           # 任务创建、并发管理、启动恢复
├── storage/
│   └── TaskStore.java             # WAL 持久化与重放
└── web/
    ├── TaskController.java        # REST 接口
    └── ApiExceptionHandler.java   # 统一错误响应
```
