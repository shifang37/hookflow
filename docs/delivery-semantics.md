# 投递语义（Delivery Semantics）

这份文档是这个项目的规格说明。README 里的每条承诺，最终都要能追溯到这里的某一段，以及某个测试或演练。

---

## 1. 术语

| 术语 | 含义 |
|---|---|
| **event** | 业务系统提交的一次事实（如 `order.paid`）。一个 event 可以被多个 endpoint 订阅 |
| **endpoint** | 客户的回调地址 + 签名密钥 + 订阅的事件类型 |
| **delivery** | 一个 `(event, endpoint)` 对。**投递的进度是记在 delivery 上的**，不是记在 event 上 |
| **attempt** | delivery 的一次网络请求尝试。第 1、2、3… 次 |
| **lease** | worker 认领 delivery 时获得的租约，带过期时间与 fencing token |
| **outbox** | "这条 delivery 的第 N 次尝试该入队了"的持久化意图 |

## 2. 状态机

```
                    ┌──────────── claim ────────────┐
                    │                               ▼
  创建 ──▶ PENDING ──────────────────────────▶ DELIVERING
              ▲                                    │
              │                                    ├── 2xx ───────────────────▶ SUCCEEDED ◆
              │                                    │
              │                                    ├── 可重试 且 还有额度 ─────▶ RETRY_WAIT
              │                                    │                              │
              │                                    ├── 可重试 但 额度用尽 ────┐   │
              │                                    │                          ▼   │
              │                                    └── 不可重试 ─────────────▶ DEAD ◆
              │                                                                    │
              └──── 手动 replay ────────────────────────────────────────────────────┘

   lease 过期 ──▶ PENDING（由 sweeper 回收）
   endpoint pause ──▶ CANCELED ◆（PENDING / RETRY_WAIT 的 delivery）
                ◆ = 终态
```

**合法转换全部由 CAS SQL 表达**，例如：

```sql
UPDATE delivery SET status='SUCCEEDED', ...
 WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?;
```

更新 0 行 = 有人抢先动了，**绝不能当成成功**。

## 3. 保证清单

### 3.1 平台保证

| # | 保证 | 实现手段 | 验证 |
|---|---|---|---|
| G1 | 事件一旦返回 202 并提交成功，就不会因为 MQ 故障而丢失 | event + delivery + outbox 同一 MySQL 事务 | `EventIngestIT` |
| G2 | 同一 `(tenant, Idempotency-Key)` 只对应一个 event，且重复请求返回同一 `eventId`。**批量提交里的每一条同样成立**：一批里部分键已存在时，它们返回原来的 `eventId` 并标记 `replay`，不产生重复事件 | `UNIQUE (tenant_id, idempotency_key)` | `EventIngestIT`（20 线程并发）、`BatchIngestIT`（20 条夹 3 条已存在） |
| G3 | 同一 `(event, endpoint)` 只有一条 delivery | `UNIQUE (event_id, endpoint_id)` | IT |
| G4 | 同一 delivery 的第 N 次尝试不会被消费两次 | `attempt_count = attemptNo - 1` CAS | `claimIsExclusive` |
| G5 | 已被回收的 delivery 不会被"死而复生"的旧 worker 覆盖 | `lease_token` fencing | `expiredLeaseIsReclaimed` |
| G6 | 投递不阻塞事件接收：HTTP 调用不在任何数据库事务内 | claim / call / settle 三段分离 | 代码结构 + 压测（v0.3） |
| G7 | 接收方可以独立验证请求来源 | HMAC-SHA256 绑定 event_id + delivery_id + timestamp | `HmacSignerTest`、E2E IT |
| G8 | 慢接收方不会永久占用 worker | connect/read 超时 + lease >> timeout | `serverErrorsAreRetriedUntilExhausted` |
| G9 | 4xx 不做无意义重试 | 错误分类 | `clientErrorsAreNotRetried` |
| G10 | 死信可人工重放，且重放保留历史 attempt | `replay` 只加一次额度，不清空计数 | `replayRecoversADeadDelivery` |
| G11 | 一个租户的积压不会把别的租户饿死，且这一点是量化的 | 租户并发预算 + 加权轮询（ADR-0009） | `FairnessIT`：p99 4 584ms → 190ms |
| G12 | 过载时拒绝新工作，而不是拖垮已接受的工作 | 入口水位线 + 队列长度上限（ADR-0010） | `BackpressureIT`、`performance.md §8` |
| G13 | 一条投递的 5 段耗时可以在一个 trace 里看到 | W3C traceparent 贯穿 payload / MQ / 出站请求（ADR-0011） | `TracingIT` |

### 3.2 平台**不**保证

| # | 不保证 | 原因 | 影响 |
|---|---|---|---|
| N1 | **exactly-once** | 接收方状态不可知（见 ADR-0002） | 接收方可能收到重复事件，必须按 `event_id` 去重 |
| N2 | 顺序 | 多 worker 并行消费 | 同一 endpoint 可能先收到 `order.refunded` 再收到 `order.paid` |
| N3 | 有界总耗时 | 退避有上限但总时长 = Σ 各轮间隔，且可能有多次 lease 回收 | 最坏情况投递完成时间可达数十分钟 |
| N4 | 接收方幂等 | 这是接收方责任 | 平台提供稳定 id 与 attempt 信息让它自己处理 |
| N5 | 单次请求必然到达 | 网络本身不保证 | 只保证"最终会到达，或进入 DEAD 并可查" |
| N6 | payload 字节级一致 | payload 在**摄入时**被解析为 JSON 树并规范化一次；字段顺序与空白可能与提交时不同 | 语义一致，但不要对原始字节做签名比对之外的逐字假设 |

## 4. 接收方必须做的事

平台在 HTTP 请求头里提供：

| 头 | 含义 |
|---|---|
| `X-HookFlow-Signature` | `v1=<hex>`，HMAC-SHA256 |
| `X-HookFlow-Timestamp` | Unix 秒 |
| `X-HookFlow-Event-Id` | **所有重试中保持不变** |
| `X-HookFlow-Delivery-Id` | 该 (event, endpoint) 对的 id |
| `X-HookFlow-Attempt` | 第几次尝试（从 1 开始） |
| `X-HookFlow-Event-Type` | 事件类型 |

接收方的正确做法（三步，缺一不可）：

1. **验签**：`HMAC_SHA256(secret, "v1:" + event_id + ":" + delivery_id + ":" + timestamp + ":" + rawBody)`，与头里的 hex 常量时间比较
2. **防重放**：`|now - timestamp| < 300s`，超出则拒绝
3. **幂等**：以 `event_id` 为唯一键写入自己的库（唯一约束），重复请求返回 200 但不重复执行

参考实现：`src/main/java/dev/hookflow/tools/MockReceiver.java`（前两步）与 README 里的说明。

## 5. 重试阶梯

```
attempt 1 失败
  ↓ delay(1) ∈ [2.5s, 5s)
attempt 2 失败
  ↓ delay(2) ∈ [5s, 10s)
attempt 3 失败
  ↓ delay(3) ∈ [10s, 20s)
...
delay(n) = jitter( min(base × 2^(n-1), max) )
jitter(x) = x/2 + rand(0, x/2)
```

- `base = hookflow.retry.base-delay-ms`（默认 5000）
- `max = hookflow.retry.max-delay-ms`（默认 3600000 = 1h）
- 尝试次数上限 = `endpoint.max_attempts`（默认 8，可按 endpoint 配置 1–50）

**抖动是必须的**，不是"看起来更专业"。没有抖动时，一次 broker 故障后所有等待中的 delivery 会在同一个位点上同时重试，恢复瞬间的水位等于积压量。

## 6. 崩溃窗口逐个分析

| 崩溃点 | MySQL 状态 | 恢复动作 | 对外表现 |
|---|---|---|---|
| 事务中（写 event/delivery/outbox） | 全部回滚 | 调用方重试（幂等键保护） | 收到 5xx，重试后同一个 eventId |
| relay 已领取、尚未 publish | outbox = PUBLISHING | `OutboxSweeper` 60s 后重置为 NEW | 延迟约 60s，不丢 |
| relay 已 publish、未标记 | outbox = PUBLISHING，**MQ 有消息** | sweeper 重置 → 再发一次 | **可能重复**，被 attempt 护栏吸收 |
| worker 已 claim、未发 HTTP | delivery = DELIVERING，lease 有效 | lease 到期 → 回收 → 重新入队 | 延迟 ≤ lease TTL |
| worker 已发 HTTP、响应前崩溃 | delivery = DELIVERING，attempt = IN_PROGRESS | lease 到期 → attempt 置 ABANDONED → 回收 | **可能重复**（对方可能已处理） |
| worker 已写 attempt、未更新 delivery | 同上 | 同上 | 无额外影响 |
| broker 丢消息 | delivery = PENDING 且 `updated_at` 过旧 | `requeueStuckPending` 重新入队 | 延迟约 60s，不丢 |
| MySQL 完全不可用 | — | 无法写入 | 接收事件返回 5xx；这是**唯一会拒绝事件的故障**，因为 MySQL 是权威 |

**这张表解释了一个常被误解的点**：为什么 broker 丢消息不算事故？因为 broker 里没有权威数据，消息只是"提醒 worker 去看一眼数据库"。

## 7. 容量与背压（v0.3 已实现）

v0.1/v0.2 的缺口现在都有了明确答案。**投递语义没有变化，但"接受"的语义变了**：

| 机制 | 行为 | 对语义的影响 |
|---|---|---|
| outbox 保留策略 | `PUBLISHED` 行 7 天后批量清理 | 无（`PUBLISHED` 只表示 broker 收到了） |
| 租户并发预算（`tenant.max_in_flight`） | 一个租户同时在投递中的条数有上限，超出的留在 outbox | 无。**吞吐**被该租户自己的预算限制，**延迟**不再被别人的积压影响 |
| 端点并发上限（`endpoint.max_concurrency`） | 打到某个 URL 的并行调用有上限 | 无。排队发生在 outbox，仍是 at-least-once |
| 入口降载（`42902` / `50301`） | 积压超水位时**拒绝新事件**，带 `Retry-After` | **有**：被拒的事件**没有被接受过**，所以 G1 仍然成立（"返回 202 就一定会投递"），但"所有事件都会被接受"不再成立 |
| 批量提交（`POST /v1/events/batch`，v0.4） | N 条事件在一个事务里写入；部分重复按条返回 `replay`；同一批内重复的键解析到第一次出现；限流与降载**按条数计** | **无**：G1/G2/G3 对每一条仍然成立。整批被拒（400/429/503）时一条都没有被接受，所以不存在"半批成功"的语义 |
| broker 队列长度上限 + `reject-publish` | 队列满时 broker 拒绝消息，relay 把行放回 `NEW` | 无。积压回到 MySQL，不丢、不重复 |
| worker 超预算策略 | `defer`（默认，交给恢复器）/ `requeue`（立刻退回队列） | 无 |

**一句话**：v0.3 之后，"返回 202" 的承诺变强了（它一定会在有界资源内被投递），而"你提交什么都会被接受"从来不是承诺——现在它有了一个诚实的、可观测的拒绝方式。

容量数字见 [capacity.md](capacity.md)，瓶颈与测量方法见 [performance.md](performance.md)。
