# ADR-0003：用 lease + fencing token 认领投递，不在网络调用期间持有行锁

- 状态：已采纳（v0.1）
- 日期：2026-10-22

## 背景

一个 delivery 同一时刻只能被一个 worker 投递。最直觉的写法是：

```sql
BEGIN;
SELECT * FROM delivery WHERE id = ? FOR UPDATE;   -- 锁住这一行
-- 发 HTTP 请求（最长 10 秒）
UPDATE delivery SET status = 'SUCCEEDED';
COMMIT;
```

## 为什么不这么做

1. **行锁跨越网络调用**：接收方慢 10 秒，这一行就被锁 10 秒。worker 线程池很快被慢接收方占满，一个坏客户能拖垮整个平台的吞吐。
2. **锁不会在进程崩溃时自动"过一段时间"释放**：MySQL 在连接断开时回滚事务，所以崩溃场景其实还好；但**连接半开**（网络分区、容器被 SIGSTOP）时锁会一直挂着，直到 TCP keepalive 或 `innodb_lock_wait_timeout` 生效。
3. **锁无法表达"我怀疑这个 worker 死了"**：没有 `kill -9` 检测机制。DBA 视角之外没人知道哪个 worker 该被接手。

## 决定

用**两段式 + 租约**：

```sql
-- 1) 认领（短事务，无网络 I/O）
UPDATE delivery
   SET status='DELIVERING', attempt_count = attempt_count + 1,
       lease_token = :uuid, lease_until = NOW(3) + INTERVAL 60 SECOND
 WHERE id = :id AND status IN ('PENDING','RETRY_WAIT')
   AND attempt_count = :attemptNo - 1;      -- 幂等护栏
-- 提交

-- 2) 发 HTTP（不在任何事务里）

-- 3) 收敛（短事务）
UPDATE delivery SET status='SUCCEEDED', ...
 WHERE id=:id AND status='DELIVERING' AND lease_token=:uuid;   -- fencing
```

三个关键点：

- **`lease_token` 是 fencing token**：每次认领生成新的 UUID，之后所有"离开 DELIVERING"的更新都必须带上它。一个被判定死亡的旧 worker 拿着旧 token 回来写，会更新 0 行。
- **`attempt_count` 是幂等护栏**：消息里带 `attemptNo`，只有库里 `attempt_count = attemptNo - 1` 时才认领成功。重复消息因此无害。
- **回收靠时间，不靠检测**：`lease_until < NOW()` 的行由 sweeper 收回，同时把那条 `IN_PROGRESS` 的 attempt 标成 `ABANDONED`。**不需要知道 worker 是不是真的死了**——过期就是过期。

## 代价

- 需要**续约**的考虑：如果一次投递可能超过 lease 时长，就必须续约。v0.1 的做法是让 `lease_ttl(60s) >> read_timeout(10s)`，留 6 倍余量，从而不需要续约逻辑。v0.3 若支持长轮询类投递需补续约。
- 时钟依赖：lease 判定用数据库的 `NOW(3)`，不让应用服务器参与判断，避免多机时钟偏移。
- 短暂的双写可能：lease 刚好过期、旧 worker 又刚好回来。此时**两个 worker 都可能发出 HTTP 请求**，但只有持新 token 的那个能写库。这是 at-least-once 的又一来源，已被接受。

## 备选方案

| 方案 | 否决理由 |
|---|---|
| 行锁 + 网络调用 | 慢接收方拖垮线程池，见上 |
| Redis 分布式锁 | 引入第二个状态源；锁的过期与 fencing 问题一样存在，但没有数据库事务的原子性 |
| 单一 worker 单线程 | 放弃水平扩展，且单点故障直接停摆 |
| RabbitMQ 消息独占 + prefetch=1 | 无法表达"这次尝试的第几轮"，重试阶梯只能落在 MQ 里，导致 broker 变成重试调度器（我们刻意避免，见 ADR-0001 的替代设计） |

## 如何验证

- `claimIsExclusive`：第二次 claim 返回 0 行
- `expiredLeaseIsReclaimed`：过期 lease 被回收，`attempt_count` 不被退还
- 故障演练 2（v0.2）：worker 被 `kill -9`，另一 worker 在 lease 过期后接手，attempt 记录连续无重复
