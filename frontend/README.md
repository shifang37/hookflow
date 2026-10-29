# HookFlow 控制台（frontend/）

运营控制台：事件流、投递、租户、系统监控四个页面。React 18 + TypeScript + Vite，
Ant Design 提供组件，React Query 管服务端状态，Zustand 管 UI 状态，Recharts 画图。

## 跑起来

```bash
# 后端（终端 1）
./mvnw spring-boot:run           # 默认 8080

# 前端（终端 2）
cd frontend
npm install
npm run dev                      # http://localhost:5173
```

开发服务器把 `/v1` 和 `/actuator` 代理到 `localhost:8080`，所以前端代码里**没有 base URL**，
也不需要为本地开发开 CORS。

首次打开会提示「尚未配置凭证」。点右上角「凭证」：

| 输入 | 用途 | 从哪来 |
|---|---|---|
| 租户 API Key（`hk_…`） | 事件流 / 投递页 | `POST /v1/admin/tenants` 的响应里 `apiKey` |
| 运营令牌 | 租户 / 监控页 | `hookflow.admin.bootstrap-token`（默认 `hookflow-dev-bootstrap-token`） |

两个凭证分别对应后端的两个信任域，UI 不会拿租户 Key 去调 `/v1/admin/**` —— 那是一条
`403`，不是「权限不够的提示」。想预置默认值可以用 `.env.local`（见 `.env.example`），
但注意 `VITE_` 变量会被打进 bundle。

## 生产构建

```bash
npm run build:backend
```

构建后把 `dist/` 复制到 `src/main/resources/static/ui/`，后端从 `/ui/` 提供服务。
`GET /ui/events` 这类客户端路由由 `UiForwardController` 转发到 `index.html`，
所以刷新页面不会 404。`src/main/resources/static/ui/` 是构建产物，已加入 `.gitignore`。

**没有接进 Maven 构建**。`frontend-maven-plugin` 能让 `mvn package` 顺带构建前端，
但会给每次后端构建加上几分钟和一个 npm 网络依赖 —— 对一个还在改后端契约的项目，
这个代价不划算。需要时再加。

## 目录

```
src/
├── api/
│   ├── client.ts     # axios 实例、两个信任域的请求头、统一 ApiError
│   ├── queries.ts    # React Query hooks + 轮询间隔（集中一处）
│   ├── time.ts       # 时间与数字格式化（后端时间是 UTC 无时区）
│   ├── auth.ts       # 凭证存取（localStorage 优先于构建时默认值）
│   └── types.ts      # 手写的 wire 类型，逐个对应后端 DTO
├── components/       # AppShell / StatusTag / PageTitle / TokenDrawer / CredentialAlert
├── pages/            # 四个页面
├── stores/uiStore.ts # 时间窗口、凭证抽屉
├── test/setup.ts     # jsdom 缺口（matchMedia、ResizeObserver、getComputedStyle）
├── test/tenantFixtures.ts  # 租户页三个测试文件共用的夹具与 mock 默认值
└── theme.ts          # AntD token + 图表配色
```

## 几个刻意的选择

**轮询而不是 WebSocket。** 后端没有推送端点，事件流是「最近 N 条 + 分页」——对一条分页
查询每 2 秒轮询一次，比给每个浏览器连接维护订阅、背压和重连语义便宜得多。间隔集中在
`POLL_INTERVAL_MS`：事件流 2s（路线图要求 ≤3s），其余更慢，因为图表的分桶宽 15 分钟，
一秒重画一次只是每秒多一次分桶查询。

**服务端状态只在 React Query 里。** 不把 fetch 到的列表再拷一份进 Zustand —— 那是两个
事实来源开始互相矛盾的起点。Zustand 只放时间窗口和凭证抽屉这类纯 UI 状态。

**`new Date(...)` 是禁止的，走 `parseTimestamp`。** 后端序列化的是 UTC 但没有时区后缀，
直接 `new Date` 会按本地时间解析，在 Asia/Shanghai 下每行时间都偏 8 小时。这类 bug 通常
被算在服务端头上。

**Tailwind 关掉了 preflight。** Ant Design v5 自带 reset，叠加 Tailwind 的 preflight 会把
按钮内边距和表格边框重置成一堆难查的状态。这里 Tailwind 只负责布局 utility。

**测试用 Vitest 而不是 Jest。** 路线图写的是 Jest + RTL，RTL 是照用的；但 Vitest 复用
Vite 的转换管线，不用再配 babel/ts-jest 一套独立编译。`npm test` 直接跑。

## 校验

```bash
npm run typecheck    # tsc --noEmit
npm run lint         # eslint（含 react-hooks 规则）
npm test             # 60 个用例
npm run test:coverage
```

> **为什么慢**：AntD 在 jsdom 里渲染一个带抽屉/弹窗的页面要几秒，覆盖率插桩后再翻倍，
> 所以 `testTimeout` 是 30s 而不是默认的 5s（`vite.config.ts` 里有说明）。
>
> Vitest 只在**文件之间**并行，文件内部的用例是串行的 —— 所以页面测试按主题拆文件，
> 而不是把一套夹具和十个用例全塞进一个文件：`TenantsPage.list/keys/create.test.tsx`
> 三个文件能同时跑，共用 `src/test/tenantFixtures.ts`。拆完这套是 `npm test` 约 32s、
> `npm run test:coverage` 约 70s（拆之前 60s / 139s）。下一个瓶颈是
> `EndpointsPage.test.tsx`（插桩后约 63s）；要再快就按同样办法拆它，不必调超时。

## 还没做的

- **路由级代码分割**：目前按库拆了 vendor chunk（见 `vite.config.ts`），页面本身还没
  用 `React.lazy`。
- **投递详情页**：后端 `GET /v1/deliveries/{id}` 返回完整 attempt 历史，UI 暂时只在列表
  里显示最近一次错误。
- **API Key 的撤销是立即生效、不可逆的**：点下去之后用那把 Key 的客户端会在下一个请求
  收到 401。UI 用 Popconfirm 说明了这一点，但没有「宽限期」——后端也没做。
- **Endpoint 的删除**：后端也没有 DELETE，只有 pause/resume（路线图里的软删除尚未实现）。
- **重复 URL / 重名租户**：后端现在返回 `409 ALREADY_EXISTS`（见 `docs/api.md`），UI 直接把
  `message` 弹出来即可，不需要额外处理。
