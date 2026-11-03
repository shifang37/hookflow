# 0008 — 演练 A3：MySQL 主库备份、恢复到空实例，再在 broker 也丢光的前提下自愈

- **类型**：演练
- **执行时间**：2026-11-03 15:53
- **被测组件**：MySQL（§2 认定的唯一关键路径）、worker 角色里 `DeliveryRecovery` 的三条恢复路径
- **被测版本**：`0.4.1-SNAPSHOT`（`v0.4.0` 之后没有代码改动，只有本演练脚本与文档）
- **命令**：`./scripts/backup-restore.ps1 -DbPort 13306`（从前置检查到 PASS 59 秒）
- **状态**：通过

---

## 1. 目的

v0.2 的三次演练（[0004](0004-drill-d1-relay-kill.md)–[0006](0006-drill-d3-dependency-outage.md)）覆盖了进程崩溃与依赖故障，
但都没有碰**唯一会真的丢数据的场景**：MySQL 主库没了。[operations.md §2](../operations.md) 的结论是
"只有 MySQL 是关键路径"，它的另一面——"所以**只需要备份 MySQL**，RabbitMQ 里的消息、Redis 里的计数都可以丢"——
在这次演练之前只是一句推论。

要验证的不变式：

| 不变式 | 期望 |
|---|---|
| 备份可恢复 | 恢复实例每张表的行数、`delivery`/`outbox`/`delivery_attempt` 的状态分布与源实例**逐项相等**，`flyway_schema_history` 完整 |
| 在途工作不丢 | 备份时刻处于 `DELIVERING` / `PENDING` 的投递，恢复后**全部** `SUCCEEDED`，无需人工 |
| broker 不是状态来源 | 把 `hookflow.delivery` 队列清到 `ready=0 unacked=0` **之后**再起角色，仍然收敛 |
| attempt 编号不重复 | 被打断的那条 attempt 1 记为 `ABANDONED`、attempt 2 成功；其余 `attempts=1` |

## 2. 演练设计：三种在途形态，各对应一条恢复路径

脚本先用一个新租户投递 20 个事件（证明正常路径、也给备份一些历史），然后**故意把三种形态的工作冻结在库里**，再停掉全部角色：

| 形态 | 怎么造出来 | 备份里的样子 | 恢复后靠谁 |
|---|---|---|---|
| **在途** | 向 3 秒慢端点投递，等到 `DELIVERING` 时 `kill -9` worker | `delivery.DELIVERING`，attempt `IN_PROGRESS`，lease 还没过期 | `reclaimExpiredLeases`：lease 过期 → attempt 记 `ABANDONED` → 重新入队 |
| **消息丢失** | worker 已死时再提交 3 个事件：relay 把消息发进了 broker，没人消费；恢复前把队列清空 | `delivery.PENDING`，`outbox.PUBLISHED` | `requeueStuckPending`：PENDING 超过宽限 → `append` 撞唯一键 → `rearmStalePublished` 把旧的 `PUBLISHED` 行置回 `NEW` → relay 重发 |
| **从未发布** | 先停 relay 再提交 5 个事件 | `delivery.PENDING`，`outbox.NEW` | relay 正常排空，不需要任何恢复逻辑 |

然后 `mysqldump --single-transaction --no-tablespaces` 全库 → 起一个**没有数据卷的** `mysql:8.4` 容器 → 导入 → 14 项断言 →
清空 broker 队列 → 三个角色把 `DB_PORT` 指到恢复实例 → 看它们自己收敛。为了让演练在一分钟内结束，恢复实例上的 worker
把 lease TTL 压到 8 秒、滞留宽限压到 10 秒、滞留扫描压到 5 秒（默认 60 / 60 / 30 秒）；这只改变"多快"，不改变"会不会"。

## 3. 原始输出

```
=== pre-flight ===========================================
[15:53:39] docker up, mysql:8.4 present, ports 8080/8081/8082 free

=== setup: start roles and seed history ==================
[15:53:40] started api (profile=api, DB_PORT=13306, log=api.log)
[15:53:41] started relay (profile=relay, DB_PORT=13306, log=relay.log)
[15:53:42] started worker (profile=worker, DB_PORT=13306, log=worker.log)
[15:53:42] started receiver on 9099
[15:53:49] api is UP
[15:53:50] tenant 6 provisioned
[15:53:50] fast endpoint 9 -> http://127.0.0.1:9099/hook
[15:53:50] slow endpoint 10 -> http://127.0.0.1:9099/hook?delay=3000
[15:53:53] warm-up: 20/20 SUCCEEDED - normal path proven, history for the backup

=== freeze with work in flight (three shapes) ============
[15:53:53] event 7572 is DELIVERING (worker is inside the 3s HTTP call)
[15:53:54] KILLED worker (pid 81488) - SIGKILL equivalent, no graceful shutdown
[15:53:54] events 7573, 7574, 7575 are PENDING with their broker message PUBLISHED (worker is down, nothing consumes)
[15:53:55] stopped relay (pid 76880)
[15:53:55] events 7576, 7577, 7578, 7579, 7580 are PENDING with outbox rows NEW (relay is down)
[15:53:56] stopped api (pid 77608)
[15:53:56] all roles are down; the database is frozen at the disaster point

--- frozen inventory (drill tenant) ---
  delivery DELIVERING: 1   delivery PENDING: 8   delivery SUCCEEDED: 20
  outbox NEW: 5   outbox PUBLISHED: 24
  attempt IN_PROGRESS: 1   attempt SUCCEEDED: 20

=== backup ==============================================
[15:53:56] dump: docker exec hookflow-mysql sh -c 'mysqldump --single-transaction --no-tablespaces -uroot hookflow > /tmp/hookflow-backup.sql'
[15:53:57] backup written: D:\Code\hookflow\logs\backup-restore\hookflow-20261103-155333.sql (8.4 MB) in 0.4s

=== restore into an empty instance ======================
[15:53:57] start: docker run -d --name hookflow-mysql-restore ... -p 13307:3306 mysql:8.4 (empty; no data volume)
[15:54:08] empty instance is up (11.4s); it contains zero business rows
[15:54:08] tables in the empty instance before restore: 0
[15:54:09] apply: docker exec hookflow-mysql-restore sh -c 'mysql -uroot hookflow < /tmp/restore.sql'
[15:54:12] restore applied in 3.5s

=== verification (source vs restored) ===================
[15:54:12] check tenant                 source=6       restored=6       PASS
[15:54:12] check api_key                source=7       restored=7       PASS
[15:54:13] check endpoint               source=10      restored=10      PASS
[15:54:13] check event                  source=7580    restored=7580    PASS
[15:54:13] check delivery               source=3909    restored=3909    PASS
[15:54:13] check delivery_attempt       source=13288   restored=13288   PASS
[15:54:13] check outbox                 source=13296   restored=13296   PASS
[15:54:14] check audit_log              source=10      restored=10      PASS
[15:54:14] check flyway_schema_history  source=5       restored=5       PASS
[15:54:14] check delivery.status        PASS
[15:54:14] check outbox.status          PASS
[15:54:15] check attempt.status         PASS
[15:54:15] check flyway.integrity       failed migrations=0 PASS
[15:54:15] check instance.time_zone     source=UTC restored=UTC PASS
[15:54:15] verification: 14/14 checks passed in 3.1s

=== the broker loses everything too =====================
[15:54:16] broker queue hookflow.delivery at the freeze point: ready=5 unacked=0
[15:54:22] queue after the purge: ready=0 unacked=0
[15:54:22] no broker message carries any of the frozen work now - only MySQL state can save it

=== heal: roles point at the restored instance ==========
[15:54:23] started api (profile=api, DB_PORT=13307, log=api-restored.log)
[15:54:24] started relay (profile=relay, DB_PORT=13307, log=relay-restored.log)
[15:54:25] started worker (profile=worker, DB_PORT=13307, log=worker-restored.log)
[15:54:33] api is UP on the restored instance (10.4s after role start)
[15:54:38] converged: 29/29 deliveries SUCCEEDED, outbox fully published, in 5.1s

--- per-event outcome ---
[15:54:38]   backlog (message never published) event 7576 -> SUCCEEDED attempts=1
[15:54:38]   backlog (message never published) event 7577 -> SUCCEEDED attempts=1
[15:54:38]   backlog (message never published) event 7578 -> SUCCEEDED attempts=1
[15:54:38]   backlog (message never published) event 7579 -> SUCCEEDED attempts=1
[15:54:38]   backlog (message never published) event 7580 -> SUCCEEDED attempts=1
[15:54:38]   lost-message event 7573 -> SUCCEEDED attempts=1
[15:54:38]   lost-message event 7574 -> SUCCEEDED attempts=1
[15:54:38]   lost-message event 7575 -> SUCCEEDED attempts=1
[15:54:38]   in-flight event 7572 -> SUCCEEDED attempts=2
[15:54:38]     attempt 1 ABANDONED error=LEASE_EXPIRED
[15:54:38]     attempt 2 SUCCEEDED error=

--- recovery evidence from the restored-instance logs ---
[15:54:38]   07:54:34.312 INFO  [-] d.hookflow.worker.DeliveryRecovery - Delivery 3902 looked stuck in PENDING; re-enqueued attempt 1
[15:54:38]   07:54:34.323 INFO  [-] d.hookflow.worker.DeliveryRecovery - Delivery 3903 looked stuck in PENDING; re-enqueued attempt 1
[15:54:38]   07:54:34.335 INFO  [-] d.hookflow.worker.DeliveryRecovery - Delivery 3904 looked stuck in PENDING; re-enqueued attempt 1
[15:54:38]   07:54:34.393 WARN  [-] d.hookflow.worker.DeliveryRecovery - Lease expired for delivery 3872 at attempt 2; reclaimed
[15:54:38]   07:54:34.427 WARN  [-] d.hookflow.worker.DeliveryRecovery - Lease expired for delivery 3901 at attempt 1; reclaimed
[15:54:38] final tenant delivery distribution: SUCCEEDED	29

=== RTO funnel ===========================================
  dump (--single-transaction)          0.4s   (8.4 MB)
  empty instance ready                11.4s
  restore SQL applied                  3.5s
  verification (14 checks)             3.1s
  app healthy on restored data        10.4s
  --- restore RTO (empty -> healthy)   28.4s
  --- end to end (dump -> healthy)     28.8s
  catch-up after healthy               5.1s

=== drill PASS: data survived the restore, the platform healed itself ===
restore container removed (use -KeepRestore to keep it for inspection)
backup file kept: D:\Code\hookflow\logs\backup-restore\hookflow-20261103-155333.sql
all drill processes stopped
```

角色日志里的时间是 UTC（JVM 以 `-Duser.timezone=UTC` 启动），脚本时间戳是本机时间（UTC+8），所以 `07:54:34` 就是 `15:54:34`。

## 4. 观察

### 4.1 备份与恢复：RTO

| 阶段 | 耗时 | 备注 |
|---|---|---|
| `mysqldump --single-transaction` | 0.4 s | 8.4 MB；7 580 个事件、3 909 条投递、13 288 条 attempt、13 296 条 outbox |
| 空 `mysql:8.4` 容器起到可连接 | 11.4 s | 这是 RTO 里最大的一块，而且和数据量无关 |
| 导入 | 3.5 s | |
| 14 项断言 | 3.1 s | 9 张表行数 + 3 个状态分布 + flyway 完整性 + 时区 |
| 三角色起到 `/actuator/health` UP | 10.4 s | Flyway 校验 5 个迁移、建连接池、连 broker |
| **空实例 → 可用** | **28.4 s** | |
| **dump → 可用（端到端）** | **28.8 s** | |
| 可用之后在途工作全部收敛 | 5.1 s | 见 4.3 |

两点值得记住：

- 这个量级下**恢复耗时几乎与数据量无关**——28 秒里有 22 秒是起容器和起 JVM。数据量大 10 倍时导入会从 3.5 秒涨到几十秒，
  但前后两块不动。
- 断言不是"能连上"而是**逐项相等**。`flyway_schema_history` 那一行尤其重要：它也在备份里，所以应用起来时 Flyway 校验
  通过、不迁移、不 baseline。按表挑着备份而漏掉它，应用会把恢复出来的库当成"没迁移过的非空库"拒绝启动。

### 4.2 broker 真的空了，这件事必须自己证明

冻结点队列里有 **5 条 ready**：3 条是"消息丢失"形态的消息（relay 发出、没人消费），另外 2 条是被 `kill -9` 的 worker
手里的在途消息——连接断掉后 RabbitMQ 把它们重新放回 ready（1 条是本租户 7572 的，1 条是前一次演练留在库里的
`delivery 3872` 的第 2 次 attempt，见 §7）。

`rabbitmqctl purge_queue` **只清 ready 消息**，unacked 的会留在死掉的 channel 上。第三次重放时就撞到了这一点：
purge 前后都是 4 条，而恢复照样收敛了——那样的记录证明不了"靠的是 MySQL"。所以脚本现在先等 `unacked=0`，purge 之后
再断言 `ready=0 unacked=0`，不满足就让演练失败。本次：`ready=5 unacked=0` → 6 秒后 `ready=0 unacked=0`，
**然后**才起角色。

### 4.3 自愈：每条日志对应一种形态

角色指向恢复实例后 10.4 秒 UP，再过 5.1 秒 29/29 全部 `SUCCEEDED`。日志里的五行恢复证据与三种形态一一对应：

| 日志 | 形态 | 结果 |
|---|---|---|
| `Delivery 3902/3903/3904 looked stuck in PENDING; re-enqueued attempt 1` | 消息丢失（事件 7573–7575） | `append` 撞唯一键 → 旧的 `PUBLISHED` 行被置回 `NEW` → relay 重发 → `attempts=1`，**没有多余 attempt** |
| `Lease expired for delivery 3901 at attempt 1; reclaimed` | 在途（事件 7572） | attempt 1 `ABANDONED / LEASE_EXPIRED`，attempt 2 `SUCCEEDED`，编号连续 |
| `Lease expired for delivery 3872 at attempt 2; reclaimed` | 前一次演练的残留（§7） | 一起收敛 |
| （无日志） | 从未发布（事件 7576–7580） | relay 看到 5 行 `NEW` 直接发出，`attempts=1` |

"消息丢失"形态的 `attempts=1` 是这次演练最有分量的一个数字：消息丢了、队列清了，投递既没丢也没多算一次尝试，
因为 attempt 编号是在 MySQL 里由 outbox 的唯一键守着的，broker 从头到尾不持有任何状态。

### 4.4 第一次重放为什么失败：演练脚本自己的缺陷

本记录是**第四次**重放。前三次都是脚本问题，不是平台问题，按 README 的约定如实记下：

1. **14:20 第一次**：预热阶段断言 `SUCCEEDED == 20` 轮询满 90 秒后超时。根因是 PowerShell 5.1 会把函数返回的单元素数组
   拆包成字符串，于是 `$rows[0]` 取到的是 `"20"` 的第一个**字符** `'2'`，`[int]` 再把它变成 ASCII 码 **50**。同一类错误还
   藏在 `DELIVERING` 判定和 `@@system_time_zone` 断言里。更糟的是真正的异常被 `finally` 吞掉了：在
   `$ErrorActionPreference = 'Stop'` 下，`docker rm -f` 对不存在的容器写一行 stderr 就是终止错误，控制台里只剩
   "No such container"。
2. **15:42 第二次**：根因修掉后，新加的 SQL 失败诊断立刻暴露了一处没带 `hookflow.` 前缀的 `outbox` 查询（1046 No database
   selected）和冻结清单里的歧义列 `status`（1052）。
3. **15:45 第三次**：全程 PASS，但 `purge` 前后队列都是 4 条（§4.2），证据不够硬。
4. **15:53 第四次**：本记录。

修复都在 `scripts/backup-restore.ps1`：所有 docker 调用走 `Invoke-Docker`（`Continue` 偏好 + 合并 stderr，退出码真实、
永不抛出），`Invoke-Sql` 用 `,$rows` 保住数组并在失败时打印 SQL 与错误，`Get-Scalar`/`Get-Count` 取第一行而不是第一个字符，
`catch` 先打印 ABORTED 原因再 `finally`。三次失败的控制台日志留在 `logs/`（gitignored）里。

## 5. 结论

[operations.md §10](../operations.md) "恢复后必须检查的 3 件事"，每一件都对应本次输出里的一行：

| 检查 | 证据 |
|---|---|
| 数据与备份一致，而不只是"能连上" | `verification: 14/14 checks passed` |
| 在途工作自己收敛了，再对外宣布"已恢复" | `converged: 29/29 deliveries SUCCEEDED, outbox fully published, in 5.1s`，且在 `ready=0 unacked=0` 之后 |
| 新实例时区是 UTC、拿的是同一把 master key | `instance.time_zone source=UTC restored=UTC PASS`；29 条投递全部验签通过（master key 不在备份里，脚本用同一环境变量起角色） |

README 里那句"丢 broker 消息不等于丢投递"现在有了最强形式的证据：**整个 broker 队列被清空、MySQL 换成了从备份恢复的
另一个实例**，平台仍然在 5 秒内把全部在途投递收敛完，attempt 编号连续、没有重复尝试、没有人工介入。

## 6. 与自动化测试的关系

| 路径 | 自动化测试 | 本演练 |
|---|---|---|
| lease 过期回收 | `EventIngestIT.expiredLeaseIsReclaimed`（mapper 级 CAS）+ [0005](0005-drill-d2-worker-kill.md) D2 | 在恢复实例上、跨实例 |
| broker 不可用期间不丢工作 | `BrokerOutageIT`（容器 pause/unpause） | 不覆盖（那是 D3） |
| **消息发出后丢失 → 滞留 PENDING → 重入队（`rearmStalePublished`）** | **没有**：`grep -r rearmStalePublished src/test` 为空 | **只有本演练覆盖** |
| 备份可恢复、恢复后一致 | 没有（本质上无法在单元/集成测试里做） | 14 项断言 |

## 7. 遗留

- **`requeueStuckPending` → `rearmStalePublished` 没有自动化测试。** 这是三条恢复路径里唯一只靠演练覆盖的一条。
  建议补一个 `DeliveryRecoveryIT`：提交事件 → 等 `PUBLISHED` → 直接 `purge_queue` → 把 `updated_at` 回拨到宽限之前 →
  断言一次扫描后 outbox 行回到 `NEW`、投递最终 `SUCCEEDED`、`attemptCount=1`。
- **只在一台机器上**：恢复实例是同机第二个容器，网络与磁盘都是本机的；没有演练主从/半同步复制、binlog 时间点恢复、跨机器
  拷贝 dump。RTO 里 11.4 秒起容器 + 10.4 秒起 JVM 的部分在生产形态（已有空实例、滚动起 pod）会不同。
- **数据量**：8.4 MB 的库。导入 3.5 秒线性外推到 GB 级不可靠，需要在更大的库上重测一次。
- **demo 库会积累演练租户**：每次重放新建一个租户（本次是第 6 个），而中止的重放会把在途行留在源库里，下一次重放的
  预热阶段会顺手把它们收敛掉——这就是日志里 `delivery 3872 at attempt 2` 的来历。它不影响断言，但让证据多了一行需要解释。
  改进：演练结束时删掉演练租户，或者对着一个专用库跑。
- **purge 的前提是 unacked 归零**：死掉的消费者的连接要等 broker 察觉（本次 6 秒；心跳默认 60 秒时可能更久），脚本给了
  60 秒预算，超时会失败而不是假装通过。
