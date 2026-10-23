# API 参考

Base URL：`http://localhost:8080`
在线文档（OpenAPI/Swagger UI）：`http://localhost:8080/docs`

## 认证

两个信任域，不要混用：

| 用途 | 凭证 | 位置 |
|---|---|---|
| 租户业务接口 `/v1/**` | API Key | `Authorization: Bearer hk_<prefix>_<secret>` |
| 运营接口 `/v1/admin/**` | Bootstrap Token | `X-Bootstrap-Token: <token>` |

API Key 只在创建时返回一次，之后只存 `SHA-256(secret)`。

## 统一错误体

```json
{
  "code": 40901,
  "error": "IDEMPOTENCY_KEY_REUSED",
  "message": "Idempotency key already used with a different payload",
  "requestId": "3f2a1b9c-...",
  "timestamp": "2026-10-22T05:11:22.481Z"
}
```

| code | error | HTTP | 说明 |
|---:|---|---:|---|
| 40000 | `INVALID_REQUEST` | 400 | 参数错误 |
| 40100 | `UNAUTHORIZED` | 401 | 凭证缺失/无效/已撤销 |
| 40300 | `FORBIDDEN` | 403 | 无权限（scope 不足或租户被停用） |
| 40400 | `NOT_FOUND` | 404 | 资源不存在（含跨租户访问） |
| 40500 | `METHOD_NOT_ALLOWED` | 405 | — |
| 41300 | `PAYLOAD_TOO_LARGE` | 413 | payload 超限 |
| 40901 | `IDEMPOTENCY_KEY_REUSED` | 409 | 幂等键复用 |
| 40902 | `ENDPOINT_PAUSED` | 409 | endpoint 已暂停 |
| 40903 | `ILLEGAL_TRANSITION` | 409 | 状态不允许该操作（如重放非 DEAD 的投递） |
| 42900 | `RATE_LIMITED` | 429 | 超出租户速率（**按事件数计**，一批 500 条要 500 个配额） |
| 42902 | `TENANT_BACKLOG_EXCEEDED` | 429 | **该租户**未投递积压超过水位线，带 `Retry-After` |
| 50000 | `INTERNAL` | 500 | 未预期错误（细节只在日志里，用 requestId 查） |
| 50300 | `DEPENDENCY_UNAVAILABLE` | 503 | 依赖不可用（限流 fail-closed 时的 Redis；或批量接口整批未提交） |
| 50301 | `OVER_CAPACITY` | 503 | 平台整体过载，正在降载，带 `Retry-After` |

---

## 运营接口

### POST /v1/admin/tenants

创建租户并签发首个 API Key。

```bash
curl -X POST localhost:8080/v1/admin/tenants \
  -H 'X-Bootstrap-Token: hookflow-dev-bootstrap-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","rateLimit":100,"maxInFlight":32,"weight":1}'
```

`201` → `{"tenantId":1,"name":"acme","apiKey":"hk_xxxxxxxx_yyyy…","keyPrefix":"xxxxxxxx"}`

| 字段 | 默认 | 含义 |
|---|---|---|
| `rateLimit` | 100 | 每秒事件数，`0` 表示不限速 |
| `maxInFlight` | 32 | **该租户同时在投递中的条数上限**。这是 v0.3 的隔离栅栏：它同时限制了"这个租户能排在别人前面多少条"。`0` = 不限（回到 v0.2 行为，不推荐） |
| `weight` | 1 | 竞争时的加权轮询权重（1–100）。只影响发布顺序的份额，不放大吞吐 |

`maxInFlight` 的定值方法：**目标等待时间 × 排空速率**。本机实测排空约 300/s，想要 p99 等待 < 500ms，就取 150–200 而不是默认的 32 —— 反过来，机器排空只有 40/s 时（接收方很慢），32 就已经是 800ms 的队头了。见 [capacity.md](capacity.md)。

### POST /v1/admin/tenants/{tenantId}/keys

为已有租户追加一个 API Key（用于轮换）。

### POST /v1/admin/keys/{apiKeyId}/revoke

撤销 Key。`204`。

---

## Endpoint

### POST /v1/endpoints

```bash
curl -X POST localhost:8080/v1/endpoints \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "url": "https://customer.example/hooks/hookflow",
        "subscribedTypes": "order.paid,order.refunded",
        "maxAttempts": 8,
        "maxConcurrency": 10
      }'
```

`201` → 含 `signingSecret`（**仅此一次**）

```json
{
  "id": 1,
  "url": "https://customer.example/hooks/hookflow",
  "subscribedTypes": "order.paid,order.refunded",
  "maxAttempts": 8,
  "maxConcurrency": 10,
  "status": "ACTIVE",
  "signingSecret": "base64url-32-bytes"
}
```

`subscribedTypes` 支持逗号分隔列表或 `*`（全部）。匹配是**精确匹配**，不是前缀匹配。

`maxConcurrency`（默认 `0` = 不限）：允许打到这个 URL 的**并行调用数上限**。接收方扛不住并发时填它，替代方案是让它把整个租户拖慢——见 [capability 文档](capacity.md) §4 的"接收方慢"一行。

### GET /v1/endpoints?page=0&size=20

`PageResponse<EndpointResponse>`（不含密钥）。

### GET /v1/endpoints/{id}

### POST /v1/endpoints/{id}/pause

暂停。**同时会取消该 endpoint 下所有 `PENDING` / `RETRY_WAIT` 的投递**（状态变 `CANCELED`）。

### POST /v1/endpoints/{id}/resume

恢复。**已取消的投递不会复活**——需要重放请针对具体 delivery 操作。

---

## Event

### POST /v1/events

```bash
curl -X POST localhost:8080/v1/events \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: order-1001-paid' \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"order.paid","payload":{"orderId":1001,"amount":99.00}}'
```

| 状态码 | 含义 |
|---:|---|
| `202` | 新事件已接受，`idempotentReplay: false` |
| `200` | 该幂等键已存在，返回**同一个** `eventId`，`idempotentReplay: true` |

```json
{"eventId":1,"deliveryCount":2,"idempotentReplay":false}
```

约束：

- `Idempotency-Key` 必填，1–255 字符，租户范围内唯一
- `eventType` 必须匹配 `[A-Za-z0-9._-]{1,128}`（限制字符集是为了能安全地嵌进投递的 JSON 信封而不需要转义）
- `payload` 必填，任意 JSON

**重试建议**：网络错误时用**同一个** `Idempotency-Key` 重试，不会产生重复事件；返回 200 表示"上次已经成功了"。

**响应头**：

| 头 | 说明 |
|---|---|
| `X-Request-Id` | 每个请求都有；出现在日志 MDC 与错误体里 |
| `X-HookFlow-Trace-Id` | 当前 trace id（tracing 开启时）。拿去 Jaeger 查这条投递的完整链路 |
| `Retry-After` | **只在降载响应上出现**（`42902` / `50301`），单位秒。不带它退避的客户端会把降载变成重试风暴 |

降载语义：

| 状态码 | 谁的问题 | 客户端该做什么 |
|---|---|---|
| `429` `RATE_LIMITED` | 超过租户每秒配额 | 下一整秒再试 |
| `429` `TENANT_BACKLOG_EXCEEDED` | 该租户自己的未投递积压太高 | 按 `Retry-After` 退避；平台对其他租户仍然健康 |
| `503` `OVER_CAPACITY` | 平台整体过载 | 按 `Retry-After` 退避；这不是租户的问题 |

### GET /v1/events/{id}

---

## 批量提交（v0.4）

### POST /v1/events/batch

一次 HTTP 提交最多 500 条事件，**在一个事务里**写入 event / delivery / outbox。

```bash
curl -X POST localhost:8080/v1/events/batch \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "events": [
          {"eventType":"order.paid","payload":{"orderId":1001,"amount":99.00},
           "idempotencyKey":"order-1001-paid"},
          {"eventType":"order.paid","payload":{"orderId":1002,"amount":19.00},
           "idempotencyKey":"order-1002-paid"}
        ]
      }'
```

`202`（至少一条是新事件）或 `200`（全部是重放）：

```json
[
  {"index": 0, "eventId": 1, "deliveryCount": 2, "replay": false},
  {"index": 1, "eventId": 2, "deliveryCount": 2, "replay": false}
]
```

| 字段 | 含义 |
|---|---|
| `index` | 该条在请求 `events[]` 里的下标；结果与请求顺序一一对应，但**按 `index` 配对更稳** |
| `eventId` | 这条事件的 id。幂等键已存在时返回**原来那个** id |
| `deliveryCount` | 该事件的投递条数（匹配到的 endpoint 数） |
| `replay` | `true` = 这条没有创建新事件：幂等键以前就存在，**或者**它在同一批里重复了前面某条 |

约束与语义：

- `events` 长度 1–500。**超过 500 是整批 400，不会截断执行**——截断会让调用方以为自己提交了 500 条
- 每条都要自己的 `idempotencyKey`（1–255 字符，租户内唯一）。**没有请求级 `Idempotency-Key` 头**：一批是一个事件集合，不是一个可以整体重放的请求
- 部分重复**不是错误**：预先存在的键按条返回 `replay:true` + 原 `eventId`，其余照常写入。同一批内重复的键解析到**第一次出现**创建的那个事件
- 整批失败（如 MySQL 不可用）返回 `503` `DEPENDENCY_UNAVAILABLE`：一个事务，一条都没提交，所以**用同一批幂等键原样重试是安全的**
- 校验错误（`eventType` 不合法、`payload` 缺失、键超长）返回 `400`，错误消息里带 `events[i]` 下标

**限流与降载按条数计，不按请求数**：

| 机制 | 单条接口 | 批量接口 |
|---|---|---|
| 租户速率（Redis 固定窗口） | 1 次请求 = 1 个配额 | N 条 = **N 个配额**；整批超配额则整批 `429` |
| 平台/租户水位线 | 按当前积压判断 | 按 `当前积压 + N` 判断，一批不能一次跳过水位线 |

不做按条计的话，批量接口就是绕过限流和降载的最短路径。

**吞吐提升来自哪里**：来自**减少事务与往返次数**（一批一次 `INSERT ... VALUES (...),(...)`、一次提交），**不是来自并发**。批量请求仍然在一个连接上、一个事务里串行执行；并发的收益要靠多个客户端同时提交。

**语义没有变化**：每一条被接受的事件仍然走同一条链路，at-least-once、不保证顺序、接收方按 `event_id` 去重——这些都不因为批量而改变。

---

## Delivery

### GET /v1/deliveries?eventId=&status=&page=0&size=20

`status` 可选值：`PENDING` `DELIVERING` `RETRY_WAIT` `SUCCEEDED` `DEAD` `CANCELED`

### GET /v1/deliveries/{id}

返回投递状态 + **完整 attempt 历史**：

```json
{
  "delivery": {
    "id": 3, "eventId": 1, "endpointId": 2,
    "status": "DEAD", "attemptCount": 8, "maxAttempts": 8,
    "nextAttemptAt": null,
    "lastErrorClass": "SERVER_5XX", "lastStatusCode": 500,
    "createdAt": "2026-10-22T05:11:22.481",
    "updatedAt": "2026-10-22T05:26:11.902"
  },
  "attempts": [
    {"attemptNo":1,"status":"FAILED","statusCode":500,"errorClass":"SERVER_5XX",
     "durationMs":12,"responsePreview":"{\"error\":\"boom\"}",
     "startedAt":"...","finishedAt":"..."}
  ]
}
```

`responsePreview` 截断到 `hookflow.delivery.max-response-preview-bytes`（默认 512），大响应体和不敏感头不落库。

### POST /v1/deliveries/{id}/replay

仅允许 `DEAD`。效果：

- `status` → `PENDING`
- `max_attempts` → `attempt_count + 1`（**只给一次额外额度，不清空历史**）
- 立即重新入队

```json
{"code":40903,"error":"ILLEGAL_TRANSITION","message":"Only DEAD deliveries can be replayed (current: SUCCEEDED)"}
```

---

## 演示控制台 API（仅本地 `all` profile）

这些接口**不需要 API Key**，也**只在** `spring.profiles.active=all` 且
`hookflow.demo.enabled=true` 时存在（其他 profile 下是 404，不是「有密码」）。原因见
[demo.md](demo.md) §4。

| 方法 | 路径 | 参数 | 作用 |
|---|---|---|---|
| `GET` | `/v1/demo/state` | — | 页面每秒轮询的全量状态（平台、模拟器、接收方、租户、投递流水） |
| `POST` | `/v1/demo/simulator` | `running`, `rate` | 启停模拟业务系统 / 改速率（每租户 0–200/秒） |
| `POST` | `/v1/demo/receiver` | `mode`, `delayMillis` | `OK` / `ERROR` / `SLOW` / `REJECT`；OK 模式下可加延迟（≤2000ms） |
| `POST` | `/v1/demo/burst` | `tenantId`, `count`, `perSecond` | 灌事件（走真实 HTTP，`perSecond=0` 表示全速） |
| `POST` | `/v1/demo/tenants/{id}/budget` | `maxInFlight` | 改租户并发预算并立刻刷新调度器快照 |
| `POST` | `/v1/demo/tenants/{id}/event` | — | 让该租户的业务系统提交一条事件 |
| `POST` | `/v1/demo/deliveries/{id}/replay` | — | 重放死信（等价于 `/v1/deliveries/{id}/replay`） |
| `POST` | `/v1/demo/reset` | — | 停模拟、把演示租户的事件/投递/outbox 清空（带重试） |

`GET /` 在演示启用时重定向到 `/demo/index.html`。

---

## 健康检查与指标

| 路径 | 说明 |
|---|---|
| `GET /actuator/health` | 总体健康 |
| `GET /actuator/health/readiness` | K8s readiness（含 MySQL/RabbitMQ 依赖） |
| `GET /actuator/health/liveness` | K8s liveness |
| `GET /actuator/prometheus` | Prometheus 指标 |

## 接收方验签示例（Java）

```java
String expected = HmacSigner.sign(
        secret,                                  // 建 endpoint 时拿到的那串
        Long.parseLong(header("X-HookFlow-Event-Id")),
        Long.parseLong(header("X-HookFlow-Delivery-Id")),
        Long.parseLong(header("X-HookFlow-Timestamp")),
        rawBody);
boolean ok = expected.equals(header("X-HookFlow-Signature"));
```

再配合时间戳窗口校验（±300s）和以 `event_id` 为唯一键的幂等写入，接收方就完整了。

## 接收方侧：把 trace 接上（v0.3）

每次投递都带标准 W3C `traceparent` 头：

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

如果接收方的服务已经接了 OpenTelemetry / Jaeger，**不需要任何额外配置**，这条 webhook 会自动挂到"触发它的那次 API 调用"的同一条 trace 上——排查"客户说 webhook 慢"时，双方看到的是同一张图。

没有 APM 也能用：把 `traceparent` 的第二个字段（32 位十六进制）和 API 响应头 `X-HookFlow-Trace-Id` 对上，就是同一个 trace。
