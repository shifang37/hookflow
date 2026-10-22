# Changelog

本项目的变更记录。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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

### 变更

- `POST /v1/admin/tenants` 新增 `maxInFlight` / `weight`；`POST /v1/endpoints` 新增 `maxConcurrency`
- listener 容器线程数与 `prefetch` 改为可配置（`MQ_CONCURRENCY` / `MQ_PREFETCH`），`all` profile 默认固定线程数（有界）
- `hookflow.fairness.enabled=false` 可退回 v0.2 行为（按 id 发布、无准入），用于对照测量
- 测试从 67 增至 **96**（73 单元 + 23 集成）

### 计划未完成（不掩饰，见 [v0.3-checklist](docs/v0.3-checklist.md)）

- wrk2 与 async-profiler 两项没有跑成：本机没有二进制、镜像拉取失败。用自研开环压测器与 JFR 采样替代，**没有对拍**
- Jaeger UI 未人工验证：所有 `jaegertracing/all-in-one` tag 在本机 `docker pull` 都失败（`failed size validation`），链路连通性由 `TracingIT` 的断言证明
- 令牌桶限流、SSRF DNS rebinding、`delivery_attempt` 保留策略、`stale-publishing-seconds` 拆分：仍留待后续

## [Unreleased]

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
