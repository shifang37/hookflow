# ADR-0014：relay 批量发布——一批一次 confirm 窗口、一次 UPDATE

- 状态：已采纳（v0.4）
- 日期：2026-10-23

## 背景

v0.4 的 B0 再基线（[performance.md §15](../performance.md)）测出：这台机器上平台的**持续吞吐是 41 events/s**，
而入口能接 450–900 events/s。差额全部堆在 MySQL 的 outbox 里。瓶颈指标是
`hookflow_outbox_published_total`，也就是 relay 的发布速率。

每发布一行花 24 ms，`statements.txt` 把它拆成两半：

| 成本 | 实测 | 原因 |
|---|---:|---|
| `UPDATE outbox SET status='PUBLISHED' WHERE id=?` | 12.4 ms | 单行 UPDATE，自动提交 → 一次 fsync |
| `convertAndSend` + 等 publisher confirm | ≈ 11 ms | 一次网络往返；quorum queue 确认前要落盘 |

两者都在 relay 的**同一个线程**里串行发生。所以这不是"多开几个 relay"能解决的问题（那会乘以实例数，但每个实例
仍是 41/s 且抢同一张表），而是"每一行都买了一次往返和一次提交"。

## 选项

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 维持逐条 | 现状 | 41/s 的天花板；其它任何优化都被它盖住 |
| B. 只批量 `UPDATE` | 多条 id 一个 UPDATE | 天花板 ≈ 1/11ms ≈ 90/s；confirm 往返仍是每行一次 |
| C. 只批量 confirm | 先发 N 条再一起等 | 天花板 ≈ 1/12.4ms ≈ 80/s；提交仍是每行一次 |
| **D. 发送全部 + 一个 confirm 窗口 + 一次 UPDATE（选中）** | `publishAll` 返回逐条结果；ack 的 id 一个 UPDATE 标记 | 崩溃窗口从 1 行扩大到 1 批（见「代价」） |
| E. 完全异步确认（监听器 + pending map） | 发布后立刻返回，confirm 到达时再改行 | relay 的状态机变成异步：行会长时间停在 PUBLISHING，"PUBLISHED 表示已确认"这个不变量消失；恢复路径与 sweeper 的语义全部要重写 |
| F. 加 relay 实例 | 不改代码 | `FOR UPDATE SKIP LOCKED` 支持横向扩展，但每实例 41/s，且写同一张 outbox 表；属于 B2 的题目，不是这一层的解 |

## 决定

- `DeliveryPublisher.publishAll(List<DeliveryMessage>)`：先**全部发送**（不等确认），再按顺序等每个
  `CorrelationData` 的 future。**整个批次共用一个 10 s 截止时间**，不是每条 10 s——否则一个卡住的 broker
  能把 relay 挂住 N×10 s。
- 返回逐条结果 `ACKED / NACKED / FAILED`，调用方（`OutboxRelay.publishBatch`）按结果分三堆：
  - `ACKED` → `OutboxMapper.markPublishedBatch(ids)`，一条 UPDATE；
  - `NACKED` → `releaseBackBatch(ids)`，计 `hookflow.outbox.publish_rejected`；
  - `FAILED`（没发出去 / 确认没来 / 超时）→ `releaseBackBatch(ids)`，计 `hookflow.outbox.publish_failed`。
- 只要有一条不是 `ACKED`，`relayOnce` 这一 tick 就停下（broker 看起来不健康时不要空转），与 v0.3 的行为一致。
- **没有 ack 绝不标记 PUBLISHED**。宁可重发（重复投递是已承诺的语义），不可假装完成。
- 单条 `publish(...)` 保留：`OutboxCrashWindowIT` 用它构造"已确认但没标记"的崩溃窗口，那条路径要能单独验证。

## 为什么

- **为什么不等逐条确认**：24 ms 里 11 ms 是往返，而往返的成本与消息条数无关（一次窗口内可以塞 N 条 confirm）。
  实测从 41/s 到 ~440/s（§16），10.7×。
- **为什么 UPDATE 也要批量**：它是另一半 12.4 ms，而且一次 UPDATE 50 行只多花 ~10 ms（22 ms vs 12.4 ms），
  摊到每行 0.48 ms——26 倍。只批一半的话天花板只有 80–90/s，改完仍然是瓶颈。
- **为什么不用选项 E（完全异步）**：这个项目对 outbox 的整个恢复论证建立在"PUBLISHED 意味着 broker 已经确认过"
  上（[ADR-0001](0001-outbox-over-distributed-transaction.md)、[delivery-semantics.md](../delivery-semantics.md)）。
  异步确认会让"已发布"变成一个稍后才成立的状态，sweeper、`rearmStalePublished` 和故障复盘都得重写。
  收益是省掉剩下的一个往返——在 worker 只有 ~145/s 的时候（§16），这点收益买不回那个复杂度。
- **为什么一个批次一个截止时间**：`relay.interval-ms=200`，一 tick 最多 20 轮。逐条 10 s 超时意味着最坏情况
  一 tick 挂 20×50×10 s。整批一个截止时间把最坏情况钉在 10 s。

## 代价与已知问题

- **重复投递的窗口从 1 行扩大到 1 批**。崩溃发生在"全部确认"与"UPDATE 提交"之间时，整批仍是 PUBLISHING，
  sweeper 60 s 后会重发这一整批。at-least-once 本来就允许，但**重复的粒度变粗了**（一次可能多出 50 条重复，
  而不是 1 条）。如果未来客户对重复敏感，正确方向是接收方按 `event_id` 幂等（SDK，D1），不是把批次改小。
- **一条 NACK 会停下整个 tick**，即使同批其它行已经 ACKED。这是刻意的：队列到上限时继续灌没有意义。
  但代价是——队列刚腾出空间时，relay 要等到下一个 tick（≤200 ms）才继续。
- **`releaseBackBatch` 的粒度是批**：一次 nack 风暴会让同一批里的失败行一起回到 NEW，下一 tick 重新排队，
  顺序可能与原来不同（本项目不承诺顺序，见 plan §4）。
- **`publishAll` 不是事务**：如果进程在发送与 UPDATE 之间崩溃，已经到达 broker 的消息仍会被消费（worker 有
  `attemptNo` 栅栏，重复消息会被 `duplicate_skipped` 丢掉）——这条链路依赖 worker 的幂等，不是依赖 relay 的原子性。
- **batch 大小成为新的旋钮**：`hookflow.relay.batch-size` 现在是"一次 confirm 窗口的条数"，不再只是
  "一次 claim 的条数"。太大则单批的锁持有时间与重复窗口变大，太小则回到每行一次往返。默认 50 未重新调参。
  **（v0.4 B8 量了：450 到达率下 50 是 357–368/s、100 是 472/s、200 没有进一步收益；900 下 50/100/200
  发布 337/449/731/s。默认已改为 100，见 [performance.md §22](../performance.md)。）**
- **它揭穿了一个测试的隐含前提**：`FairnessIT` 原来用 50 ms 的接收方、逐条灌 1 500 条洪水，
  靠"relay 只有 41/s、比 ingest 还慢"来制造排队；relay 变快之后队列不再堆积，受害者只等了 158 ms（测试失败）。
  把接收方调到 200 ms 也只是撑了一阵：B5 把 worker 并发翻倍之后，消费速度又超过了逐条灌入的速度，
  受害者只等 361 ms。最终的修法是把洪水改成**批量灌入**（1 500 条 ≈1 s 打完，worker 40–160/s 追不上），
  这样"受害者排在洪水后面"由**提交速度**保证，不再依赖系统里某个环节恰好很慢。
  代价是那一次 IT 仍然要 ~140 s（洪水要以 40/s 排完两次）。
  这件事本身也是个结论：**旧测量里"公平性"的部分证据，其实是 relay 慢的副作用。**

## 什么情况下会改这个决定

1. worker 追上来、relay 又成为瓶颈（§16 之后）→ 下一步是批量 claim（现在每批仍要一次 `SELECT ... FOR UPDATE`
   + 一次 `markPublishing` 提交），或把 claim 与发布做成流水线。
2. 需要严格的重复上界（一次崩溃最多重复 K 条）→ 回到逐条，或引入批次内逐条标记（两次提交的折中）。
3. 传输层抽成 SPI（D3）→ `publishAll` 会成为接口方法，Kafka 实现可以用自己的批量确认语义。

## 如何验证

- `DeliveryPublisherTest`（单元）：broker 不可达 → 全部 `FAILED`；全部 ack → 全部 `ACKED` 且 N 条只等一个窗口；
  nack → `NACKED`；确认永不到达 → `FAILED`（用 200 ms 的测试超时，不真等 10 s）。
- `OutboxRelayPublishBatchTest`（单元）：全部 ack → **一次** `markPublishedBatch(全部 id)`；混合 → ack 的标记、
  nack 的 releaseBack、计数正确、tick 停止；未确认 → 一律 releaseBack，绝不标记；坏 payload → 跳过不发布。
- `BrokerOutageIT`（真实容器，暂停 broker）：停 broker 期间行回到 NEW、不产生 attempt；恢复后一次 attempt 成功。
- [performance.md §16](../performance.md)：同一到达率下 relay 的发布速率 41/s → ~440/s；负载期间 outbox 积压
  从"每秒涨 ~400 行"变成"稳定在几十行"。
