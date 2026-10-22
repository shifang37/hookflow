# ADR-0002：只承诺 at-least-once，不承诺 exactly-once

- 状态：已采纳（v0.1）
- 日期：2026-10-22

## 背景

Webhook 投递的"正确性"到底能保证到什么程度，是这类系统最容易被夸大的地方。常见说法是"我们保证 exactly-once"，但这句话在 HTTP 上不成立。

## 分析

考虑这个序列：

```
worker --POST--> 接收方
                    │  接收方已写入自己的库、已执行业务逻辑
                    │  接收方正在写响应
        X <──────────┘  连接断了 / 读超时 / 进程被杀
```

此刻 worker 面对两个**无法区分**的世界：

- 世界 A：请求根本没到，什么都没发生
- 世界 B：请求到了，业务已执行，只是响应回不来

worker 只有一个选择：

- **重试** → 世界 B 会重复执行（至少一次）
- **不重试** → 世界 A 会永久丢失（至多一次）

**必须二选一，因为"对方到底执行了没有"这个信息在物理上不可获得。** 所谓 exactly-once 只能在"接收方参与协议、并且平台能读到接收方状态"的前提下成立，而 Webhook 的接收方是任意第三方服务，没有这个前提。

## 决定

平台语义明确为 **at-least-once**：

1. 保证"不丢"（选重试），接受"可能重复"
2. 通过三件事把重复变成**可处理的**问题：
   - `event_id` 在所有重试中保持稳定 → 接收方可以据此去重
   - `X-HookFlow-Delivery-Id` + `X-HookFlow-Attempt` → 接收方能看清这是第几次尝试
   - HMAC 签名绑定 `event_id` / `delivery_id` / 时间戳 → 接收方能拒绝伪造和过期请求
3. 文档、README、OpenAPI 描述**全部**写 at-least-once，不使用 exactly-once 措辞

## 内部为什么也不会重复消费

虽然外部语义是 at-least-once，平台内部不能因为重复消息就重复投递同一轮。护栏是：

```sql
UPDATE delivery SET status='DELIVERING', attempt_count = attempt_count + 1, ...
 WHERE id = ? AND status IN ('PENDING','RETRY_WAIT')
   AND attempt_count = ?   -- 消息声明的"已经执行过的次数"
```

重复消息携带同一个 `attemptNo`，第二次执行时 `attempt_count` 已经不匹配，更新 0 行，worker 直接跳过。所以**重复消息不会造成额外的投递尝试**——重复只来自"HTTP 请求已发出但结果未知"这一种情况。

## 代价

- 接收方**必须**做去重，否则业务重复。这是真实约束，写进 `docs/delivery-semantics.md`
- `duplicate_skipped` 指标会大于 0，这是正常的，不是 bug
- 不能对客户承诺"绝对不会重复"，销售话术上不占优（本项目是作品集，不是产品，这个代价可以接受）

## 如何验证

- `HmacSignerTest`：换 delivery id / 换时间戳 / 篡改 body 都验签失败
- `claimIsExclusive`：同一 attempt 的第二次 claim 更新 0 行
- 故障演练 2（v0.2）：worker 投递中被 kill，lease 过期后接手，`attempt_no` 不重复
