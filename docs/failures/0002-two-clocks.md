# 0002 — 重试永远不触发：应用时钟与数据库时钟混用

- **类型**：缺陷（由集成测试发现）
- **发现时间**：2026-10-22 14:05
- **影响面**：**所有重试都不生效**。5xx/超时的投递会永远停在 `RETRY_WAIT`，`next_attempt_at` 比数据库的当前时间晚 8 小时
- **严重级别**：P1（不丢数据，但投递重试完全失效，实际等价于"一次失败就永不重试"）
- **涉及设计点**：时间来源、退避调度、lease 过期判定
- **状态**：已修复

---

## 1. 现象

`EndToEndDeliveryIT.serverErrorsAreRetriedUntilExhausted` 失败：

```
java.lang.AssertionError: Delivery for event 1 did not reach DEAD within PT1M;
  state=RETRY_WAIT attemptCount=1 maxAttempts=8
  nextAttemptAt=2026-10-22T13:46:29.152
  lastErrorClass=SERVER_5XX attemptRows=1
```

两个关键线索：

1. `attemptCount=1` —— 只发生过一次尝试，第 2 次从未开始
2. `attemptRows=1` —— attempt 记录也只有一条

也就是说：**退避时间算出来了，也写进库了，但调度器永远认为"还没到期"。**

## 2. 触发方式

只需在 **JVM 时区与 MySQL 时区不一致** 的环境里跑一次失败投递：

```
JVM  : Asia/Shanghai (UTC+8)   ← Windows 开发机默认
MySQL: UTC                     ← mysql:8.4 容器默认 TZ=UTC
```

## 3. 根因

代码把两个不同的时钟放在一起比较：

```java
// 应用侧算出一个绝对时间，用的是 JVM 时区
LocalDateTime nextAttemptAt = LocalDateTime.now().plusNanos(delayNanos);
deliveryMapper.markRetryWait(..., nextAttemptAt);   // 写入 next_attempt_at

// 数据库侧判断是否到期，用的是数据库时区
SELECT * FROM delivery WHERE status='RETRY_WAIT' AND next_attempt_at <= NOW(3);
```

`LocalDateTime` 不带时区，JDBC 驱动把它**原样**写进 `DATETIME(3)`。于是：

| | 值 |
|---|---|
| 写入的 `next_attempt_at` | `13:46:29`（UTC+8 的本地时间） |
| 数据库看到的 `NOW(3)` | `05:46:29`（UTC） |

`next_attempt_at` 比 `NOW(3)` 大 **8 小时**，所以在 8 小时内这条记录永远不会被扫到。

### 同一 bug 的其他受害者

| 位置 | 症状 |
|---|---|
| `next_attempt_at <= NOW(3)` | 重试永不触发（本次暴露） |
| `lease_until < NOW(3)` | lease 要么 8 小时后才过期（无法回收崩溃 worker），要么立刻过期（重复投递） |
| `publishing_since < cutoff` | outbox 卡死回收失效 |
| `updated_at < cutoff` | 卡住的 PENDING 永不被重扫 |

一个时区配置错误，能同时打掉四条恢复路径——**而它们全都是"静默失效"**：不报错、不告警，只是不再工作。

## 4. 为什么现有测试没发现

集成测试里第一次跑时就暴露了（`RETRY_WAIT` 卡住），因为它用真实 MySQL 容器（UTC）而 JVM 用开发机时区（UTC+8）。

反过来说：**如果这里用 H2 跑在同一个 JVM 进程内，两边时区一致，这个 bug 永远测不出来。** 这是坚持"用真实容器、真实外部依赖"的直接收益。

## 5. 修复

**规则：所有时间比较都在数据库内完成，Java 只传"时长"，不传"时刻"。**

```sql
-- before
SET next_attempt_at = #{nextAttemptAt}                     -- Java 算的绝对时间
WHERE next_attempt_at <= NOW(3)

-- after
SET next_attempt_at = DATE_ADD(NOW(3), INTERVAL #{delayMicros} MICROSECOND)
WHERE next_attempt_at <= NOW(3)
```

同理：

```sql
lease_until       = DATE_ADD(NOW(3), INTERVAL #{leaseSeconds} SECOND)
published_at   <  DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
updated_at     <  DATE_SUB(NOW(3), INTERVAL #{graceSeconds} SECOND)
publishing_since < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
```

调用方从"传 `LocalDateTime`"改为"传 `int` 秒 / `long` 微秒"。**签名本身就让人无法犯这个错误**——想传时刻都没有参数可传。

### 为什么不用"统一时区"解决

也可以在启动时强制 `-Duser.timezone=UTC` 并让所有环境一致。但：

- 它依赖**部署纪律**，一个没写 `TZ` 的容器就复发
- 它治不好"同一秒里两个时钟精度不同"的问题（`DATETIME(3)` vs 应用纳秒）
- 让数据库拥有自己的时钟是更强的约束：**跨机器部署时不需要协调时钟**

项目仍然在测试与镜像里固定 UTC（`argLine=-Duser.timezone=UTC`、`TZ=UTC`），但那是为了**日志与断言可复现**，不是正确性的依赖。

## 6. 回归测试

`EndToEndDeliveryIT.serverErrorsAreRetriedUntilExhausted`：

- 接收方恒返 500
- 断言 60 秒内走完 8 次尝试并进入 `DEAD`
- 断言 `attemptRows == maxAttempts`（每次尝试都有记录，不只是最后一次）

`EventIngestIT.expiredLeaseIsReclaimed` 也改用 `claim(..., leaseSeconds = -5)` 来制造过期 lease —— 不再由 Java 计算过期时刻。

## 7. 对不变式的影响

| 不变式 | 修复前 | 修复后 |
|---|---|---|
| 投递失败会按退避重试 | **不成立** | 成立 |
| lease 过期会被回收 | 不成立（时区不一致时） | 成立 |
| outbox 卡死可回收 | 不成立（时区不一致时） | 成立 |
| 卡住的 PENDING 会被重扫 | 不成立（时区不一致时） | 成立 |

## 8. 遗留与教训

- 文档中新增硬性要求：**MySQL 必须以 UTC 运行**（`compose.yaml` 已设 `TZ: UTC`）。这不是为了让时间比较正确（那已由数据库时钟保证），而是为了让 `occurred_at` 这类对外暴露的展示值可解释。
- 教训可以概括成一句话：**"两个时钟相比，一定要有一方说出自己的时区；如果说不出，就不要比。"**
- 这类 bug 在代码评审中极难发现，因为每一行单独看都对。唯一可靠的防线是"在时区不一致的环境里跑真实依赖的集成测试"。
