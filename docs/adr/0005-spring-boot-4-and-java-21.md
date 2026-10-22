# ADR-0005：Spring Boot 4.1 + JDK 21

- 状态：已采纳（v0.1）
- 日期：2026-10-22

## 背景

国内 Java 后端岗位普遍使用 Spring Boot 2.7 / 3.x。作为作品集项目，选版本有两个相互冲突的诉求：

- **贴合招聘市场**：用面试官熟悉的版本，减少"这个版本我没用过"的隔阂
- **工程严谨性**：作品集如果建立在已停止安全更新的框架上，本身就是自相矛盾的

## 关键事实（2026-10 核查）

- Spring Boot 3.5.x 的 OSS 支持已于 **2026-06-30 结束**，3.5.16 是 3.5 系列最后一个 OSS 版本
- Spring Boot 4.1.1 是当前稳定版，要求 Java 17–26，Spring Framework 7.0.9
- Boot 4 把 starter 拆细了：`spring-boot-starter-web` → `spring-boot-starter-webmvc`，JSON 支持独立成 `spring-boot-starter-jackson`
- Boot 4 默认使用 **Jackson 3**（包名从 `com.fasterxml.jackson` 变为 `tools.jackson`），同时保留 Jackson 2 的 BOM 以兼容旧库
- MyBatis 官方 starter 的版本线已对齐：`4.1.x` 对应 Boot 4.1，`4.0.x` 对应 Boot 4.0
- springdoc-openapi `3.x` 是 Boot 4 对应的版本线

## 决定

**Spring Boot 4.1.1 + JDK 21 + MyBatis starter 4.1.0 + springdoc 3.1.1。**

## 为什么不退回 Boot 3.5

3.5 已 EOL。在一个主打"可靠性、可运维"的项目里用不接收安全补丁的框架，README 写再多理由也无法自洽——面试官只要问一句"为什么用停更版本"就破了。**宁可承担迁移成本，也不要承担这个叙事上的破绽。**

## 为什么是 JDK 21 而不是 17

- 21 是 LTS，且是 Boot 4.1 支持范围内的中间版本
- 可以使用 record（本项目 domain 与 DTO 全用 record）、`HexFormat`、增强的 `HttpClient`、模式匹配
- **但没有用虚拟线程**。原因见下。

## 关于虚拟线程的取舍

JDK 21 有虚拟线程，很吸引人。v0.1 **刻意不用**：

- worker 的并发上限真正需要的是"对下游施压的限流"，虚拟线程解决的是"线程数不够"，不解决"接收方扛不住"
- Tomcat + 虚拟线程 + RabbitMQ 消费者的交互还有细节需要验证（阻塞点、pinning）
- 先用**有界线程池 + 超时**把并发控制讲清楚，比"用了虚拟线程"更有说服力

v0.3 压测阶段会做一次对照实验：同一负载下平台线程池 vs 虚拟线程的 p99 与线程占用，用数据决定是否切换。

## 已知的迁移代价

| 变化 | 本项目的应对 |
|---|---|
| Jackson 3 包名 | 依赖注入 Boot 提供的 `tools.jackson.databind.ObjectMapper`，不直接 new |
| `HttpStatus.PAYLOAD_TOO_LARGE` 废弃 | 改用 `CONTENT_TOO_LARGE`（RFC 9110 命名） |
| starter 拆分 | web 用 `starter-webmvc`，其余不变 |
| **Flyway 自动配置独立成 `spring-boot-starter-flyway`** | 只加 `flyway-core` 会导致**迁移静默不执行**：没有报错，只是所有表都不存在，第一次查询才炸 `table doesn't exist`。这是本项目实际踩到的坑 |
| Testcontainers 2.x 重命名 artifact | `testcontainers-mysql` / `testcontainers-rabbitmq` / `testcontainers-junit-jupiter`，包名也变为 `org.testcontainers.mysql.*` |
| 多构造器 Bean 需显式 `@Autowired` | `SecretCipher` 有一个测试用构造器，必须标注主构造器，否则启动失败 |

这些细节本身就是面试可讲的内容：**"Boot 4 迁移我实际踩过哪些坑"比"我熟悉 Spring Boot"具体得多。**

## 面试时的风险与应对

风险：面试官不熟 Boot 4，可能觉得"你在用我没听过的东西做项目"。

应对：主动说明版本选择的理由，并强调"业务语义与 Boot 3 一致，差异集中在 starter 拆分和 Jackson 3"。同时保留 `pom.xml` 里的版本号可查，不掩饰。
