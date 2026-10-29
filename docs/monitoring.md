# 监控：Prometheus + Grafana

这套监控是**本地可复现**的：三条命令起依赖和应用，再用一个 compose profile 起
Prometheus 与 Grafana，3 个预装面板和 2 条告警规则自动加载。

它证明的是「投递链路能被看见」：摄入、outbox 积压、投递延迟分位数、成功率、死信率、
重试/回收、消费者确认、JVM 与连接池。它**不是**生产监控方案——没有 Alertmanager、
没有远程存储、没有鉴权边界，见 §7。

---

## 1. 起起来

### 1.1 依赖与应用

```powershell
# 仓库根目录
docker compose up -d                 # MySQL 8.4 + RabbitMQ 4 + Redis 8
.\mvnw.cmd spring-boot:run           # all profile，8080
```

应用起来后先确认指标端点：

```powershell
curl.exe -s http://localhost:8080/actuator/prometheus | Select-String 'hookflow_delivery'
```

### 1.2 Prometheus + Grafana

```powershell
docker compose --profile monitoring up -d
docker compose --profile monitoring ps
```

| 地址 | 内容 | 凭据 |
|---|---|---|
| http://localhost:3000 | Grafana，预装 3 个面板 | `admin` / `hookflow`（`GRAFANA_PASSWORD` 可覆盖） |
| http://localhost:9090 | Prometheus：Targets、/alerts、/rules | 无 |

Grafana 首次启动会从 `monitoring/grafana/provisioning/` 加载数据源和面板、从
`monitoring/grafana/dashboards/` 加载 JSON。默认首页是「HookFlow 概览」。

> 应用也可以跑在 compose 的 `full` profile 里（`docker compose --profile full --profile monitoring up -d`）。
> Prometheus 抓的是 `host.docker.internal:8080`；两种跑法都把 8080 发布到宿主机，所以同一个地址都通。
> Linux 上靠 `extra_hosts: host.docker.internal:host-gateway`；Docker Desktop 原生支持。

### 1.3 停止

```powershell
docker compose --profile monitoring down          # 保留数据卷
docker compose --profile monitoring down -v       # 连 Grafana/Prometheus 数据一起删
```

---

## 2. 两个先补的指标

路线图点名的缺口，代码在
[`DeliveryProcessor`](../src/main/java/dev/hookflow/worker/DeliveryProcessor.java)：

| Micrometer 名 | Prometheus 名 | 何时 +1 / 记录 |
|---|---|---|
| `hookflow.delivery.attempted` | `hookflow_delivery_attempted_total` | 每次**真正到达网络**的投递尝试。它是成功率的分母 |
| `hookflow.delivery.duration`（Timer + `publishPercentileHistogram`） | `hookflow_delivery_duration_seconds_bucket`（以及 `_count` / `_sum`） | 每次尝试 settle 时记录一次，单位秒；直方图让 p50/p95/p99 可算 |

有意的排除：

- **重复消息跳过**（`hookflow_delivery_duplicate_skipped_total`）不算 attempted：没有发 HTTP。
- **endpoint 已暂停/已删除**导致的取消不算 attempted：claim 成功了，但从未发请求。
  把它们算进分母会让「成功率」在暂停 endpoint 时无故下降，掩盖真正的问题。
- Timer **不打 endpoint/tenant 标签**：那会让序列数随租户数增长。要看单个 endpoint，
  用投递详情 API，不是指标。

Actuator 已经暴露 `prometheus`，见
[`application.yml`](../src/main/resources/application.yml) 的 `management.endpoints.web.exposure.include`。
Micrometer 的点号在 Prometheus 侧变成下划线，counter 加 `_total`；**别在 PromQL 里写点号**。

---

## 3. 三个面板

JSON 在 `monitoring/grafana/dashboards/`，数据源统一用变量 `${DS_PROMETHEUS}`（默认 `prometheus`）。
面板只读，改了会被 30s 后的文件同步覆盖（`allowUiUpdates: true` 只是允许在 UI 里临时改）。

### 3.1 HookFlow 概览（`hookflow-overview`）

| 面板 | 口径 |
|---|---|
| 事件摄入速率 | `rate(http_server_requests_seconds_count{uri=~"/v1/events.*"}[5m])`，含单条与批量 |
| 尝试成功率（5m） | `succeeded / attempted`，低于 90% 橙、95% 绿；**每次重试也算一次 attempted**，所以它是「单次尝试成功率」，不是「投递最终成功率」。终态口径看「死信率」 |
| 投递 p95 延迟 | `histogram_quantile(0.95, rate(hookflow_delivery_duration_seconds_bucket[5m]))` |
| Outbox 积压 | `sum(hookflow_outbox_backlog)`，1000 橙、10000 红；平台水位 100000，到这里才会 503 |
| 投递结果（每秒） | attempted / succeeded / dead / retry_scheduled 四条线 |
| Outbox 积压与发布（每秒） | backlog 与 published/publish_failed 的对照：积压涨而发布跌 = relay 问题 |
| 死信率（2m） | `dead / (dead + succeeded)`（终态投递口径），与告警同一个表达式；**不是** `dead / attempted`，否则一次死信消耗 8 次重试会把比例稀释成 12% |
| 降载与超预算（每分钟） | `ingest_shed{scope}`、`worker_over_capacity{policy}`、`fairness_skipped`，乘 60 变成次/分 |

### 3.2 HookFlow 投递详情（`hookflow-deliveries`）

- 顶部三个 1 小时累计：死信、重试排队、重复消息跳过。
- 尝试结果、延迟分位数（p50/p95/p99）、恢复与回收（retry enqueued / lease reclaimed /
  stuck requeued / outbox recovered）、消费者 ack/nack/requeue/poison、outbox 发布四态、
  公平调度 admitted/skipped/over capacity。
- 排查顺序：先看「尝试结果」哪条线起来了，再看「恢复与回收」是否在兜底，最后看
  「消费者确认」是否在 requeue/poison。

### 3.3 HookFlow JVM 与资源（`hookflow-jvm`）

进程 CPU（`process_cpu_usage`）、堆使用率、存活线程、运行时长、堆 used/max、GC 暂停率、
Hikari active/idle/pending/max、HTTP 服务端 p95（按 `uri`）。

这一页回答「是应用本身慢，还是下游/数据库慢」：worker 是 IO 密集的，CPU 打满通常意味着
序列化/日志/GC，而不是 HTTP 等待；`hikaricp_connections_pending > 0` 才是 DB 连接池在排队。

---

## 4. 告警

规则文件：[`monitoring/alerts/hookflow.yml`](../monitoring/alerts/hookflow.yml)。
Prometheus 每 15s 评估一次；没有 Alertmanager，所以「响」= **Prometheus `/alerts` 里
FIRING + Grafana Alerting 里能看到**，不会发邮件/Slack。

| 告警 | 条件 | for | 含义 |
|---|---|---|---|
| `HookFlowOutboxBacklogGrowing` | `sum(hookflow_outbox_backlog) > 1000` 且 10 分钟导数为正 | 2m | 积压高**且还在涨**；距离 100000 的平台水位还有时间处理 |
| `HookFlowDeadLetterRateHigh` | `dead / (dead + succeeded) > 10%`（2m 窗口） | 1m | 到达终态的投递中超过一成是 DEAD：要么不可重试，要么重试预算耗尽 |

为什么积压告警要加 `deriv > 0`：只报「大于 1000」会在一次已经停止的洪峰后一直响；
积压高但不涨通常只是还没排完，积压**涨**才是 relay/worker 跟不上。

### 4.1 制造一次死信告警（验收用）

```powershell
# 让演示接收方永远返回 500；demo 的退避是 800ms 起步，约 10s 进 DEAD。
# 注意这两个接口收的是 query 参数，不是 JSON body。
curl.exe -s -X POST 'http://localhost:8080/v1/demo/receiver?mode=ERROR'

# 制造流量（默认模拟器已经是运行的，这里只是调高到 20/s）
curl.exe -s -X POST 'http://localhost:8080/v1/demo/simulator?running=true&rate=20'
```

约 1–2 分钟后：

```powershell
curl.exe -s http://localhost:9090/api/v1/alerts | Select-String 'HookFlowDeadLetterRateHigh'
```

Grafana 里「投递详情 → 死信（近 1h）」会上升，「概览 → 死信率」也会上升。恢复：

```powershell
curl.exe -s -X POST 'http://localhost:8080/v1/demo/receiver?mode=OK'
curl.exe -s -X POST 'http://localhost:8080/v1/demo/simulator?running=false'
```

---

## 5. 改抓取目标

`monitoring/prometheus.yml` 里只有一个应用 target：

```yaml
- targets: ['host.docker.internal:8080']
```

- 应用换了端口：改这里，然后
  `curl.exe -X POST http://localhost:9090/-/reload`（compose 已开 `--web.enable-lifecycle`）。
- 应用在另一台机器/集群：把 target 改成可达地址。**不要把 `localhost` 填进去**——
  在容器里 `localhost` 是 Prometheus 自己。
- 抓取间隔 15s 与 Grafana 数据源的 `timeInterval` 对齐；调小会让 `rate[5m]` 更灵敏，
  但也更贵。

---

## 6. 验证清单（本机可复现）

```powershell
docker compose --profile monitoring up -d
curl.exe -s http://localhost:9090/-/ready
curl.exe -s http://localhost:3000/api/health
# Grafana 需要先登录拿 session；用 admin/hookflow 调 API 或直接打开 /dashboards
```

- Prometheus `Targets` 里 `hookflow` 为 UP；
- `curl http://localhost:9090/api/v1/rules` 能看到两条 `hookflow-delivery` 规则；
- Grafana 「HookFlow」文件夹下有 3 个面板，打开无 "Datasource not found"；
- 按 §4.1 操作后 1–2 分钟内 `HookFlowDeadLetterRateHigh` 变为 firing。

---

## 7. 这套东西**不**包含什么

- **没有 Alertmanager**：没有邮件、Slack、PagerDuty。要接，就在 compose 里加
  `prom/alertmanager` 和一条 `alerting:` 配置；本次没做，因为验收只要求「告警可见」。
- **没有长期存储**：Prometheus 默认保留 15 天，容器删了数据卷也在本地。要看历史趋势，
  接 Thanos/VictoriaMetrics。
- **没有鉴权**：Prometheus 和 Grafana 都只监听 localhost 映射端口；Grafana 默认密码是
  `hookflow`（演示值）。不要把 3000/9090 暴露到公网。
- **没有 K8s 采集**：任务 K（Helm）之后可以换成 ServiceMonitor/PodMonitor；本机 compose
  是当前唯一路径。
- **没有 per-tenant / per-endpoint 维度**：指标故意不打租户/端点标签（基数会随客户数增长）。
  按租户或 endpoint 看，用控制台的报表/投递详情接口；Prometheus 这边只保留平台级聚合。
- **没有 RabbitMQ 队列深度**：路线图的资源面板提过它，但本项目没有部署 rabbitmq_exporter，
  broker 侧只有管理 UI。队列深度要另加 exporter（或走 management API）才能进 Prometheus；
  当前用 `hookflow_outbox_backlog` + `publish_rejected` 判断「broker 是否在把积压退回来」。
- **没有 SLO 燃烧率告警**：只有两条基础规则，够发现「积压」和「死信率」两类事故，
  不覆盖延迟 SLO。

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Grafana 面板全是 "No data" | Prometheus target DOWN | 打开 9090/targets 看错误；确认应用在 8080 且 `/actuator/prometheus` 可访问 |
| target 显示 `connection refused` | 应用没起，或不在宿主机网络 | 先起应用；Linux 检查 `extra_hosts` |
| 面板报 "Datasource not found" | 数据源 UID 不是 `prometheus` | 确认 provisioning 已加载；变量 `DS_PROMETHEUS` 默认值为 `prometheus` |
| `hookflow_delivery_duration_seconds_bucket` 不存在 | 应用只跑了 api/relay 角色 | Timer 在 worker 进程；用 `all` profile 或指向 worker 的 actuator |
| 告警一直 Pending 不 Firing | `for` 未满（2m / 1m），或流量已停导致比率回落 | 看 `/api/v1/alerts` 的 `activeAt`；保持流量至少 3 分钟 |
| Prometheus 容器起不来 | 规则 YAML 语法错 | `docker logs hookflow-prometheus`；本地 `promtool check rules` 如果有 promtool |
