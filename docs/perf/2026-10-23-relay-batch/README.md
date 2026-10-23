# v0.4 B4：relay 批量发布之后（2026-10-23）

这些目录是 [performance.md §16](../../performance.md) 引用的原始输出，采集命令与
[`../2026-10-23-downstream/`](../2026-10-23-downstream/) **完全相同**（同一台机器、同样的到达率与线程数）：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 450 -Duration 60 -Warmup 10 -Threads 16 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest
```

唯一变化的变量是代码：`DeliveryPublisher.publishAll` + `OutboxMapper.markPublishedBatch`
（[ADR-0014](../../adr/0014-batched-relay-publish.md)）。

| 目录 | 入口 | 标称到达率 | 线程 |
|---|---|---:|---:|
| `single-450/` | `POST /v1/events`，1 条/请求 | 450 events/s | 48 |
| `batch20-450/` | `POST /v1/events/batch`，20 条/请求 | 450 events/s | 16 |
| `batch20-900/` | 同上 | 900 events/s | 24 |

文件说明同 [`../2026-10-23-downstream/README.md`](../2026-10-23-downstream/README.md)。

## 读这批数字要注意的两件事

1. **两个批量目录都撞到了 broker 队列的长度上限**（`x-max-length=20000`）：大约在第 65 秒，
   `messages_ready` 顶到 ~19 400，之后 publisher confirm 变成 nack，`publish_rejected` ≈ 175/s，
   行被 `releaseBackBatch` 放回 NEW。这是 [ADR-0010](../../adr/0010-backpressure-and-shedding.md) 设计的降级形态
   （积压留在 MySQL，不塞进 broker），也是失败回退路径在真实过载下的证据。
   **所以「负载前 60 秒」和「整次运行」的发布速率不是一回事**：前者是 relay 的上限，后者被 nack 拖低了。
2. **`single-450/` 那次的问题不是 relay**：单条路径 450 events/s 意味着每秒 450 次提交，16 个连接的池被 ingest
   占满（`hikaricp_connections_pending` 全程 36–42），worker 与 relay 一起变慢，开环 p50 7.7 s。
   它说明的是「A2 的批量入口仍然是必需的」，不是「B4 让单条路径变慢了」。
