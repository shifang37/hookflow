# 0006 — 演练 D3：Redis 与 RabbitMQ 先后不可用，事件接收还能不能继续

- **类型**：演练
- **执行时间**：2026-10-22 14:53
- **被测组件**：Redis（限流）、RabbitMQ（投递通道）
- **命令**：`./scripts/drill.ps1 -DbPort 13306`
- **状态**：通过

---

## 1. 目的

`docs/operations.md` §2 声称：「RabbitMQ 和 Redis 都不是关键路径，只有 MySQL 是。」这句话必须被验证过，否则就只是一句漂亮的架构论述。

要验证的不变式：

| 不变式 | 期望 |
|---|---|
| Redis 挂掉事件仍被接收 | `fail-open=true` 时放行 |
| Redis 挂掉不影响投递 | 投递链路完全不依赖 Redis |
| RabbitMQ 挂掉事件仍被接收 | 只写 MySQL |
| RabbitMQ 挂掉工作不丢 | 停在 outbox 里 |
| 恢复后自动追平 | 无需人工干预 |

## 2. 原始输出

```
=== D3: stop dependencies ================================
[14:53:53] redis stopped
[14:53:53] event 23 accepted while Redis is down (fail-open) -> ok
[14:53:53] redis started
[14:53:53] rabbitmq paused
[14:53:57] event 24 accepted while RabbitMQ is paused; unpublished outbox = 1
[14:53:57] rabbitmq resumed
[14:53:59] event 24 -> SUCCEEDED attempts=1
[14:53:59] unpublished outbox after resume = 0
```

## 3. 观察

### Redis 停机

- 事件**立刻**被接受，延迟无可测量的变化。
- 原因：Redis 只承担限流计数，`hookflow.rate-limit.fail-open=true` 时连不上就放行并打一条 WARN。
- 这一条也说明了为什么限流可以放在 Redis：**它丢了只会让流量进来多一点，不会让事件消失**。事件的权威状态从头到尾都在 MySQL。

### RabbitMQ 暂停

- 事件仍被接受，`unpublished outbox = 1` —— 工作停在数据库里。
- 恢复后 **2 秒**内投递完成，`attempts=1`。没有产生任何失败尝试，因为消息**从未被发出**过，谈不上失败。
- 这一点和「broker 停机」语义不同：这里连 publish 都没发生，所以没有任何"可能已送达"的不确定性，重复风险为零。

### MySQL 呢

本演练**没有**覆盖 MySQL 不可用。原因是它的语义不同且更简单：MySQL 是权威状态，挂了就只能拒绝接收（返回 500）。这是**唯一**会让平台拒绝事件的依赖，也正是容量规划和备份必须压在 MySQL 上的原因（见 `docs/operations.md` §2）。

## 4. 结论

README 里那句"broker 丢消息不等于丢投递"在真实环境里成立：RabbitMQ 完全不可用的 4 秒内，平台照常接受事件、把工作停在 outbox、恢复后自动追平，**没有人工介入、没有额外尝试、没有重复**。

## 5. 与自动化测试的关系

同样的性质在 CI 里由 `BrokerOutageIT` 用容器 `pause/unpause` 覆盖，粒度更细（还断言了"故障期间不得产生任何 attempt"以及 `unpublished outbox >= 1`）。

演练与自动化测试的分工：

| 工具 | 覆盖什么 | 何时用 |
|---|---|---|
| `BrokerOutageIT` | broker 不可用 + 恢复的完整语义 | 每次 CI |
| 本演练 | 真实运营动作（`docker stop` / `docker pause`）在真实多进程部署下的表现 | 发布前、面试演示 |

## 6. 遗留

- 未演练 MySQL 不可用（预期行为是接收返回 500；需要单独设计，因为演练期间所有角色都会报错）
- 未演练 Redis 的 `fail-open=false` 模式（预期返回 503 `DEPENDENCY_UNAVAILABLE`），目前只有单元级的代码路径，没有端到端演练
- 未演练"限流真的生效"（本次 `rateLimit=1000` 且事件很少，未触发 429）。建议补一次：把租户限流设为 1/s，连续提交 5 个事件，观察 429 与错误体
