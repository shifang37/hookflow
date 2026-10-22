# 0004 — 演练 D1：relay 被 `kill -9` 时，队列里的工作会不会丢

- **类型**：演练
- **执行时间**：2026-10-22 14:53（本地 Windows 11 + Docker Desktop，JDK 21.0.10）
- **被测角色**：`relay`（独立 JVM，profile=`relay`）
- **命令**：`./scripts/drill.ps1 -DbPort 13306`（脚本可完整重放本次演练）
- **状态**：通过

---

## 1. 目的

relay 是「MySQL 里的 outbox 行 → RabbitMQ 消息」这一段的唯一执行者。它崩溃时最重要的问题是：

> 已经落库、但还没发布出去的工作，会不会就此永远卡住？

要验证的不变式：

| 不变式 | 期望 |
|---|---|
| 已接受的事件不丢 | relay 死掉期间提交的事件，重启后仍被投递 |
| 工作不丢 | 未发布的工作停在数据库里可见（`unpublished outbox > 0`） |
| 恢复自动 | **不重启 api、不手工重放**，只重启 relay 即追平 |
| **不重复投递同一轮** | 每个事件最终 `attemptCount = 1`，不因恢复而多投一次 |

## 2. 环境

| 组件 | 形式 |
|---|---|
| MySQL 8.4 / RabbitMQ 4 / Redis 8 | Docker Compose |
| api / relay / worker | 三个独立 JVM 进程（同一 jar，不同 profile） |
| 接收方 | `dev.hookflow.tools.MockReceiver`（本机 9099） |

`api` 以 `--hookflow.security.egress.allow-private-targets=true` 启动，因为接收方在回环地址上，而生产 profile 默认拒绝内网目标（见 [ADR 与 SSRF 说明](#附录a顺带验证到的-ssrf-防护)）。

## 3. 步骤与原始输出

```
[14:53:25] tenant 4 provisioned
[14:53:25] fast endpoint 5 -> http://127.0.0.1:9099/hook
[14:53:30] warm-up delivery 16 status=SUCCEEDED attempts=1        <- 先证明健康路径通

=== D1: kill the relay while work is queued ==============
[14:53:30] KILLED relay (pid 32272) - SIGKILL equivalent, no graceful shutdown
[14:53:31] submitted 5 events while the relay is dead
[14:53:34] unpublished outbox rows = 5   <- parked, not lost
[14:53:35] started relay profile=relay
[14:53:39] relay back after 4s; unpublished outbox rows = 0
[14:53:39]   event 17 -> SUCCEEDED attempts=1 attemptsRecorded=1
[14:53:39]   event 18 -> SUCCEEDED attempts=1 attemptsRecorded=1
[14:53:39]   event 19 -> SUCCEEDED attempts=1 attemptsRecorded=1
[14:53:39]   event 20 -> SUCCEEDED attempts=1 attemptsRecorded=1
[14:53:39]   event 21 -> SUCCEEDED attempts=1 attemptsRecorded=1
```

## 4. 观察

1. **relay 死亡期间事件照常被接受**：5 个事件全部返回 `eventId`。这是设计使然——写事件只需要 MySQL，不需要 broker。
2. **工作停在 outbox 里**：3 秒后查询 `outbox`，有 5 行处于 `NEW`/`PUBLISHING`。没有任何东西消失。
3. **恢复只用了 4 秒**：重启 relay 后，backlog 从 5 清到 0，约等于 relay 的启动时间。
4. **每个事件恰好投递一次**：5 个事件全部 `attemptCount = 1`，`attemptsRecorded = 1`。**没有因为 relay 崩溃而多投一次。**
5. 从 kill 到全部投递完成，**总耗时 9 秒**，接收方对应地只收到 5 次请求。

## 5. 结论

不变式全部成立。这个演练最关键的一点是第 4 条：relay 崩溃**没有**产生重复投递，因为崩溃发生在「领取（PUBLISHING）之前」——relay 死了，行还没被标成 PUBLISHING，重启后正常领取并发布。

**所以这个演练没有触及真正的危险窗口。** 危险窗口是「broker 已确认、行未标记」这一段：那里一定会重复发布。这一段无法用随机 kill 命中，只能构造：

- 自动化版本：`OutboxCrashWindowIT`——把行手工置成过期的 `PUBLISHING`、再真的发一遍消息，验证 sweeper 重新武装 + worker 的 attempt 护栏吸收重复（`attempts == 1`）
- 语义说明：`docs/delivery-semantics.md` §6 的崩溃窗口表

**这次演练证明的是"粗粒度崩溃不丢不重"；细粒度窗口由自动化测试证明。两者互补，不能互相替代。**

## 6. 附录A：顺带验证到的 SSRF 防护

演练第一次跑失败了，失败本身是个有意义的结果。用**生产 profile**（`api`）创建指向 `http://127.0.0.1:9099/hook` 的 endpoint 时：

```
Invoke-RestMethod : The remote server returned an error: (400) Bad Request.
```

原因：`api` profile 下 `hookflow.security.egress.allow-private-targets=false`，回环地址被 `EgressGuard` 拒绝。只有本地演示 profile（`all`）才放开。演练随后显式放开该开关才能继续。

这条意外收获比演练本身更能说明"默认安全"不是口号：**在错误的环境里，你连一个内网回调地址都建不出来。**

## 7. 遗留

- 演练没有覆盖"relay 在 publish 期间被 kill"（需要命中毫秒级窗口），由 `OutboxCrashWindowIT` 覆盖
- 演练用的是单 relay 实例；多实例下的 `SKIP LOCKED` 互斥由 `EventIngestIT` 的 claim 测试覆盖，未做多实例实测
