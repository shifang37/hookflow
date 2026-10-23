# v0.4 B8：relay batch-size 50 → 100 → 200（2026-10-23）

这些目录是 [performance.md §22](../../performance.md) 引用的原始输出。要回答的问题是
**"`hookflow.relay.batch-size` 从 50 往上翻，relay 的发布速率怎么变；每批的固定成本还值不值得合并 `claim` 与
`markPublished` 两条提交"**。ADR-0014 把"batch 大小成为新的旋钮"和"默认 50 未重新调参"写在了代价一节。

采集命令（**900 events/s** × 60 s，批量 20 条，24 发送线程；worker 固定为 32 并发 + 池 32，让它不是限制）：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 900 -Duration 60 -Warmup 10 -Threads 24 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest `
    -MqConcurrency 32 -MqMaxConcurrency 32 -MqPrefetch 8 -DbPoolSize 32 `
    -RelayBatchSize 200
```

`-RelayBatchSize` 是本批给 `bench.ps1` 新加的旋钮（记录在 `meta.txt` 的 `app args` 行）。

| 目录 | relay `batch-size` | 到达率 | 为什么在这个到达率测 |
|---|---:|---:|---|
| `b50/` | 50 | 900 events/s | 有积压，claim 能拿满 50 行 |
| `b100/` | 100 | 900 events/s | 同上 |
| `b200/` | 200 | 900 events/s | 同上，且能看出"每批一次提交"被摊薄到什么程度 |

**为什么不在 450 测这一格**：450 到达率下 relay（batch 100 时）已经能跟上，outbox 积压只有几十行，
claim 每次只拿到 66–72 行——batch-size 200 与 100 落在同一个"供给受限"区间，分不出高低。那两个点
（`b100`、`b200` 的 450 版）在 [`../2026-10-23-worker32/`](../2026-10-23-worker32/) 里，用于说明
"450 时 100 已经够了、200 不加分"；本目录的 900 三次运行才是 batch-size 的容量曲线。

## 关于 `meta.txt` 里的 `git` 行

三次运行都在 commit `dd56f8a` 之上、**改动尚未提交**的状态下采集，被测应用的代码与配置等于 `dd56f8a`
（只改了 `scripts/bench.ps1` 的旋钮参数；`application.yml` 的默认值改动没有参与，三次都显式传了 `-RelayBatchSize`）。

## 这一批的边界

- 每个格子只有一次运行；`b200/` 在负载末段撞到了 broker 队列上限（第 ~81 秒 `publish_rejected` ≈ 459/s），
  所以它的发布速率只看第 22–68 秒的稳定段，不要用整次均值。
- 900 到达率下 **worker 是交付的限制**（32 并发只能交付 380–420/s，负载停下后回到 ~470–500/s），
  所以这一格测到的是 relay 的**发布**能力，不是端到端交付能力。
- 接收方是 2 ms 的本机 MockReceiver；单机、单 JVM；没有多 relay / 多 worker 进程。
- `b100/` 的平均批是 97.7 行、`b200/` 是 194.9 行——两个都接近满批，这一点和 450 那组不同。
