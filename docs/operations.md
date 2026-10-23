# 运维手册

## 1. 启动

### 本地演示（单进程）

```bash
docker compose up -d                      # MySQL / Redis / RabbitMQ
./mvnw spring-boot:run                    # 默认 profile = all
```

### 生产形态（三角色）

```bash
# 同一镜像，不同 profile
java -jar hookflow.jar --spring.profiles.active=api    --server.port=8080
java -jar hookflow.jar --spring.profiles.active=relay
java -jar hookflow.jar --spring.profiles.active=worker
```

| 角色 | 需要 MySQL | 需要 RabbitMQ | 需要 Redis | 可否多实例 |
|---|:---:|:---:|:---:|---|
| `api` | ✅ | —（不声明队列、不消费） | ✅（限流） | ✅ 无状态 |
| `relay` | ✅ | ✅ | — | ✅ 靠 `SKIP LOCKED` |
| `worker` | ✅ | ✅ | — | ✅ 靠 lease + attempt 护栏 |

**扩容顺序建议**（v0.3 改口）：**先看指标，不要凭顺序加机器。** 判断表见 [capacity.md §4](capacity.md)：

- `hikaricp_connections_pending > 0` → 加连接池（本机实测恒为 0，加池子没有收益）
- p99 随速率上升但连接池空闲 → 瓶颈在 DB 提交，改提交策略或换盘，**加 api 实例没用**
- outbox 积压涨、worker 空闲 → 加 relay
- 接收方慢（p99 由接收方决定）→ 加 worker，**并且同时调大该租户的 `max_in_flight`**，否则预算才是上限
- 接收方自己也扛不住并发 → 给它设 `endpoint.max_concurrency`，别让它拖住整个租户

v0.2 的建议是"先加 worker"，那是基于"接收方延迟是主要瓶颈"的假设；实测显示**默认配置下 DB 提交先咬合**，所以顺序取决于负载形态，而不是固定的。

## 2. 依赖故障时的行为

这是运维最需要知道的一张表。**MySQL 是唯一权威，所以它的故障影响最大。**

| 故障 | `api` 行为 | `relay` 行为 | `worker` 行为 | 恢复后 |
|---|---|---|---|---|
| **MySQL 不可用** | 接收事件返回 500（无法保证落库，必须拒绝） | 停止领取，日志报错 | claim 失败，消息 nack 不 requeue → 进 DLQ | 自动恢复；DLQ 里的消息**不需要**手工处理（delivery 仍在库里，`requeueStuckPending` 会兜底） |
| **RabbitMQ 不可用** | **正常接收事件**（只是 outbox 堆积） | publish 失败 → `releaseBack` → 行回到 `NEW`，下轮重试 | 消费停止，无消息可消费 | relay 自动追上；积压 drains，投递延迟上升但不丢 |
| **Redis 不可用** | `fail-open: true` → 放行；`false` → 503 | 无影响 | 无影响 | 自动恢复 |
| **worker 全部挂掉** | 正常 | outbox 会堆积（消息发出去但没人消费） | — | 起 worker 后立即消费积压 |
| **磁盘/MySQL 只读** | 500 | 停止 | 停止 | 需人工介入 |

**关键运维结论**：RabbitMQ 和 Redis 都不是关键路径。只有 MySQL 是关键路径。这意味着容量规划和备份的重点应该压在 MySQL 上。

## 3. 观测什么

### 必须告警的指标

| 指标 | 阈值建议 | 含义 |
|---|---|---|
| `hookflow.outbox` 中 `status='NEW'` 的数量 | > 10000 持续 5 分钟 | relay 跟不上或 broker 异常 |
| `hookflow_outbox_backlog`（v0.3 gauge，`NEW+PUBLISHING`） | 超过租户水位线 | **降载的触发量**，同时是容量规划的主指标 |
| `hookflow_delivery_dead` 速率 | > 基线 3 倍 | 客户接收方大面积故障 |
| `hookflow_delivery_retry_scheduled` 速率 | 突增 | 同上 |
| `hookflow_recovery_lease_reclaimed` | 持续 > 0 | worker 在被杀或卡住，检查 GC / 线程池 |
| `hookflow_recovery_stuck_requeued` | 持续 > 0 | 有消息在 broker 层丢失，检查 MQ 稳定性 |
| `hookflow_consumer_poison` | > 0 | 有无法解析的消息，检查发布端 |
| `hookflow_outbox_corrupt` | > 0 | outbox payload 损坏，**需要人工检查** |
| `hookflow_ingest_shed_total{scope="tenant"}` | 持续 > 0 | 有租户在被限流（42902）。**这是租户自己的问题**，不会影响别人 |
| `hookflow_ingest_shed_total{scope="platform"}` | > 0 | 平台在降载（50301）。这是**全平台**问题，应直接告警 |
| `hookflow_outbox_publish_rejected_total` | 持续 > 0 | broker 队列撞到 `x-max-length`。积压都在 MySQL 里，没有丢，但要检查 relay 是否空转 |
| `hookflow_fairness_skipped_over_budget_total` | 持续 > 0 | 调度器跳过了超预算的行。正常现象，用来判断是"预算在起作用"还是"预算定小了" |
| `hookflow_worker_over_capacity_total{policy}` | 持续 > 0 | worker 收到的消息超过租户/端点预算。若持续出现，说明 `refresh-interval-ms` 太长或有人手工绕过了 relay |
| `hookflow_db_lock_conflict_total{operation}` | 持续升高（偶发 > 0 正常） | 有事务在死锁/锁等待里被选为牺牲者并被重试（`operation` = `endpoint.pause` / `demo.reset`）。**偶发是正常的**：死锁是 InnoDB 的正常调度结果，重试是正确的处理方式。持续升高说明有批量操作在撞热点，见 §4。`docs/failures/0007` 的两次真实死锁在 v0.4 之前是靠异常栈抓到的，有了这个指标才能看到"被重试成功"的那部分 |
| `hookflow_attempt_pruned_total` | 长期为 0 且表在涨 | attempt 清理没有生效：检查 `hookflow.retention.attempt-days` 是否被设成 `0`、清理任务是否在跑、以及 `delivery_attempt.started_at` 上是否有 `idx_attempt_started`。该指标只在真的删了行时增加，长时间为 0 在低流量下是正常的 |
| `hikaricp_connections_pending` | 长期 > 0 | **连接池是瓶颈**，加池子或加只读副本（见 capacity.md §4） |

### 正常的"看起来像故障"的指标

- `hookflow.delivery.duplicate_skipped > 0`：**这是护栏在工作**，说明 at-least-once 的重复被拦住了。不是 bug。

### 常用排查查询

```sql
-- 积压：有多少工作还没送到 broker
SELECT status, COUNT(*) FROM outbox GROUP BY status;

-- 卡在 DELIVERING 的（lease 是不是没被回收？）
SELECT id, attempt_count, lease_until FROM delivery
 WHERE status = 'DELIVERING' AND lease_until < NOW(3);

-- 某个租户的失败分布
SELECT last_error_class, COUNT(*) FROM delivery
 WHERE tenant_id = ? AND created_at > NOW() - INTERVAL 1 HOUR
 GROUP BY last_error_class;

-- 某条事件的完整链路
SELECT d.id, d.status, d.attempt_count, a.attempt_no, a.status_code, a.error_class, a.duration_ms
  FROM delivery d LEFT JOIN delivery_attempt a ON a.delivery_id = d.id
 WHERE d.event_id = ? ORDER BY d.id, a.attempt_no;

-- 慢接收方 TOP
SELECT endpoint_id, AVG(duration_ms) avg_ms, COUNT(*) n FROM delivery_attempt
 WHERE started_at > NOW() - INTERVAL 1 HOUR AND duration_ms IS NOT NULL
 GROUP BY endpoint_id ORDER BY avg_ms DESC LIMIT 10;
```

## 4. 运维操作

### 重放一条死信

```bash
curl -X POST localhost:8080/v1/deliveries/123/replay -H "Authorization: Bearer $TOKEN"
```

会写 `audit_log`（action = `delivery.replay`）。

### 暂停一个持续失败的 endpoint

```bash
curl -X POST localhost:8080/v1/endpoints/7/pause -H "Authorization: Bearer $TOKEN"
```

效果：新事件不再为它创建 delivery；已有的 `PENDING`/`RETRY_WAIT` 变为 `CANCELED`。
**注意：`CANCELED` 是终态，resume 不会复活它们。** 如需补投，请对具体 delivery 重放（当前不支持重放 CANCELED）。

**并发坑（v0.3 发现，见 [failures/0007](failures/0007-cancel-vs-claim-deadlock.md)）**：这个操作是"按 endpoint_id 批量 UPDATE"，与 worker 的"按主键逐行认领"加锁顺序相反，在高并发下会被 InnoDB 选为死锁牺牲者。v0.4 起重试逻辑已抽成公共组件 `common/RetryOnLockConflict`（3 次 + 递增退避 + `hookflow.db.lock_conflict{operation}` 指标），`EndpointService.pause` 与 `DemoController.reset` 共用同一实现。**以后新增任何"按维度批量改状态/批量删除"的运维操作，必须调用它**，否则它会在压力下随机失败；`RetryOnLockConflictTest` 只覆盖组件本身，新调用点要自己加一条验证。

### 调整一个租户的并发预算

```sql
-- 直接改库（当前没有 API）：maxInFlight 是"同时在投递中的条数上限"
UPDATE tenant SET max_in_flight = 200, weight = 1 WHERE id = 7;
```

生效延迟 ≤ `hookflow.fairness.refresh-interval-ms`（默认 1s）。

定值方法：**目标等待时间 × 排空速率**。排空速率 ≈ `worker 并发 ÷ 接收方延迟`。
例：8 个 worker、接收方 100ms → 80/s；想让它最多等 500ms → `max_in_flight = 40`。
把它设成 `0` 等于放弃隔离（回到 v0.2：一个大租户可以吃掉整个队列）。

### 队列长度上限改不了

`hookflow.relay.max-queue-length`（默认 20000）是**队列声明参数**。RabbitMQ 拒绝对已存在的队列改参数，
本地开发改这个值需要先删队列：

```bash
docker compose down -v          # 或者：
docker exec hookflow-rabbitmq rabbitmqctl delete_queue hookflow.delivery
```

### 密钥管理

| 密钥 | 来源 | 丢失后果 | 轮换方式 |
|---|---|---|---|
| `HOOKFLOW_MASTER_KEY` | 环境变量，≥32 字符 | **所有 endpoint 的签名密钥不可解密**，客户必须重新配置 | `hookflow.security.key-version` 递增，新旧密钥并存期由 v0.4 提供；当前需重签全部 endpoint |
| `HOOKFLOW_BOOTSTRAP_TOKEN` | 环境变量 | 无法开租户/签新 key，已签的 key 不受影响 | 直接替换环境变量并重启 |
| 租户 API Key | 数据库存 hash | 只能撤销后重签 | `POST /v1/admin/keys/{id}/revoke` |

**`HOOKFLOW_MASTER_KEY` 是最关键的密钥。** 它不进数据库、不进仓库、不进镜像。生产环境应来自 KMS / Secret Manager。

## 5. 时钟与时区（部署时的硬性要求）

**规则：所有时间比较在 MySQL 内部完成，Java 只传时长，不传时刻。**

退避、lease 过期、outbox 卡死回收、PENDING 重扫，全部写成：

```sql
next_attempt_at   = DATE_ADD(NOW(3), INTERVAL #{delayMicros} MICROSECOND)
published_at    < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
```

因此**同一条链路不存在"两个时钟互相比"的情况**，即使 JVM 与数据库时区不同，重试逻辑依然正确。详见 [failures/0002-two-clocks.md](failures/0002-two-clocks.md)。

仍然要求 MySQL 以 **UTC** 运行（`compose.yaml` 已设 `TZ: UTC`），原因是对外暴露的时间（`occurred_at`、`nextAttemptAt`、`createdAt`）是从数据库里读出来的裸 `DATETIME`，需要一致的参照系才能正确解释。应用侧也固定 `-Duser.timezone=UTC`，但那只为日志可读，正确性不依赖它。

**部署检查清单：**

- [ ] MySQL 容器/实例 `time_zone` 为 UTC（`SELECT @@global.time_zone;`）
- [ ] 应用 `TZ=UTC`（镜像已内置）
- [ ] 若使用托管数据库，确认其默认时区不是 `SYSTEM` 且宿主为本地时区

## 6. 数据保留（v0.4 已实现两张表）

两张只增不减的表现在都有清理策略，都由定时任务按批删除，都遵守同一条规则：**删的是已经结束的历史，不是还在进行中的工作**。

| 表 | 配置 | 默认 | `0` 的含义 | 清理条件 | 指标 |
|---|---|---|---|---|---|
| `outbox` | `hookflow.relay.retention-days` | 7 天 | 关闭 | `status='PUBLISHED' AND published_at < NOW(3) - INTERVAL n DAY` | `hookflow_outbox_pruned_total` |
| `delivery_attempt` | `hookflow.retention.attempt-days` | 7 天 | 关闭 | `status <> 'IN_PROGRESS' AND started_at < NOW(3) - INTERVAL n DAY` | `hookflow_attempt_pruned_total` |

单批上限 5 000 行、每次 tick 最多 20 批（`hookflow.retention.interval-ms`，默认 1 小时）。批量而不是一条大 DELETE，是因为后者会长时间持锁，把 relay / worker 一起拖住。

**`delivery_attempt` 的 `IN_PROGRESS` 例外是硬约束，不是优化。** `DeliveryProcessor.settle` 用它收尾：

```sql
UPDATE delivery_attempt SET status='SUCCEEDED', ...
 WHERE delivery_id = ? AND attempt_no = ? AND status = 'IN_PROGRESS';
```

行被删掉时这条语句**更新 0 行且不报错**，一次在途尝试的结果就静默丢了。所以清理 SQL 里带 `status <> 'IN_PROGRESS'`，并且这个条件写在 SQL 里而不是 Java 里——它是唯一无法被调用方绕过的地方。崩溃留下的 `IN_PROGRESS` 行会被 lease 回收器改成 `ABANDONED`，之后正常按时间老去。

**保留期 ≥ `hookflow.lease.ttl-seconds` + 1 天**是前提：一次尝试最长只会在 `IN_PROGRESS` 停留一个 lease TTL（默认 60s）。默认 7 天给了两个数量级以上的余量；`DeliveryAttemptRetentionTest.shippedRetentionWindowCoversTheLease` 直接读 `application.yml` 钉住这个前提，改任一值都会让测试失败。

表继续涨时先查这四件事：配置是不是 `0`、清理任务是否在跑、`idx_attempt_started` 是否还在（`V3` 迁移加的）、以及是不是流量本身涨了。

## 7. 升级与迁移

- 数据库变更只通过 Flyway，**禁止手工改表**
- 迁移脚本一旦发布到 main 分支就不要修改（Flyway 校验 checksum）
- 新增迁移文件命名：`V{n}__{snake_case_description}.sql`
- 破坏性变更（删列/改类型）必须拆成两步：先加新列并双写 → 发版 → 再删旧列

## 8. 优雅停机

`worker` 停机时会等待正在进行的投递结束（Spring 的 graceful shutdown）。如果超过宽限期被强杀：

- 该 delivery 的 lease 会过期（默认 60s）
- 回收器把 attempt 标记为 `ABANDONED` 并重新入队
- **最多延迟 60s，不会丢**

建议把 K8s `terminationGracePeriodSeconds` 设为 `lease.ttl-seconds + read-timeout` 以上（默认 70s 起步）。

## 9. 分布式 trace（v0.3）

### 打开导出

导出**默认关闭**（`management.otlp.tracing.export.enabled=false`）：`mvn verify` 不能依赖 collector，生产也不能因为 collector 不在而失败。

```bash
# 本地看 trace：起 collector（可选 profile）
docker compose --profile tracing up -d          # Jaeger UI: http://localhost:16686

# 让应用把 span 发过去
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--management.otlp.tracing.export.enabled=true --management.otlp.tracing.endpoint=http://localhost:4318/v1/traces"
```

采样率 `management.tracing.sampling.probability` 默认 `1.0`（全采）。**生产上线第一件事就是把它降下来**：
本项目默认 1.0 是为了演示"链路连通性"，被采样掉的 trace 和断掉的 trace 在排查时无法区分。

### 一条投递在 trace 里长什么样

```
api            http.server.requests                     (根 span，来自 HTTP 请求)
 └ relay       hookflow.publish          PRODUCER       (从 outbox 行里的 traceparent 续接)
    └ worker   hookflow.delivery         CONSUMER       (MQ 消息里恢复父上下文)
       └       hookflow.receiver         CLIENT         (出站 POST，注入 W3C traceparent)
```

定位方法：拿 API 响应头 `X-HookFlow-Trace-Id`，或者客户收到的 `traceparent` 第二段，在 Jaeger 里搜这个 trace id。
耗时分布可以直接读出"排队（publish 之间）、relay、worker 领取、HTTP 调用"各占多少。

### 本机拉不到 Jaeger 镜像（已记录）

在本文的测量机器上，**所有** `jaegertracing/all-in-one` 标签都拉不下来：

```
failed commit on ref "index-sha256:...": commit failed: "unknown-sha256:..."
failed size validation: 2840 != 27969: failed precondition
```

`1.62` 和 `latest` 两个不同 tag 报同样的错，所以这是 Docker daemon 在提交 layer 时失败，不是 tag 写错。
因此 **"在 Jaeger UI 里看到一条 trace" 这一步在本机没有做**，也不声称做过。替代证据是：

- `TracingIT`：断言接收方收到的 `traceparent` 与 API 返回的 trace id **相同**、span id **不同**（5 跳一条 trace，在真实 HTTP 上）；
- `DeliveryMessageTest`：traceparent 在 outbox 的 JSON 里往返，且 v0.2 旧行仍可解析。

换一台能拉到镜像的机器，上面两条命令即可复现；collector 不在时投递行为完全不受影响（导出是异步、失败即丢 span）。

### 关闭 tracing 的影响

`management.tracing.enabled=false` 时 `TraceContextSupport` 全部退化为 no-op：不建 span、`DeliveryMessage.traceparent` 为 null、响应里没有 `X-HookFlow-Trace-Id`。**投递语义不变**。
