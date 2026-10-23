# 压测原始输出

每个目录对应 [performance.md](../performance.md) 里的一次运行，**报告只引用这里的内容**。

| 目录 | 场景 | 对应报告 |
|---|---|---|
| `100-baseline-run{1,2,3}` | 100 req/s × 30s，三次重复，用于看方差 | §4 |
| `300-baseline-32threads` | 300 req/s × 30s，32 发送线程，`innodb_flush_log_at_trx_commit=1` | §4、§5.5 A/B 的前一半 |
| `300-fsync2-32threads` | 同上，唯一变量 `innodb_flush_log_at_trx_commit=2` | §5.5 A/B 的后一半 |
| `300-fsync2-4threads-generator-bound` | **反面教材**：4 个发送线程时压测器自己成了瓶颈，得出"容量 175/s"的错误结论 | §4 末尾、§11 |
| `450-default-watermark-48threads` | 450 req/s × 30s，默认水位线，开始出现 429 | §4 |
| `600-default-watermark-64threads` | 600 req/s × 30s，默认水位线 10 000 | §3、§8 |
| `600-watermark200-64threads` | 600 req/s × 30s，水位线 200（64 发送线程） | §8 |
| `600-watermark200-4threads` | 同上但 4 发送线程；用来证明"发送线程数会改变结论" | §8 |
| `fairness-ab` | 同一洪水场景跑两遍，唯一变量是洪水租户的 `max_in_flight`（4 与 0） | §7 |
| `2026-10-23-batch/` | v0.4 A2 批量入口对照（单条 vs 批量 20，同一到达率） | §14 |
| `2026-10-23-downstream/` | v0.4 B0 下游再基线（批量前后 × 下游速率、积压曲线、语句摘要） | §15 |
| `2026-10-23-relay-batch/` | v0.4 B4 批量发布之后（同一组到达率，只改代码） | §16 |
| `2026-10-23-worker/` | v0.4 B5 worker 那一格：并发 × prefetch × 连接池 | §18 |
| `2026-10-23-settle-batch/` | v0.4 B6 settle 批量提交：**负结果**，提交 −36% / 吞吐 −25%，已回退 | §20 |
| `2026-10-23-worker32/` | v0.4 B7 worker 32 并发 + 池 32（450 入口；含 relay batch 50/100/200 与"新默认值"的一次运行） | §21 |
| `2026-10-23-relay-batch-size/` | v0.4 B8 relay batch-size 50/100/200（900 入口、有积压时的容量曲线） | §22 |

每个目录里：

| 文件 | 内容 |
|---|---|
| `loadgen.txt` | 压测器完整输出（开环与闭环两个视角的 p50/p90/p95/p99/p99.9/p99.99/max、成功率、吞吐） |
| `timeline.csv` | 每秒接受 / 429 / 其他错误数，用来判断"降级还是雪崩" |
| `gc-summary.txt` | 该次运行的 `-Xlog:gc*` 汇总（次数、总停顿、最大停顿） |
| `meta.txt` | 机器规格、JVM 参数、应用参数、payload、速率、git rev、Docker 版本 |
| `metrics.txt` 或 `db.txt` | 该次运行采集的 Micrometer 指标 / 数据库状态（只有部分目录保留） |
| `series.csv` / `downstream.txt` | **每秒级的下游曲线**：计数原始值与折算后的速率（发布/s、积压、队列深度、连接等待） |
| `statements.txt` | MySQL 按 digest 聚合的语句耗时与次数（窗口 = 本次运行） |
| `broker.txt` | 跑完后 `hookflow.delivery` 队列的深度、在途、消费者数 |
| `fairness.txt` / `fairness.json` | 公平性 A/B 的汇总 |

## 没有提交进仓库的

- `jfr.jfr`（每次 7–12 MB）、`gc.log` 原始全文、`app.log`：体积太大，且用 `scripts/bench.ps1` 一条命令就能重新生成。
- 生成方式见 [performance.md §12](../performance.md)。JFR 的关键事件统计（`ThreadPark` / `SocketRead` / `ExecutionSample` / `GCPhasePause`）已经抄进报告 §5.3。

## 这些数字的边界

- 全部来自**一台桌面机**（i5-14600K，14C/20T），不是 4C8G 云主机；
- 全部是**单 JVM 三合一**（`all` profile），不是 api/relay/worker 分离部署；
- 接收方是**本机回环**上的 MockReceiver，不是公网客户；
- 压测期间**限流被关闭**（`rateLimit=0`），测的是容量而不是配额。
