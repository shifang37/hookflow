# ADR 索引

架构决策记录（Architecture Decision Record）。每条记录回答三个问题：**当时有什么选项、选了哪个、代价是什么。**

写 ADR 而不是只写最终架构图的原因：三个月后（或面试时）被问到"为什么这么设计"，最终架构图答不了，ADR 可以。

| 编号 | 决策 | 状态 |
|---|---|---|
| [0001](0001-outbox-over-distributed-transaction.md) | 用事务 Outbox，而不是分布式事务 | 已采纳 |
| [0002](0002-at-least-once-not-exactly-once.md) | 只承诺 at-least-once，不承诺 exactly-once | 已采纳 |
| [0003](0003-lease-based-worker-claim.md) | 用 lease + fencing token 认领，不在网络调用期间持锁 | 已采纳 |
| [0004](0004-modular-monolith-with-roles.md) | 模块化单体 + 按角色启动 | 已采纳 |
| [0005](0005-spring-boot-4-and-java-21.md) | Spring Boot 4.1 + JDK 21，不回退 3.5 | 已采纳 |
| [0006](0006-redis-for-rate-limit-only.md) | Redis 只做限流，不存事件状态 | 已采纳 |
| [0007](0007-hand-rolled-api-key-auth.md) | v0.1 不用 Spring Security | 已采纳（v0.4 重审） |
| [0008](0008-fault-injection-tooling.md) | 故障注入用容器级操作与构造状态，放弃 Toxiproxy | 已采纳 |
| [0009](0009-fair-scheduling.md) | 公平调度放在 outbox → broker 边界（准入 + 加权轮询），不分片队列 | 已采纳 |
| [0010](0010-backpressure-and-shedding.md) | 过载在入口淘汰（429/503 + Retry-After），内部只设上限 | 已采纳 |
| [0011](0011-distributed-tracing.md) | trace 上下文进 payload，同时也在 AMQP header；用 W3C 标准 | 已采纳 |

## 模板

新建 ADR 时复制这个结构：

```markdown
# ADR-NNNN：<决策的一句话>

- 状态：提议 / 已采纳 / 已废弃（被 ADR-XXXX 取代）
- 日期：YYYY-MM-DD

## 背景
<是什么问题逼出了这个决策？>

## 选项
| 方案 | 做法 | 代价 |

## 决定
<选了什么>

## 为什么
<关键理由，要能回答"为什么不选另一个">

## 代价与已知问题
<诚实写>

## 什么情况下会改这个决定
<明确的触发条件，不是"以后再说">

## 如何验证
<哪个测试 / 哪个演练证明它成立>
```
