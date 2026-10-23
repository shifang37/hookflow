# v0.4 批量提交对照（2026-10-23）

这些目录是 [performance.md §14](../../performance.md) 引用到的原始输出，用 `scripts/bench.ps1 -Scenario api` 采集。

| 目录 | 构建来源 | 请求形态 | 标称到达率 |
|---|---|---|---:|
| `baseline-single-300-run1/`、`baseline-single-300-run2/` | `git worktree` 检出的 `37ad12b`（v0.4 第 1 轮之前的 HEAD） | `POST /v1/events`，1 条/请求 | 300 events/s |
| `single-300-run1/`、`single-300-run2/` | 同一工作树 + 未提交的 v0.4 改动 | `POST /v1/events`，1 条/请求 | 300 events/s |
| `single-450/` | 同上 | `POST /v1/events`，1 条/请求 | 450 events/s |
| `batch20-450/` | 同上 | `POST /v1/events/batch`，20 条/请求 | 450 events/s |
| `batch20-900/` | 同上 | `POST /v1/events/batch`，20 条/请求 | 900 events/s |

## 关于 `meta.txt` 里的 `git` 行

v0.4 的改动在采集时**还没有提交**，所以 `meta.txt` 里记录的是它们的父提交 `37ad12b`；baseline 目录是用同一个 `37ad12b` 检出后构建的 jar，因此两组的 `git` 行相同——区别只在是否包含工作树里的改动（清单见 [v0.4-plan.md §3 A2](../../v0.4-plan.md)）。

## 每个目录里有什么

与 v0.3 的 `docs/perf/2026-10-22/` 保持一致：

| 文件 | 内容 |
|---|---|
| `loadgen.txt` | 生成器原始输出（到达率、成功/失败、开环与闭环两套分位数） |
| `meta.txt` | 机器、JVM 参数、app 参数、到达率、batch size、git、docker 版本 |
| `metrics.txt` | `/actuator/prometheus` 抽样（outbox 积压/发布、GC、Hikari、降载、公平调度） |
| `db.txt` | 跑完后的表状态（outbox/delivery 按状态计数） |
| `timeline.csv` | 逐秒的 reached/accepted/429/其它（批量模式下已按事件数换算） |
| `gc-summary.txt` | 从 `gc.log` 提取的一行摘要（`pauses=` / `totalPauseMs=` / `maxPauseMs=`） |

原始 `gc.log` / `app.log` **没有进仓库**：`.gitignore` 忽略 `*.log`，所以这里只保留提取后的摘要与 Prometheus 里的 `jvm_gc_pause_seconds_*`。需要原始日志时重跑一次即可。

## 复现时的两个必须

1. 显式覆盖 `--hookflow.backpressure.tenant-high-watermark=1000000`（`bench.ps1 -Scenario api` 现在默认这么做）。`application-all.yml` 为演示把它设成 200，不覆盖的话 300 events/s 测到的是降载后的延迟。
2. 线程数与到达率匹配：单条 300/450 分别用 32/48 个发送线程，批量 20 条用 16/24 个。生成器是开环的，但每个线程仍要等上一个响应，线程不够时开环延迟会涨到十几秒（那是生成器的瓶颈，不是平台的）。
