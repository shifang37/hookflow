# ADR-0012：演示控制台打进应用里（静态页 + 条件 bean），不做独立前端工程

- 状态：已采纳（v0.3 之后）
- 日期：2026-10-22

## 背景

项目需要一个「能点、能看」的界面：

1. 一个**模拟业务系统**，持续向平台提交事件，否则演示只能手敲 curl；
2. 一块**面板**，让人看到事件变成投递、失败变成重试、积压变成 429，而不是听我讲。

硬约束：这个项目没有 Node 工具链，CI 只有 JDK + Docker，`./mvnw spring-boot:run` 是唯一的启动方式。
如果为了演示引入 `npm install` + Vite + 构建产物，那么「60 秒跑通」就不再成立。

## 选项

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 独立 SPA（React/Vite）+ 独立后端** | 前后端分离，独立部署 | 引入 Node 工具链和第二个进程；CORS；CI 多一步构建；演示从「一条命令」变成「三条命令 + 一个 node_modules」 |
| **B. 静态页 + 条件 bean，打进 jar** | `src/main/resources/static/demo/` 里放原生 HTML/CSS/JS；控制器 `@Profile("all")` + `@ConditionalOnProperty` | 没有组件化、没有类型检查（原生 JS）；前端逻辑只能靠手工验证 |
| **C. 模板引擎（Thymeleaf）+ 服务端渲染** | 加依赖，页面用模板 | 每秒刷新的实时面板用 SSR 反而更绕；还要为一个演示加一个模板依赖 |
| **D. 只做 CLI 演示脚本** | 扩展 `demo.ps1` | 已经是 v0.1 的做法，看不到「公平性 / 背压」这类随时间变化的量 |

## 决定

选 **B**。

- 页面是三个文件（`index.html` / `style.css` / `app.js`），没有构建步骤，跟着 jar 一起发；
- 数据全部来自一个 `GET /v1/demo/state`（一次轮询=一张一致的快照，页面不用自己拼接）；
- 后端只有条件 bean：`@Profile("all")` + `@ConditionalOnProperty(hookflow.demo.enabled)`；
- 模拟业务系统走**公开 HTTP API**（带真实 API Key），不是内部服务调用；
- 假客户的 HTTP 服务和模拟器都在同进程内，但**行为与外部进程等价**（真 HTTP、真签名校验）。

## 为什么不是 A

不是「前端工程不好」，而是**这个仓库的验收标准是 `./mvnw verify`**。一个需要 `npm ci && npm run build`
才能看到演示的方案，在 CI 里要么多一个 job，要么没人维护。演示的价值是「评审者 60 秒内看到真实行为」，
不是「展示前端技术栈」。

如果将来要做真正的管理 UI（多页面、鉴权、表格编辑），那就是 v0.4 的独立前端工程，届时应把
`/v1/demo/**` 整个删掉——它本来就是一个演示夹具，不是产品功能。

## 代价与已知问题

- **前端没有自动化测试**。状态接口有 `DemoConsoleIT`（5 个用例）覆盖，但页面本身的 DOM 渲染、
  按钮、场景脚本只做过手工验证。这是真实缺口，写在 [demo.md](demo.md) §5。
- **原生 JS 没有类型检查**，改字段名时页面会静默不更新。缓解：字段名只出现在 `DemoDtos` 和
  `app.js` 两处，且 `statePayloadIsComplete` 断言了字段集合。
- **无鉴权的 `/v1/demo/**`**。安全边界是「bean 不存在」，不是密码。任何人在 `all` profile 上
  打开这个端口都能点按钮——所以它只该跑在开发机上。
- **演示 profile 调低了水位线与退避**，数字与生产默认值不同（表格在 [demo.md](demo.md) §3）。
  这不是「隐藏配置」，而是演示必须的：10 000 的水位线点不出来。
- **「在 `all` 里默认打开」把集成测试也打开了**（真实踩到）：`all` 既是评审者的本地 profile，也是
  所有 IT 的 `@ActiveProfiles`，于是每个测试上下文都想绑 9199，第一个成功、其余 22 个报
  `Address already in use`，同时还在共享库里插了两个演示租户。修法不是把演示默认关掉（那会破坏
  「一条命令看到界面」），而是给测试加一个 `test` profile（`src/test/resources/application-test.yml`）
  把它关掉，需要它的 `DemoConsoleIT` 再用 `@SpringBootTest(properties=...)` 打开——内联属性的优先级高于
  profile 专属配置文件。

## 什么情况下会改这个决定

1. 需要多角色登录、审计、跨租户看板 → 独立前端工程，按 v0.4 的路线走。
2. 页面要给别人当产品用（而不是演示）→ 现在这套没有鉴权、没有权限模型，必须重做。
3. 前端逻辑继续变复杂（超过一个文件的量级）→ 至少引入构建与类型检查。

## 如何验证

- `DemoConsoleIT`（5 个用例）：租户与 endpoint 供给、模拟流量产生真实投递且接收方验签、
  5xx → 重试 → 死信 → 重放恢复、预算按钮改库、状态载荷字段完整。
- 截图（[demo-console.png](demo-console.png)、[demo-console-retries.png](demo-console-retries.png)）
  是用 headless Chrome 真实渲染出来的，不是手绘。
