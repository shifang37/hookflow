# Changelog

本项目的变更记录。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 修复

- `scripts/drill.ps1`（D1–D3）不再在 demo 库留下 `drill-*` 租户：结束前逐表删除自己建的租户，`finally` 兜底受控中止，
  pre-flight 清扫被硬中断留下的同类租户，`-KeepTenant` 可保留——与 `scripts/backup-restore.ps1` 已验证的修法一致
- 三处 IT flake 的根因已修，CI 不再靠 rerun：
  - `ReportService.latency` 把 `latencyStats` 与 `latencyHistogram` 放进同一个 `REPEATABLE READ` 事务，
    两个查询读同一 InnoDB 快照，`samples` 与桶计数不可能再差几行（CI 曾见 `samples=3809`、`buckets=3803`）
  - `BatchReplayIT.cleanUp` 先把租户的非终态 delivery 置为 `CANCELED` 并删 outbox，堵住 claim/发布窗口；
    再等所有 `IN_PROGRESS` attempt 结算，最后按外键顺序删除，消除 `DELETE FROM delivery` 的 FK 竞争
  - `EventIngestService.ingest` / `ingestBatch` 用 `RetryOnLockConflict` 重试与 relay 的 outbox 锁冲突，
    这正是 `ReadApiIT.setUp` 偶发 `DeadlockLoserDataAccess` 的来源；重试安全，因为失败事务已回滚且 idempotency key 不变

### 测试 / 构建

- 根 `pom.xml` 的 `coverage` profile 增加 `jacoco:check`：单测 LINE 覆盖率低于 28%（2026-11-04 基线 29.1%）时构建失败；
  Java SDK 自己的 80% 门禁不变
- 前端新增 `src/api/queries.test.tsx`（26 个用例）：真实 `QueryClient` + mocked `api`，覆盖全部查询/变更 hooks、
  轮询预算、缓存键、`enabled` 门控、`keepPreviousData` 与每个 mutation 的失效路径；`queries.ts` 覆盖率 24% → 100%
- 后端新增 `EventIngestRetryTest`：死锁时 ingest 重试整个事务、第二次只创建一个事件，并断言
  `hookflow.db.lock_conflict{operation=event.ingest}` 计数为 1；单测总数 174 → **175**，LINE 覆盖率 28.3% → **29.1%**

## [0.4.0] — 2026-11-03

主题：**从「能讲」到「能运维」**。v0.3 用测量证明了平台的性质，这一版把它变成能点、能看、能部署、能接入的东西：
一个打进 jar 的演示控制台、一个独立前端工程的运营控制台、Java SDK、Helm chart、Prometheus + Grafana 监控、
完整的 CI/CD 发布链；同时把**持续投递吞吐从 41 events/s 抬到 455–470 events/s**。

> **版本号说明**：`v0.3.0` 之后 `pom.xml` 一直停在 `0.3.1-SNAPSHOT`，README 把演示控制台记作「v0.3.1」，
> 但从未打过这个 tag，而 README 与 docs 早已按 v0.4 描述。本版把 pom、CHANGELOG 和 git tag 对齐到 **0.4.0**：
> `v0.3.0` 之后的全部改动（含演示控制台）都属于 0.4.0。`docker.yml` 会把 `v0.4.0` tag 发布为
> `ghcr.io/shifang37/hookflow:v0.4.0`。

### 新增 — 演示控制台（`/demo/`）

- **模拟业务系统**：两个租户各自的订单服务，跑真实的 `order.created → order.paid → order.shipped`
  生命周期，通过**公开 HTTP API**（带真实 API Key）提交事件。不走内部服务调用，因为那样就跳过了
  认证、限流、背压和 429——恰好是业务系统在过载时真正感觉到的东西
- **假客户接收方**：应用内一个真实 HTTP 服务（`127.0.0.1:9199/hook/<租户>`），自己用 HMAC 验签，
  并且可以被切成「返回 500 / 慢 5 秒 / 直接断连」，分别对应 `SERVER_5XX`、`TIMEOUT`、`CONNECT_ERROR`
- **面板**：每秒轮询 `GET /v1/demo/state`，显示投递流水（含重放按钮）、每租户 p50/p99、outbox 积压、
  429 速率、接收方最近收到的 webhook 与验签结果、以及每条 webhook 的 trace id
- **五个场景按钮**：正常投递 / 客户挂了 / 大租户饿死小租户 / 加上并发预算 / 过载与降载。
  按钮只做「改配置、灌事件」这类操作，流量走真实链路
- 静态页打进 jar，**没有前端构建步骤**（[ADR-0012](docs/adr/0012-demo-console.md)）
- `/v1/demo/**` **无鉴权**，但只在 `all` profile + `hookflow.demo.enabled=true` 时存在——安全边界是
  「bean 不存在」而不是密码（[docs/demo.md](docs/demo.md) §4）
- `DemoConsoleIT`（5 个用例）：租户供给、模拟流量产生真实投递且接收方验签、5xx→重试→死信→重放恢复、
  预算按钮真的改库、状态载荷字段完整

### 新增 — v0.4 生产就绪债（A1 + A2 + A4）

- **`delivery_attempt` 保留策略（A1）**：`V3` 加 `idx_attempt_started`，`worker/DeliveryAttemptRetention` 按批清理
  `status <> 'IN_PROGRESS' AND started_at < NOW(3) - INTERVAL n DAY`；配置 `hookflow.retention.attempt-days`
  （默认 7，`0` 关闭）与 `hookflow.retention.interval-ms`；指标 `hookflow.attempt.pruned`。
  **`IN_PROGRESS` 的行永不删除**——`settle` 的 UPDATE 带这个条件，删掉会让一次在途尝试的结果静默消失
- **批量提交 `POST /v1/events/batch`（A2）**：最多 500 条，**一个事务**、固定 5 条 SQL 写 event/delivery/outbox；
  部分重复按条返回 `replay:true` + 原 `eventId`，同批内重复的键解析到第一次出现；限流与降载**按事件数计**；
  整批未提交返回 503。吞吐提升来自减少事务与往返次数，不是并发。同机对照：同一到达率 450 events/s 下，
  单条路径开环 p50 1.10s、批量 42.7ms（[performance.md §14](docs/performance.md)）
- **统一批量操作重试（A4）**：`common/RetryOnLockConflict`（3 次 + 递增退避，只重试
  `PessimisticLockingFailureException`），`EndpointService.pause` 与 `DemoController.reset` 共用同一实现；
  新指标 `hookflow.db.lock_conflict{operation}`
- 新测试：`BatchIngestIT`、`AttemptRetentionIT`、`RetryOnLockConflictTest`、`DemoResetRetryTest`、
  `DeliveryAttemptRetentionTest`、`TenantRateLimiterTest`；`EndpointPauseRetryTest` 改为验证公共组件
- `scripts/bench.ps1` / `tools/LoadGen` 支持 `-BatchSize`（`--batch-size`），`--rate` 始终是 events/s，
  用于单条 vs 批量的同机对照

### 新增 — 吞吐：41 → 455–470 events/s（B0–B8，[performance.md §15–§23](docs/performance.md)）

- **B0 再基线**：A2 之后重新量下游，发现平台**持续投递**只有 **41 events/s**——v0.3 的「300–350/s」是入口接受速率，
  不是送达速率；瓶颈在 relay 的逐条 publish + 逐条 confirm。[capacity.md](docs/capacity.md) 据此整体改写
- **B3 语句摘要**：`scripts/bench.ps1 -StatementDigest` 用 MySQL `performance_schema` 把一次事务拆到语句级：批量入口下
  一次 ingest 事务约 14.5ms，三条多行插入合计只占约 1.9ms，其余是那次提交
- **B4 relay 批量发布**（[ADR-0014](docs/adr/0014-batched-relay-publish.md)）：一批先全部发送、再一起等 confirm，
  确认过的行一条 UPDATE 标记，未确认的一律放回 `NEW`；`hookflow.relay.batch-size` 可配。relay 发布 **41 → 400–466/s**。
  崩溃重复窗口的粒度因此从一行变成一批，语义见 [delivery-semantics.md](docs/delivery-semantics.md)
- **B5 worker 并发 8 → 16**：持续吞吐 **152 → 264 events/s（+74%）**；`MQ_CONCURRENCY` / `MQ_PREFETCH` / 连接池三个旋钮一起量
- **B6 负结果（已回退）**：worker 的 settle 批量提交，提交 −36% 但吞吐 −25%——固定并发下 worker 是延迟受限的，
  杠杆是并发不是提交次数（[§20](docs/performance.md)）
- **B7 + B8 新默认值**：worker 32 并发 + 连接池 32 + relay batch 100。450 入口下持续投递 **264 → 455–470 events/s**；
  900 入口下 worker 先满（382–421/s），relay 还能发 449–731/s（[§21–§22](docs/performance.md)）
- **V4 索引 A/B**（[§23](docs/performance.md)）：`idx_delivery_updated_status` 让图表查询 **316 → 132ms（2.4×）**，
  代价是 settle −10%、insert −7%

### 新增 — 运营控制台（`/ui/`，源码在 [`frontend/`](frontend/README.md)）

- **独立前端工程**：React 18 + TypeScript + Vite + Ant Design + React Query，五个页面（事件流、投递、Endpoints、租户、系统监控）
  全部接真实接口；`npm run build:backend` 把产物写进 `src/main/resources/static/ui/`（gitignored），后端从 `/ui/` 提供，
  `UiForwardController` 把客户端路由兜到 `index.html`。两个凭证对应两个信任域：租户 API Key 走 `/v1/**`，运营令牌走 `/v1/admin/**`
- **控制台读接口**（`AdminConsoleController` / `ReportService` / `ReportMapper`，口径表在 [api.md](docs/api.md)）：
  租户列表/详情/统计、平台概览、时间序列、延迟分布、API Key 元数据列表（SQL 里不出现 `key_hash`）；
  `PATCH /v1/admin/tenants/{id}` 改限流/权重/并发预算，并支持 `status: SUSPENDED / ACTIVE`——停用是生命周期不是删除，
  Key、Endpoint 与投递历史全部保留，新事件 `403`；迁移 `V4` 加 `idx_delivery_updated_status`
- **Endpoint 软删除** `DELETE /v1/endpoints/{id}` → `204`：待投递的 `PENDING`/`RETRY_WAIT` 立刻 `CANCELED`，迟到的消息记
  `ENDPOINT_DELETED`；行保留（投递历史的外键），但 `GET`/`pause`/`resume`/列表/fan-out 一律看不到它。迁移 `V5` 用
  STORED 生成列 `url_key` 做「只约束活着的行」的唯一键，所以同一个 URL 删掉后可以立刻重建
- **事件详情与批量重放**：`GET /v1/events/{id}`（payload + 扇出投递）、`POST /v1/deliveries/replay`（最多 100 条，
  逐条独立事务，HTTP 永远 200，结果逐条返回）
- **同步端点探测** `POST /v1/endpoints/{id}/test`：用 endpoint 已保存的密钥发一条合成 webhook，不写 event/delivery/attempt；
  每 endpoint 每秒一次，超限 `429`
- 控制台页面：投递 attempt 历史抽屉、租户停用/启用、Endpoint 测试发送、事件流与投递页按时间范围筛选
  （`from`/`to`，UTC，`to` 为开区间）、390px 视口可用
- 前端测试 **87 个**（16 个文件），`tsc --noEmit` + eslint 进 CI；覆盖率语句 70.79% / 分支 72.51% / 函数 60.81% / 行 69.72%（Vitest 4 口径）
- 新增错误码 `40904 ALREADY_EXISTS`

### 新增 — Java SDK（[`sdk/java/`](sdk/java/README.md)）

- 独立 Maven 项目 `dev.hookflow:hookflow-java-sdk:0.1.0`，**Java 17+**，OkHttp 4.12 + Jackson 2.17（不是服务端的 Jackson 3）
- 单条 / 批量发送事件；客户端生成 `Idempotency-Key` 且**重试复用同一个 key**；重试是手写循环（不用 Failsafe，理由见
  [remaining-plan.md §J.2](docs/remaining-plan.md)）
- 接收方验签工具：时间戳窗口 + 常量时间比较；签名串与服务端 `HmacSigner` 逐字对齐，**顺手修正了文档里写错的签名串格式**
- 66 个测试（JUnit 5 + MockWebServer），JaCoCo LINE 92.9%，`LINE >= 80%` 门槛 `haltOnFailure`；`sdk.yml` 在 `sdk/java/**` 变更时跑
- 发布 workflow `sdk-publish.yml`（tag `sdk-v*` → GitHub Packages）已写好，**尚未实际发布**；当前只能 `mvn install` 后本地引用

### 新增 — 监控（[monitoring.md](docs/monitoring.md)）

- compose profile `monitoring` 起 Prometheus + Grafana，自动加载 **3 个面板**（概览、投递、JVM）与 **2 条告警规则**
  （`monitoring/alerts/hookflow.yml`），告警实测触发
- 补了两个指标缺口：`hookflow.delivery.attempted`（计数）与 `hookflow.delivery.duration`（Timer，投递耗时分位数），
  `DeliveryProcessorMetricsTest` 覆盖
- 明确不是生产监控方案：没有 Alertmanager、远程存储和鉴权边界

### 新增 — Helm chart（[`helm/hookflow/`](helm/hookflow/README.md)）

- 同一个镜像跑成 api / relay / worker 三种角色，只有 api 有 Service/Ingress；MySQL / RabbitMQ / Redis **不在 chart 里**，
  `config.*` 指向外部地址
- `values-dev.yaml` / `values-prod.yaml`，readiness / liveness 接 actuator，HPA 可选（需要 metrics-server）；要求 Kubernetes 1.28+
- kind 上实测：三角色 Ready、滚动更新、杀 worker、HPA；`helm.yml` 在 `helm/**` 变更时 lint + template
- `helm-publish.yml`：tag `helm-v*` → `oci://ghcr.io/shifang37/charts`，`helm-v0.1.0` 已发布且可匿名拉取

### 新增 — CI/CD（README「CI/CD」一节）

- `ci.yml`：单测 → `-Pcoverage` 的 JaCoCo → `verify`（Testcontainers IT）→ package，外加前端 job（typecheck / lint / test:coverage / build）；
  Codecov 步骤没有 token 时跳过并打印原因；失败时把测试报告打进日志
- `docker.yml`：push `master` → `ghcr.io/shifang37/hookflow:latest` + `:sha-<短>`；tag `v*` → `:v0.4.0`；先 `npm ci && npm run build:backend`
  再 buildx（Dockerfile 不跑 npm）。**run #1 已验证**镜像推送成功
- `helm.yml` / `helm-publish.yml` / `sdk.yml` / `sdk-publish.yml`；`python-sdk.yml` 预置（`sdk/python/` 出现后自动生效）
- 三种发布 tag 互不匹配：镜像 `v*`、Helm `helm-v*`、SDK `sdk-v*`
- README 换成实时徽章；Vitest 3.2.7 → **4.1.12**，修掉 CI 上 `onTaskUpdate` 超时导致的 exit 1

### 变更

- **默认值**（都来自实测，见上文 B7/B8）：`hookflow.relay.batch-size` **100**、worker 并发 `MQ_CONCURRENCY` **32**、连接池 **32**
- `all`（本地演示）profile 增加演示用的取值：租户水位线 200、读超时 3s、退避基数 0.8s。
  **这些不是生产建议值**，生产默认值未变
- 演示截图：[demo-console.png](docs/demo-console.png)、[demo-console-retries.png](docs/demo-console-retries.png)
  （headless Chrome 真实渲染）
- `docs/capacity.md` 按 B0–B8 整体改写（v0.3 的 300–350/s 是入口数字，不是送达数字）；新增 ADR-0013（批量摄入）、
  ADR-0014（批量发布）；交接文档 `docs/remaining-plan.md` + `docs/next-steps.md` 为权威，`docs/v0.4-plan.md` 只保留 backlog
- 测试：96（v0.3.0）→ 后端 **245**（174 单元 + 71 集成）+ 前端 **87** + Java SDK **66**

### 未完成 / 已知（不掩饰）

- **Python SDK** 未开始（`sdk/python/` 不存在，只有 workflow 预置）；Java SDK 未发布到任何远程仓库
- **A3 备份恢复演练**未做（唯一没覆盖的数据丢失场景）；**4C8G 真机基线、传输层 SPI + Kafka、令牌桶限流**未开始
- P3 的 2000 events/s 目标未达：当前 455–470 events/s，下一个瓶颈是 worker 的两次提交（[§20](docs/performance.md)）
- Codecov 上传未验证（没有 `CODECOV_TOKEN`）；后端单测覆盖率有数字（LINE 28.3%，IT 不计）但没有门槛
- ~~三个已知的 IT flake 根因未修（`ReadApiIT.latencyHistogramIsComplete` 两条 SQL 不是同一快照、`BatchReplayIT.cleanUp`
  清理时后台仍在写、`ReadApiIT.setUp` 死锁），CI 靠 rerun~~ —— **已于 2026-11-04 修复**，见 [Unreleased](#unreleased)
- 控制台没有登录与 RBAC（只有运营令牌 + 租户 Key）；`/demo/` 无鉴权且前端没有自动化测试，只应在本地 `all` profile 打开

## [0.3.0] — 2026-10-22

主题：**用可复现的测量说清瓶颈在哪、扩容先加谁。** 计划的红线是「测不出来就不写数字」，所以本轮的每个数字都指向 `docs/perf/` 下的一份原始输出。

### 新增 — 多租户公平调度

- **在 outbox → broker 边界做准入 + 加权轮询**（[ADR-0009](docs/adr/0009-fair-scheduling.md)）。不分片队列，也不在消费端做：一个租户的积压留在 MySQL，broker 里最多只有 `max_in_flight` 条属于它，因此别人的排队位置与它的积压量**无关**
- `tenant.max_in_flight`（默认 32）、`tenant.weight`（1–100）、`endpoint.max_concurrency`（默认 0 = 不限）；运行时改库即可，生效 ≤ 1s
- 迁移 `V2`：`outbox` 增加 `tenant_id` / `endpoint_id`（INSTANT ALTER + 回填）、`tenant`/`endpoint` 预算列、`delivery(tenant_id, status)` 索引
- 实测：同一场景下受害租户 p99 **4 584ms → 190ms**（`FairnessIT`）；压测环境 **4 278ms → 639ms**（[performance.md §7](docs/performance.md)）

### 新增 — 背压与降载

- **两级水位 + 明确语义**（[ADR-0010](docs/adr/0010-backpressure-and-shedding.md)）：`50301 OVER_CAPACITY`（平台，503）与 `42902 TENANT_BACKLOG_EXCEEDED`（租户，429），都带 `Retry-After`；一个租户的洪水不再变成全平台的 503
- **broker 队列长度上限**（`x-max-length` + `reject-publish`）：队列满时 broker 拒收，relay 把行放回 `NEW`，积压回到它本来就在的 MySQL
- **worker 有界**：listener 容器固定线程数 + 有界 `prefetch`；消息到达但预算已满时按 `hookflow.worker.over-capacity-policy`（`defer`/`requeue`）处理，两种代价都写清了
- 实测：过载 3 倍时，水位 10 000 → p50 905ms / p99 2.7s / 积压 10 258；水位 200 → p99 **84ms**、92% 请求 2.3ms 内拿到 429、积压 15 秒归零

### 新增 — 严格的延迟测量 + JVM 证据

- `dev.hookflow.tools.LoadGen`：**开环**压测器，同一次运行同时输出「按应发时刻计时」和「按实发时刻计时」两个视角；后者就是闭环工具会报的数
- `scripts/bench.ps1`：一次运行产出 `loadgen.txt` / `timeline.csv` / `gc.log` / `jfr.jfr` / `metrics.txt` / `db.txt` / `meta.txt`；默认先清库（否则测的是上一轮）
- 结果：coordinated omission 在过载时让 p99 低估 **11.9 倍**（905ms vs 229ms）
- 证据：GC 只占 wall time 的 0.25–0.40%；JFR 里 78 秒只有 3.2 秒 on-CPU 采样；连接池 `pending` 恒为 0；`innodb_flush_log_at_trx_commit` A/B 让 p99 −39% —— 瓶颈是 **DB 提交**，已写进 [capacity.md](docs/capacity.md)
- **明确标注没测的**：wrk2 交叉验证、async-profiler 火焰图、4C8G 真机

### 新增 — OpenTelemetry trace 贯通

- [ADR-0011](docs/adr/0011-distributed-tracing.md)：W3C `traceparent` 存进 `DeliveryMessage`（outbox 的 JSON payload，**权威副本**），同时写 AMQP header；relay 与 worker 从它续接 span，出站 POST 带 `traceparent`
- 依赖用 `spring-boot-starter-opentelemetry`（Boot 4 把 tracing autoconfig 拆成独立模块，只加 bridge 会有类无 bean）；OTLP **导出默认关闭**，`management.otlp.metrics.export.enabled=false` 避免顺带推 OTLP metrics
- API 响应新增 `X-HookFlow-Trace-Id`；接收方可用标准 W3C 上下文接上，不需要知道 HookFlow 的存在
- `TracingIT`：断言接收方 `traceparent` 的 trace id 与响应头一致（5 跳一条链路）

### 新增 — 文档

- [docs/performance.md](docs/performance.md)：方法、四问答案、吞吐阶梯、协调遗漏实测、瓶颈判定、优化前后对比、**测量本身犯过的三个错误**、复现命令、明确不声称的部分
- [docs/capacity.md](docs/capacity.md)：容量公式 + 四项约束的本机实测值 + 4C8G 区间 + 「先加谁」的指标判据
- [docs/v0.3-checklist.md](docs/v0.3-checklist.md)：逐项验收，⚠️/⬜ 项都写明原因
- ADR-0009/0010/0011、failures/0007

### 修复

- **批量取消 vs 逐行认领死锁**（[failures/0007](docs/failures/0007-cancel-vs-claim-deadlock.md)）：`EndpointService.pause` 做批量 `UPDATE`，与 worker 按主键逐行认领的加锁顺序相反，1 500 行并发时被 InnoDB 选为牺牲者。改为事务级重试（3 次递增退避），`EndpointPauseRetryTest` 覆盖。v0.2 的测试每次只有一两条 delivery，撞不上
- **CI 从来没跑过**：workflow 只监听 `main`（默认分支是 `master`），且 `mvnw` 是 mode 644，在 Linux 上 `./mvnw` 会 Permission denied。两处都修了；第一次真实运行立刻抓到一个 flaky 集成测试
- **`ReceiverFaultIT.refusedConnectionIsConnectError` 在 CI 上偶发失败**：它用"刚释放的临时端口"制造连接拒绝，CI 上该端口可能在投递前被别的监听者占用，于是 attempt **成功**、`error_class` 为 null。改为固定端口 1（非特权进程永不监听），并按 `(event, endpoint)` 精确定位 delivery（多 endpoint 时扇出顺序未定义，"最新一条"不是那条）；断言消息里带上 attempt 状态。CI 失败时现在会把测试报告打进日志

### 变更

- `POST /v1/admin/tenants` 新增 `maxInFlight` / `weight`；`POST /v1/endpoints` 新增 `maxConcurrency`
- listener 容器线程数与 `prefetch` 改为可配置（`MQ_CONCURRENCY` / `MQ_PREFETCH`），`all` profile 默认固定线程数（有界）
- `hookflow.fairness.enabled=false` 可退回 v0.2 行为（按 id 发布、无准入），用于对照测量
- 测试从 67 增至 **96**（73 单元 + 23 集成）

### 计划未完成（不掩饰，见 [v0.3-checklist](docs/v0.3-checklist.md)）

- wrk2 与 async-profiler 两项没有跑成：本机没有二进制、镜像拉取失败。用自研开环压测器与 JFR 采样替代，**没有对拍**
- Jaeger UI 未人工验证：所有 `jaegertracing/all-in-one` tag 在本机 `docker pull` 都失败（`failed size validation`），链路连通性由 `TracingIT` 的断言证明
- 令牌桶限流、SSRF DNS rebinding、`delivery_attempt` 保留策略、`stale-publishing-seconds` 拆分：仍留待后续

## [0.2.0] — 2026-10-22

主题：**把"能跑"变成"有测试保障"**。v0.1 的每条可靠性主张，现在都能指向一个可重放的测试或一次真实演练。

### 新增 — 安全

- **SSRF 出网防护**（`EgressGuard`）：创建 endpoint 时拒绝回环、私有、链路本地（含 `169.254.169.254` 云元数据）、CGNAT、IPv6 ULA、保留段与组播；支持显式 host 白名单；仅本地演示 profile 放开
  - 对 IPv4 全地址空间做属性测试，穷举校验范围分类
- 生产 profile（api/relay/worker）默认严格，`all`（本地演示）profile 显式放开

### 新增 — 运维

- **outbox 保留策略**：`hookflow.relay.retention-days`（默认 7），批量清理 PUBLISHED 行，避免表无限增长
- `OutboxMapper.countUnpublished()`：统计 `NEW + PUBLISHING`。单看 `NEW` 会在积压最严重时读到 0（行在两个状态间振荡），是一个会谎报健康的指标
- 三个角色分配默认端口（8080/8081/8082），使同机多角色运行成为可能

### 新增 — 测试

- **jqwik 属性测试**（16 个属性，共 26 个测试方法）：退避抖动区间、签名完整性与防篡改、订阅匹配语义、IPv4 范围分类
- **`ReceiverFaultIT`**：慢接收方 → `TIMEOUT`、连接被拒 → `CONNECT_ERROR`、故障消失后同一个 delivery 自动成功
- **`BrokerOutageIT`**：冻结 RabbitMQ 后事件仍被接受、`attempt` 数为零、工作停在 outbox；恢复后自动追平且只尝试一次
- **`OutboxCrashWindowIT`**：确定性重建「确认后崩溃」窗口——sweeper 重新武装 + 消息重复 + attempt 护栏吸收
- 测试总数 34 → **67**（47 单元 + 20 集成）

### 新增 — 演练

- `scripts/drill.ps1`：把 api/relay/worker 作为独立 JVM 启动，真杀进程、真停容器
- D1 relay `kill -9`：5 个事件停在 outbox，重启后 4 秒追平，全部 `attemptCount=1`
- D2 worker 请求中途 `kill -9`：attempt 1 `ABANDONED/LEASE_EXPIRED`，attempt 2 成功，11 秒收敛
- D3 停 Redis / 暂停 RabbitMQ：事件均被接受，工作停在 outbox，恢复后 2 秒追平

### 修复（v0.2 测试与演练发现）

- `docs/failures/0003`：broker 停机时 outbox 行卡在 `PUBLISHING` —— `DeliveryPublisher` 把 `convertAndSend` 放在 try 之外，`AmqpException` 绕过 relay 的 `releaseBack`，快速恢复路径形同死代码
- 演练发现的部署缺陷：三个角色都默认监听 8080，同机多角色必撞

### 变更

- `docs/adr/0008`：放弃 Toxiproxy，改用容器 pause/unpause + 确定性状态构造（理由与代价已记录）
- README、运维手册、投递语义文档同步更新

## [0.1.0] — 2026-10-22

首个可用版本：核心投递链路完整，语义与故障行为有测试支撑。

### 新增

**业务能力**
- 多租户 + API Key 鉴权（只存 SHA-256 前缀与哈希，常量时间比较）
- Endpoint CRUD、订阅过滤（精确匹配 / `*`）、暂停与恢复
- 事件提交，`Idempotency-Key` 租户内去重，重复请求返回同一 `eventId`
- 事件扇出到多个 endpoint，为每个 (event, endpoint) 建立独立 delivery
- 投递查询：状态、次数、下次重试时间、完整 attempt 历史（响应码 / 耗时 / 错误分类）
- `DEAD` 投递手动重放（只加一次额度，保留 attempt 历史）
- HMAC-SHA256 请求签名，绑定 event_id / delivery_id / 时间戳
- Endpoint 签名密钥 AES-256-GCM 加密落库，带密钥版本字段

**可靠性**
- MySQL 事务 Outbox，`FOR UPDATE SKIP LOCKED` 领取，publisher confirm 后才标记发布
- `PUBLISHING` 卡死自动回收（崩溃窗口恢复）
- Worker 以 `attempt_count` CAS + `lease_token` fencing 认领，网络调用不持锁
- 指数退避 + equal jitter，可配置上限与每 endpoint 最大尝试次数
- 4xx 不重试（408/429 除外）、5xx/超时/连接失败重试
- lease 过期回收 + attempt 标记 `ABANDONED`
- 卡住的 `PENDING` 自动重扫（broker 丢消息不丢投递）
- 已发布但未产生效果的 outbox 行在宽限期后重新武装

**工程**
- Spring Boot 4.1.1 + JDK 21 + MyBatis 4.1 + Flyway + springdoc 3
- 模块化单体，按 profile 启动 `api` / `relay` / `worker` / `all`
- Redis + Lua 租户限流，`fail-open` 可配置
- 统一错误体 + `X-Request-Id` 贯穿日志 MDC
- Prometheus 指标（投递结果、队列健康、恢复路径触发次数）
- Flyway `V1__init.sql`（8 张表，含 CHECK 约束与关键索引）
- Docker Compose 一键起依赖，Dockerfile 多阶段 + 非 root + healthcheck
- GitHub Actions CI（unit → verify → package）
- 21 个单元测试 + 13 个集成测试（Testcontainers，真实 MySQL 8.4 / RabbitMQ 4）
- 纯 JDK 的 MockReceiver，可按 query 参数模拟 2xx/4xx/5xx/慢响应
- 三个角色的 README、7 篇 ADR、投递语义规格、API 文档、运维手册、失败案例模板

### 修复（开发期由集成测试发现）
- `docs/failures/0001`：并发幂等提交时输掉竞争的一方返回 500 —— MySQL REPEATABLE READ
  快照在失败事务中看不到赢家，补偿查询必须放到事务之外
- `docs/failures/0002`：重试永不触发 —— JVM（UTC+8）与 MySQL（UTC）两个时钟被比较；
  改为所有时间运算在数据库内完成，Java 只传时长

### 已知限制
- 不保证 exactly-once（at-least-once），不保证顺序
- outbox / attempt 无保留策略，只增不删
- 无背压、无租户公平调度
- 无 SDK、无管理 UI
- 未提供压测数据（v0.3）
- 真实故障演练记录尚未产出（v0.2）
