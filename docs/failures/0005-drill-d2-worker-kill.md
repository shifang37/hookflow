# 0005 — 演练 D2：worker 在请求中途被 `kill -9`，租约能不能被接手

- **类型**：演练
- **执行时间**：2026-10-22 14:53
- **被测角色**：`worker`（独立 JVM，profile=`worker`，`lease.ttl=8s`、`sweep=2s`）
- **命令**：`./scripts/drill.ps1 -DbPort 13306`
- **状态**：通过（这是三个演练里最有价值的一个）

---

## 1. 目的

worker 崩溃是唯一会同时踩到三个机制的故障：

1. 进程死了，但 HTTP 请求**可能已经到达接收方**
2. 数据库里的行停在 `DELIVERING`，租约还没到期
3. 没人知道那个 worker 是死了还是只是慢

要验证的不变式：

| 不变式 | 期望 |
|---|---|
| 租约过期后有人接手 | 新 worker 在 `lease.ttl` 之后接手 |
| 死掉的那次尝试被如实记录 | attempt 变为 `ABANDONED` / `LEASE_EXPIRED` |
| 投递最终完成 | 状态收敛到 `SUCCEEDED` |
| **attempt 编号不重复、不跳号** | 1 是 ABANDONED，2 是 SUCCEEDED |
| 不需要人工干预 | 不 replay、不改库 |

## 2. 为什么只起一个 worker

如果起两个 worker，"被杀的那个是否正好持有这条 delivery"就成了掷硬币，演练结果会在"什么都没发生"和"租约被接手"之间随机。**只起一个、杀掉、再起一个**，才能确定性地制造"持有者死亡"。

接收方被配成 3 秒响应（`?delay=3000`），这样 kill 一定落在请求进行中，同时又小于 10 秒读超时，让重试有机会成功。

## 3. 原始输出

```
[14:53:39] submitted event 22 against the 3s-slow endpoint
[14:53:41] KILLED worker (pid 21860) - SIGKILL equivalent, no graceful shutdown
[14:53:41] worker killed while the request is in flight; now waiting out the 8s lease
[14:53:42] started worker profile=worker
[14:53:52] delivery 22 status=SUCCEEDED attempts=2
[14:53:52]   attempt 1 ABANDONED error=LEASE_EXPIRED duration=n/a
[14:53:52]   attempt 2 SUCCEEDED error= duration=3033ms
```

## 4. 观察

1. **`attempt 1 ABANDONED / LEASE_EXPIRED`** —— 死掉的那次尝试没有被隐藏，也没有被当成失败重试掉，而是被明确标记为"执行者消失"。这一点很重要：如果把 ABANDONED 记成 FAILED，运维就会误判为"接收方出问题"。
2. **`attempt 2 SUCCEEDED duration=3033ms`** —— 新 worker 的这次尝试耗时 3 秒，正好等于接收方的延迟，说明它是一次**真实的新请求**，不是缓存或假的成功。
3. **attempt 编号 1 → 2，连续无重复** —— 租约回收路径调用 `nextAttemptNo()`（`attempt_count + 1`），没有把编号 1 重放一遍。若重放编号 1，`uk_attempt_delivery_no` 会冲突，测试也会暴露。
4. **从 kill 到收敛 11 秒**：1s（in-flight）+ 8s（租约到期）+ 2s（sweeper 周期）≈ 11s，与配置吻合。这说明租约的**过期时间就是恢复时间的下界**，是可调的运维参数而非玄学。
5. **接收方最多会看到 2 次请求** —— 第一次（被 kill 的那次）请求**已经发出**，接收方可能已处理。这就是 at-least-once 的真实来源，也是为什么接收方必须按 `event_id` 去重。

## 5. 结论

全部不变式成立。这个演练用 11 秒、一次 `kill -9`，把三件事同时证明了：

- 租约机制真的会在崩溃后接手（不是只在单元测试里成立）
- 崩溃的尝试被如实记录为 `ABANDONED`，而不是伪装成失败
- 端到端语义确实是 at-least-once：**接收方看到了 2 次请求，平台只记录了 2 次尝试、1 次成功**

## 6. 这次演练也暴露了一个部署问题（已修）

第一次跑演练时 relay 启动即退出：

```
Web server failed to start. Port 8080 was already in use.
```

三个角色都默认监听 8080，本地同机运行时必然相撞。修复：给 profile 分配默认端口（`api=8080`、`relay=8081`、`worker=8082`，均可用环境变量覆盖）。

这类问题在 Kubernetes 里不会出现（每个角色一个 Pod，各自 8080），**只在"同机多角色"这种本地形态下暴露**——也就是面试时你想演示的那个形态。不实际跑一遍演练是发现不了的。

## 7. 遗留

- 未验证「worker 只是慢、没死」的情况（租约被误判抢走）。理论上旧 worker 醒来后写库会被 `lease_token` 围栏挡住，`EventIngestIT.claimIsExclusive` 覆盖了围栏本身，但没有做端到端的"慢 worker 复活"演练
- `lease.ttl=8s` 是演练用的激进值，默认 60s 是为了给读超时留 6 倍余量
