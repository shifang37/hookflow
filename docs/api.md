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
| 42900 | `RATE_LIMITED` | 429 | 超出租户速率 |
| 50000 | `INTERNAL` | 500 | 未预期错误（细节只在日志里，用 requestId 查） |
| 50300 | `DEPENDENCY_UNAVAILABLE` | 503 | 依赖不可用（限流 fail-closed 时的 Redis） |

---

## 运营接口

### POST /v1/admin/tenants

创建租户并签发首个 API Key。

```bash
curl -X POST localhost:8080/v1/admin/tenants \
  -H 'X-Bootstrap-Token: hookflow-dev-bootstrap-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","rateLimit":100}'
```

`201` → `{"tenantId":1,"name":"acme","apiKey":"hk_xxxxxxxx_yyyy…","keyPrefix":"xxxxxxxx"}`

`rateLimit` 为每秒事件数，`0` 表示不限速。

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
        "maxAttempts": 8
      }'
```

`201` → 含 `signingSecret`（**仅此一次**）

```json
{
  "id": 1,
  "url": "https://customer.example/hooks/hookflow",
  "subscribedTypes": "order.paid,order.refunded",
  "maxAttempts": 8,
  "status": "ACTIVE",
  "signingSecret": "base64url-32-bytes"
}
```

`subscribedTypes` 支持逗号分隔列表或 `*`（全部）。匹配是**精确匹配**，不是前缀匹配。

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

### GET /v1/events/{id}

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
