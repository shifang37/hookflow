# 容量模型：4C8G 单机能扛多少，先加谁

- 版本：v0.3.0
- 依据：全部系数来自 [performance.md](performance.md) 的实测（本机 i5-14600K 14C/20T，见该文 §2）
- **诚实声明**：本文的公式是实测的，代入本机测出的系数；**没有一台 4C8G 的机器可以实测**，所以 4C8G 的结论是"区间 + 判据"，不是"我跑过"。凡是没测的地方都标了「未测」。

---

## 1. 一条投递要花什么

一个事件从 `POST /v1/events` 到一个 2xx 的完整成本，按实测拆开：

| 阶段 | 每次事件的数量 | 实测单位成本 | 证据 |
|---|---|---|---|
| ingest 事务 | 1 个事务 / ≈7 条 SQL / 1 次提交 | 服务端 16–23 ms | [§5.5](performance.md#55-真正的成本中心一个-ingest-一个事务--一次-fsync) |
| 其中 DB 连接占用 | ≈2–5 ms | `hikaricp_connections_usage_seconds_*` | 同上 |
| relay 发布 | 1 条消息 + 1 次 publisher confirm | ≈1 次网络往返（未单独计时，**未测**） | — |
| worker 认领 | 1 个事务（claim + attempt insert） | 未单独计时 | — |
| worker 出站 HTTP | 1 次 POST | 接收方延迟（本组压测 2–3ms，故障演练里 200ms–3s） | §7 / `ReceiverFaultIT` |
| worker 结算 | 1 个事务（attempt finish + delivery 状态） | 未单独计时 | — |
| GC | — | 0.25–0.40% wall time | [§5.1](performance.md#51-gc-不是每个场景都一样) |
| CPU | ≈0.04 核 | 基本在等 I/O | [§5.3](performance.md#53-jfr进程基本在等-io) |

**关键结论：成本几乎全是 I/O 往返，不是 CPU。** 所以按 CPU 核数做线性外推（"14C → 4C 就是 3.5 分之一"）是错的。

---

## 2. 容量公式（四个约束取最小值）

```
TPS_max = min(
    ingest_threads / ingest_service_time,          # A: API 线程与事务串行度
    db_connections / db_hold_time,                 # B: 连接池与单连接占用
    redo_commit_rate,                              # C: 提交持久化（fsync / group commit）
    relay_publishes_per_second,                    # D: relay 单线程发布循环
    worker_threads / receiver_latency              # E: worker 并发 ÷ 接收方延迟
) × (1 - headroom)                                 # 留 30% 余量给突发与重试
```

各约束在本机测到的值：

| 约束 | 本机实测 | 说明 |
|---|---|---|
| A | ≈200 Tomcat 线程 × (1/0.020s) = 10 000/s（**不构成约束**） | 服务端耗时 20ms 时也只占约 4 个线程 |
| B | 16 连接 / 0.0022s ≈ 7 200/s（**不构成约束**，`hikaricp_connections_pending=0`） | — |
| C | 默认 fsync：实测约 **300–350/s** 时 p99 仍 110ms；改成每秒刷盘后 p99 −39% | 见 §5.5 A/B |
| D | 单 tick 最多 20 轮 × 50 行，`interval-ms=200` → 理论上限 5 000/s（**未测到成为约束**） | 450 req/s 时积压开始增长，说明 D 或 E 在 350/s 附近开始咬合，**具体是哪个未测** |
| E | 8 个 worker × (1/0.002s) = 4 000/s；接收方 200ms 时 = **40/s** | 见 §4 |

代入本机（默认 fsync）：`min(10000, 7200, ~330, ~5000, 4000) × 0.7 ≈ 230/s`，实测稳态 300–350/s —— 模型偏保守但同量级，原因是 C 的 330/s 本来就是从实测反推的，属于自洽验证而非独立预测。

---

## 3. 4C8G 的答案

把上表的**约束值**换成 4C8G 上应有的值（这一步是模型，不是实测）：

| 约束 | 4C8G 的估计 | 为什么 |
|---|---|---|
| A（线程/事务） | 4 核能把服务端 20ms 的耗时维持在什么水平？**未知**。CPU 采样显示只有 0.04 核，所以 4 核不会成为约束 | 成本是 I/O 等待，不是计算 |
| B（连接） | `DB_POOL_SIZE=16` 在 8G 内存下没问题；但 4 核机器上 MySQL 与 Java 抢 I/O 更明显 | — |
| C（提交） | **这是最可能的约束**：同一块盘上 fsync 延迟随并发升高更快 | 本机 fsync=1 时 p99 110ms@300/s，fsync=2 时 68ms |
| D（relay） | 单线程，与核数关系不大；单实例 relay 的判断不变 | — |
| E（worker） | `8 / receiver_latency`，与机器无关 | 纯并发÷延迟 |

**结论（区间，不是点估计）**：

> 4C8G 同机部署（api+relay+worker 一个进程、MySQL 同机 Docker）、payload 2KB、接收方延迟 ≈5ms、目标 **p99 < 500ms**：
> **约 150–300 events/s**。
> 下限对应 fsync=1 + MySQL 与 Java 抢盘；上限对应能独占 I/O 或接受 `innodb_flush_log_at_trx_commit=2`。
> **瓶颈在 DB 提交（C），不在网络。** 网络不是约束：本机回环下 `SocketRead` 里有 4 个线程在等，但那是等 MySQL，不是等客户。
> 若接收方延迟升到 200ms，**worker（E）会先成为约束**：`8 / 0.2 = 40 events/s`，与 CPU 和 DB 都无关。

**p99 < 500ms 这个目标本身的排队论含义**：在 TPS=300、单事件服务端 20ms 时，系统内的在途请求 ≈ `300 × 0.02 = 6`；只要 outbox 积压 < `排空速率 × 0.5s` ≈ 150 行，p99 就能待在 500ms 内。这就是把租户水位线定为 **200**（而不是 10 000）的定量依据——见 [performance.md §8](performance.md#8-过载-3-倍降级还是雪崩)。

---

## 4. 扩容先加谁：按"当前咬合的是哪个约束"决策

不要问"先加哪个组件"，要问"现在哪个约束在咬合"。判断方法都是现成的指标：

| 现象（一眼能看出来） | 咬合的约束 | 先加谁 | 边际收益（实测/推算） |
|---|---|---|---|
| `hikaricp_connections_pending > 0` | B 连接 | `DB_POOL_SIZE`，必要时加只读副本给查询（ingest 仍写主库） | 本机 pending 恒为 0，所以**加连接池收益为 0** |
| p99 随 ingest 速率上升，`pending=0`，CPU 空闲 | C 提交 | 改提交策略（fsync=2 / 批量提交）、换更快的盘、把 MySQL 挪到独立机器 | 实测 p99 −39%（300 req/s） |
| outbox 积压增长但 worker 空闲 | D relay | **加 relay 实例**（`FOR UPDATE SKIP LOCKED` 已支持横向扩展） | 未实测 |
| 接收方慢（p99 由 receiver 决定），worker 忙 | E worker | 加 worker（同一租户需同时调 `max_in_flight`，否则预算才是上限） | `容量 = 线程数 / 延迟`，线性 |
| CPU 饱和（本机不是） | A | 加 api 实例 | 单机实测用不到 |

三条容易踩的坑：

1. **加 api 不解决 ingest 瓶颈**，因为 ingest 的瓶颈在它下游的 DB 提交。api 是无状态、可横扩，但扩了只会让 DB 更挤。
2. **加 worker 不一定提升吞吐**：`max_in_flight` 是租户级硬上限，worker 加到 32 而预算仍是 4 时，吞吐不变。两者必须一起调，这也是 ADR-0009 说"预算买隔离、付吞吐"的含义。
3. **接收方慢的时候，是接收方在决定你的容量**：`8/0.2 = 40/s`。此时唯一的扩容方式是**限制该 endpoint 的 `max_concurrency`**，别让它把整个租户拖住（v0.3 已实现），而不是加机器。

---

## 5. 这个模型哪里可能是错的

| 假设 | 风险 | 怎么证伪 |
|---|---|---|
| ingest 成本主要是提交 fsync | 也可能有相当部分是语句之间的往返与锁等待。**我只测到 16–23ms 的总量，没测到语句级** | MySQL `performance_schema.events_statements_summary_by_digest` |
| relay 不是约束 | 450 req/s 时积压已开始增长，D 与 E 哪个先咬合**未测** | 对比 `hookflow_outbox_published_total` 与 worker 完成速率 |
| 4C8G 与 14C/20T 的差别主要在 I/O | 如果 MySQL 与应用分机部署，结论会明显乐观 | 需要一台 4C8G 实测 |
| 接收方 5ms | 真实客户公网 P99 常见 200ms+ | 用 `?delay=` 复现，见 `bench.ps1 -ReceiverDelayMs` |

**没测到的东西，本文不写数字。** 上表第二、三行就是因此标了"未测"。

---

## 6. 一句话

**这台机器能稳定吃下 300 events/s（p99 110ms）；瓶颈是一次事务一次 fsync 的 DB 提交，不是网络、不是 GC、不是连接池。**
4C8G 同机部署估计 150–300 events/s；扩容顺序由指标决定，最常见的错误是在 DB 已经咬合时去加 api 实例。
