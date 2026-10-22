# ADR-0007：v0.1 不用 Spring Security，用自建 API Key 过滤器

- 状态：已采纳（v0.1）
- 日期：2026-10-22

## 背景

`/v1/**` 需要鉴权。可选方案有两个：引入 `spring-boot-starter-security` 并用 `SecurityFilterChain` 配置，或者写一个 `OncePerRequestFilter`。

## 决定

v0.1 用**自建的 `ApiKeyAuthFilter`**（约 60 行），不引入 Spring Security。

## 理由

1. **v0.1 只有一种凭证**：Bearer API Key。没有 session、没有表单登录、没有 OAuth2、没有 CSRF 面（纯无状态 JSON API）。Security 的价值在于"多种认证方式 + 授权模型 + 标准协议"，这些此刻都不存在。
2. **配置面积 vs 逻辑面积**：为了做一件事而配置一个框架的六七个组件，代码总量反而更大，而且新人（包括三个月后的自己）要理解的是框架的默认行为，不是业务。
3. **错误响应一致性**：本项目的错误体是统一的 `{code, error, message, requestId}`。Security 的 `AuthenticationEntryPoint` / `AccessDeniedHandler` 要额外适配才能保持一致，而过滤器里直接调 `ApiErrorWriter` 就完了。
4. **可测性**：过滤器是纯函数式的——取 header、查库、比较 hash、设置上下文。集成测试直接打 HTTP 即可。

## 关键实现约束（这才是安全性的来源）

- 只存 `SHA-256(secret)` 和 8 字符前缀，**数据库里没有明文凭证**
- 前缀唯一索引 → 鉴权是一次索引查询，不需要全表比对
- hash 比较用 `MessageDigest.isEqual`（常量时间），避免时序侧信道
- 令牌格式 `hk_<8字符前缀>_<32字符secret>`，格式错误立刻 401，不做多余查询
- 租户上下文放在 `ThreadLocal`，**在 finally 中清理**，避免线程池复用导致跨请求串号
- 管理员接口用独立的 `X-Bootstrap-Token`，与租户凭证是两个信任域

## 明确的技术债

**v0.4 引入 OAuth2 client credentials 时，必须迁到 Spring Security。** 触发条件：

- 需要多种凭证（OAuth2 / mTLS / 签名）
- 需要更细的授权模型（资源级、字段级）
- 需要标准的 `WWW-Authenticate` 交互

到那时 Security 的复杂度是**为了换取标准协议**，是值得的；现在不是。

## 代价

- 没有 Spring Security 生态的现成能力（审计、CSRF、session fixation 等）——本项目都不需要
- 未来迁移要重写鉴权链路，但边界清晰（只有一个过滤器 + 一个 principal），迁移成本可控
- 面试时可能被问"为什么不用 Spring Security"——这个 ADR 就是答案，而且比"我配了 Security"更能体现判断力
