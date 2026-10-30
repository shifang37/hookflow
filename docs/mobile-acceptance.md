# 移动端（响应式 Web）验收记录

> 验收对象是 `/ui/` 这个 **Web 控制台**，不是 iOS/Android App。没有原生客户端、推送、应用商店或设备能力。
> 本文记录的是 **390×844 视口** 的验收结果；真机浏览器是可选补充，不是交付前提。

- 日期：2026-10-30
- 前端：`ba6c109` 之后的移动端修复（drawer 宽度、表格滚动、签名提示换行）
- 浏览器：Chrome 156 headless，CDP `Emulation.setDeviceMetricsOverride`，390×844，mobile=true
- 数据：本机 `all` profile 的 demo 租户（真实接口、真实投递数据）
- 方法：设置租户 Key / Bootstrap Token 后逐页导航，量 `document.documentElement.scrollWidth` 与
  `window.innerWidth`，打开抽屉后再量 `.ant-drawer-content-wrapper` 的实际宽度

## 通过标准

1. 页面不出现**整页横向滚动**：`scrollWidth == innerWidth (390)`；
2. 侧栏在 `lg` 以下折叠；
3. 表格允许在卡片内部横向滚动，但不把页面撑宽；
4. Drawer 宽度不超过视口；
5. 签名提示能完整读到正确公式、`v1=<hex>`、epoch 秒和 ±300s 窗口；
6. 不出现被凭据提示遮住、按钮不可点、图表不缩放等阻塞问题。

## 结果

| 页面 | innerWidth | document.scrollWidth | 侧栏折叠 | 结果 |
|---|---:|---:|---|---|
| `/events` | 390 | 390 | 是 | ✅ |
| `/deliveries` | 390 | 390 | 是 | ✅ |
| `/tenants` | 390 | 390 | 是 | ✅ |
| `/endpoints` | 390 | 390 | 是 | ✅ |
| `/monitor` | 390 | 390 | 是 | ✅ |

### Drawer

| Drawer | 配置宽度 | 实际渲染宽度 | 页面 scrollWidth | 结果 |
|---|---|---:|---:|---|
| 投递详情 | `min(900px, 100vw)` | 390 | 390 | ✅ |
| 租户详情 | `min(520px, 100vw)` | 390 | 390 | ✅ |
| 事件详情 | `min(760px, 100vw)` | 由单元测试固定 | — | ✅ |
| 凭证 | `min(420px, 100vw)` | 由 `drawerWidth` 单测固定 | — | ✅ |

### 签名提示

`/endpoints` 展开行里实际读到：

- ✅ `HMAC-SHA256(secret, "v1:" + eventId + ":" + deliveryId + ":" + timestamp + ":" + rawBody)`
- ✅ `v1=<hex>`
- ✅ `X-HookFlow-Timestamp` 是 **epoch 秒**，窗口 **±300s**
- ✅ 不包含旧的 `timestamp + "."` 草稿公式

## 这次修了什么

- 四个 Drawer 不再写死像素宽度，统一用 `drawerWidth(maxPx)` 生成 `min(maxPx, 100vw)`；
- 投递详情 / 事件详情 / 租户详情的 `Descriptions` 在 `xs` 下改为单列，避免 390px 下值被挤成两行；
- 事件流表格加 `scroll={{ x: 760 }}` 并固定事件类型列宽，不再把 `order.paid` 拆成一字一行；
- 投递 attempt 表、事件扇出表加内部横向滚动；
- Endpoint 签名提示限制在 `min(640px, calc(100vw - 2rem))` 内并允许断词，公式在手机上完整可读。

## 明确没有做的

- **没有真机实测**（没有 iPhone/Android 设备）。视口模拟覆盖了布局和溢出，但触摸惯性、软键盘、浏览器地址栏收缩
  这些只影响手感的项没有验证。
- 没有做原生 App、PWA 安装、离线缓存、推送。
- 表格在 390px 下仍然是“卡片内横向滚动”，不是把四列表格重排成卡片；这是有意的取舍：运营表格的列语义比移动端
  单列堆叠更重要，横向滚动也符合 AntD 的既有行为。

## 怎么复现

1. 起后端和前端：

   ```bash
   ./mvnw spring-boot:run
   cd frontend && npm run dev -- --host 127.0.0.1
   ```

2. Chrome DevTools → Device Toolbar → 390×844 → 打开 `http://127.0.0.1:5173/events`；
3. 在右上角「凭证」填入租户 Key 和 Bootstrap Token；
4. 依次检查五个页面；打开投递/租户/事件抽屉，确认页面没有出现整页横向滚动条；
5. 在 Endpoints 页展开任意一行，确认签名公式可完整阅读。
