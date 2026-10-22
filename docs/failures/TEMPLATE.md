# NNNN — <一句话标题>

- **类型**：缺陷 / 演练
- **发现时间**：YYYY-MM-DD HH:mm
- **影响面**：<受影响的租户数 / 事件数 / 持续时长>
- **严重级别**：P0（数据丢失）/ P1（投递受损）/ P2（延迟上升）/ P3（噪声）
- **涉及设计点**：<例如 outbox 领取 / lease 回收 / 4xx 分类>
- **状态**：已修复 / 已确认 / 观察中

---

## 1. 现象

用**可观测到的事实**描述，不要写猜测。

```
<贴日志片段、指标曲线、SQL 查询结果>
```

例如：

```
14:22:10 WARN  OutboxSweeper - Re-queued 37 outbox rows stuck in PUBLISHING before 14:21:10
14:22:11 INFO  DeliveryProcessor - Attempt 1 of delivery 991 was already claimed; skipping
```

## 2. 触发方式（演练必填）

精确到可复现：

```bash
# 1. 让 relay 在 confirm 后、标记前被杀死
docker kill --signal=SIGKILL hookflow-relay
# 或
taskkill /F /PID <pid>
```

## 3. 时间线

| 时间 | 事件 |
|---|---|
| 14:21:03 | 提交事件，outbox 出现新行 |
| 14:21:03 | relay 领取并 publish |
| 14:21:03 | **进程被 kill**（此刻 broker 已确认） |
| 14:21:04 | outbox 行停留在 `PUBLISHING` |
| 14:22:10 | sweeper 发现超过 60s，重置为 `NEW` |

## 4. 根因

**必须区分"表面原因"和"根本原因"。**

- 表面：relay 进程死了
- 根本：`PUBLISHING` 状态没有超时回收机制，是一个永久卡死状态

## 5. 为什么现有测试没发现

诚实写。如果是因为测试没覆盖崩溃路径，就说没覆盖。

## 6. 修复

- 改了什么（文件 + 行为）
- 为什么选这个方案（备选方案是什么，为什么不选）
- 引入了什么新代价

## 7. 回归测试

**必须有一个自动化测试或一个可重放的演练命令**，否则下次还会犯。

```java
// src/test/java/dev/hookflow/it/...IT.java
@Test
void stalePublishingIsRecovered() { ... }
```

## 8. 对不变式的影响

| 不变式 | 修复前 | 修复后 |
|---|---|---|
| 事件不丢 | 成立 | 成立 |
| 不重复投递同一 attempt | 成立 | 成立 |
| 消息不重复 | 不成立（按设计接受） | 不成立（按设计接受） |

## 9. 遗留

写清楚这次没有解决的部分，以及打算什么时候解决。**不要写"以后优化"。**
