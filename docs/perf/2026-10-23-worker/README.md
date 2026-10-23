# v0.4：worker 那一格（2026-10-23）

这些目录是 [performance.md §18](../../performance.md) 引用的原始输出。要回答的问题是
**"worker 的吞吐随什么变"**——在 §16 把瓶颈从 relay 换到 worker 之后，`capacity.md §4` 那行"加 worker（线性）"
需要一个带数字的依据。

采集命令与 §16 完全相同（450 events/s × 60 s，批量 20 条，16 发送线程）：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 450 -Duration 60 -Warmup 10 -Threads 16 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest `
    -MqConcurrency 16 -MqMaxConcurrency 16 -MqPrefetch 8 -DbPoolSize 16
```

| 目录 | `MQ_CONCURRENCY` | `MQ_MAX_CONCURRENCY` | `MQ_PREFETCH` | `DB_POOL_SIZE` |
|---|---:|---:|---:|---:|
| `c8-p8/` | 8 | 8 | 8 | 16 |
| `c8-p32/` | 8 | 8 | 32 | 16 |
| `c16-p8/` | 16 | 16 | 8 | 16 |
| `c16-p32/` | 16 | 16 | 32 | 16 |
| `c16-p8-pool32/` | 16 | 16 | 8 | 32 |
| `c32-p8-pool32/` | 32 | 32 | 8 | 32 |
| `default-8-16/` | 不传参数（用改后的 `application.yml` 默认值） | | | 16 |

`concurrency` 与 `max-concurrency` 取相同值，是为了**钉死**并发、去掉 Spring 默认 2→8 的爬坡
（爬坡会把整次运行的均值拉低，§16 那次就是这样）。

文件说明同 [`../2026-10-23-downstream/README.md`](../2026-10-23-downstream/README.md)；
`meta.txt` 里的 `worker env` 行记录了这一格实际用的环境变量（没有传参数时写 `application.yml defaults`）。

## 关于 `meta.txt` 里的 `git` 行

七次运行都是在 commit `14ff714` 之上、**改动尚未提交**的状态下采集的：

- `c8-p8` … `c32-p8-pool32`：只改了 `scripts/bench.ps1`（新增四个旋钮参数），被测应用的代码与配置都等于 `14ff714`；
- `default-8-16`：额外包含 `application.yml` 的默认值改动（`MQ_CONCURRENCY` 2→8、`MQ_MAX_CONCURRENCY` 8→16），
  也就是这次提交的内容。

所以 `git` 行相同不代表被测配置相同，区别在 `worker env` 行。

## 这一批的边界

- 每个格子**只跑了一次**，`c8-p8`（171/s）与 `c8-p32`（165/s）之间的差可以当作重复性参考（约 ±5%）。
- 接收方是 2 ms 的本机 MockReceiver；慢接收方下并发收益会立刻消失（`16 / 0.2 = 80/s`）。
- 单机、单 JVM 的**进程内并发**，不是多 worker 进程（B2 的题目）。
- `c32-p8-pool32/` 特别注意：那次 broker 队列峰值是 0、`PENDING` 只剩 1，说明 worker 把 relay 发布的全吃掉了，
  所以它测到的是 **relay** 的速率，worker 只给了下界。
