# 0001 — 并发幂等提交时，输掉竞争的那一方返回 500

- **类型**：缺陷（由集成测试发现）
- **发现时间**：2026-10-22 13:52
- **影响面**：并发提交同一 `Idempotency-Key` 时，**输掉竞争的请求会收到 500**，而不是返回已存在的事件 id
- **严重级别**：P1（不丢数据，但违背了"幂等"对调用方的承诺）
- **涉及设计点**：幂等实现、事务边界、隔离级别
- **状态**：已修复

---

## 1. 现象

`EventIngestIT.concurrentDuplicateSubmissionsCollapseToOneEvent`（20 线程同时提交同一幂等键）失败：

```
java.util.concurrent.ExecutionException:
org.springframework.dao.DuplicateKeyException:
### Error updating database. Cause: java.sql.SQLIntegrityConstraintViolationException:
    Duplicate entry '12-idem-6449fa1e-...' for key 'event.uk_event_tenant_idem'
### SQL: INSERT INTO event (tenant_id, event_type, payload, idempotency_key) VALUES (?, ?, ?, ?)
	at dev.hookflow.it.EventIngestIT.concurrentDuplicateSubmissionsCollapseToOneEvent(EventIngestIT.java:80)
```

关键点：**唯一约束按预期生效了**（这正是幂等的裁判），但异常没有被吞掉，而是冒到了调用方。

## 2. 触发方式

```java
// 20 个线程，同 tenant + 同 idempotencyKey，同时调用 ingest()
EventIngestService.IngestResult result = ingestService.ingest(tenantId, "order.paid", "{\"amount\":42}", key);
```

必须**真正并发**。串行调用时第一个请求就把事件写好了，后续请求走"预检命中"分支，永远碰不到这个 bug。

## 3. 根因

原实现把"补偿查询"放在**同一个事务内部**：

```java
@Transactional
public IngestResult ingest(...) {
    Event existing = eventMapper.findByTenantAndIdempotencyKey(...);   // 预检
    if (existing != null) { return replay(existing); }

    try {
        eventMapper.insert(...);            // ← 19 个线程在这里抛 DuplicateKeyException
    } catch (DuplicateKeyException race) {
        Event winner = eventMapper.findByTenantAndIdempotencyKey(...)   // ← 读不到！
                .orElseThrow(() -> race);                              // ← 于是重新抛出
        return replay(winner);
    }
    ...
}
```

MySQL 默认隔离级别是 **REPEATABLE READ**。事务的一致性快照在**第一次读**时建立，早于赢家提交。所以：

1. 赢家提交了 `event(id=12, key=...)`
2. 输家的快照建立得更早，**看不到**这一行
3. `DuplicateKeyException` 被捕获 → 补偿 SELECT 返回空
4. `orElseThrow(() -> race)` 把原始异常抛出去 → 500

值得注意的是：**唯一约束检查是在"当前读"上做的**（它能看到未提交/刚提交的冲突行，这是它必须做的），而**普通的 SELECT 是快照读**。所以数据库告诉我们"冲突了"，我们却读不到冲突的那一行。

## 4. 为什么现有测试没发现

单元测试没有并发；只有集成测试的 20 线程用例会触发。这也是坚持"用真实 MySQL 而不是 H2"的原因之一——H2 的默认隔离级别与 MVCC 行为不同，很可能掩盖这个问题。

## 5. 修复

把补偿查询移到**事务之外**，让它跑在一个新连接、新快照上：

```java
public IngestResult ingest(...) {
    try {
        return transactionTemplate.execute(status -> ingestInTransaction(...));
    } catch (DuplicateKeyException lostRace) {
        // 此刻失败事务已回滚，这个 SELECT 在自动提交连接上执行，能看见赢家
        Event winner = eventMapper.findByTenantAndIdempotencyKey(...)
                .orElseThrow(() -> lostRace);
        return new IngestResult(winner.id(), deliveryCount(...), true);
    }
}
```

### 为什么不用 `SELECT ... FOR SHARE`

在补偿查询上加锁读（当前读）也能读到赢家的行，但：

- 会让一个本该轻量的读路径变成加锁路径
- 在事务内加共享锁，后续可能有锁等待
- 语义上仍然"在一个已经知道要失败的上下文里继续做事"

把补偿查询放到新事务/自动提交上更干净，也更符合直觉：**失败的那个事务不该被继续使用。**

### 备选：整段重试

也可以捕获异常后**重新执行整个 `ingest`**（此时预检会命中）。这更简单但会多做一次无用的预检查询，而且如果赢家随后又被删除（本项目不会），会进入循环。当前方案更直接。

## 6. 回归测试

`EventIngestIT.concurrentDuplicateSubmissionsCollapseToOneEvent`：

- 20 线程 + `CountDownLatch` 同时放行
- 断言：所有调用拿到**同一个** eventId
- 断言：只产生 1 条 delivery

该用例现在稳定通过。**它同时守住了两个不变式**：唯一约束是裁判（不能去掉），以及补偿路径必须可见（不能把 SELECT 放回事务里）。

## 7. 对不变式的影响

| 不变式 | 修复前 | 修复后 |
|---|---|---|
| 同幂等键 → 1 个事件 | 成立（唯一约束保证） | 成立 |
| 重复请求返回同一 eventId | **不成立**（500） | 成立 |
| 失败事务不被继续复用 | 不成立 | 成立 |

## 8. 遗留

无。但引出一个值得记录的运维注意点：**应用层永远不要假设"冲突之后还能在同一个事务里读到对方的行"**。这在 REPEATABLE READ 下不成立，在 READ COMMITTED 下通常成立——于是这类 bug 会随隔离级别配置漂移，测试必须在目标隔离级别下跑。
