# v0.4 B0：A2 之后的下游速率（2026-10-23）

这些目录是 [performance.md §15](../../performance.md) 引用的原始输出。目的是回答一个 §14 没有回答的问题：
批量提交把**入口**成本压平之后，平台**每秒真正能送到客户**多少、卡在哪。

| 目录 | 入口 | 标称到达率 | 发送线程 |
|---|---|---:|---:|
| `single-450/` | `POST /v1/events`，1 条/请求 | 450 events/s | 48 |
| `batch20-450/` | `POST /v1/events/batch`，20 条/请求 | 450 events/s | 16 |
| `batch20-900/` | 同上 | 900 events/s | 24 |

每个目录都是 **10 s 预热 + 60 s 负载 + 20 s 观察期**，用同一条命令采集：

```powershell
.\scripts\bench.ps1 -Scenario api -Rate 450 -Duration 60 -Warmup 10 -Threads 16 -BatchSize 20 `
    -SampleSeconds 5 -TailSeconds 20 -StatementDigest
```

## 每个目录里有什么

| 文件 | 内容 |
|---|---|
| `downstream.txt` | 每 5 秒一行：接受/s、**发布/s**、投递成功/s、outbox 积压、delivery PENDING、broker 队列深度、Hikari pending、拒绝/s、锁冲突/s；末行是负载段的均值 |
| `series.csv` | 上面这张表的原始计数（未折算），三路来源：Micrometer / MySQL 行数 / `rabbitmqctl` |
| `statements.txt` | MySQL 按 digest 聚合的语句耗时与次数（B3 的分解表，窗口 = 本次运行） |
| `db.txt` | 跑完后 delivery / outbox 的状态分布，以及已成功投递的平均「创建→更新」耗时 |
| `broker.txt` | 跑完后 `hookflow.delivery` 队列的深度、在途、消费者数 |
| `metrics.txt` | `/actuator/prometheus` 的抽样（发布/积压/GC/Hikari/降载/公平调度） |
| `loadgen.txt` | 生成器原始输出（到达率、成功/失败、开环与闭环两套分位数） |
| `meta.txt` | 机器、JVM 参数、app 参数、到达率、batch size、git、docker 版本 |
| `timeline.csv` | 逐秒 reached/accepted/429/其它 |

`gc.log` / `jfr.jfr` / `app.log` 没有进仓库（`.gitignore` 忽略 `*.log`，JFR 每次 5–12 MB）。`jfr.jfr` 在运行结束时
`jcmd` 会显式 dump 一份到 `bench-results/<run>/`；注意 bench 结束时打印的目录清单里 `app.log` / `jfr.jfr` 可能是 `0`
——那是 Windows 对仍被打开的文件返回的目录项大小，进程被 `Stop-Process` 之后再看就有内容了（别当成 JFR 又坏了）。

## 复现时的四个必须

1. **`-SampleSeconds` 和 `-TailSeconds` 都要给**。只给前者会得到"涨"，看不到"落"；只给后者反过来会漏掉上升段。
2. **`-StatementDigest` 会 TRUNCATE `performance_schema`**，需要 root（脚本用容器里的 `-uroot -proot`）。
   不清空的话，digest 是服务器生命周期累计值，会把上一次运行也算进去。
3. **`bench.ps1` 会先删掉 `hookflow.delivery` / `hookflow.delivery.dlq` 两个队列**。队列的参数（`x-max-length`）
   一旦不同，RabbitMQ 会拒绝重新声明（`PRECONDITION_FAILED`），应用每两秒报一次错，而旧队列继续服务——
   那测到的是一套没有配置文件描述的拓扑。
4. **到达率要与线程数匹配**（同 §14）：批量 20 条时 450 events/s 只需 16 个线程，900 需要 24；单条 450 需要 48。

## 这一批的边界

- 单机、单 JVM（`all` profile）、relay 单实例；没有多进程拓扑。
- 接收方是本机 MockReceiver（≈2 ms），不是公网客户。
- 每次 60 s 负载，只有三次运行；出口速率的散布是 35–47/s，别把它读成小数点后一位。
- 压测期间限流关闭（`rateLimit=0`），测的是容量不是配额。
