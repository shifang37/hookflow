# HookFlow 后续优化路线图

## 文档目的

本文档面向**校招/实习求职场景**，列出 HookFlow 项目的优化方向、技术选型和实施计划。
当前项目已具备**生产级后端架构**（后端 233 个测试、15 条 ADR、完整的故障恢复机制），
并在前端控制台、Java SDK、K8s 和监控上完成了大部分产品化工作。

> **状态同步（2026-10-30）**：以下勾选已按 2026-10-30 的 `master` 复核；
> 完成度速览见下一节。当前基线：后端 174 单测 + 71 集成、前端 83、Java SDK 66，
> 共 394 个测试；15 条 ADR。

**核心原则**：
- ✅ 优先提升**可视化**和**易用性**（面试时更容易展示）
- ✅ 选择**主流技术栈**（证明技术广度）
- ✅ 每个优化都要有**可衡量的目标**（避免过度设计）
- ❌ 不追求完美，保持项目的**核心优势**（系统设计深度）

---

## 优先级矩阵

| 优化项 | 全栈岗 | 后端岗 | 工作量 | ROI |
|--------|--------|--------|--------|-----|
| **P0：前端可视化** | 必做 | 推荐 | 3-5天 | ⭐⭐⭐⭐⭐ |
| **P0：管理后台** | 必做 | 推荐 | 5-7天 | ⭐⭐⭐⭐⭐ |
| **P1：Java SDK** | 推荐 | 必做 | 2-3天 | ⭐⭐⭐⭐ |
| **P1：K8s 部署** | 推荐 | 必做 | 2-3天 | ⭐⭐⭐⭐ |
| **P1：监控面板** | 推荐 | 必做 | 1-2天 | ⭐⭐⭐⭐ |
| **P2：CI/CD 完善** | 可选 | 推荐 | 2-3天 | ⭐⭐⭐ |
| **P2：Python SDK** | 可选 | 推荐 | 2天 | ⭐⭐⭐ |
| **P3：性能优化** | 可选 | 可选 | 1周 | ⭐⭐ |

---

## 完成度速览（2026-10-30）

| 优化项 | 状态 | 验收 | 说明 |
|---|---|---|---|
| P0 前端可视化 | ✅ 完成 | 6/6 | 5 个页面、2s 轮询、图表、覆盖率达标；390×844 视口已验收（非真机） |
| P0 管理后台 | 🟡 基本完成 | 3/5 | 主要 CRUD、租户停用、Endpoint 测试可在 UI 完成；无登录、租户硬删除、告警/设置 |
| P1 Java SDK | 🟢 已实现，未发布 | 5/6 | 66 测试、行覆盖 92.9%；未发布到 GitHub Packages / Maven Central |
| P1 K8s 部署 | ✅ 已完成 | 5/5 | Helm chart、HPA、探针、优雅停机，kind 实测 |
| P1 监控面板 | ✅ 已完成 | 4/4 | 3 个面板、2 条告警、compose `monitoring` profile |
| P2 CI/CD | 🟠 大部分完成 | 4/5 | CI / Docker / Helm 均已验证；Codecov 上传未启用 |
| P2 Python SDK | ❌ 未开始 | 0/6 | `sdk/python/` 不存在，workflow 仅是预置 |
| P3 性能优化 | 🟠 部分完成 | 3/4 | 多轮优化 + 报告；455–470/s，2000/s 目标未达 |

> 「验收」列是本节验收标准的勾选数；未勾选但已部分实现的条目在「说明」里写了缺口。

---

## P0：前端可视化（最高优先级）

> **状态（2026-10-30）**：基本完成。运营控制台在 [`frontend/`](../frontend/)，
> React 18 + TypeScript + Vite + Ant Design + React Query + Zustand + Recharts；
> 生产构建由后端从 `/ui/` 提供。5 个页面（事件流 / 投递 / Endpoints / 租户 / 系统监控）
> 都接真实接口，事件流 2s 轮询，前端 83 个测试、语句覆盖约 69%。
> 移动端按 390×844 视口验收（无整页横向滚动、侧栏折叠、Drawer 不超宽），见
> [mobile-acceptance.md](mobile-acceptance.md)；真机浏览器未测，原生 App 不在范围内。
> 单条投递详情抽屉已接（attempt 历史走 `GET /v1/deliveries/{id}`）。

### 立项时的现状问题（已解决）

- 演示控制台是纯 HTML + 原生 JS，无构建流程
- UI 样式简单，缺少现代感
- 没有复杂的交互（如实时图表、筛选、分页）

### 优化目标

构建一个**现代化单页应用**，作为 HookFlow 的官方 Web UI，展示：
1. 实时事件流（WebSocket 或轮询）
2. 投递状态可视化（成功率、重试次数、延迟分布）
3. 租户管理和 API Key 生成

### 技术选型

#### 方案 A：React + TypeScript（推荐，全栈岗必选）

**技术栈**：
```
前端框架：React 18 + TypeScript
构建工具：Vite (比 CRA 更快)
UI 组件库：Ant Design 或 shadcn/ui (更现代)
状态管理：Zustand (轻量) 或 Redux Toolkit
图表库：Recharts 或 ECharts
HTTP 客户端：Axios + React Query
CSS 方案：TailwindCSS
实时通信：WebSocket (SockJS) 或 SSE
```

**目录结构**：
```
frontend/
├── src/
│   ├── components/
│   │   ├── EventStream.tsx      # 实时事件流
│   │   ├── DeliveryTable.tsx    # 投递列表
│   │   ├── MetricsChart.tsx     # 指标图表
│   │   └── TenantManager.tsx    # 租户管理
│   ├── api/
│   │   └── hookflow.ts          # API 封装
│   ├── stores/
│   │   └── eventStore.ts        # 状态管理
│   ├── App.tsx
│   └── main.tsx
├── package.json
├── vite.config.ts
└── tsconfig.json
```

**关键功能实现**：

1. **实时事件流**
```typescript
// src/components/EventStream.tsx
import { useEffect, useState } from 'react';
import { Table, Tag, Badge } from 'antd';

interface Event {
  eventId: number;
  eventType: string;
  createdAt: string;
  deliveryCount: number;
}

export function EventStream() {
  const [events, setEvents] = useState<Event[]>([]);

  useEffect(() => {
    // 方案1: 轮询
    const interval = setInterval(async () => {
      const response = await fetch('/v1/events?limit=50');
      const data = await response.json();
      setEvents(data.events);
    }, 2000);

    // 方案2: WebSocket (需要后端支持)
    // const ws = new WebSocket('ws://localhost:8080/ws/events');
    // ws.onmessage = (event) => {
    //   const newEvent = JSON.parse(event.data);
    //   setEvents(prev => [newEvent, ...prev].slice(0, 50));
    // };

    return () => clearInterval(interval);
  }, []);

  return (
    <Table
      dataSource={events}
      columns={[
        { title: 'Event ID', dataIndex: 'eventId', key: 'eventId' },
        { title: 'Type', dataIndex: 'eventType', key: 'eventType' },
        { 
          title: 'Status', 
          dataIndex: 'deliveryCount',
          render: (count) => count > 0 ? 
            <Badge status="success" text="Delivered" /> : 
            <Badge status="processing" text="Pending" />
        },
        { title: 'Created', dataIndex: 'createdAt', key: 'createdAt' },
      ]}
      pagination={{ pageSize: 20 }}
    />
  );
}
```

2. **投递延迟分布图**
```typescript
// src/components/MetricsChart.tsx
import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend } from 'recharts';

export function MetricsChart() {
  const [data, setData] = useState([]);

  useEffect(() => {
    const fetchMetrics = async () => {
      const response = await fetch('/actuator/prometheus');
      const text = await response.text();
      
      // 解析 Prometheus 格式（或新增 JSON API）
      const parsed = parsePrometheusMetrics(text);
      setData(parsed);
    };

    fetchMetrics();
    const interval = setInterval(fetchMetrics, 5000);
    return () => clearInterval(interval);
  }, []);

  return (
    <LineChart width={800} height={400} data={data}>
      <CartesianGrid strokeDasharray="3 3" />
      <XAxis dataKey="timestamp" />
      <YAxis />
      <Tooltip />
      <Legend />
      <Line type="monotone" dataKey="deliverySucceeded" stroke="#52c41a" name="成功" />
      <Line type="monotone" dataKey="deliveryRetried" stroke="#faad14" name="重试" />
      <Line type="monotone" dataKey="deliveryDead" stroke="#f5222d" name="失败" />
    </LineChart>
  );
}
```

#### 方案 B：Vue 3 + TypeScript（备选）

如果更熟悉 Vue，可以选择：
```
前端框架：Vue 3 + Composition API + TypeScript
构建工具：Vite
UI 组件库：Element Plus 或 Naive UI
状态管理：Pinia
```

### 后端配套修改

需要新增以下 API：

```java
// src/main/java/dev/hookflow/api/DashboardController.java
@RestController
@RequestMapping("/v1/dashboard")
public class DashboardController {
    
    /**
     * 获取仪表板概览
     */
    @GetMapping("/overview")
    public DashboardOverview getOverview(@RequestParam(required = false) String range) {
        // 返回：总事件数、成功率、平均延迟、积压数
    }
    
    /**
     * 获取时间序列指标（图表用）
     */
    @GetMapping("/metrics/timeseries")
    public List<MetricPoint> getTimeSeries(
        @RequestParam String metric,  // "deliverySucceeded", "deliveryLatency"
        @RequestParam Long startTime,
        @RequestParam Long endTime,
        @RequestParam(defaultValue = "1m") String interval
    ) {
        // 从 Prometheus 或直接查 DB 聚合
    }
    
    /**
     * WebSocket 端点（可选）
     */
    @MessageMapping("/events")
    @SendTo("/topic/events")
    public Event streamEvent(Event event) {
        return event;
    }
}
```

**依赖添加**（如果使用 WebSocket）：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 集成方案

**方案1：独立前端项目（推荐）**
```bash
hookflow/
├── backend/           # 现有的 Spring Boot 项目
└── frontend/          # 新建的 React 项目
    ├── package.json
    └── src/
```

开发时：
```bash
# 终端 1
cd backend && ./mvnw spring-boot:run

# 终端 2
cd frontend && npm run dev  # 跑在 localhost:5173，代理到 8080
```

生产构建：
```bash
cd frontend && npm run build
# 将 dist/ 复制到 backend/src/main/resources/static/ui/
```

**方案2：嵌入 Spring Boot**（类似现在的 `/demo`）

修改 `pom.xml`：
```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.0</version>
    <executions>
        <execution>
            <id>install node and npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
        </execution>
        <execution>
            <id>npm install</id>
            <goals><goal>npm</goal></goals>
        </execution>
        <execution>
            <id>npm run build</id>
            <goals><goal>npm</goal></goals>
            <configuration>
                <arguments>run build</arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 验收标准

- [x] 使用 React + TypeScript + Vite 搭建
- [x] 至少包含 4 个页面：事件流、投递详情、租户管理、系统监控（另有 Endpoints 页；投递详情抽屉可看 attempt 历史）
- [x] 实时刷新（事件流 2s、投递 3s 轮询；满足 ≤3s）
- [x] 至少 1 个交互式图表（吞吐折线 + 延迟分布，Recharts）
- [x] 响应式设计，支持移动端查看 —— 390×844 视口实测：五个页面无整页横向滚动、侧栏折叠、Drawer 不超宽；记录见 [mobile-acceptance.md](mobile-acceptance.md)。真机浏览器未测
- [x] 单元测试覆盖率 ≥ 50%（Vitest + Testing Library；语句 69.16%，行 67.87%）

### 参考资料

- [Vite 官方文档](https://vitejs.dev/)
- [Ant Design Charts 示例](https://charts.ant.design/zh/examples)
- [Spring Boot WebSocket 指南](https://spring.io/guides/gs/messaging-stomp-websocket/)

---

## P0：管理后台

> **状态（2026-10-30）**：基本完成。租户、Endpoint、API Key、事件和监控都能在
> [`frontend/`](../frontend/) 操作；读接口与租户策略更新在
> [`AdminConsoleController.java`](../src/main/java/dev/hookflow/api/AdminConsoleController.java)，
> 开通与 Key 轮换在 [`AdminController.java`](../src/main/java/dev/hookflow/api/AdminController.java)。
> 租户停用/启用和 Endpoint 测试发送已支持；缺口：没有登录体系（用 Bootstrap Token +
> 租户 API Key），刻意不做租户硬删除，告警阈值和系统设置页未做。

### 立项时的现状问题（已解决）

- 只有 REST API，没有可视化管理界面
- 租户开通、API Key 生成需要用 curl
- 无法直观看到每个租户的使用情况

### 优化目标

构建一个**完整的管理后台**，支持：
1. 租户 CRUD 操作
2. API Key 生命周期管理（创建、查看前缀、撤销）
3. Endpoint 配置（订阅事件类型、暂停/恢复）
4. 统计报表（每租户事件量、成功率、Top 失败原因）

### 功能模块设计

```
管理后台
├── 租户管理
│   ├── 租户列表（搜索、分页）
│   ├── 创建租户（表单：name, rateLimit, maxInFlight）
│   ├── 租户详情（API Keys、Endpoints、统计）
│   └── 修改租户配置
├── Endpoint 管理
│   ├── Endpoint 列表（按租户过滤）
│   ├── 创建 Endpoint（URL、订阅类型、并发上限）
│   ├── 测试 Endpoint（发送测试 webhook）
│   └── 暂停/恢复/删除
├── 事件查询
│   ├── 高级筛选（租户、类型、时间范围、状态）
│   ├── 事件详情（payload、所有 attempts）
│   └── 批量重放
├── 监控大盘
│   ├── 系统级指标（QPS、积压、P99 延迟）
│   ├── 租户级指标（Top 10 by volume）
│   └── 告警配置（阈值设置）
└── 设置
    ├── 系统配置（全局限流、退避参数）
    └── 用户管理（管理员账号）
```

### 技术选型

**前端**：复用前面的 React 技术栈，增加以下库：
```json
{
  "dependencies": {
    "@tanstack/react-table": "^8.11.0",    // 强大的表格组件
    "react-hook-form": "^7.49.0",          // 表单验证
    "zod": "^3.22.0",                      // Schema 验证
    "react-router-dom": "^6.21.0"          // 路由
  }
}
```

**后端新增 API**：

```java
// src/main/java/dev/hookflow/api/admin/TenantAdminController.java
@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantAdminController {
    
    @GetMapping
    public Page<TenantDTO> listTenants(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        // 支持按名称搜索、分页
    }
    
    @GetMapping("/{id}/stats")
    public TenantStats getStats(
        @PathVariable Long id,
        @RequestParam(required = false) String range  // "7d", "30d", "90d"
    ) {
        return TenantStats.builder()
            .totalEvents(...)
            .successRate(...)
            .avgDeliveryLatency(...)
            .topFailureReasons(...)  // 按错误码分组
            .build();
    }
    
    @PatchMapping("/{id}")
    public TenantDTO updateTenant(
        @PathVariable Long id,
        @RequestBody @Valid TenantUpdateRequest request
    ) {
        // 允许修改 rateLimit、maxInFlight
    }
}

// src/main/java/dev/hookflow/api/admin/EndpointAdminController.java
@RestController
@RequestMapping("/v1/admin/endpoints")
public class EndpointAdminController {
    
    @PostMapping("/{id}/test")
    public TestResult testEndpoint(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        // 立即发送一个测试 webhook，不入库
        // 返回：响应时间、状态码、响应体
    }
    
    @DeleteMapping("/{id}")
    public void deleteEndpoint(@PathVariable Long id) {
        // 软删除：标记 endpoint.deleted_at，取消未完成的投递
    }
}
```

### 核心页面实现

**1. 租户列表页**

```typescript
// src/pages/Tenants.tsx
import { useQuery } from '@tanstack/react-query';
import { Table, Button, Input, Space } from 'antd';
import { useState } from 'react';

export function TenantsPage() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  
  const { data, isLoading } = useQuery({
    queryKey: ['tenants', search, page],
    queryFn: () => 
      fetch(`/v1/admin/tenants?search=${search}&page=${page}&size=20`)
        .then(res => res.json())
  });

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space>
        <Input.Search
          placeholder="搜索租户名称"
          onSearch={setSearch}
          style={{ width: 300 }}
        />
        <Button type="primary" onClick={() => navigate('/tenants/new')}>
          创建租户
        </Button>
      </Space>
      
      <Table
        dataSource={data?.content}
        loading={isLoading}
        pagination={{
          current: page + 1,
          pageSize: 20,
          total: data?.totalElements,
          onChange: (p) => setPage(p - 1)
        }}
        columns={[
          { title: 'ID', dataIndex: 'tenantId', width: 80 },
          { title: '名称', dataIndex: 'name' },
          { 
            title: '限流',
            dataIndex: 'rateLimit',
            render: (val) => `${val} events/min`
          },
          {
            title: '操作',
            render: (_, record) => (
              <Space>
                <Button size="small" onClick={() => navigate(`/tenants/${record.tenantId}`)}>
                  详情
                </Button>
                <Button size="small" danger>删除</Button>
              </Space>
            )
          }
        ]}
      />
    </Space>
  );
}
```

**2. 租户详情页（含统计）**

```typescript
// src/pages/TenantDetail.tsx
import { useParams } from 'react-router-dom';
import { Card, Descriptions, Statistic, Row, Col, List } from 'antd';
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons';

export function TenantDetailPage() {
  const { id } = useParams();
  
  const { data: tenant } = useQuery(['tenant', id], () =>
    fetch(`/v1/admin/tenants/${id}`).then(res => res.json())
  );
  
  const { data: stats } = useQuery(['tenantStats', id], () =>
    fetch(`/v1/admin/tenants/${id}/stats?range=7d`).then(res => res.json())
  );

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="租户信息">
        <Descriptions column={2}>
          <Descriptions.Item label="ID">{tenant?.tenantId}</Descriptions.Item>
          <Descriptions.Item label="名称">{tenant?.name}</Descriptions.Item>
          <Descriptions.Item label="限流">{tenant?.rateLimit} events/min</Descriptions.Item>
          <Descriptions.Item label="创建时间">{tenant?.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="7 天统计">
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title="总事件数"
              value={stats?.totalEvents}
              valueStyle={{ color: '#3f8600' }}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="成功率"
              value={stats?.successRate}
              precision={2}
              suffix="%"
              valueStyle={{ color: stats?.successRate > 95 ? '#3f8600' : '#cf1322' }}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="平均延迟"
              value={stats?.avgDeliveryLatency}
              suffix="ms"
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="当前积压"
              value={stats?.backlog}
            />
          </Col>
        </Row>
      </Card>

      <Card title="Top 失败原因">
        <List
          dataSource={stats?.topFailureReasons || []}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                title={item.errorCode}
                description={`${item.count} 次 (${item.percentage}%)`}
              />
            </List.Item>
          )}
        />
      </Card>

      <Card title="API Keys">
        {/* 表格展示，只显示前缀，有"生成新 Key"按钮 */}
      </Card>

      <Card title="Endpoints">
        {/* 表格展示，有"新增 Endpoint"按钮 */}
      </Card>
    </Space>
  );
}
```

**3. 创建租户表单**

```typescript
// src/pages/TenantCreate.tsx
import { Form, Input, InputNumber, Button, Card } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

export function TenantCreatePage() {
  const navigate = useNavigate();
  const [form] = Form.useForm();
  
  const createMutation = useMutation({
    mutationFn: (values) =>
      fetch('/v1/admin/tenants', {
        method: 'POST',
        headers: { 
          'Content-Type': 'application/json',
          'X-Bootstrap-Token': 'hookflow-dev-bootstrap-token'  // 实际应走登录态
        },
        body: JSON.stringify(values)
      }).then(res => res.json()),
    onSuccess: (data) => {
      // 显示 API Key（仅一次）
      Modal.info({
        title: '租户创建成功',
        content: (
          <div>
            <p>API Key（请妥善保存，仅显示一次）：</p>
            <Input.TextArea value={data.apiKey} readOnly rows={3} />
          </div>
        ),
        onOk: () => navigate(`/tenants/${data.tenantId}`)
      });
    }
  });

  return (
    <Card title="创建租户">
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => createMutation.mutate(values)}
        style={{ maxWidth: 600 }}
      >
        <Form.Item
          label="租户名称"
          name="name"
          rules={[{ required: true, message: '请输入租户名称' }]}
        >
          <Input placeholder="my-tenant" />
        </Form.Item>

        <Form.Item
          label="限流（events/分钟）"
          name="rateLimit"
          initialValue={100}
          rules={[{ required: true }]}
        >
          <InputNumber min={1} max={10000} style={{ width: '100%' }} />
        </Form.Item>

        <Form.Item
          label="并发预算（可选）"
          name="maxInFlight"
          tooltip="该租户同时在途的最大投递数，0 表示不限制"
        >
          <InputNumber min={0} max={1000} style={{ width: '100%' }} />
        </Form.Item>

        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" loading={createMutation.isLoading}>
              创建
            </Button>
            <Button onClick={() => navigate('/tenants')}>取消</Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>
  );
}
```

### 鉴权方案

管理后台需要登录保护，有两个方案：

**方案 A：Spring Security + Session（简单）**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```java
// src/main/java/dev/hookflow/config/SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/admin/**", "/ui/**").authenticated()
                .requestMatchers("/v1/events", "/v1/deliveries").permitAll()  // 业务 API 用 API Key
            )
            .formLogin(form -> form
                .loginPage("/ui/login")
                .defaultSuccessUrl("/ui/dashboard")
            )
            .logout(logout -> logout.logoutSuccessUrl("/ui/login"));
        
        return http.build();
    }
    
    @Bean
    public UserDetailsService userDetailsService() {
        // 先硬编码，后续可从 DB 读
        UserDetails admin = User.builder()
            .username("admin")
            .password("{noop}admin123")  // 生产环境用 BCrypt
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
```

**方案 B：JWT Token（前后端分离）**

前端登录后获取 JWT，后续请求带 `Authorization: Bearer <token>`。

### 验收标准

- [x] 至少 5 个页面：租户列表、租户详情、创建租户、Endpoint 管理、监控大盘（详情和创建是抽屉/弹窗，不是独立路由）
- [ ] 所有 CRUD 操作可通过 UI 完成，无需 curl —— 现有资源均可增删改查；租户停用/启用和 Endpoint 测试已支持，但租户硬删除（刻意不做）、告警/设置没有做
- [ ] 有基础的权限控制（需登录）—— 现在是 Bootstrap Token / 租户 API Key 鉴权，没有登录、账号和角色
- [x] 至少 1 个统计报表（租户级 + 系统级）
- [x] 表单有客户端和服务端双重验证

---

## P1：Java SDK

> **状态（2026-10-29）**：已实现，代码在 [`sdk/java/`](../sdk/java/)，包名 `dev.hookflow.sdk`，
> 是**独立的 Maven 项目**（自己的 `pom.xml`，不改根 pom）。下面「技术选型 / 项目结构 /
> 核心代码」是立项时的草案，和最终实现的差异：
> - **没有引入 Failsafe 3.3.2**：它的同步重试内部直接 `Thread.sleep`，无法注入测试用的
>   `Sleeper`（Plan J.5 明确要求「测试里不要真的 sleep」），也拿不到按响应覆盖延迟的钩子；
>   重试循环改为手写（`HookFlowClient.executeWithRetry`），行为按 J.3 的表格实现。
> - Jackson 用 **2.17.x**（不是草案里的 2.16，也不是服务端的 Jackson 3）。
> - 重试白名单额外包含 **429**（草案只写了 5xx）。
>
> 验收状态见本节末尾。

### 现状问题

- 用户需要手写 HTTP 请求
- 没有封装签名验证、幂等性、重试逻辑
- 增加用户接入成本

### 优化目标

提供一个**官方 Java SDK**，让用户 5 分钟内完成接入：

```java
// 1. 初始化客户端
HookFlowClient client = HookFlowClient.builder()
    .apiKey("hk_...")
    .baseUrl("https://hookflow.example.com")
    .build();

// 2. 发送事件
Event event = client.sendEvent(
    "order.paid",
    Map.of("orderId", 12345, "amount", 99.00),
    "order-12345-paid"  // 幂等键
);

// 3. 批量发送
BatchResult result = client.sendEvents(Arrays.asList(
    new EventRequest("order.paid", payload1, "key1"),
    new EventRequest("order.shipped", payload2, "key2")
));

// 4. 验证 Webhook 签名（接收方使用）
boolean valid = HookFlowWebhook.verify(
    requestBody,
    signature,
    timestamp,
    secret
);
```

### 技术选型

```xml
<dependencies>
    <!-- HTTP 客户端 -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>
    
    <!-- JSON 序列化 -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.16.0</version>
    </dependency>
    
    <!-- 重试机制 -->
    <dependency>
        <groupId>dev.failsafe</groupId>
        <artifactId>failsafe</artifactId>
        <version>3.3.2</version>
    </dependency>
</dependencies>
```

### 项目结构

```
hookflow-java-sdk/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/hookflow/sdk/
│   │           ├── HookFlowClient.java           # 主客户端
│   │           ├── HookFlowWebhook.java          # Webhook 工具
│   │           ├── models/
│   │           │   ├── Event.java
│   │           │   ├── EventRequest.java
│   │           │   ├── BatchResult.java
│   │           │   └── Delivery.java
│   │           ├── exceptions/
│   │           │   ├── HookFlowException.java
│   │           │   ├── RateLimitException.java
│   │           │   └── IdempotencyException.java
│   │           └── internal/
│   │               ├── HttpClient.java
│   │               └── CryptoUtils.java
│   └── test/
│       └── java/
│           └── com/hookflow/sdk/
│               ├── HookFlowClientTest.java
│               └── HookFlowWebhookTest.java
├── pom.xml
└── README.md
```

### 核心代码实现

**1. 客户端主类**

```java
// src/main/java/com/hookflow/sdk/HookFlowClient.java
package com.hookflow.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HookFlowClient {
    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy<Response> retryPolicy;

    private HookFlowClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.objectMapper = new ObjectMapper();
        
        // 配置 HTTP 客户端
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(builder.connectTimeout))
            .readTimeout(Duration.ofSeconds(builder.readTimeout))
            .build();
        
        // 配置重试策略（仅对网络错误和 5xx 重试）
        this.retryPolicy = RetryPolicy.<Response>builder()
            .handle(IOException.class)
            .handleResultIf(response -> response.code() >= 500)
            .withDelay(Duration.ofSeconds(1))
            .withMaxRetries(builder.maxRetries)
            .build();
    }

    /**
     * 发送单个事件
     *
     * @param eventType 事件类型，如 "order.paid"
     * @param payload 事件负载
     * @param idempotencyKey 幂等键，必填
     * @return 创建的事件对象
     * @throws HookFlowException 如果请求失败
     */
    public Event sendEvent(String eventType, Map<String, Object> payload, String idempotencyKey) 
            throws HookFlowException {
        
        EventRequest request = new EventRequest(eventType, payload);
        
        RequestBody body = RequestBody.create(
            toJson(request),
            MediaType.get("application/json")
        );
        
        Request httpRequest = new Request.Builder()
            .url(baseUrl + "/v1/events")
            .header("Authorization", "Bearer " + apiKey)
            .header("Idempotency-Key", idempotencyKey)
            .post(body)
            .build();
        
        try (Response response = executeWithRetry(httpRequest)) {
            if (response.isSuccessful()) {
                return objectMapper.readValue(response.body().string(), Event.class);
            } else {
                handleErrorResponse(response);
                return null;  // never reached
            }
        } catch (IOException e) {
            throw new HookFlowException("Failed to send event", e);
        }
    }

    /**
     * 批量发送事件（单个事务）
     *
     * @param requests 事件请求列表，最多 500 条
     * @return 批量结果，包含每个事件的 ID 和幂等重放标记
     */
    public BatchResult sendEvents(List<EventRequest> requests) throws HookFlowException {
        if (requests.size() > 500) {
            throw new IllegalArgumentException("Batch size cannot exceed 500");
        }
        
        RequestBody body = RequestBody.create(
            toJson(Map.of("events", requests)),
            MediaType.get("application/json")
        );
        
        Request httpRequest = new Request.Builder()
            .url(baseUrl + "/v1/events/batch")
            .header("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();
        
        try (Response response = executeWithRetry(httpRequest)) {
            if (response.isSuccessful()) {
                return objectMapper.readValue(response.body().string(), BatchResult.class);
            } else {
                handleErrorResponse(response);
                return null;
            }
        } catch (IOException e) {
            throw new HookFlowException("Failed to send batch events", e);
        }
    }

    /**
     * 查询投递状态
     *
     * @param deliveryId 投递 ID
     * @return 投递详情，包含所有尝试记录
     */
    public Delivery getDelivery(Long deliveryId) throws HookFlowException {
        Request request = new Request.Builder()
            .url(baseUrl + "/v1/deliveries/" + deliveryId)
            .header("Authorization", "Bearer " + apiKey)
            .get()
            .build();
        
        try (Response response = executeWithRetry(request)) {
            if (response.isSuccessful()) {
                return objectMapper.readValue(response.body().string(), Delivery.class);
            } else {
                handleErrorResponse(response);
                return null;
            }
        } catch (IOException e) {
            throw new HookFlowException("Failed to get delivery", e);
        }
    }

    /**
     * 重放死信投递
     *
     * @param deliveryId 投递 ID
     */
    public void replayDelivery(Long deliveryId) throws HookFlowException {
        Request request = new Request.Builder()
            .url(baseUrl + "/v1/deliveries/" + deliveryId + "/replay")
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create("", null))
            .build();
        
        try (Response response = executeWithRetry(request)) {
            if (!response.isSuccessful()) {
                handleErrorResponse(response);
            }
        } catch (IOException e) {
            throw new HookFlowException("Failed to replay delivery", e);
        }
    }

    // ===== 私有辅助方法 =====

    private Response executeWithRetry(Request request) throws IOException {
        return Failsafe.with(retryPolicy)
            .get(() -> httpClient.newCall(request).execute());
    }

    private void handleErrorResponse(Response response) throws HookFlowException, IOException {
        String body = response.body() != null ? response.body().string() : "";
        
        switch (response.code()) {
            case 429:
                String retryAfter = response.header("Retry-After", "5");
                throw new RateLimitException(
                    "Rate limit exceeded, retry after " + retryAfter + " seconds",
                    Integer.parseInt(retryAfter)
                );
            case 409:
                throw new IdempotencyException("Idempotency key conflict: " + body);
            case 503:
                throw new HookFlowException("Service overloaded: " + body);
            default:
                throw new HookFlowException(
                    "HTTP " + response.code() + ": " + body
                );
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }

    // ===== Builder =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl = "http://localhost:8080";
        private int connectTimeout = 10;
        private int readTimeout = 30;
        private int maxRetries = 3;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl.replaceAll("/$", "");  // 去掉尾部斜杠
            return this;
        }

        public Builder connectTimeout(int seconds) {
            this.connectTimeout = seconds;
            return this;
        }

        public Builder readTimeout(int seconds) {
            this.readTimeout = seconds;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public HookFlowClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new HookFlowClient(this);
        }
    }
}
```

**2. Webhook 验证工具**

```java
// src/main/java/com/hookflow/sdk/HookFlowWebhook.java
package com.hookflow.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 工具类：验证 HookFlow 发送的 Webhook 签名。
 *
 * 签名串是 "v1:eventId:deliveryId:timestampSeconds:rawBody"，header 是 "v1=<hex>"。
 * 生产接入请直接用官方 SDK 的 HookFlowWebhook（见 sdk/java），这里保留草稿是为了
 * 说明协议；SDK 已经处理了大小写不敏感的 header、毫秒时间戳与常量时间比较。
 */
public class HookFlowWebhook {
    
    /**
     * 验证 Webhook 签名。
     *
     * @param rawBody 收到的原始字节（不能反序列化后再 toString）
     * @param signature HTTP 头 X-HookFlow-Signature 的值
     * @param timestamp HTTP 头 X-HookFlow-Timestamp 的值（epoch 秒）
     * @param eventId HTTP 头 X-HookFlow-Event-Id 的值
     * @param deliveryId HTTP 头 X-HookFlow-Delivery-Id 的值
     * @param secret Endpoint 的签名密钥
     * @return 签名是否有效
     */
    public static boolean verify(byte[] rawBody, String signature, String timestamp,
                                 long eventId, long deliveryId, String secret) {
        // 1. 防重放攻击：检查时间戳（5 分钟内有效）
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > 300) {  // 5 minutes
            return false;
        }
        
        // 2. 计算期望签名：HMAC-SHA256(secret, "v1:eventId:deliveryId:timestamp:rawBody")
        String signedPrefix = "v1:" + eventId + ":" + deliveryId + ":" + ts + ":";
        String expectedSignature = "v1=" + HexFormat.of().formatHex(
            computeHMAC(signedPrefix.getBytes(StandardCharsets.UTF_8), rawBody, secret));
        
        // 3. 时间安全的比较（比较完整的 "v1=<hex>"，不是裸 hex）
        return MessageDigest.isEqual(
            signature.trim().getBytes(StandardCharsets.UTF_8),
            expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }
    
    /**
     * 计算 HMAC-SHA256 签名
     */
    private static byte[] computeHMAC(byte[] prefix, byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            );
            mac.init(secretKey);
            mac.update(prefix);
            mac.update(body);
            return mac.doFinal();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
    
    /**
     * Spring Boot 控制器示例
     */
    // @PostMapping("/webhooks/hookflow")
    // public ResponseEntity<Void> handleWebhook(
    //     @RequestBody byte[] body,
    //     @RequestHeader("X-HookFlow-Signature") String signature,
    //     @RequestHeader("X-HookFlow-Timestamp") String timestamp,
    //     @RequestHeader("X-HookFlow-Event-Id") long eventId,
    //     @RequestHeader("X-HookFlow-Delivery-Id") long deliveryId
    // ) {
    //     String secret = "whsec_...";  // 从配置读取
    //     
    //     if (!HookFlowWebhook.verify(body, signature, timestamp, eventId, deliveryId, secret)) {
    //         return ResponseEntity.status(401).build();
    //     }
    //     
    //     // 处理事件...
    //     return ResponseEntity.ok().build();
    // }
}
```

### 验收标准

- [x] 支持单条/批量发送事件
- [x] 支持幂等性和自动重试（客户端生成/复用 `Idempotency-Key`，重试重发同一份字节）
- [x] 提供 Webhook 签名验证工具（时间戳窗口 + 常量时间比较）
- [x] 有完整的 JavaDoc 和 README（`mvn javadoc:javadoc` 无 error）
- [x] 单元测试覆盖率 ≥ 80%（JaCoCo **LINE 92.9%**，66 个测试）
- [ ] 发布到 Maven Central 或 JitPack —— **未发布**。本计划改为 GitHub Packages：
      tag `sdk-v*` 触发 [`.github/workflows/sdk-publish.yml`](../.github/workflows/sdk-publish.yml)，
      但还没有实际 deploy 产物；`mvn install` + 一个独立的临时项目引用并跑通 `sendEvent` 已验证。

### 发布流程

```bash
# 在仓库根目录。SDK 是独立项目，构建命令不会碰根 pom。
mvn -f sdk/java/pom.xml -B verify
mvn -f sdk/java/pom.xml source:jar javadoc:jar install

# 发布到 GitHub Packages（需要 packages: write 的 token，CI 里用 GITHUB_TOKEN）
git tag sdk-v0.1.0
git push origin sdk-v0.1.0
```

---

## P1：K8s 部署

> **状态（2026-10-30）**：已完成。chart 在 [`helm/hookflow/`](../helm/hookflow/)，
> 三角色同镜像（api/relay/worker），kind 上实测滚动更新不丢投递、force-kill worker 后
> lease 回收重投、HPA 1→3。部署/验收细节见 [helm/hookflow/README.md](../helm/hookflow/README.md)。
> 有状态依赖不在 chart 里；kind 演示用 `deploy/kind/dependencies.yaml`。

### 现状问题

- 只有 Docker Compose 方案，无法水平扩展
- 没有生产级编排和健康检查
- 无法演示云原生技术栈

### 优化目标

提供**完整的 Kubernetes 部署方案**，支持：
1. Deployment + Service + Ingress
2. 水平自动扩展（HPA）
3. ConfigMap/Secret 管理配置
4. 健康检查和滚动更新
5. Helm Chart 打包

### 技术选型

```yaml
容器编排：Kubernetes 1.28+
配置管理：Helm 3
数据库：PostgreSQL Operator (Zalando) 或 Bitnami Chart
消息队列：RabbitMQ Operator
```

### 部署架构

```
┌─────────────────────────────────────────┐
│            Ingress (Nginx)              │
│   hookflow.example.com -> Service       │
└─────────────────────────────────────────┘
                    │
        ┌───────────┴──────────┐
        │   Service (ClusterIP) │
        │   hookflow-service    │
        └───────────┬──────────┘
                    │
        ┌───────────┴──────────────┐
        │ Deployment (3 replicas)  │
        │   hookflow-app           │
        │  ┌──────┐  ┌──────┐     │
        │  │ Pod1 │  │ Pod2 │ ... │
        │  └──────┘  └──────┘     │
        └──────────────────────────┘
                    │
        ┌───────────┴──────────┐
        │ StatefulSet          │
        │   postgres + rabbitmq │
        └──────────────────────┘
```

### Kubernetes 资源文件

#### 1. Namespace

```yaml
# k8s/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: hookflow
  labels:
    name: hookflow
```

#### 2. ConfigMap

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: hookflow-config
  namespace: hookflow
data:
  application.yaml: |
    server:
      port: 8080
    spring:
      datasource:
        url: jdbc:postgresql://postgres:5432/hookflow
        username: hookflow
      rabbitmq:
        host: rabbitmq
        port: 5672
        username: hookflow
    hookflow:
      relay:
        batchSize: 20
        maxConcurrency: 50
      worker:
        maxConcurrency: 100
```

#### 3. Secret

```yaml
# k8s/secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: hookflow-secret
  namespace: hookflow
type: Opaque
data:
  # 生成：echo -n 'your-password' | base64
  db-password: aG9va2Zsb3ctcGFzcw==
  rabbitmq-password: aG9va2Zsb3ctcGFzcw==
  bootstrap-token: aG9va2Zsb3ctYm9vdHN0cmFwLXRva2Vu
```

#### 4. Deployment

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hookflow
  namespace: hookflow
  labels:
    app: hookflow
spec:
  replicas: 3
  selector:
    matchLabels:
      app: hookflow
  template:
    metadata:
      labels:
        app: hookflow
    spec:
      containers:
      - name: hookflow
        image: ghcr.io/<your-username>/hookflow:latest
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
          name: http
        # Actuator 与业务共用 8080：application.yml 未配置 management.server.port。
        # 若要拆出独立管理端口，先设 management.server.port: 9090，再把探针改回 9090。
        
        # 环境变量
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: hookflow-secret
              key: db-password
        - name: SPRING_RABBITMQ_PASSWORD
          valueFrom:
            secretKeyRef:
              name: hookflow-secret
              key: rabbitmq-password
        - name: HOOKFLOW_BOOTSTRAP_TOKEN
          valueFrom:
            secretKeyRef:
              name: hookflow-secret
              key: bootstrap-token
        
        # 挂载配置文件
        volumeMounts:
        - name: config
          mountPath: /app/config
          readOnly: true
        
        # 资源限制
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        
        # 健康检查
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: http
          initialDelaySeconds: 60
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: http
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
      
      volumes:
      - name: config
        configMap:
          name: hookflow-config
      
      # 优雅关闭
      terminationGracePeriodSeconds: 30
```

#### 5. Service

```yaml
# k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: hookflow
  namespace: hookflow
  labels:
    app: hookflow
spec:
  type: ClusterIP
  selector:
    app: hookflow
  ports:
  - name: http
    port: 8080
    targetPort: 8080
    protocol: TCP
```

#### 6. Ingress

```yaml
# k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hookflow
  namespace: hookflow
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - hookflow.example.com
    secretName: hookflow-tls
  rules:
  - host: hookflow.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: hookflow
            port:
              number: 8080
```

#### 7. HPA（水平自动扩展）

```yaml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: hookflow
  namespace: hookflow
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: hookflow
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 5 分钟内不重复缩容
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
```

### Helm Chart 封装

```bash
# 创建 Chart 骨架
helm create hookflow

# 目录结构
hookflow/
├── Chart.yaml          # Chart 元信息
├── values.yaml         # 默认配置
├── values-prod.yaml    # 生产环境覆盖
└── templates/
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── configmap.yaml
    ├── secret.yaml
    ├── hpa.yaml
    └── _helpers.tpl
```

**Chart.yaml**：
```yaml
apiVersion: v2
name: hookflow
description: A production-grade webhook delivery platform
type: application
version: 1.0.0
appVersion: "0.4.0"
keywords:
  - webhook
  - event-delivery
  - microservices
maintainers:
  - name: shifang37
    email: your-email@example.com
```

**values.yaml**（精简版）：
```yaml
replicaCount: 3

image:
  repository: ghcr.io/<username>/hookflow
  pullPolicy: Always
  tag: "latest"

resources:
  limits:
    cpu: 1000m
    memory: 1Gi
  requests:
    cpu: 250m
    memory: 512Mi

autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: hookflow.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: hookflow-tls
      hosts:
        - hookflow.example.com

postgresql:
  enabled: true
  auth:
    username: hookflow
    password: hookflow-pass
    database: hookflow

rabbitmq:
  enabled: true
  auth:
    username: hookflow
    password: hookflow-pass
```

### 部署命令

```bash
# 1. 创建命名空间
kubectl create namespace hookflow

# 2. 添加依赖 Chart
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# 3. 安装（开发环境）
helm install hookflow ./hookflow \
  --namespace hookflow \
  --create-namespace

# 4. 安装（生产环境）
helm install hookflow ./hookflow \
  --namespace hookflow \
  --values values-prod.yaml \
  --set image.tag=v1.0.0

# 5. 升级
helm upgrade hookflow ./hookflow \
  --namespace hookflow \
  --values values-prod.yaml

# 6. 回滚
helm rollback hookflow 1 --namespace hookflow

# 7. 卸载
helm uninstall hookflow --namespace hookflow
```

### 验收标准

- [x] 支持 Helm 一键部署（`helm install ... -f values-dev.yaml`，kind 实测）
- [x] 有完整的 README 和部署文档（[helm/hookflow/README.md](../helm/hookflow/README.md)）
- [x] 支持 HPA 自动扩缩容（CPU 70%，kind 上 1→3）
- [x] 健康检查和优雅关闭（readiness/liveness/startup + `server.shutdown=graceful` + 75s grace）
- [x] 至少 2 种环境配置（`values-dev.yaml` / `values-prod.yaml`）

---

## P1：监控面板

> **状态（2026-10-29）**：已完成。Prometheus + Grafana 走 compose 的 `monitoring` profile，
> 3 个预装面板（概览 / 投递详情 / JVM 与资源）和 2 条告警规则；两个指标缺口已补齐：
> `hookflow.delivery.duration`（Timer + percentile histogram）与
> `hookflow.delivery.attempted`（成功率分母）。怎么起、每个面板的口径、告警怎么响见
> [monitoring.md](monitoring.md)。

### 现状问题

- Prometheus 指标已暴露，但没有可视化
- 无法直观看到系统运行状态
- 面试时难以展示系统实时表现

### 优化目标

搭建一个**完整的监控体系**：
1. Grafana 仪表板（至少 3 个面板）
2. 关键指标告警
3. 预构建的仪表板 JSON（开箱即用）

### 技术选型

```
监控存储：Prometheus
可视化：Grafana
告警：Alertmanager（可选）
```

### Grafana 仪表板设计

#### 面板 1：系统概览

**指标**：
- 总事件数（24h）
- 投递成功率
- 平均投递延迟（P50/P95/P99）
- 当前积压数
- QPS（每秒事件数）

**Prometheus 查询**：
```promql
# 总事件数（24h）
increase(hookflow_outbox_published_total[24h])

# QPS（每秒事件数）——代码没有单独的 ingest 计数，出队发布速率是最接近的代理
sum(rate(hookflow_outbox_published_total[1m]))

# 成功率（已结算口径：成功 / (成功 + 死信)）
sum(rate(hookflow_delivery_succeeded_total[5m]))
  /
(sum(rate(hookflow_delivery_succeeded_total[5m]))
  + sum(rate(hookflow_delivery_dead_total[5m])))

# P95 延迟 —— 需先加指标，见下方「延迟直方图的缺口」
histogram_quantile(0.95,
  sum by (le) (rate(hookflow_delivery_duration_seconds_bucket[5m]))
)

# 积压（只有 outbox 侧有 gauge；worker 侧队列深度尚未暴露）
hookflow_outbox_backlog
```

> **指标口径说明**：代码里的 Micrometer 名用点号（`hookflow.delivery.succeeded`），
> 经 Prometheus registry 输出后变成下划线并给计数器补 `_total`
> （`hookflow_delivery_succeeded_total`）。查询时以 Prometheus 侧的名字为准。
>
> **延迟直方图（已补）**：`DeliveryProcessor` 现在注册
> `Timer.builder("hookflow.delivery.duration").publishPercentileHistogram()`，每次 settle 记录一次，
> Prometheus 侧产出 `hookflow_delivery_duration_seconds_bucket`。
>
> **总投递数（已补）**：`hookflow.delivery.attempted` 在每次真正发出 HTTP 的尝试前 +1，作为
> 「尝试成功率」的分母。注意它**不是**终态投递数：一次死信会消耗最多 8 次尝试，所以死信率用
> `dead / (dead + succeeded)`，不用 `attempted` 作分母。

#### 面板 2：投递详情

**指标**：
- 投递状态分布（饼图：成功/重试/死信）
- Top 失败 Endpoint（表格）
- 重试次数分布（直方图）
- 每租户吞吐量（堆叠面积图）

#### 面板 3：资源使用

**指标**：
- JVM 堆内存使用率
- GC 暂停时间
- 线程数（relay/worker）
- 数据库连接池使用率
- RabbitMQ 队列深度

### Grafana Dashboard JSON

创建预构建的仪表板配置文件（详细配置参考 Grafana 文档），至少覆盖上面 3 个面板。

### Docker Compose 集成

更新 `compose.yaml`（仓库里就是这个文件名，不是 `docker-compose.yml`），加入 Prometheus 和 Grafana（详细配置略）。

### 验收标准

- [x] 至少 3 个 Grafana 仪表板（概览 / 投递详情 / JVM 与资源，JSON 在 `monitoring/grafana/dashboards/`）
- [x] 包含预构建的 JSON 配置（开箱即用，数据源用 `${DS_PROMETHEUS}` 变量）
- [x] 至少 2 条告警规则（outbox 积压持续增长、死信率 > 10%）
- [x] 有监控搭建文档（[monitoring.md](monitoring.md)）

---

## P2：CI/CD 完善

> **状态（2026-10-30）**：大部分完成，见下方「实现位置」。`ci` run #22 与任务 U 后的 run #26
> 均绿；`docker` run 已成功构建并推送 GHCR；`helm-v0.1.0` 的 `helm-publish` run #1 已发布
> OCI chart。**只剩 Codecov 上传没有验证**（没有 token，步骤按设计跳过），不要标成已绿。

### 现状问题（v0.4 之前）

- 没有自动化构建和测试流程
- 缺少代码质量检查
- 无 Docker 镜像自动发布

### 优化目标

建立**完整的 CI/CD 流水线**：
1. 自动化测试和代码检查
2. Docker 镜像自动构建和推送
3. Helm Chart 自动发布

### GitHub Actions 工作流

创建 `.github/workflows/ci.yml` 和 `.github/workflows/docker.yml` 实现自动化构建（详细配置参考 GitHub Actions 文档）。

### 验收标准

- [x] 每次 push 自动运行测试 —— `ci.yml`（push `master`/`main` 与所有 PR；后端单测 + `verify`、前端 typecheck/lint/test/build）。
      run #22 与任务 U 后的 run #26 均绿；build 曾因 `BatchReplayIT.cleanUp` 的 FK/并发 flake 失败一次，重跑通过。前端 exit 1
      曾由 Vitest 3 的 `onTaskUpdate` unhandled error 引起（77 个测试其实全过），已升级 Vitest 4.1.12
- [ ] 覆盖率报告自动上传到 Codecov —— `ci.yml` 的「Unit tests with JaCoCo」+「Upload coverage to Codecov」。
      **上传未启用**：仓库没有 `CODECOV_TOKEN`，步骤打印原因后跳过（`fail_ci_if_error: false`），
      因此这条不能算完成
- [x] master 分支自动构建 Docker 镜像 —— `docker.yml`（push `master` / tag `v*`，先 `npm run build:backend`
      再 buildx 推 `ghcr.io/shifang37/hookflow`）。**run #8 已成功构建并推送 GHCR**
- [x] 打 tag 自动发布 Helm Chart —— `helm-publish.yml`（tag `helm-v*`，`helm package --version <tag>` 后推
      `oci://ghcr.io/shifang37/charts`）。**`helm-v0.1.0` run #1 已成功推送，chart 可匿名拉取**
- [x] README 显示 CI 状态徽章 —— 换成 GitHub Actions 实时徽章（CI / Docker / Helm / Java SDK）

### 实现位置

| 内容 | 文件 |
|---|---|
| 测试 + 覆盖率采集 + Codecov 步骤 | `.github/workflows/ci.yml`，根 `pom.xml` 的 `coverage` profile |
| 镜像构建与推送 | `.github/workflows/docker.yml` |
| Chart 校验（lint/render） | `.github/workflows/helm.yml` |
| Chart 发布（OCI） | `.github/workflows/helm-publish.yml` |
| Java SDK | `.github/workflows/sdk.yml`（发布：`sdk-publish.yml`） |
| Python SDK（预置） | `.github/workflows/python-sdk.yml`；`sdk/python/` 还不存在，路径过滤使它现在不会运行 |

本机能验证的是：`./mvnw -o -Pcoverage test`（174 个通过，LINE 28.3%，仅单测）、
`helm package`/`helm lint`、前端 81 个测试与 `npm run build:backend`。
`docker build` 在本机卡在容器内的 Maven `dependency:go-offline`（网络），本地镜像没有构建完成；
但 **GitHub Actions 的 `docker` run #8 已经成功构建并推送 GHCR**。
Helm OCI 也已在 Actions 上验证（`helm-v0.1.0`）；只剩 Codecov 上传未验证，本地无法伪造。

---

## P2：Python SDK

> **状态（2026-10-30）**：未开始。`sdk/python/` 不存在；[`python-sdk.yml`](../.github/workflows/python-sdk.yml)
> 只是预置，路径过滤使它现在不会运行。以下 6 条验收全部未完成。

### 现状问题

- 只有 Java SDK，限制了用户群体
- Python 生态用户需要手写 HTTP 请求

### 优化目标

提供**官方 Python SDK**，支持异步和同步两种模式：

```python
# 同步模式
from hookflow import HookFlowClient

client = HookFlowClient(api_key="hk_...", base_url="https://hookflow.example.com")
event = client.send_event(
    event_type="order.paid",
    payload={"orderId": 12345, "amount": 99.00},
    idempotency_key="order-12345-paid"
)

# 异步模式
import asyncio
from hookflow import AsyncHookFlowClient

async def main():
    client = AsyncHookFlowClient(api_key="hk_...")
    event = await client.send_event("order.paid", {...})

asyncio.run(main())
```

### 项目结构

```
hookflow-python-sdk/
├── hookflow/
│   ├── __init__.py
│   ├── client.py           # 同步客户端
│   ├── async_client.py     # 异步客户端
│   ├── webhook.py          # Webhook 验证
│   ├── models.py           # 数据模型
│   └── exceptions.py       # 异常定义
├── tests/
│   ├── test_client.py
│   ├── test_async_client.py
│   └── test_webhook.py
├── pyproject.toml
├── README.md
└── examples/
    ├── basic_usage.py
    └── fastapi_webhook.py
```

### 验收标准

- [ ] 支持同步和异步两种模式
- [ ] 提供 Webhook 验证工具
- [ ] 类型提示完整（mypy 检查通过）
- [ ] 单元测试覆盖率 ≥ 80%
- [ ] 发布到 PyPI
- [ ] 有完整的 README 和示例代码

---

## P3：性能优化

> **状态（2026-10-30）**：部分完成。已做至少 5 轮优化（A2 批量入口、B4 relay 批量发布、
> B5/B7/B8 worker 并发与 relay batch、V4 索引），报告在 [performance.md](performance.md)，
> 原始输出在 [`perf/`](perf/)。当前端到端 455–470 events/s（450 到达率），900 到达率时
> 382–421/s；**2000 events/s 目标未达到**。

### 立项时的现状问题（已部分解决）

- 当前吞吐量约 **500 events/s**（单机）
- 未针对高并发场景优化
- 没有性能基准和压测报告

### 优化目标

将吞吐量提升到 **2000+ events/s**，P99 延迟 < 500ms。

### 优化方向

#### 1. 数据库优化

**索引优化**：
```sql
-- 为常见查询添加复合索引
CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);
CREATE INDEX idx_deliveries_status_next_attempt ON deliveries(status, next_attempt_at) 
  WHERE status IN ('PENDING', 'RETRYING');
```

**连接池调优**：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 10000
```

#### 2. RabbitMQ 优化

- 批量确认（basicAck with multiple=true）
- 调整预取数量（basicQos）
- 持久化策略优化

#### 3. HTTP 客户端优化

- 连接池复用
- 虚拟线程（Java 21）
- 热点数据缓存

#### 4. 压测方案

使用 k6 进行压测，目标：
- QPS ≥ 2000
- P99 延迟 < 500ms
- 成功率 ≥ 99.9%

### 验收标准

- [x] 完成至少 3 轮优化迭代（A2 / B4 / B5 / B7-B8 / V4 索引）
- [x] 提供压测报告（吞吐量、P99 延迟；[performance.md](performance.md) + [`perf/`](perf/) 原始输出）
- [x] 文档记录每次优化的效果
- [ ] 吞吐量达到 2000 events/s（单机）—— 当前最好 455–470/s，未达标

---

## 总结

### 推荐的实施顺序

1. **第 1 周**：P0 前端可视化（React + TypeScript）
2. **第 2 周**：P0 管理后台 + P1 Java SDK
3. **第 3 周**：P1 K8s 部署 + 监控面板
4. **第 4 周**（可选）：P2 CI/CD + Python SDK
5. **第 5 周**（可选）：P3 性能优化

> **实际进度（2026-10-30）**：P0 两项基本完成；P1 三项完成（Java SDK 仅差发布）；
> P2 CI/CD 完成 4/5，Python SDK 未开始；P3 完成 3/4 条验收，2000 events/s 目标未达。
> 剩余任务按 [remaining-plan.md](remaining-plan.md) 的 U → P → H 执行。

### 最小可演示版本（MVP）

如果时间紧张，只做以下 3 项即可：
- ✅ P0 前端可视化（证明全栈能力）
- ✅ P1 Java SDK（证明 SDK 设计能力；尚未发布，演示前先 `mvn -f sdk/java/pom.xml install`）
- ✅ P1 监控面板（证明运维意识）

### 面试时如何展示

1. **全栈岗**：重点展示 React 前端 + 管理后台
2. **后端岗**：重点展示 K8s 部署 + 监控 + 性能优化
3. **基础架构岗**：重点展示 K8s + CI/CD + 性能调优

祝求职顺利！🚀
