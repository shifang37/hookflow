# Changelog

本项目的变更记录。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased] — v0.2 计划

### 计划新增
- 故障注入自动化（Toxiproxy）：把手工 `kill -9` 演练变成 CI 可重放的测试
- 属性测试 / 模型测试（jqwik）验证状态机不变式
- OpenTelemetry trace 贯通 `api → outbox → relay → MQ → worker → receiver`
- `docs/failures/` 补三份真实演练记录（D1 relay 崩溃 / D2 worker 崩溃 / D3 依赖宕机）
- outbox / delivery_attempt 保留策略
- SSRF 防护：拒绝内网地址与出网 IP 白名单

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
