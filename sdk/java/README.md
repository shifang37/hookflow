# HookFlow Java SDK

HookFlow 的官方 Java 客户端：发送单条 / 批量事件，并在**接收方**验证 webhook 签名。

- 要求 **Java 17+**（与服务端的 Java 21 无关；客户端不必跟着升级）
- 依赖：OkHttp 4.12、Jackson 2.17（**不是**服务端用的 Jackson 3）
- 产物：`dev.hookflow:hookflow-java-sdk:0.1.0`
- 当前状态：`mvn install` 已验证；GitHub Packages 的发布 workflow 已写好，但**尚未真正发布**（本版本暂无远程仓库可用，见 §8）

---

## 1. 5 分钟接入

### 1.1 添加依赖

本地仓库（`mvn install` 之后）：

```xml
<dependency>
    <groupId>dev.hookflow</groupId>
    <artifactId>hookflow-java-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 1.2 发一条事件

```java
import dev.hookflow.sdk.EventResult;
import dev.hookflow.sdk.HookFlowClient;
import java.util.Map;

try (HookFlowClient client = HookFlowClient.builder()
        .apiKey("hk_...")                            // 租户 API Key
        .baseUrl("https://hookflow.example.com")     // 本地调试用 http://localhost:8080
        .build()) {

    EventResult result = client.sendEvent("order.paid", Map.of("orderId", 12345));

    System.out.println(result.getEventId());          // 服务端分配的事件 id
    System.out.println(result.getDeliveryCount());    // 扇出了几条投递
    System.out.println(result.isIdempotentReplay());  // false = 新建
}
```

`sendEvent` 返回 `202`（新事件）或 `200`（幂等键已存在），两者都是 `EventResult`；
`isIdempotentReplay()` 区分这两种情况。

### 1.3 发一批事件

```java
import dev.hookflow.sdk.BatchResult;
import dev.hookflow.sdk.EventRequest;
import java.util.List;
import java.util.Map;

BatchResult batch = client.sendEvents(List.of(
        new EventRequest("order.paid", Map.of("orderId", 1), "order-1-paid"),
        new EventRequest("order.shipped", Map.of("orderId", 2))));   // 不传 key 时自动生成 UUID

batch.getResults().forEach(item -> System.out.printf(
        "index=%d eventId=%d deliveries=%d replay=%s%n",
        item.getIndex(), item.getEventId(), item.getDeliveryCount(), item.isReplay()));
```

- 一批 1–500 条，**每条自己的 `idempotencyKey`**
- 结果按 `index` 排序后返回，与请求列表一一对应（不依赖服务端数组顺序）
- 501 条在本地直接抛 `IllegalArgumentException`，**不会发请求**（服务端也是整批 400，不截断）

---

## 2. 幂等与重试

### 2.1 幂等键

| 场景 | 行为 |
|---|---|
| `sendEvent(type, payload)` / `new EventRequest(type, payload)` | 构造时生成 UUID（36 字符）并保存在请求对象里 |
| `sendEvent(type, payload, key)` / `new EventRequest(type, payload, key)` | 使用你给的 key（1–255 字符） |
| 自动重试 | **复用同一个 key 和同一份字节**，不会产生重复事件 |
| 同一个 `EventRequest` 对象发两次 | 第二次是重放：服务端返回原 `eventId`，`idempotentReplay=true` |

想让"进程重启后重发也不会重复"，就传一个由业务身份派生的 key，例如
`order-12345-paid`；UUID 只在单个进程内保证不重复。

> 重试时 key 必须不变。SDK 已经保证这一点；自己实现 HTTP 重试时尤其要注意，
> 否则服务端会把重试当成新事件，用户收到两次扣款。

### 2.2 哪些失败会重试

| 情况 | 重试 |
|---|---|
| 网络失败 / 连接超时 / 读超时 | ✅ |
| `429 RATE_LIMITED`、`429 TENANT_BACKLOG_EXCEEDED` | ✅ |
| `503 OVER_CAPACITY` | ✅ |
| 其他 `5xx` | ✅ |
| `400` / `401` / `403` / `409` 及其他 `4xx` | ❌ 立即抛 `HookFlowException` |

延迟规则：

1. 响应带 `Retry-After`（整秒）时以它为准，封顶 `maxDelay`；
2. 否则指数退避：`baseDelay × 2^(n-1)`，封顶 `maxDelay`，再加 ±`jitter` 的抖动。

默认策略：3 次尝试（含第一次），`baseDelay=200ms`，`maxDelay=10s`，`jitter=0.2`。

> 一个实现细节：OkHttp 4.12 自身会对 `503 + Retry-After: 0` 立刻重发一次。SDK 发的
> body 和幂等键与首次完全相同，所以不会产生重复事件，但服务端看到的请求数可能比
> `maxAttempts` 多 1。正常服务端返回的是正整数秒，不会走到这个分支。

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .baseDelay(Duration.ofMillis(300))
        .maxDelay(Duration.ofSeconds(30))
        .jitter(0.3)
        .honorRetryAfter(true)
        .build();

HookFlowClient client = HookFlowClient.builder()
        .apiKey("hk_...")
        .baseUrl("https://hookflow.example.com")
        .retryPolicy(policy)
        .build();
```

### 2.3 最坏耗时

`sendEvent` 不是"瞬时"的。默认配置下最坏情况约为：

```
3 × callTimeout(20s) + 2 × maxDelay(10s) ≈ 80s
```

按指数退避而没有 `Retry-After` 时更接近 `3 × 20s + 0.6s ≈ 60.6s`。
不要在一个有更短超时的请求线程里同步调用它；后台任务、队列消费者更合适。
需要更紧的上界时，把 `callTimeout` / `maxAttempts` 调小。

---

## 3. 接收方验签

HookFlow 投递 webhook 时带这些头：

| Header | 内容 |
|---|---|
| `X-HookFlow-Signature` | `v1=<hex>` |
| `X-HookFlow-Timestamp` | **epoch 秒**（不是毫秒） |
| `X-HookFlow-Event-Id` | 事件 id |
| `X-HookFlow-Delivery-Id` | 投递 id |
| `X-HookFlow-Event-Type` | 事件类型 |
| `X-HookFlow-Attempt` | 第几次尝试（从 1 开始） |

签名串（**冒号分隔**，版本前缀 `v1:` 也在串里）：

```
v1:eventId:deliveryId:timestampSeconds:rawBody
```

```
HMAC-SHA256(secret, 签名串) → 小写 hex → header 值 = "v1=" + hex
```

### 3.1 用 SDK 验证

```java
import dev.hookflow.sdk.HookFlowWebhook;
import dev.hookflow.sdk.Verification;

@PostMapping(path = "/webhooks/hookflow", consumes = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                    @RequestHeader Map<String, String> headers) {
    Verification verification = HookFlowWebhook.verify(secret, headers, rawBody);
    if (!verification.valid()) {
        // reason: MISSING_HEADER / MALFORMED_TIMESTAMP / STALE_TIMESTAMP / BAD_SIGNATURE
        log.warn("webhook rejected: {}", verification.reason());
        return ResponseEntity.status(401).build();
    }
    // 这里再做 JSON 反序列化；验签必须用上面的原始字节
    return ResponseEntity.ok().build();
}
```

`secret` 可以是 `String` 或 `byte[]`，`headers` 可以是 `Map<String,String>` 或任何
`(name) -> String` 的访问器（例如 `headers::get`）。header 名大小写不敏感，值会 trim。

### 3.2 三个必须注意的点

1. **body 必须是原始字节。** 先把 JSON 反序列化、再 `toString()` 验签一定失败：
   字段顺序、空格、数字格式都会变。Spring 用 `@RequestBody byte[]`，Servlet 用
   `getInputStream()`，Node 用原始 buffer。
2. **时间戳是秒。** 写 `1760000000000` 会被判定为 `MALFORMED_TIMESTAMP`，接收方永远验不过。
3. **时间窗和 HMAC 两个检查都要做。** 只验签名 = 重放漏洞；只验时间 = 伪造请求。
   默认窗口 ±300 秒，可传 `Duration` 覆盖：

```java
Verification v = HookFlowWebhook.verify(
        secret, headers::get, rawBody,
        Duration.ofSeconds(60),      // 自定义容忍窗口
        Clock.systemUTC());          // 测试时可注入固定时钟
```

常量时间比较由 `MessageDigest.isEqual` 完成，不要自己用 `String.equals` 重写。

### 3.3 参考向量

下面这组输入在服务端 `HmacSignerTest` 与 SDK `HookFlowWebhookTest` 里**同时断言**，
任何一边改了签名串格式，两边至少有一个测试会红：

```
secret     = "s3cr3t"（UTF-8）
eventId    = 12
deliveryId = 34
timestamp  = 1760000000
rawBody    = {"amount":42}
→ X-HookFlow-Signature: v1=9c1e11fe90c12fb041fa3f1a13252152b8b666e3b736e7aab65c65d324a8d0d4
```

---

## 4. 错误处理

所有失败都是 `HookFlowException`（`RuntimeException`）：

```java
try {
    client.sendEvent("order.paid", payload);
} catch (HookFlowException e) {
    log.error("status={} code={} error={} requestId={}",
            e.getStatusCode(), e.getCode(), e.getErrorName(), e.getRequestId(), e);
    if (e.getStatusCode() == 409) {
        // 同一个幂等键被用于不同 payload —— 这是调用方 bug，重试无用
    }
}
```

- 服务端有响应：`statusCode` = HTTP 状态，`code` / `errorName` / `requestId` 来自错误体
- 网络失败：`statusCode=0`，`cause` 是 `IOException`
- `requestId` 一定保留（错误体没有时回退到 `X-Request-Id` 响应头）——排障只认它
- 客户端预校验失败（eventType 非法、payload 为空/超过 64 KiB、批量条数越界等）
  抛 `IllegalArgumentException`，**不发请求**

| 状态 | code | 含义 | 是否重试 |
|---|---|---|---|
| 400 | 40000 | eventType / payload 非法、批量越界 | ❌ |
| 401 | 40100 | API Key 缺失 / 无效 / 被撤销 | ❌ |
| 403 | 40300 | Key 缺 `event:write` scope | ❌ |
| 409 | 40901 | 幂等键复用但 payload 不同 | ❌ |
| 429 | 42900 | 租户限流（带 `Retry-After`） | ✅ |
| 429 | 42902 | 租户积压超水位（带 `Retry-After`） | ✅ |
| 503 | 50301 | 平台过载（带 `Retry-After`） | ✅ |
| 5xx | — | 服务端故障 | ✅ |
| — | — | 网络失败 / 超时 | ✅ |

---

## 5. 配置

| Builder 方法 | 默认 | 说明 |
|---|---|---|
| `apiKey(String)` | 必填 | 以 `Bearer` 发送；HookFlow 的 key 以 `hk_` 开头 |
| `baseUrl(String)` | `http://localhost:8080` | 必须是绝对 http(s) URL；尾部 `/` 会被去掉 |
| `retryPolicy(RetryPolicy)` | `RetryPolicy.defaults()` | 见 §2 |
| `connectTimeout(Duration)` | 5s | TCP 建连 |
| `readTimeout(Duration)` | 15s | 单次读 |
| `callTimeout(Duration)` | 20s | **单次尝试**的总时长，不包含重试等待 |
| `sleeper(Sleeper)` | 阻塞当前线程 | 测试可注入记录型实现，见 §7 |

`client.close()` 关闭 dispatcher 与连接池；client 不可变、线程安全，**整个应用建一个**即可，
不要每条事件建一个。

### 日志

SDK **不引入任何日志门面绑定**（不依赖 slf4j / logback），也不主动打日志——日志由你的应用决定。
需要可观测性时，在调用点记录 `HookFlowException` 的 `statusCode` / `code` / `requestId`。

---

## 6. 可运行示例

源码在 `src/main/java/dev/hookflow/sdk/examples/`：

- `SendEventsExample`：单条 + 显式幂等键 + 批量，打印结果；
- `WebhookReceiverExample`：只依赖 JDK `HttpServer` 的接收方，演示原始字节验签。

```powershell
# 在仓库根目录
.\mvnw.cmd -f sdk/java/pom.xml -q compile

# 发布端示例（需要服务端在跑）
$env:HOOKFLOW_API_KEY = 'hk_...'
.\mvnw.cmd -f sdk/java/pom.xml -q exec:java `
  "-Dexec.mainClass=dev.hookflow.sdk.examples.SendEventsExample"
```

接收方：

```powershell
$env:HOOKFLOW_WEBHOOK_SECRET = 'the secret shown when the endpoint was created'
.\mvnw.cmd -f sdk/java/pom.xml -q exec:java `
  "-Dexec.mainClass=dev.hookflow.sdk.examples.WebhookReceiverExample"
```

（`exec:java` 需要 `exec-maven-plugin`，第一次运行会联网下载；不装插件也可以把
`target/classes` 和 OkHttp/Jackson 的 jar 放进 classpath 直接 `java`。）

---

## 7. 测试与构建

```powershell
# 在仓库根目录。第一次需要联网下载依赖，之后可加 -o
.\mvnw.cmd -f sdk/java/pom.xml -B verify

# JavaDoc 必须无 error
.\mvnw.cmd -f sdk/java/pom.xml javadoc:javadoc

# 安装到本地仓库（source/javadoc jar 一并产出）
.\mvnw.cmd -f sdk/java/pom.xml source:jar javadoc:jar install
```

- 测试用 OkHttp **MockWebServer**，真的发 HTTP（断言方法、路径、头、body、重试次数），
  不启动 Spring；
- 重试测试注入 `RecordingSleeper`，**不真的 sleep**；验签测试注入固定 `Clock`；
- JaCoCo 门槛：**LINE ≥ 80%**（`haltOnFailure`），报告在
  `sdk/java/target/site/jacoco/index.html`；
- `mvn verify` 同时是 CI job（`.github/workflows/sdk.yml`）。

---

## 8. 发布状态与 GitHub Packages

**没有发布到 Maven Central，也还没有实际 deploy 到 GitHub Packages。** 原因：前者需要
Sonatype 账号，后者需要仓库 token；两者都不是 SDK 代码能单方面完成的。本次只验证了
`mvn install` 与"独立项目依赖本地 jar 跑通 sendEvent"。

发布 workflow（tag 触发）在 `.github/workflows/sdk-publish.yml`：

```powershell
git tag sdk-v0.1.0
git push origin sdk-v0.1.0
```

它会用 `GITHUB_TOKEN`（`packages: write`）执行 `mvn deploy` 到
`https://maven.pkg.github.com/shifang37/hookflow`。发布成功后，使用方这样引用：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/shifang37/hookflow</url>
    </repository>
</repositories>
```

`~/.m2/settings.xml` 需要 GitHub 用户名与一个有 `read:packages` 权限的 token：

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>${env.GITHUB_TOKEN}</password>
    </server>
</servers>
```

在发布之前，替代方案就是 §7 的 `mvn install`，然后按 §1.1 在本地引用。

未来的更简单路径：把 `sdk/java` 拆成独立仓库（`shifang37/hookflow-java-sdk`），
JitPack 一条 tag 即可发布——那是仓库侧的操作，不影响本目录的代码。
