# v0.4 B7：worker 32 并发、池 32（2026-10-23）

这些目录是 [performance.md §21](../../performance.md) 引用的原始输出。要回答的问题是
**"把 worker 抬到 relay 之上以后，交付速率是多少、连接池还卡不卡"**——§18 里 32 并发那一次测到的是
**relay** 的 ~390/s（broker 队列空、worker 只拿到下界），而 §18.5 把"32 个出站请求同时打一个客户"留成了推理。

采集命令（450 events/s × 60 s，批量 20 条，16 发送线程，10 s 预热 + 20 s 观察）：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 450 -Duration 60 -Warmup 10 -Threads 16 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest `
    -MqConcurrency 32 -MqMaxConcurrency 32 -MqPrefetch 8 -DbPoolSize 32 `
    -RelayBatchSize 100
```

`-RelayBatchSize` 是这一批给 `bench.ps1` 新加的旋钮（写进 `meta.txt` 的 `app args` 行）；`-Mq*` / `-DbPoolSize`
写进 `meta.txt` 的 `worker env` 行。

| 目录 | relay `batch-size` | `MQ_CONCURRENCY` | `MQ_MAX_CONCURRENCY` | `DB_POOL_SIZE` |
|---|---:|---:|---:|---:|
| `b50-run1/` | 50（当时默认） | 32 | 32 | 32 |
| `b50-run2/` | 50（重复一次） | 32 | 32 | 32 |
| `b100/` | 100 | 32 | 32 | 32 |
| `b200/` | 200 | 32 | 32 | 32 |
| `default/` | **不传参数（用改后的 `application.yml` 默认值：32 / 32 / 32 / batch 100）** | | | |

`b50-run1` / `b50-run2` 是同一格子的两次运行：§18 的 ≥361/s 是在 relay batch 50 下测的，这里需要知道
**worker 32 在默认 relay 前面到底是"刚好"还是"有余"**。`b100` / `b200` 是 relay 那一格（§22）在 450 到达率下的
两个点：450 时 outbox 几乎是空的，所以 claim 拿不到一整批，200 与 100 落在同一个"供给受限"区间。
`default/` 是改完默认值之后**不传任何旋钮**的一次，用来证明提交的配置就是被测量的配置。

## 关于 `meta.txt` 里的 `git` 行

五次运行都在 commit `dd56f8a` 之上、**改动尚未提交**的状态下采集：

- `b50-run1` / `b50-run2` / `b100` / `b200`：只改了 `scripts/bench.ps1`（新增 `-RelayBatchSize`），
  被测应用的代码与配置都等于 `dd56f8a`；
- `default/`：额外包含 `application.yml` 的默认值改动（worker 32 / 32、池 32、relay batch 100），
  也就是这次提交的内容（`worker env` 行写的是 `application.yml defaults`）。

## 这一批的边界

- 每个格子只有一到两次运行；同一格子的重复性参考 `b50-run1`（357.2/s）与 `b50-run2`（368.0/s）。
- 接收方是 2 ms 的本机 MockReceiver。**没有测 32 并发打到慢接收方**（200 ms 时 `32 / 0.2 = 160/s`，
  而且接收方会同时挨 32 个请求）——这条只写进 README 配置表，不在这里声称测过。
- 单机、单 JVM 的**进程内并发**，不是多 worker 进程（B2 的题目）。
- `b200/` 在 450 下与 `b100/` 落在同一供给区间：两次的 claim 平均只有 72.0 与 65.8 行，不是 200 与 100 行。
