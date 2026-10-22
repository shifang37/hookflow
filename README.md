# HookFlow

**多租户 Webhook / 事件投递平台：接收事件，签名后异步投递到客户回调地址，失败自动退避重试，全程可追踪、可重放。**

[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2ea44f)](.github/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-ED8B00)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4.x-FF6600)
![Redis](https://img.shields.io/badge/Redis-8-DC382D)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

---

## 一句话解释它解决什么问题

业务系统（比如订单服务）要把 `order.paid` 这类事件推给客户的服务器。客户的服务随时可能返回 500、超时、或者干脆连不上。**如果推送逻辑写在订单服务里，那么：网络抖动会拖慢下单接口，投递失败无人知道，失败之后没有重试，重试又可能把同一个事件推两次。**

HookFlow 把这件事从业务系统里拆出来，变成一个**独立的、语义明确的投递平台**：业务系统只负责"提交事件"，剩下的签名、排队、重试、死信、追踪、重放都由平台承担。

## 它不是

- ❌ 不是 exactly-once 系统。**它是 at-least-once**，并且这里不打算掩饰这一点（见 [ADR-0002](docs/adr/0002-at-least-once-not-exactly-once.md)）
- ❌ 不是微服务集群。它是模块化单体，按运行角色（api / relay / worker）启动（见 [ADR-0004](docs/adr/0004-modular-monolith-with-roles.md)）
- ❌ 没有宣称的 QPS 数字。压测数据在 v0.3 提供，且会附带机器配置与失败率
- ❌ 不是 Svix / Hookdeck 的替代品，没有生产级 SLA

---

## 60 秒跑通

```bash
# 1. 起依赖（MySQL 8.4 + Redis + RabbitMQ）
docker compose up -d
#    若本机 3306 已被占用：DB_PUBLISH_PORT=13306 DB_PORT=13306 docker compose up -d

# 2. 起服务
./mvnw spring-boot:run

# 3. 起一个假接收方（另一个终端）
java -cp target/classes dev.hookflow.tools.MockReceiver 9099
# → listening on http://localhost:9099/hook
```

```bash
# 4. 开租户，拿到 API Key（响应里只会出现这一次）
curl -s -X POST localhost:8080/v1/admin/tenants \
  -H 'X-Bootstrap-Token: hookflow-dev-bootstrap-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo","rateLimit":100}'
# {"tenantId":1,"name":"demo","apiKey":"hk_aB3dE7fG_...","keyPrefix":"aB3dE7fG"}
```

```bash
# 5. 建一个回调 endpoint，拿到签名密钥（同样只出现一次）
curl -s -X POST localhost:8080/v1/endpoints \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"http://127.0.0.1:9099/hook","subscribedTypes":"order.paid"}'

# 6. 提交一个事件
curl -s -X POST localhost:8080/v1/events \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: order-1001-paid' \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"order.paid","payload":{"orderId":1001,"amount":99.00}}'
# 202 {"eventId":1,"deliveryCount":1,"idempotentReplay":false}
```

```bash
# 7. 看结果：接收方收到了什么
curl -s localhost:9099/received

# 8. 看投递状态与每次尝试
curl -s "localhost:8080/v1/deliveries/1" -H "Authorization: Bearer $TOKEN"
# {"delivery":{"status":"SUCCEEDED","attemptCount":1,"lastStatusCode":200},
#  "attempts":[{"attemptNo":1,"status":"SUCCEEDED","statusCode":200,"durationMs":7}]}
```

**现在故意让它失败一次**：让接收方返回 500，观察重试阶梯。

```bash
# 让 MockReceiver 之后都返回 500（不需要重启）
# 触发一次新事件，然后轮询 delivery 状态
curl -s "localhost:8080/v1/deliveries?status=RETRY_WAIT" -H "Authorization: Bearer $TOKEN"
# nextAttemptAt 会按 指数退避 + 抖动 递增；attempt 用完后状态变成 DEAD

# 修好之后手动重放
curl -s -X POST localhost:8080/v1/deliveries/2/replay -H "Authorization: Bearer $TOKEN"
```

> MockReceiver 的行为由 query 参数控制：`/hook?status=500`、`/hook?delay=3000`（触发读超时）。

> **这套流程已在 Windows 11 + Docker Desktop 上实测通过**，包括接收方独立验签（`signature_valid=true`）
> 与幂等重放（同一 `Idempotency-Key` 返回同一 `eventId`）。
> 首次运行需要下载 MySQL / Redis / RabbitMQ 镜像，约 240 MB + 180 MB，视网络 1–10 分钟。

---

## 架构

```
                       ┌──────────────────────────────┐
  业务系统 ──POST──▶   │  api        (Spring Boot)     │
                       │  · API Key 鉴权（只存 hash）   │
                       │  · Redis 租户限流 (Lua)        │
                       │  · MySQL 幂等去重              │
                       └───────────────┬──────────────┘
                                       │  一个事务内写入
                                       ▼
                    ┌────────────────────────────────────────┐
                    │  MySQL 8.4 — 唯一权威状态               │
                    │  event / delivery / delivery_attempt    │
                    │  outbox  ← 待发布的工作消息             │
                    └───────────────┬────────────────────────┘
                                    │ FOR UPDATE SKIP LOCKED
                                    ▼
                       ┌──────────────────────────┐
                       │  relay                    │
                       │  领取 → publish → confirm │
                       │  → 标记 PUBLISHED         │
                       └────────────┬─────────────┘
                                    │ publisher confirm
                                    ▼
                       ┌──────────────────────────┐
                       │  RabbitMQ (quorum queue)  │
                       └────────────┬─────────────┘
                                    │ manual ack
                                    ▼
                       ┌──────────────────────────────────┐
                       │  worker                           │
                       │  claim(CAS+lease) → 签名 → HTTP    │
                       │  → attempt 落库 → 收敛状态         │
                       └────────────┬─────────────────────┘
                                    ▼
                          客户 Webhook 接收方
```

三个角色**同一份代码、同一个镜像**，用 Spring profile 切换：`--spring.profiles.active=api|relay|worker`（不填则 `all`，本地演示用）。

为什么这样切：[ADR-0004](docs/adr/0004-modular-monolith-with-roles.md)。

---

## 投递语义（这是项目的核心，请重点看）

完整说明见 **[docs/delivery-semantics.md](docs/delivery-semantics.md)**。

### 保证什么

| 保证 | 怎么做到的 | 怎么验证的 |
|---|---|---|
| 事件被接受后不会丢 | 事件与 outbox 在同一 MySQL 事务内落库 | `EventIngestIT`、relay 崩溃演练 |
| 同一幂等键只产生一个事件 | `UNIQUE (tenant_id, idempotency_key)` | `EventIngestIT` 20 线程并发测试 |
| 每个 (event, endpoint) 只有一条 delivery | `UNIQUE (event_id, endpoint_id)` | 同上 |
| 同一 delivery 不会被两个 worker 同时投递 | `attempt_count` CAS + `lease_token` 护栏 | `claimIsExclusive` |
| worker 崩溃后投递会被接手 | lease 过期 → 回收 → 重新入队 | `expiredLeaseIsReclaimed` |
| 4xx 不会无意义重试 | 错误分类（见下表） | `clientErrorsAreNotRetried` |
| 接收方能验证请求确实来自本平台 | HMAC-SHA256 + 时间戳 + 绑定 event/delivery id | `successfulDelivery`（接收方自行验签） |

### 明确不保证什么

| 不保证 | 原因 |
|---|---|
| **exactly-once** | 接收方可能已处理但本平台没收到响应。任何承诺端到端 exactly-once 的说法都是错的 |
| **顺序** | 多个 worker 并行消费，同一 endpoint 的 delivery 不保证按提交顺序到达 |
| **投递时间上限** | 指数退避 + 抖动，最长间隔受 `max-delay-ms` 约束，但整体耗时不承诺 |
| **接收方幂等** | 这是接收方的责任。平台提供稳定的 `event_id` 让它去重 |

### 失败分类（决定重试还是一次性判死）

| 接收方行为 | 分类 | 是否重试 |
|---|---|---|
| 2xx | 成功 | — |
| 5xx | `SERVER_5XX` | ✅ 重试 |
| 408 / 429 | `RETRYABLE_4XX` | ✅ 重试 |
| 连接失败 / DNS 失败 | `CONNECT_ERROR` | ✅ 重试 |
| 读超时（> `read-timeout-ms`） | `TIMEOUT` | ✅ 重试 |
| 其他 4xx | `CLIENT_4XX` | ❌ 直接 `DEAD` |
| 3xx（不跟随） | `CLIENT_3XX` | ❌ 直接 `DEAD` |

### 重试阶梯

`delay = min(base × 2^(n-1), max)`，然后 jitter 到 `[delay/2, delay)`。

默认 `base = 5s`、`max = 1h`、`maxAttempts = 8`，实际间隔约为
`2.5–5s → 5–10s → 10–20s → 20–40s → 40–80s → 80–160s → 160–320s → 320–640s`。

**为什么要抖动**：不加抖动，一次 broker 故障会让所有等待中的 delivery 在同一毫秒重试，恢复后的接收方会被同步的请求洪峰打死。

---

## 故障窗口：崩溃了会怎样

这张表是这个项目最值得看的部分。每一行都说明"在哪个位置崩溃"以及"恢复后会发生什么"。

| 崩溃位置 | 数据是否丢 | 是否会重复 | 谁负责收敛 |
|---|---|---|---|
| 写 `event`/`delivery`/`outbox` 事务中 | 不丢（事务回滚，调用方收到错误） | — | 调用方重试 |
| relay 领取后、publish 前 | 不丢（outbox 仍是 PUBLISHING） | 可能重复 | `OutboxSweeper`（60s 后重置为 NEW） |
| **broker 已确认、outbox 未标记** | 不丢 | **会重复** | `OutboxSweeper` 重新发布 → 这正是 at-least-once 的来源 |
| worker claim 后、HTTP 前 | 不丢 | — | lease 过期 → `DeliveryRecovery` 回收 |
| **HTTP 已发出、响应前崩溃** | 不丢 | **会重复**（对方可能已处理） | lease 过期回收；接收方按 `event_id` 去重 |
| worker 写完 attempt、未更新 delivery | 不丢 | — | lease 回收，attempt 标记 `ABANDONED` |
| broker 丢消息 | 不丢 | — | `requeueStuckPending`：PENDING 超过 60s 自动重新入队 |

**核心结论：MySQL 是唯一权威状态，broker 里没有"必须存在"的信息。** 所以丢一条 MQ 消息不等于丢一次投递——这是刻意设计的。

---

## 接口

完整说明见 [docs/api.md](docs/api.md)，在线文档 `http://localhost:8080/docs`。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/admin/tenants` | 开租户并发首个 API Key（需 `X-Bootstrap-Token`） |
| `POST` | `/v1/admin/tenants/{id}/keys` | 追加 API Key |
| `POST` | `/v1/endpoints` | 建回调地址，返回签名密钥（仅一次） |
| `GET` | `/v1/endpoints` | 列出 endpoint |
| `POST` | `/v1/endpoints/{id}/pause` | 暂停（并取消排队中的投递） |
| `POST` | `/v1/endpoints/{id}/resume` | 恢复（已取消的不复活） |
| `POST` | `/v1/events` | 提交事件，需 `Idempotency-Key` |
| `GET` | `/v1/events/{id}` | 查事件 |
| `GET` | `/v1/deliveries` | 查投递（按 event / status 过滤） |
| `GET` | `/v1/deliveries/{id}` | 查投递 + 全部 attempt |
| `POST` | `/v1/deliveries/{id}/replay` | 重放 `DEAD` 投递 |
| `GET` | `/actuator/health` | 健康检查（含 readiness/liveness） |
| `GET` | `/actuator/prometheus` | 指标 |

统一错误体：

```json
{"code": 40901, "error": "IDEMPOTENCY_KEY_REUSED", "message": "...", "requestId": "...", "timestamp": "..."}
```

`requestId` 同时出现在响应头 `X-Request-Id` 和日志 MDC 里，可以直接拿它去搜日志。

---

## 测试：每个测试在证明什么

```bash
./mvnw test      # 47 个单元测试（含 16 个 jqwik 属性测试），秒级，不需要 Docker
./mvnw verify    # 47 单元 + 20 集成 = 67 个测试，起真实 MySQL 8.4 与 RabbitMQ 4
```

| 测试 | 证明的命题 |
|---|---|
| `BackoffTest` / `BackoffPropertyTest` | 退避指数增长、被上限截断、抖动恒落在 `[cap/2, cap)`；对 200 组随机 `(base, max, attempt)` 成立 |
| `SigningPropertyTest` | 任意 secret/id/时间戳/body 都能自验签；任一项变化则验签失败；签名长度与十六进制格式恒定 |
| `SubscriptionPropertyTest` | 订阅匹配是精确匹配；通配符覆盖全部；空白与重复声明不改变语义 |
| `EgressGuardTest` | 回环/私有/链路本地/CGNAT/保留段全部拒绝（对 IPv4 全空间做属性测试）；白名单与演示模式可覆盖 |
| `SecretCipherTest` | 每次加密用新 IV；篡改密文或换主密钥则解密失败 |
| `DeliveryStatusTest` | 状态机语义与文档一致 |
| `EventIngestIT` | 20 并发同幂等键 → 1 个事件；扇出过滤；claim 互斥；lease 回收；outbox 唯一键允许下一轮 |
| `EndToEndDeliveryIT` | 真实链路 2xx/5xx/4xx/replay/pause 五种结局 |
| `ReceiverFaultIT` | 慢接收方 → `TIMEOUT`；连接被拒 → `CONNECT_ERROR`；故障消失后**同一个** delivery 自动成功 |
| `BrokerOutageIT` | broker 冻结期间事件仍被接受、不产生任何 attempt、工作停在 outbox；恢复后自动追平且只尝试一次 |
| `OutboxCrashWindowIT` | 「确认后崩溃」窗口：sweeper 重新武装 → 消息重复 → attempt 护栏吸收，接收方只看到一次尝试 |

**为什么坚持用容器而不是 H2**：`FOR UPDATE SKIP LOCKED`、JSON 列、CHECK 约束、quorum queue 这些行为在替代品里都不一样。更重要的是——下面三个真实缺陷只有"真实 MySQL + 真实 RabbitMQ + 与数据库不同时区的 JVM"才能暴露，H2 同进程跑会全部掩盖：

| 缺陷 | 一句话 | 文档 |
|---|---|---|
| 0001 | 并发幂等时输掉竞争的一方返回 500（REPEATABLE READ 快照看不到赢家） | [docs/failures/0001](docs/failures/0001-idempotent-race-snapshot.md) |
| 0002 | 重试永不触发（JVM 是 UTC+8，MySQL 是 UTC，两个时钟被拿来比较） | [docs/failures/0002](docs/failures/0002-two-clocks.md) |
| 0003 | broker 停机时 outbox 行卡在 PUBLISHING（异常类型没被接住） | [docs/failures/0003](docs/failures/0003-publisher-send-outside-try.md) |

这三个文档比任何功能列表都更能说明这个项目被真正跑过。

## 故障演练（真实进程，真实依赖故障）

`./scripts/drill.ps1` 把三个角色作为独立 JVM 启动，然后真的杀进程、真的停容器。完整记录在 `docs/failures/0004`–`0006`。

```bash
docker compose up -d            # 若 3306 被占用：DB_PUBLISH_PORT=13306 docker compose up -d
./scripts/drill.ps1 -DbPort 13306      # 约 3 分钟
```

| 演练 | 动作 | 实测结果 |
|---|---|---|
| **D1** | `kill -9` relay，期间提交 5 个事件 | 5 行停在 outbox；重启后 **4 秒**追平；5 个事件全部 `attemptCount=1` |
| **D2** | `kill -9` worker（请求进行中） | attempt 1 → `ABANDONED / LEASE_EXPIRED`，attempt 2 → `SUCCEEDED`；**11 秒**收敛；编号连续无重复 |
| **D3** | 停 Redis、暂停 RabbitMQ | 事件均被接受；工作停在 outbox；恢复后 **2 秒**追平，无额外尝试 |

自动化测试与演练的分工写在 [ADR-0008](docs/adr/0008-fault-injection-tooling.md)：精密的时间窗口由确定性测试覆盖，进程崩溃的运行时行为由演练覆盖。**两者不能互相替代。**

---

## 可观测

```
GET /actuator/prometheus
```

自定义指标：

| 指标 | 含义 |
|---|---|
| `hookflow.delivery.succeeded` | 成功投递数 |
| `hookflow.delivery.retry_scheduled` | 进入重试的投递数 |
| `hookflow.delivery.dead` | 进入死信的投递数 |
| `hookflow.delivery.duplicate_skipped` | 被 attempt 护栏挡下的重复消息数（**这个数字大于 0 是正常的**，说明护栏在工作） |
| `hookflow.outbox.published` / `publish_failed` / `recovered` | relay 链路健康度 |
| `hookflow.recovery.retry_enqueued` / `lease_reclaimed` / `stuck_requeued` | 恢复路径被触发的次数 |

---

## 配置

所有可调项集中在 `application.yml` 的 `hookflow.*` 下，没有任何魔法数字散落在业务代码里。

| 配置 | 默认 | 说明 |
|---|---|---|
| `hookflow.delivery.connect-timeout-ms` | 3000 | 连接接收方超时 |
| `hookflow.delivery.read-timeout-ms` | 10000 | 读超时；慢接收方不能永久占住 worker 线程 |
| `hookflow.lease.ttl-seconds` | 60 | lease 有效期，必须显著大于 read timeout |
| `hookflow.relay.batch-size` | 50 | 单次 relay 领取行数 |
| `hookflow.relay.stale-publishing-seconds` | 60 | PUBLISHING 多久视为可疑并重发 |
| `hookflow.retry.base-delay-ms` / `max-delay-ms` | 5000 / 3600000 | 退避参数 |
| `hookflow.retry.default-max-attempts` | 8 | 默认最大尝试次数 |
| `hookflow.rate-limit.enabled` | true | Redis 租户限流开关 |
| `hookflow.rate-limit.fail-open` | true | **Redis 挂掉时：放行（true）还是拒绝（false）** |
| `hookflow.security.egress.allow-private-targets` | **false** | SSRF 防护：默认拒绝回环/私有/链路本地/云元数据地址。仅 `all`（本地演示）profile 打开 |
| `hookflow.security.egress.allowed-hosts` | `[]` | 显式放行的 host 白名单，优先于范围检查 |
| `hookflow.relay.retention-days` | 7 | PUBLISHED 的 outbox 行保留天数，0 关闭清理 |
| `hookflow.security.master-key` | 环境变量 | ≥32 字符；**丢失等于所有 endpoint 密钥作废** |

环境变量：`DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` `MQ_HOST` `MQ_PORT` `REDIS_HOST` `REDIS_PORT` `HOOKFLOW_MASTER_KEY` `HOOKFLOW_BOOTSTRAP_TOKEN`。

---

## 设计决策

每条决策都有 ADR 记录"当时的选项、选了什么、代价是什么"：

- [ADR-0001](docs/adr/0001-outbox-over-distributed-transaction.md) — 为什么用 Outbox，而不是分布式事务
- [ADR-0002](docs/adr/0002-at-least-once-not-exactly-once.md) — 为什么只承诺 at-least-once
- [ADR-0003](docs/adr/0003-lease-based-worker-claim.md) — 为什么用 lease + fencing token，而不是行锁
- [ADR-0004](docs/adr/0004-modular-monolith-with-roles.md) — 为什么先是单体，而不是微服务
- [ADR-0005](docs/adr/0005-spring-boot-4-and-java-21.md) — 为什么是 Boot 4.1 + JDK 21
- [ADR-0006](docs/adr/0006-redis-for-rate-limit-only.md) — 为什么 Redis 只做限流，不存事件状态
- [ADR-0007](docs/adr/0007-hand-rolled-api-key-auth.md) — 为什么 v0.1 不用 Spring Security

---

## 已知限制 / 路线图

**当前限制（不掩饰）**：

- 单 region、单 MySQL 主库，没有分片
- 管理端只有 REST，没有 UI；没有 SDK
- 顺序投递未实现（明确列为「不保证」）
- 每租户限流是固定窗口，不是令牌桶（窗口边界可 2 倍突刺）
- 无背压、无租户公平调度：一个大租户可以把 worker 线程池占满
- SSRF 防护在**创建 endpoint 时**检查一次，投递时会重新解析域名，DNS rebinding 可绕过（需要固定解析结果，与 LB/TLS SNI 交互，排到后续版本）
- 压测与 JVM 调优数据尚未提供（v0.3）
- 分布式 trace 尚未贯通（v0.3）

**规划**：

| 版本 | 内容 | 状态 |
|---|---|---|
| v0.2 | 故障注入自动化、jqwik 属性测试、SSRF 防护、outbox 保留策略、三次真实演练 | ✅ 已完成 |
| v0.3 | 多租户公平调度、背压与降载、wrk2 无 coordinated-omission 延迟测量、JFR + 火焰图 + GC 调优数据、OpenTelemetry trace 贯通 | 计划中 |
| v0.4（可选） | 传输层 SPI + Kafka 第二实现（同一套契约测试）、Java SDK | 计划中 |

---

## 目录结构

```
src/main/java/dev/hookflow/
├── api/             REST 控制器、API Key 鉴权过滤器、DTO
├── common/          错误码与统一错误体、requestId、principal 上下文
├── config/          配置属性、OpenAPI
├── crypto/          HMAC 签名、AES-GCM 密钥加密
├── domain/          tenant / apikey / endpoint / event / delivery / outbox / audit
├── messaging/       RabbitMQ 拓扑、发布器（publisher confirm）、工作消息
├── persistence/     MyBatis mapper（所有状态转换都是 CAS）
├── ratelimit/       Redis + Lua 的租户限流
├── relay/           outbox relay、PUBLISHING 卡死回收
├── service/         用例编排（幂等摄入、扇出、重放、暂停）
├── tools/           演示用假接收方（纯 JDK，无需 Spring）
└── worker/          投递执行、lease 守护、重试/回收调度、退避算法
```

---

## License

[Apache-2.0](LICENSE)
