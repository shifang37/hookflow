# 0007：批量取消与逐行认领相互死锁

- 发现于：v0.3，`FairnessIT` 第一次跑通公平性对照之后（清理阶段）
- 严重级别：P2（运维操作失败，不丢数据）
- 状态：已修复

## 症状

`FairnessIT` 在测完第一轮洪水后调用 `endpointService.pause(...)` 清理积压，抛出：

```
org.springframework.dao.DeadlockLoserDataAccessException:
### Error updating database. Cause: com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException:
Deadlock found when trying to get lock; try restarting transaction
### The error may involve dev.hookflow.persistence.DeliveryMapper.cancelPendingForEndpoint
### SQL: UPDATE delivery SET status = 'CANCELED', next_attempt_at = NULL, updated_at = NOW(3)
         WHERE endpoint_id = ? AND tenant_id = ? AND status IN ('PENDING', 'RETRY_WAIT')
```

调用栈很干净：`EndpointService.pause` → `DeliveryMapper.cancelPendingForEndpoint`。没有数据损坏，事务整体回滚，delivery 状态保持原样。

## 根因

两条路径以**相反的加锁顺序**访问同一批行：

| 路径 | 语句 | 加锁顺序 |
|---|---|---|
| 暂停端点 | `UPDATE delivery ... WHERE endpoint_id = ? AND status IN ('PENDING','RETRY_WAIT')` | 走 `idx_delivery_endpoint_status`，按**索引顺序**（endpoint 内大致按主键）逐行加锁 |
| worker 认领 | `UPDATE delivery SET status='DELIVERING' ... WHERE id = ?` | 按**单行主键**加锁 |

单看两边都不会阻塞很久，但 InnoDB 的死锁判定基于**等待图**：批量 UPDATE 在扫描索引的过程中逐步持有行锁，worker 恰好持有了批量语句下一步要锁的行、并且正等着批量语句已经持有的另一行，环就闭合了。

触发条件是"**正在被大量认领的端点被暂停**"——v0.2 的测试规模（每次一两条 delivery）永远撞不上，v0.3 的公平性测试一次造 1 500 条才让它必然出现。这是典型的"压力测试抓到并发缺陷"。

## 修复

`EndpointService.pause` 改为**事务级重试**：

```java
for (int attempt = 1; ; attempt++) {
    try {
        return transactionTemplate.execute(status -> pauseInTransaction(tenantId, endpointId));
    } catch (PessimisticLockingFailureException e) {   // 死锁 + 锁等待超时
        if (attempt >= 3) throw e;
        sleep(20ms * attempt);                          // 递增退避
    }
}
```

为什么重试是正解，而不是"把批量 UPDATE 拆小"或"改成主键范围"：

- 死锁是 InnoDB 的**正常调度结果**，不是逻辑错误。选谁做牺牲者是引擎的事，应用层能做的就是重放。
- `pause` 是幂等的：再执行一次的效果与第一次相同（endpoint 已经是 PAUSED 时直接返回）。
- 拆小批量只会降低概率、增加往返；把冲突变成"更稀有"而不是"被处理"，属于把 bug 藏起来。

捕获 `PessimisticLockingFailureException`（而不是只有 `DeadlockLoserDataAccessException`）是因为锁等待超时 `CannotAcquireLockException` 是同一个父类，两者都该重试。

## 回归测试

`EndpointPauseRetryTest`：第一次调用 `cancelPendingForEndpoint` 抛 `DeadlockLoserDataAccessException`，第二次成功；断言 `pause` 最终返回 PAUSED、审计日志只写一条、且确实重试过（`verify(times(2))`）。

## 遗留

- 同一个模式还有别处：`retry`/`replay`/`resume` 都是小事务，不批量，风险低；但**将来任何"按维度批量改状态"的操作都要么重试、要么按主键分片**。这条写进 `docs/operations.md`。
- 没有做"死锁率"指标。真实部署里 `DeadlockLoserDataAccessException` 的计数应该被监控，因为它和"暂停/批量操作撞上热点"强相关。
