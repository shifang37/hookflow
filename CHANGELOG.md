# Changelog

本项目的变更记录。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased] — v0.3 计划

### 计划新增
- 多租户公平调度：按 `tenant_id` 分片队列 + 加权轮询，防止大租户饿死小租户
- 背压与降载：有界队列、拒绝策略、积压时的降级
- wrk2 无 coordinated-omission 的延迟测量 + JFR/火焰图/GC 调优前后对比
- OpenTelemetry trace 贯通 `api → outbox → relay → MQ → worker → receiver`
- 容量模型文档（多少 worker 支撑多少 TPS，瓶颈在哪一类资源）

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
