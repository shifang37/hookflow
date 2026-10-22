# ADR-0011：trace 上下文放进消息体，同时也在 AMQP header 里

- 状态：已采纳（v0.3）
- 日期：2026-10-22

## 背景

一次投递跨三个进程边界和一个数据库往返：

```
api (HTTP 请求) -> [MySQL outbox] -> relay (定时任务) -> [RabbitMQ] -> worker (listener) -> [HTTP POST] 接收方
```

v0.2 只有 `requestId`（MDC）和日志。它能回答"这条请求发生了什么"，但回答不了：

- 一条投递慢，是慢在入队、等 relay、等 worker、还是慢在接收方？
- 一个租户报"昨晚的 webhook 迟到了 40 秒"，40 秒花在哪一段？

`requestId` 在 HTTP 请求结束时就消失了，而投递发生在另一个进程、另一个时刻。

## 选项

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 继续用 requestId + 日志** | 把 requestId 塞进 payload，逐跳打日志 | 零依赖；但"耗时分布"要靠人肉 grep 时间戳，且没有跨进程的父子关系 |
| **B. Brave + Zipkin** | micrometer-tracing-bridge-brave | 可以；但 OTel 是现在的默认生态，接收方更可能已支持 W3C |
| **C. Micrometer Tracing + OTel bridge + OTLP → Jaeger** | 选中 | 多一个 collector（本地 compose 里一个容器）；span 有 CPU/分配成本 |
| **D. 只传播 header，不采集** | 手工透传 `traceparent` | 成本最低；但没有耗时分布，等于只做了 3/4 |

## 决定

选 **C**，而且传播用 **W3C `traceparent`**，不用自研 id。

### 传输：两处都放，但以 payload 为准

| 位置 | 作用 | 为什么需要 |
|---|---|---|
| `DeliveryMessage.traceparent`（outbox 的 JSON payload） | **权威副本** | outbox 行会在崩溃窗口、broker 拒收后被**重新发布**，那时 AMQP header 早就不存在了。只放 header 会在最需要排查的场景里丢掉链路 |
| AMQP `traceparent` header | broker 侧可见 | 让 broker 插件、队列监控、下一跳的仪表化看到同一个 trace |

### 各跳怎么做

- **api**：`OutboxWriter` 在写 outbox 时读取当前 span 的 `traceparent`（Spring Boot 对 WebMVC 自动建 span）。
- **relay**：把 JSON 里的 `traceparent` 当作**父上下文**，建 `hookflow.publish` span（`PRODUCER`），再发布消息。
- **worker**：从 JSON 恢复父上下文，建 `hookflow.delivery` span（`CONSUMER`）。
- **HTTP 出站**：在 `hookflow.receiver` span 里把 `traceparent` 注入请求头。**接收方不需要知道 HookFlow**，任何支持 W3C 的 APM 都会自动把这条 webhook 接到同一个 trace 上——这是选标准、不选自研 id 的最大收益。

### 采样与导出

- 采样率默认 `1.0`：本项目展示的是**链路连通性**，被采样掉的 trace 和断掉的 trace 在排查时无法区分。真实部署第一件事就是把采样率降下来。
- **导出默认关闭**（`management.otlp.tracing.export.enabled=false`）：`mvn verify` 不能依赖 collector 存在，更不能因为 collector 不在而失败。本地 `docker compose` 里通过环境变量打开。

## 代价与已知问题

- **每个 span 有成本**：对象分配 + ThreadLocal 传播。压测报告里给出开关 tracing 的对比（见 `docs/performance.md`）。
- **`traceparent` 让 outbox payload 大约多 60 字节**。1 亿行 outbox 就是 6 GB——不是零，但在 v0.3 的规模下可忽略。
- **v0.2 写入的行没有 `traceparent`**：`DeliveryMessage` 的新字段必须允许缺失（有单测 `DeliveryMessageTest.oldRowsWithoutTraceparentParse` 盯着），否则升级时最后几行会进 DLQ。
- **采样是 head-based**：入口决定，后续无法反悔。要"慢请求全采样"需要 tail-based（collector 侧），v0.3 不做。
- **collector 是新增的运行依赖**。它挂掉不影响投递（导出是异步、失败即丢 span），但会看不到新 trace。

## 什么情况下会改这个决定

1. trace 的存储成本成为主要开销 → 降低采样率，或改 tail-based 采样。
2. 需要跨租户的成本归因（"这个 trace 花了多少 DB 时间"）→ 从 span 转到 metrics + exemplars。
3. 接收方普遍不支持 W3C → 那时需要同时发 `X-B3-*`，但这属于兼容性债务而非设计变更。

## 如何验证

- `TracingIT`：真实 HTTP 请求 → 读响应头 `X-HookFlow-Trace-Id` → 断言接收方收到的 `traceparent` 里是**同一个 trace id**（同一 trace，不同 span id）。这是"一条 trace 串起来"唯一有意义的断言。
- `DeliveryMessageTest`：`traceparent` JSON 往返 + v0.2 旧行仍可解析。
- 本地 Jaeger：一条慢投递能看到 `api -> hookflow.publish -> hookflow.delivery -> hookflow.receiver` 的耗时分布（一键命令见 README）。
