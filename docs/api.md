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
| 40904 | `ALREADY_EXISTS` | 409 | 唯一约束冲突：租户名重复，或同一租户下重复的回调地址 |
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

重名返回 `409` `ALREADY_EXISTS`（唯一键 `uk_tenant_name`），不会签发半个租户或第二把 Key。

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

## 运营控制台接口

和上面的 `/v1/admin/**` 是**同一个信任域**（`X-Bootstrap-Token`）：那组负责开通（建租户、发 Key、撤 Key），这一组负责观测（谁在用、用得怎么样、平台整体如何），外加一个改租户策略的写接口。

### GET /v1/admin/tenants?search=&page=0&size=20

租户列表，按 id 倒序。每行带 `endpointCount` 与 `activeKeyCount`，用关联子查询一次带出来，避免控制台再发 N 个请求。

```json
{
  "items": [
    {"tenantId": 1, "name": "acme", "status": "ACTIVE", "rateLimit": 100,
     "maxInFlight": 32, "weight": 1, "endpointCount": 3, "activeKeyCount": 2,
     "createdAt": "2026-10-20T03:11:08.221"}
  ],
  "page": 0, "size": 20, "total": 1
}
```

`search` 是名称的包含匹配（`LIKE %term%`）。

### GET /v1/admin/tenants/{id}

单个租户，字段与列表项相同。这是运营域，不存在"跨租户访问"问题——它看得见所有租户。

### GET /v1/admin/tenants/{id}/keys

列出该租户的 API Key **元数据**（含已撤销的，按 id 倒序）。

```json
[
  {"id": 11, "keyPrefix": "aB3dE7fG", "scopes": "event:write,endpoint:write,delivery:read",
   "status": "ACTIVE", "createdAt": "2026-10-29T08:00:00.000", "revokedAt": null},
  {"id": 10, "keyPrefix": "zY9xW8vU", "scopes": "event:write",
   "status": "REVOKED", "createdAt": "2026-10-01T08:00:00.000",
   "revokedAt": "2026-10-29T07:59:00.000"}
]
```

这个接口**只返回元数据**：表里的 `key_hash` 根本没被 `SELECT` 出来（见 `ApiKeyQueryMapper`），所以将来也不可能不小心漏出哈希；明文更是从来不存在于任何读接口。

已撤销的 Key 保留在列表里：轮换是否发生过、什么时候发生的，只能靠它证明——「消失」和「从没签发过」在界面上是一回事。

租户不存在返回 `404`，不是空数组：「没有这个租户」和「这个租户没有 Key」必须能区分。

配对的写接口在 [运营接口](#post-v1admintenantstenantidkeys) 一节：

| 方法 | 路径 | 作用 |
|---|---|---|
| `POST` | `/v1/admin/tenants/{tenantId}/keys` | 签发新 Key，**明文只在这次响应里** |
| `POST` | `/v1/admin/keys/{apiKeyId}/revoke` | 撤销，`204`；之后用它的请求一律 `401` |

### GET /v1/admin/tenants/{id}/stats?range=7d

`range` 只接受 `24h` / `7d` / `30d` / `90d`（默认 `7d`），**其他值返回 `400`**，不静默回退。

```json
{
  "tenantId": 1, "range": "7d",
  "totalEvents": 1240, "deliveries": 2480,
  "succeeded": 2400, "dead": 30, "canceled": 50, "inFlight": 0,
  "successRate": 98.77, "avgLatencyMs": 812, "backlog": 0,
  "topFailureReasons": [
    {"errorClass": "SERVER_5XX", "statusCode": 500, "count": 20, "percentage": 66.67},
    {"errorClass": "TIMEOUT", "statusCode": null, "count": 10, "percentage": 33.33}
  ]
}
```

口径是接口契约的一部分，控制台和 Prometheus 面板必须用同一套定义，否则两处数字对不上：

| 字段 | 口径 |
|---|---|
| `totalEvents` | 窗口内**被接受**的事件数 |
| `deliveries` / `succeeded` / `dead` / `canceled` / `inFlight` | 窗口内**创建**的投递、按**当前**状态分组；这五项相加 = `deliveries` |
| `successRate` | `succeeded / (succeeded + dead)`，百分比。**只按已结算投递算**：在途投递既不算成功也不算失败，否则一个百万积压会显示 99.9% 成功率；没有已结算投递时为 `0` |
| `avgLatencyMs` | 窗口内 `SUCCEEDED` 投递的 `created_at → updated_at`，**含排队等待与全部重试**（用户实际感受到的延迟）。窗口内没有成功投递时为 `null`，不是 `0` |
| `backlog` | **当前**未投递数（`PENDING`/`DELIVERING`/`RETRY_WAIT`），**不受窗口影响**——运维关心的是现在堵着什么，不是上周堵过什么 |
| `topFailureReasons` | 窗口内 `DEAD` 投递按 `(errorClass, statusCode)` 分组取前 5。**不按响应体消息分组**：那是攻击者可控的文本，既无界又会把 payload 片段漏进聚合里。`percentage` 以窗口内 `dead` 为分母 |

窗口由 MySQL 的 `NOW(3) - INTERVAL ? HOUR` 计算，**不用 JVM 时钟**：JVM 与数据库时区不一致时，Java 算出的窗口会整体偏几小时，而症状只是"数字有点怪"。见 [failures/0002-two-clocks.md](failures/0002-two-clocks.md)。

### PATCH /v1/admin/tenants/{id}

```bash
curl -X PATCH localhost:8080/v1/admin/tenants/1 \
  -H 'X-Bootstrap-Token: ...' -H 'Content-Type: application/json' \
  -d '{"rateLimit":200,"weight":5}'
```

- 省略的字段保持原值；**三个字段都不给是 `400`**，不是静默 no-op
- 约束：`rateLimit >= 0`（0 = 不限速）、`maxInFlight >= 0`（0 = 不限并发）、`weight ∈ [1,100]`
- 修改后**立即刷新调度器快照**，下一次发布就按新权重生效，不必等 refresh interval
- `200` → 更新后的租户（含 counts），控制台可直接替换那一行

### GET /v1/admin/overview?range=24h

平台级聚合，字段与租户统计一致（但去掉 `tenantId` 与 `topFailureReasons`），额外带 `activeTenants`、`activeEndpoints`。默认窗口 `24h`。

**为什么不在 `/v1/dashboard/**`**：`/v1/**` 由租户 API Key 认证，平台级数字不能出现在租户域里，否则任何租户都能读到别人的量。运营视图统一放在 `/v1/admin/**`。

### GET /v1/admin/metrics/timeseries?range=24h&interval=auto&tenantId=

折线图用的分桶计数。

```bash
curl "localhost:8080/v1/admin/metrics/timeseries?range=24h&interval=auto&tenantId=1" \
  -H 'X-Bootstrap-Token: ...'
```

```json
{
  "range": "24h", "interval": "15m", "bucketSeconds": 900,
  "from": "2026-10-28T09:15", "to": "2026-10-29T09:15",
  "points": [
    {"bucketStart": "2026-10-28T09:15", "events": 0, "attempts": 0,
     "succeeded": 0, "dead": 0, "canceled": 0},
    {"bucketStart": "2026-10-28T09:30", "events": 12, "attempts": 14,
     "succeeded": 10, "dead": 0, "canceled": 0}
  ]
}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `range` | `24h` | `24h` / `7d` / `30d` / `90d` |
| `interval` | `auto` | `auto` 或 `5m` `15m` `30m` `1h` `3h` `6h` `12h` `1d` |
| `tenantId` | — | 省略即**平台级**；带上就是单个租户（租户详情页的图） |

`auto` 的规则是「不超过 120 个点的最细粒度」，解析出的具体值会原样回填到 `interval`，前端标 x 轴不需要再实现一遍这个规则：

| range | 24h | 7d | 30d | 90d |
|---|---|---|---|---|
| auto | `15m`（96 点） | `3h`（56 点） | `6h`（120 点） | `1d`（90 点） |

几个必须知道的口径：

- **点数是确定的**：`points.length == ceil(range / interval)`，不含隐藏的尾部空桶。窗口以**当前正在填充的那个桶**收尾，所以最后一个点天然是部分数据（活图就该这样）。
- **`events`/`attempts` 按到达时间计，`succeeded`/`dead`/`canceled` 按结算时间计**。一次突发会先看到 `events` 的尖峰，再看到结算计数延迟爬升——这正是要看的形状。如果两者都按创建时间算，积压就会被画成「已经处理完了」。
- **零填充**：没有数据的桶也在结果里，计数器为 0。否则折线会把 10:00 直接连到 14:00，把四小时的空洞画成一条平滑的直线。
- `canceled` 单独列出而不是并进 `dead`：暂停 endpoint 造成的取消和对方持续 500 是两件事，混在一起会让人去查下游故障。
- `400`：未知 `range` / 未知 `interval` / 桶数超过 1000（例如 `90d` + `5m` = 25 920 个桶）。

最细粒度是 **`5m` 而不是 `1m`**：最短的 range 是 24h，一分钟粒度就是 1440 个桶，超过上限；而且分桶网格用递归 CTE 物化，MySQL 的 `cte_max_recursion_depth` 默认就是 1000。与其提供一个任何合法请求都用不了的选项，不如不提供。

分桶对齐由 MySQL 完成（`FLOOR(UNIX_TIMESTAMP(now) / bucket)`），Java 不参与：`DATETIME` 是墙钟时间，JVM 不知道数据库会话时区，自己算边界会让整张图偏移几小时。前提是数据库会话时区固定（本项目按 UTC 部署）。

### GET /v1/admin/metrics/latency?range=24h&tenantId=

单次 HTTP 尝试的耗时分布。

```json
{
  "range": "24h", "samples": 1234,
  "p50Ms": 12, "p95Ms": 480, "p99Ms": 1200,
  "buckets": [
    {"upperBoundMs": 50, "count": 900, "percentage": 72.93},
    {"upperBoundMs": 100, "count": 200, "percentage": 16.21},
    {"upperBoundMs": null, "count": 4, "percentage": 0.32}
  ]
}
```

- 边缘固定为 `50/100/250/500/1000/2500/5000/10000` ms，**最后一个桶的 `upperBoundMs` 是 `null`**，表示「≥ 10s 或超时」。没有这个开放桶，最慢的那批尝试会被悄悄丢掉。
- 百分位是 **nearest-rank**：返回的是真实观测到的耗时，不做插值。跟 SLA 对话时这个数更容易解释。
- 无样本时 `p50/p95/p99` 是 `null` 而**不是 0**，`samples` 就是分母——前端据此区分「很快」和「没测到」。
- 数据源是 `delivery_attempt.duration_ms`：**单次尝试的 HTTP 耗时**，不含排队和重试退避。端到端的（含排队和全部重试）看 `stats` 里的 `avgLatencyMs`。
- 目前这是唯一能画出延迟分布的数据源：平台没有注册 Micrometer Timer，`hookflow_delivery_duration_seconds_bucket` 还不存在（见 [optimization-roadmap.md](optimization-roadmap.md) 里 Grafana 一节的说明）。等真加了 Timer，这个接口可以平滑切过去，响应形状不变。

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

同一租户下重复的 `url` 返回 `409` `ALREADY_EXISTS`（唯一键是 `(tenant_id, url)` —— 不同租户可以用同一个地址）。

`maxConcurrency`（默认 `0` = 不限）：允许打到这个 URL 的**并行调用数上限**。接收方扛不住并发时填它，替代方案是让它把整个租户拖慢——见 [capability 文档](capacity.md) §4 的"接收方慢"一行。

### GET /v1/endpoints?page=0&size=20

`PageResponse<EndpointResponse>`（不含密钥，也不含已删除的 endpoint；`total` 用的是同一个口径）。

### GET /v1/endpoints/{id}

### POST /v1/endpoints/{id}/pause

暂停。**同时会取消该 endpoint 下所有 `PENDING` / `RETRY_WAIT` 的投递**（状态变 `CANCELED`）。

### POST /v1/endpoints/{id}/resume

恢复。**已取消的投递不会复活**——需要重放请针对具体 delivery 操作。

### DELETE /v1/endpoints/{id}

`204`，没有 body。软删除，但对外部世界是删除：

- 该 endpoint 下所有 `PENDING` / `RETRY_WAIT` 的投递立刻变 `CANCELED`；已经在投递中的那一条会跑完并记录结果。删除之后才被 worker 取到的消息同样会取消，错误类记 `ENDPOINT_DELETED`（暂停时是 `ENDPOINT_PAUSED`）。
- 之后的 `GET /v1/endpoints/{id}`、`pause`、`resume` 一律 `404`；列表、`endpointCount`、fan-out 都不再包含它。再删一次也是 `404`——它已经不存在了，`204` 会暗示还能复原。
- **投递历史保留**：`delivery.endpoint_id` 是外键，删掉行会让「这个事件当时发给了谁」变成 `MISSING_PARENT`。所以行还在，只是 `status = 'DELETED'`。
- **URL 立刻可以重新注册**：唯一键 `uk_endpoint_tenant_url` 只约束未删除的行（MySQL 没有部分索引，用 V5 的生成列 `url_key` 实现）。删掉再建同一个地址是正常操作——重建一把密钥、改对尝试上限——不需要先换个 URL。

`status` 因此有了第三个取值 `DELETED`，但它不会出现在任何响应里：拿不到行的接口只会给你 `404`。

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

### GET /v1/events?eventType=&from=&to=&page=0&size=20

控制台的事件流：**按 id 倒序**，每行带该事件的投递汇总，一个请求就能画出整张表。

```bash
curl "localhost:8080/v1/events?eventType=order.paid&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "items": [
    {"eventId": 12, "eventType": "order.paid", "deliveryCount": 2,
     "succeededCount": 1, "failedCount": 0, "inFlightCount": 1,
     "createdAt": "2026-10-29T08:31:02.114"}
  ],
  "page": 0, "size": 20, "total": 1
}
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `eventType` | — | **精确匹配**，不做前缀匹配 |
| `from` / `to` | — | ISO-8601 本地时间（UTC）。`from` 含、`to` **不含**；`from >= to` 返回 `400` |
| `page` / `size` | 0 / 20 | `size` 上限 100，超出按 100 处理 |

计数口径：`deliveryCount = succeededCount + failedCount + inFlightCount`，其中 `failed` 含 `DEAD` 与 `CANCELED`，`inFlight` 含 `PENDING`/`DELIVERING`/`RETRY_WAIT`。三个计数由同一条 `LEFT JOIN ... GROUP BY` 算出，不是每行再查一次。

**为什么不是 WebSocket**：事件流要的是"最近 N 条 + 翻页"，轮询一条 SQL 比给每个浏览器连接维护订阅、背压和重连语义便宜得多。真需要推送时再加，接口形状不变。

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

### GET /v1/deliveries?eventId=&endpointId=&status=&from=&to=&page=0&size=20

投递列表，按 id 倒序。所有筛选条件可组合：

| 参数 | 默认 | 说明 |
|---|---|---|
| `eventId` | — | 只看某个事件的投递 |
| `endpointId` | — | 只看打到某个 Endpoint 的投递 |
| `status` | — | 可选值：`PENDING` `DELIVERING` `RETRY_WAIT` `SUCCEEDED` `DEAD` `CANCELED`。**其他值返回 `400`**，不再静默返回空页 |
| `from` / `to` | — | ISO-8601 本地时间（UTC），`from` 含、`to` 不含；`from >= to` 返回 `400` |
| `page` / `size` | 0 / 20 | `size` 上限 100 |

`status` 之所以改成严格校验：以前把它直接拼进 `WHERE`，写错一个字母得到的是空列表，看起来像"这个租户没有失败"而不是"参数写错了"。

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
| `GET /ui/` | 运营控制台（前端构建产物；`/ui/events` 等客户端路由由后端兜到 `index.html`） |
| `GET /demo/` | 单机演示控制台（仅 `all` profile） |

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
