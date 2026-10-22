# 运维手册

## 1. 启动

### 本地演示（单进程）

```bash
docker compose up -d                      # MySQL / Redis / RabbitMQ
./mvnw spring-boot:run                    # 默认 profile = all
```

### 生产形态（三角色）

```bash
# 同一镜像，不同 profile
java -jar hookflow.jar --spring.profiles.active=api    --server.port=8080
java -jar hookflow.jar --spring.profiles.active=relay
java -jar hookflow.jar --spring.profiles.active=worker
```

| 角色 | 需要 MySQL | 需要 RabbitMQ | 需要 Redis | 可否多实例 |
|---|:---:|:---:|:---:|---|
| `api` | ✅ | —（不声明队列、不消费） | ✅（限流） | ✅ 无状态 |
| `relay` | ✅ | ✅ | — | ✅ 靠 `SKIP LOCKED` |
| `worker` | ✅ | ✅ | — | ✅ 靠 lease + attempt 护栏 |

**扩容顺序建议**：先加 `worker`（外部接收方延迟是主要瓶颈），再加 `api`，`relay` 最后。

## 2. 依赖故障时的行为

这是运维最需要知道的一张表。**MySQL 是唯一权威，所以它的故障影响最大。**

| 故障 | `api` 行为 | `relay` 行为 | `worker` 行为 | 恢复后 |
|---|---|---|---|---|
| **MySQL 不可用** | 接收事件返回 500（无法保证落库，必须拒绝） | 停止领取，日志报错 | claim 失败，消息 nack 不 requeue → 进 DLQ | 自动恢复；DLQ 里的消息**不需要**手工处理（delivery 仍在库里，`requeueStuckPending` 会兜底） |
| **RabbitMQ 不可用** | **正常接收事件**（只是 outbox 堆积） | publish 失败 → `releaseBack` → 行回到 `NEW`，下轮重试 | 消费停止，无消息可消费 | relay 自动追上；积压 drains，投递延迟上升但不丢 |
| **Redis 不可用** | `fail-open: true` → 放行；`false` → 503 | 无影响 | 无影响 | 自动恢复 |
| **worker 全部挂掉** | 正常 | outbox 会堆积（消息发出去但没人消费） | — | 起 worker 后立即消费积压 |
| **磁盘/MySQL 只读** | 500 | 停止 | 停止 | 需人工介入 |

**关键运维结论**：RabbitMQ 和 Redis 都不是关键路径。只有 MySQL 是关键路径。这意味着容量规划和备份的重点应该压在 MySQL 上。

## 3. 观测什么

### 必须告警的指标

| 指标 | 阈值建议 | 含义 |
|---|---|---|
| `hookflow.outbox` 中 `status='NEW'` 的数量 | > 10000 持续 5 分钟 | relay 跟不上或 broker 异常 |
| `hookflow.delivery.dead` 速率 | > 基线 3 倍 | 客户接收方大面积故障 |
| `hookflow.delivery.retry_scheduled` 速率 | 突增 | 同上 |
| `hookflow.recovery.lease_reclaimed` | 持续 > 0 | worker 在被杀或卡住，检查 GC / 线程池 |
| `hookflow.recovery.stuck_requeued` | 持续 > 0 | 有消息在 broker 层丢失，检查 MQ 稳定性 |
| `hookflow.consumer.poison` | > 0 | 有无法解析的消息，检查发布端 |
| `hookflow.outbox.corrupt` | > 0 | outbox payload 损坏，**需要人工检查** |

### 正常的"看起来像故障"的指标

- `hookflow.delivery.duplicate_skipped > 0`：**这是护栏在工作**，说明 at-least-once 的重复被拦住了。不是 bug。

### 常用排查查询

```sql
-- 积压：有多少工作还没送到 broker
SELECT status, COUNT(*) FROM outbox GROUP BY status;

-- 卡在 DELIVERING 的（lease 是不是没被回收？）
SELECT id, attempt_count, lease_until FROM delivery
 WHERE status = 'DELIVERING' AND lease_until < NOW(3);

-- 某个租户的失败分布
SELECT last_error_class, COUNT(*) FROM delivery
 WHERE tenant_id = ? AND created_at > NOW() - INTERVAL 1 HOUR
 GROUP BY last_error_class;

-- 某条事件的完整链路
SELECT d.id, d.status, d.attempt_count, a.attempt_no, a.status_code, a.error_class, a.duration_ms
  FROM delivery d LEFT JOIN delivery_attempt a ON a.delivery_id = d.id
 WHERE d.event_id = ? ORDER BY d.id, a.attempt_no;

-- 慢接收方 TOP
SELECT endpoint_id, AVG(duration_ms) avg_ms, COUNT(*) n FROM delivery_attempt
 WHERE started_at > NOW() - INTERVAL 1 HOUR AND duration_ms IS NOT NULL
 GROUP BY endpoint_id ORDER BY avg_ms DESC LIMIT 10;
```

## 4. 运维操作

### 重放一条死信

```bash
curl -X POST localhost:8080/v1/deliveries/123/replay -H "Authorization: Bearer $TOKEN"
```

会写 `audit_log`（action = `delivery.replay`）。

### 暂停一个持续失败的 endpoint

```bash
curl -X POST localhost:8080/v1/endpoints/7/pause -H "Authorization: Bearer $TOKEN"
```

效果：新事件不再为它创建 delivery；已有的 `PENDING`/`RETRY_WAIT` 变为 `CANCELED`。
**注意：`CANCELED` 是终态，resume 不会复活它们。** 如需补投，请对具体 delivery 重放（当前不支持重放 CANCELED，需 v0.2 补）。

### 密钥管理

| 密钥 | 来源 | 丢失后果 | 轮换方式 |
|---|---|---|---|
| `HOOKFLOW_MASTER_KEY` | 环境变量，≥32 字符 | **所有 endpoint 的签名密钥不可解密**，客户必须重新配置 | `hookflow.security.key-version` 递增，新旧密钥并存期由 v0.4 提供；当前需重签全部 endpoint |
| `HOOKFLOW_BOOTSTRAP_TOKEN` | 环境变量 | 无法开租户/签新 key，已签的 key 不受影响 | 直接替换环境变量并重启 |
| 租户 API Key | 数据库存 hash | 只能撤销后重签 | `POST /v1/admin/keys/{id}/revoke` |

**`HOOKFLOW_MASTER_KEY` 是最关键的密钥。** 它不进数据库、不进仓库、不进镜像。生产环境应来自 KMS / Secret Manager。

## 5. 时钟与时区（部署时的硬性要求）

**规则：所有时间比较在 MySQL 内部完成，Java 只传时长，不传时刻。**

退避、lease 过期、outbox 卡死回收、PENDING 重扫，全部写成：

```sql
next_attempt_at   = DATE_ADD(NOW(3), INTERVAL #{delayMicros} MICROSECOND)
published_at    < DATE_SUB(NOW(3), INTERVAL #{staleSeconds} SECOND)
```

因此**同一条链路不存在"两个时钟互相比"的情况**，即使 JVM 与数据库时区不同，重试逻辑依然正确。详见 [failures/0002-two-clocks.md](failures/0002-two-clocks.md)。

仍然要求 MySQL 以 **UTC** 运行（`compose.yaml` 已设 `TZ: UTC`），原因是对外暴露的时间（`occurred_at`、`nextAttemptAt`、`createdAt`）是从数据库里读出来的裸 `DATETIME`，需要一致的参照系才能正确解释。应用侧也固定 `-Duser.timezone=UTC`，但那只为日志可读，正确性不依赖它。

**部署检查清单：**

- [ ] MySQL 容器/实例 `time_zone` 为 UTC（`SELECT @@global.time_zone;`）
- [ ] 应用 `TZ=UTC`（镜像已内置）
- [ ] 若使用托管数据库，确认其默认时区不是 `SYSTEM` 且宿主为本地时区

## 6. 数据保留（v0.1 未实现，需在 v0.2 补）

当前 `outbox` 表只增不删，会无限增长。上线前必须补：

```sql
-- 建议：每日清理已发布的 outbox，保留 7 天
DELETE FROM outbox WHERE status = 'PUBLISHED' AND published_at < NOW() - INTERVAL 7 DAY LIMIT 10000;
```

同理 `delivery_attempt` 的 `response_preview` 也应考虑归档。

## 7. 升级与迁移

- 数据库变更只通过 Flyway，**禁止手工改表**
- 迁移脚本一旦发布到 main 分支就不要修改（Flyway 校验 checksum）
- 新增迁移文件命名：`V{n}__{snake_case_description}.sql`
- 破坏性变更（删列/改类型）必须拆成两步：先加新列并双写 → 发版 → 再删旧列

## 8. 优雅停机

`worker` 停机时会等待正在进行的投递结束（Spring 的 graceful shutdown）。如果超过宽限期被强杀：

- 该 delivery 的 lease 会过期（默认 60s）
- 回收器把 attempt 标记为 `ABANDONED` 并重新入队
- **最多延迟 60s，不会丢**

建议把 K8s `terminationGracePeriodSeconds` 设为 `lease.ttl-seconds + read-timeout` 以上（默认 70s 起步）。
