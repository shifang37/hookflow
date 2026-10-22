# 0003 — broker 停机时 outbox 行卡在 PUBLISHING，快速恢复路径失效

- **类型**：缺陷（由 v0.2 故障注入测试发现）
- **发现时间**：2026-10-22
- **影响面**：RabbitMQ 不可达期间，每个 relay 周期都会把一批 outbox 行标成 `PUBLISHING`，然后**无法回到 `NEW`**
- **严重级别**：P1（不丢数据，但恢复依赖 60 秒的兜底 sweeper，且期间指标读数具有误导性）
- **涉及设计点**：relay 错误处理、publisher 异常边界、可观测指标
- **状态**：已修复

---

## 1. 现象

`BrokerOutageIT` 在「停掉 broker」阶段断言 outbox 里有未发布工作，结果：

```
java.lang.AssertionError: [work is parked in the outbox, not lost]
Expecting actual: 0L to be greater than or equal to: 1L
```

而 relay 的日志明确显示了失败：

```
WARN  OutboxRelay - Publish failed for outbox row 2: Publish failed for 698ecfdc...:
      java.net.ConnectException: Connection timed out: getsockopt
```

**日志说失败，数据库说没有待发布工作。** 两者矛盾，说明行被留在了某个中间状态。

## 2. 触发方式

让 broker 在 relay 运行期间不可达（停容器 / 网络分区 / broker 僵死）：

```java
dockerClient.pauseContainerCmd(rabbitmq.getContainerId()).exec();
```

## 3. 根因

`DeliveryPublisher.publish` 把**发送**这一步放在了 try 之外：

```java
public void publish(DeliveryMessage message) {
    CorrelationData correlation = new CorrelationData(...);
    rabbitTemplate.convertAndSend("", QUEUE, body, correlation);   // ← 不在 try 里
    try {
        Confirm confirm = correlation.getFuture().get(10, SECONDS);
        ...
    } catch (Exception e) {
        throw new PublishFailedException(...);
    }
}
```

broker 不可达时，`convertAndSend` 自己就抛 `AmqpConnectException`。它绕过 catch，以**原始 `AmqpException`** 冒泡到 `OutboxRelay.publishBatch`：

```java
try {
    publisher.publish(message);
    outboxMapper.markPublished(row.id());
} catch (DeliveryPublisher.PublishFailedException e) {   // ← 只接这一种
    outboxMapper.releaseBack(row.id());
    return false;
}
```

`AmqpException` 不匹配，于是：

1. `releaseBack` 不执行 → 行停在 `PUBLISHING`
2. 异常继续冒泡出 `relayOnce()`，被 Spring 的调度器吞掉（只打一条日志）
3. 唯一的恢复路径变成 `OutboxSweeper`——它要等 `stale-publishing-seconds`（默认 60 秒）才把行重置为 `NEW`

**后果**：每次 relay tick 都新锁一批行，60 秒内积压的 PUBLISHING 行越堆越多；broker 恢复后，这些行要再等最多 60 秒才被重新发布。数据没丢，但"恢复延迟"从"下一个 tick"退化成了"最多一分钟"，而且期间 `outbox` 里 `NEW=0` 会让监控误以为一切正常。

## 4. 为什么现有测试没发现

v0.1 的所有测试都假设 broker 可达。`releaseBack` 这条分支从未被执行过——**它是死代码，而测试覆盖率不会告诉你"某条错误分支永远走不到"。** 只有真的让 broker 不可达才能暴露。

## 5. 修复

把发送也纳入 try，并把所有异常统一成一种类型：

```java
try {
    rabbitTemplate.convertAndSend("", QUEUE, body, correlation);
    Confirm confirm = correlation.getFuture().get(timeout, MILLISECONDS);
    if (!confirm.isAck()) {
        throw new PublishFailedException("nacked: " + confirm.getReason());
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new PublishFailedException("interrupted", e);
} catch (PublishFailedException e) {
    throw e;
} catch (Exception e) {
    throw new PublishFailedException("Publish failed for " + correlation.getId() + ": " + e.getMessage(), e);
}
```

现在 broker 一挂，relay 当个 tick 就把行放回 `NEW`，恢复延迟从「最多 60 秒」变成「下一个 tick」；sweeper 退回它本该扮演的角色——**只处理"确认后崩溃"那个真正无法感知的窗口**，而不是日常的 broker 故障。

## 6. 顺带修掉的观测缺陷

测试里的第二个发现：断言 `status='NEW'` 的行数 ≥ 1 会**间歇性失败**，因为 broker 挂掉时行在 `NEW ↔ PUBLISHING` 之间高频振荡，`NEW` 的窗口接近零。

于是新增 `OutboxMapper.countUnpublished()`：

```sql
SELECT COUNT(*) FROM outbox WHERE status IN ('NEW', 'PUBLISHING')
```

这才是运维应该告警的数字。单看 `NEW` 会在积压最严重时读到 0——一个会**在最坏时刻谎报健康**的指标，比没有指标更危险。

## 7. 回归测试

`BrokerOutageIT.brokerOutageDoesNotLoseEvents`，四个阶段：

| 阶段 | 断言 |
|---|---|
| baseline | 投递成功，`countUnpublished() == 0` |
| broker 冻结 | 事件仍被接受，delivery 存在且为 `PENDING` |
| 冻结持续 5s | **没有产生任何 attempt**；`countUnpublished() >= 1` |
| broker 恢复 | 同一 delivery 在 120s 内 `SUCCEEDED`，`attemptCount == 1` |

## 8. 对不变式的影响

| 不变式 | 修复前 | 修复后 |
|---|---|---|
| broker 不可达时不丢事件 | 成立 | 成立 |
| broker 恢复后自动追平 | 成立，但延迟上限 60s | 成立，延迟 = 一个 relay tick |
| outbox 指标能反映积压 | **不成立**（NEW=0 假健康） | 成立（`countUnpublished`） |
| 确认窗口的重复由 sweeper 兜底 | 成立 | 成立（sweeper 职责收窄，更清晰） |

## 9. 遗留

`OutboxSweeper` 的 `stale-publishing-seconds` 现在同时承担两个语义：「确认后崩溃」的宽限期，以及「publishing 卡住」的兜底。两者数值需求不同（前者应远大于一次 publish 的耗时，后者希望尽快），v0.3 若出现真实误判可拆成两个配置。
