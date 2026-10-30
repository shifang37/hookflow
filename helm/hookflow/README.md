# HookFlow Helm chart

把同一个镜像跑成三种角色：

| 角色 | Spring profile | 端口 | 作用 | 有 Service/Ingress？ |
|---|---|---|---:|---|
| api | `api` | 8080 | 摄入、查询、actuator | 是（唯一入口） |
| relay | `relay` | 8081 | outbox → RabbitMQ | 否 |
| worker | `worker` | 8082 | RabbitMQ → 客户 endpoint | 否 |

MySQL / RabbitMQ / Redis **不在 chart 里**：`config.*` 指向外部地址。这是有意的——
把有状态依赖塞进应用 chart 会让升级、备份和容量规划互相纠缠。

---

## 1. 前置条件

- Kubernetes **1.28+**
- `helm` 3.12+ 或 4.x
- 一个能拉取镜像的集群（kind / minikube / 托管 K8s）
- 可选：`metrics-server`（HPA 的 CPU 指标）
- 可选：Ingress controller（只有开 `ingress.enabled` 才需要）

## 2. kind 快速开始（本机可复现）

```powershell
# 1. 构建镜像。前端是可选的：不构建也能跑 API，只是 /ui/ 返回 404。
cd frontend; npm run build:backend; cd ..
docker build -t hookflow:dev .

# 2. 建集群并载入镜像（kind 不读本地 Docker 镜像，必须 load）
kind create cluster --name hookflow
kind load docker-image hookflow:dev --name hookflow

#    Docker 29/containerd image store 上 kind load 可能报
#    "content digest ... not found"; 用 docker save + ctr import 代替：
#      docker save hookflow:dev -o hookflow-dev.tar
#      docker cp hookflow-dev.tar hookflow-control-plane:/root/
#      docker exec hookflow-control-plane ctr -n k8s.io images import /root/hookflow-dev.tar

# 3. 演示用 MySQL / RabbitMQ / Redis / 接收方（不是 chart 的一部分）
kubectl apply -f deploy/kind/dependencies.yaml
kubectl -n hookflow wait --for=condition=available deploy/mysql deploy/rabbitmq deploy/redis --timeout=180s

# 4. 安装
helm install hookflow helm/hookflow -n hookflow -f helm/hookflow/values-dev.yaml
kubectl -n hookflow rollout status deploy/hookflow-api
kubectl -n hookflow rollout status deploy/hookflow-relay
kubectl -n hookflow rollout status deploy/hookflow-worker

# 5. 从本机访问 API
kubectl -n hookflow port-forward svc/hookflow-api 8080:80
# 另开一个终端
curl.exe -s http://localhost:8080/actuator/health/readiness
```

创建租户、endpoint 并投递一条真实事件（`demo-receiver` 的 `/slow/3` 会睡 3 秒，
适合观察滚动更新时的在途投递）：

```powershell
$bootstrap = 'hookflow-dev-bootstrap-token'

# 租户 + API Key（Key 只返回一次）
$tenant = Invoke-RestMethod -Method Post http://localhost:8080/v1/admin/tenants `
  -Headers @{ 'X-Bootstrap-Token' = $bootstrap } -ContentType 'application/json' `
  -Body '{"name":"kind-acme","rateLimit":100,"maxInFlight":32,"weight":1}'
$token = $tenant.apiKey

# endpoint 指向集群内的 demo-receiver
Invoke-RestMethod -Method Post http://localhost:8080/v1/endpoints `
  -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' `
  -Body '{"url":"http://demo-receiver/slow/3","subscribedTypes":"*","maxAttempts":8,"maxConcurrency":0}'

# 发事件
Invoke-RestMethod -Method Post http://localhost:8080/v1/events `
  -Headers @{ Authorization = "Bearer $token"; 'Idempotency-Key' = 'kind-1' } `
  -ContentType 'application/json' `
  -Body '{"eventType":"order.paid","payload":{"orderId":1}}'
```

## 3. 为什么滚动发布不会丢投递

一条投递有三个独立的持久化锚点，滚动更新只影响进程，不影响事实：

1. **outbox 在 MySQL 里**。relay 发布的每一行先落 `PUBLISHING`，确认后删除；
   进程被杀时，`OutboxSweeper` 在 `stale-publishing-seconds`（60s）后把卡住的行放回 `NEW`。
   relay 的 `maxUnavailable: 0` 保证旧 pod 退出前新 pod 已经 Ready。
2. **worker 用 60s lease + fencing token**。被 SIGTERM 的 worker 停止领取新消息；
   正在跑的 HTTP 调用有 `terminationGracePeriodSeconds: 75`（> lease 60s + read 10s）完成并 settle。
   如果它没来得及 settle，lease 过期后 `DeliveryRecovery` 重新入队，attempt-count 栅栏
   保证同一 attempt 不会被两个 worker 同时执行。
3. **broker 只存引用**，消息重复是 at-least-once 的正常路径；重复消息被 attempt 栅栏挡下
   （`hookflow_delivery_duplicate_skipped_total` 会涨，这是护栏在工作，不是丢投递）。

`preStop: sleep 5` 让 Service/Ingress 先把 pod 从 endpoints 摘掉，再让 Spring 收到 SIGTERM。
所以滚动更新期间：

- 新事件继续被 api 接收并写 outbox；
- 已经在途的 worker 尝试要么完成，要么被 lease 回收后重投；
- 最多看到一条投递的 `attemptCount` 增加一次，不会永久丢失。

实测命令见 §7。

## 4. 配置与 Secret

`config.*` 渲染成 ConfigMap，所有角色 `envFrom` 它；密码和密钥在 Secret 里。

| values | 默认 | 说明 |
|---|---|---|
| `image.repository` / `tag` | `ghcr.io/shifang37/hookflow` / `0.4.0` | 三角色共用 |
| `replicaCount.api/relay/worker` | 2 / 1 / 2 | HPA 开启后由 HPA 覆盖 |
| `rolePorts.*` | 8080 / 8081 / 8082 | 同时是 actuator 端口 |
| `config.dbHost` 等 | `mysql` / `rabbitmq` / `redis` | 外部依赖地址 |
| `config.demoEnabled` | `false` | `/v1/demo/**` 无鉴权，别在共享集群打开 |
| `config.egressAllowPrivateTargets` | `false` | SSRF 防护；kind 指向集群内 demo-receiver 时 dev overlay 才置 `true` |
| `config.otlpExport` | `false` | 没有 collector 时保持关闭 |
| `secrets.create` | `true` | dev 用；生产设 `false` |
| `secrets.existingSecret` | `""` | `create=false` 时必填，需含 4 个 key |
| `terminationGracePeriodSeconds` | `75` | > lease 60s + read 10s |
| `preStopSleepSeconds` | `5` | 先摘流量再 SIGTERM |

生产 Secret：

```bash
kubectl -n hookflow create secret generic hookflow-secrets \
  --from-literal=HOOKFLOW_MASTER_KEY='...' \
  --from-literal=HOOKFLOW_BOOTSTRAP_TOKEN='...' \
  --from-literal=DB_PASSWORD='...' \
  --from-literal=MQ_PASSWORD='...'
helm install hookflow helm/hookflow -n hookflow -f helm/hookflow/values-prod.yaml
```

## 5. HPA 与 PDB

- 默认 `api` 2–6、`worker` 2–8、`relay` 1–3（relay 默认关闭 HPA），CPU 70%。
- `values-dev.yaml` 只开 api（1–3），因为 kind 默认没有 metrics-server；装上后 HPA 才会动。
- `values-prod.yaml` 三角色都开 HPA，并给每个角色一个 PDB。
- worker/relay 后续可以换成 `hookflow_outbox_backlog` 自定义指标：那需要集群里的
  Prometheus Adapter（`external.metrics.k8s.io`），不属于本 chart。当前先按 CPU，
  等有 adapter 后改 `autoscaling.worker.metric` 即可（本次未实现该字段）。

kind 上装 metrics-server：

```powershell
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl -n kube-system patch deploy metrics-server --type=json `
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

## 6. 前端与迁移

**前端**：`Dockerfile` 只 `COPY src/`，不会跑 `npm`。镜像里有没有 `/ui/` 取决于构建镜像前
是否执行过 `npm run build:backend`（产物在 `src/main/resources/static/ui/`，已被 gitignore）。
二选一：
1. 构建镜像前先在 `frontend/` 跑 `npm run build:backend`（简单，推荐本地/演示）；
2. 给 Dockerfile 加一个 node stage（生产 CI 用）。本 chart 不假设镜像里一定有 `/ui/`。

**迁移**：每个 pod 启动都会跑 Flyway。Flyway 自带表级锁，多个 pod 同时启动是安全的
（一个先迁移，其余等待）。更干净的做法是用一个独立的 Job 先跑迁移再 `helm install`
（例如 `flyway/flyway` 镜像 + 同一套 DB 凭证），本 chart 没内置这个 Job，因为那会把
“迁移”和“应用发布”绑成同一个生命周期；需要时在流水线里加一步即可。

## 7. 验收 / 冒烟测试

```powershell
# a) 三个 Deployment 都 Ready
kubectl -n hookflow get deploy,pods

# b) 删一个 worker，在途投递不丢：
#    先发一条指向 /slow/3 的事件，看到它 DELIVERING 后
kubectl -n hookflow delete pod -l app.kubernetes.io/component=worker --wait=false
#    delivery 最终 SUCCEEDED；最多 attemptCount +1（lease 回收后重投）
curl.exe -s "http://localhost:8080/v1/events/1" -H "Authorization: Bearer $token"

# c) 滚动更新期间持续发事件，无丢失：
#    改 image.tag 触发 rollout，同时循环发事件，最后检查 dead/canceled 计数为 0
kubectl -n hookflow set image deploy/hookflow-api api=hookflow:dev2
kubectl -n hookflow rollout status deploy/hookflow-api

# d) HPA 扩到 ≥2（需 metrics-server）：
kubectl -n hookflow get hpa
#    对 api 打 CPU（例如循环请求 /actuator/health），watch replicas
kubectl -n hookflow get hpa hookflow-api -w
```

### 本机验证记录（2026-10-30）

kind v1.35.0、chart `0.1.0`、`values-dev.yaml`（api×1、worker×2、relay×1）：

- `helm install --wait` 后三个 Deployment 全部 Ready，`/actuator/health/readiness` 200；
- 发一条指向 `/slow/8` 的事件，在 `DELIVERING` 时 **force delete 两个 worker pod**：
  attempt 1 被 lease 回收，约 65s 后 attempt 2 **SUCCEEDED**，没有丢投递；
- 滚动重启 worker + relay 的同时持续发 40 条事件：最终 **46 SUCCEEDED / 0 DEAD**
  （`order.paid` 口径），在途投递全部收敛；
- HPA：对 api 的 `/actuator/prometheus` 施压后 **1 → 3 replicas**（CPU 191% / 目标 70%）。

> 本机的 Docker 构建容器访问不了 Maven Central，所以验收镜像用“宿主机 `mvn package` +
> 同一个 `eclipse-temurin:21-jre`”构建；仓库里的 `Dockerfile` 没有改，CI/正常网络下
> 仍按原多阶段构建执行。

## 8. 故障排查

| 现象 | 检查 |
|---|---|
| pod `CrashLoopBackOff` | `kubectl logs`：DB/MQ 地址、Secret key、master key 长度 |
| readiness 一直失败 | `kubectl exec ... -- curl localhost:8081/actuator/health/readiness`；健康检查包含 DB/MQ |
| api 起得来但 relay/worker 起不来 | 端口/profile 是否被覆盖；relay 需要 `RELAY_PORT`，worker 需要 `WORKER_PORT` |
| HPA `<unknown>` | metrics-server 没装或没 ready；kind 需要 `--kubelet-insecure-tls` |
| 滚动时丢投递 | 检查 `terminationGracePeriodSeconds` 是否被改小到 < lease 60s；`server.shutdown=graceful` 是否覆盖掉 |
