# ADR-0015：控制台登录用 Spring Security 会话 + 角色，公共 API 保持手写 Key

- 状态：已采纳（v0.4）
- 日期：2026-11-05
- 相关：[ADR-0007](0007-hand-rolled-api-key-auth.md)（公共 API 的手写 Key 认证）

## 背景

管理后台的验收清单里唯一没做的大项是「有基础的权限控制（需登录）」。此前控制台用
`X-Bootstrap-Token` 读运营接口、用租户 API Key 读业务接口，两者都是**长期共享密钥**：

- 没有账号，无法知道是谁改的租户策略；
- 没有角色，任何拿到 token 的人都能建租户、撤 Key；
- token 存在浏览器的 localStorage 里，一个 XSS 就能读走；
- 没有会话，也就没有「退出登录」和「过期」这两个真实操作。

同时，公共 API `/v1/**` 的无状态 API Key 认证是**有意为之**的（ADR-0007）：它是给
SDK 和客户服务器用的，不应该被浏览器的 session、CSRF、重定向语义污染。

## 选项

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. Spring Security + 表单登录 + Session** | 服务端会话，`formLogin`，两个角色 | 引入框架；需要 CSRF、会话固定、过期处理 |
| **B. JWT** | 登录后发 token，前端存起来 | 无法即时撤销；存哪里都是 XSS 面；还要自己写刷新/轮换 |
| **C. OAuth2 / OIDC** | 接外部 IdP | 这个项目没有 IdP，演示和 CI 都多一个外部依赖 |
| **D. 继续用 Bootstrap Token** | 不做登录 | 验收项不满足，且共享密钥的问题一个没解决 |

## 决定

选 **A**，边界划在 `/ui/**` 与 `/v1/admin/**`：

1. **登录**：`POST /api/auth/login`（`application/x-www-form-urlencoded`）由
   `UsernamePasswordAuthenticationFilter` 处理；成功后服务端创建会话，Cookie 为
   `HttpOnly`、`SameSite=Lax`。前端不保存任何运营凭证。
2. **角色**：`ADMIN` 可读写运营接口；`VIEWER` 只读。矩阵在
   `ConsoleSecurityConfig` 里用 HTTP 方法表达（GET/HEAD 允许两种角色，其余要求
   `ADMIN`），控制器不再各自检查 header。
3. **机器调用**：`X-Bootstrap-Token` 仍然可用，由一个安全过滤器桥接成
   `ROLE_ADMIN` 的 `Authentication`。脚本、Helm smoke test、`curl` 不需要登录页。
4. **公共 API 不动**：`/v1/**` 继续走 `ApiKeyAuthFilter`（ADR-0007）。Spring Security
   的第三条链对其它路径 `permitAll`，并且禁用 CSRF，避免给 SDK 客户端增加一个
   它们不理解的握手。

## 为什么是 Session 而不是 JWT

- **撤销是即时的**：停用一个账号或降级角色，下次请求就生效；JWT 要等过期或维护黑名单。
- **凭证不落到 JavaScript**：`HttpOnly` Cookie 读不到，localStorage 里的 JWT 读得到。
- **这个控制台不需要横向扩展会话**：会话在单进程内存里；真要多副本时加 Spring Session +
  Redis 即可，不影响 API 的 stateless 性质。
- JWT 的优势在跨服务、跨域、无状态校验；这里只有一个前端和一个后端，代价大于收益。

## CSRF 与其它会话加固

Cookie 会话一旦存在，写请求就必须防 CSRF：

- 使用 Spring Security 7 的 `csrf.spa()`：`CookieCsrfTokenRepository`（`XSRF-TOKEN`
  可读）+ 同时接受表单里的 XOR token 和 `X-XSRF-TOKEN` 头。
- **只对携带 `X-Bootstrap-Token` 的请求豁免 CSRF**。跨站表单无法设置自定义头；
  而脚本本来就拿着一个 bearer-like 的机器密钥。会话请求（含 `PATCH`/`POST`）没有
  token 一律 403。
- 登录成功后 CSRF token 会轮换；前端在登录后重新 `GET /api/auth/session` 取新
  Cookie，否则下一次写会用到登录前的 token。
- 会话固定：认证时 `changeSessionId()`；超时 30 分钟（`CONSOLE_SESSION_TIMEOUT`）；
  HTTPS 部署应设 `CONSOLE_SESSION_COOKIE_SECURE=true`。

## 角色矩阵

| 接口 | `ADMIN` | `VIEWER` | `X-Bootstrap-Token` |
|---|---|---|---|
| `GET /v1/admin/**` | ✅ | ✅ | ✅（桥接为 ADMIN） |
| `POST/PATCH/DELETE /v1/admin/**` | ✅ | 403 | ✅ |
| `GET /ui/**` | 登录后 | 登录后 | —（浏览器会话） |

前端隐藏 VIEWER 的写按钮只是体验；真正的边界是服务端的 403。租户域
（`/v1/events`、`/v1/endpoints` 等）仍由租户 API Key 授权，和这里的角色是**两个信任域**：
拿到租户 Key 的 VIEWER 仍能写那个租户，这不是提权，因为他同时持有租户凭证。

## 明确的边界（没有做）

- **账号在内存里，来自配置**（`hookflow.console.users`），没有用户表、密码重置、
  登录失败锁定、MFA。生产化时应换成数据库或 IdP 的 `UserDetailsService`；当前实现
  已经把接口收在 `ConsoleUsers` 一处，替换成本可控。
- 没有「记住我」、没有多设备会话管理、没有审计「谁在什么时候登录」。
- 默认开发账号 `admin/admin123`、`viewer/viewer123` 只用于本地演示；任何部署都必须
  用 `HOOKFLOW_CONSOLE_ADMIN_PASSWORD` / `HOOKFLOW_CONSOLE_VIEWER_PASSWORD` 覆盖，
  或直接配置 `{bcrypt}...` 哈希。

## 验收

- `ConsoleAuthIT`（9 个用例，真实 servlet 链 + cookie jar）：匿名跳登录、静态资源可加载、
  会话探测、ADMIN 登录读写、VIEWER 只读、无 CSRF 的会话写被拒、Bootstrap Token 兼容、
  退出登录失效、坏密码 401、`/v1/events` 仍由 API Key 过滤器返回 401。
- `ConsoleUsersTest` / `ConsoleLoginHandlerTest` / `BootstrapTokenAuthenticationFilterTest`
  覆盖密码编码、配置校验、登录 JSON 与错误体、机器凭证桥接。
- 前端 `LoginPage` / `RequireAuth` / `SessionExpiryListener` / session API 均有测试；
  `npm run test:coverage` 的四项阈值仍通过。
- 全量 `./mvnw verify` 通过（185 个单元测试 + 82 个集成测试）。
