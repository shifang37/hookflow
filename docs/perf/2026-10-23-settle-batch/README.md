# v0.4 B6：worker 的 settle 批量提交（2026-10-23，**负结果，已回退**）

这些目录是 [performance.md §20](../../performance.md) 引用的原始输出。要回答的问题是
**"把 N 条投递的 settle 合成一个事务，能不能把 worker 再抬一倍？"** 答案是**不能**：提交次数降了 36%，
吞吐反而降了 25%，改动已回退。

| 目录 | 版本 | `COMMIT` 次数 | 提交/投递 | 交付/s（整次） |
|---|---|---:|---:|---:|
| `reverted/` | B5 的当前版本（settle 每次投递一次提交） | 47 253 | 2.04 | **264** |
| `settle-batched/` | 加 `worker/SettleBatcher`（单写线程，batch ≤50，flush 10 ms） | 22 283 | 1.30 | 198 |

两次运行用同一条命令（450 events/s、批量 20、默认 8→16 并发、10 s 预热 + 60 s 负载 + 20 s 观察）：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 450 -Duration 60 -Warmup 10 -Threads 16 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest
```

文件说明同 [`../2026-10-23-downstream/README.md`](../2026-10-23-downstream/README.md)。
`settle-batched/` 的 `statements.txt` 里 `COMMIT` 只剩 22 283 次（提交确实被合并了），
而 `downstream.txt` 的 `succeed/s` 只有 182–187——这正是"提交变少但吞吐变低"的证据。

## 为什么留着这次失败

1. 它是"提交次数是唯一的杠杆"这个直觉的**反例**：在固定并发下，worker 是延迟受限的，
   批量提交拿并行换提交次数，净效果为负（§20.3 用小定律算过）。
2. 它标出了 worker 的真实上限：16 并发 273–284/s；32 并发 + 池 32 是 ≥361/s（那时 relay 先满，§18）。
3. 它避免了一次会进仓库、看起来更"高级"却更慢的改动。

## 这一批的边界

- 每个版本各一次运行；两次运行的入口条件相同，且改动的效果（−25%）远大于 §14.3 记的 run-to-run 散布。
- 只试了一种批量形状（单写线程 + 10 ms flush + batch ≤50）。§20.4 记了"什么形状才可能划算"以及为什么这轮不做。
- 接收方是 2 ms 的本机 MockReceiver，单机单 JVM。
