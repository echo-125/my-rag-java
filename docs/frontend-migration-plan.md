# 个人 RAG 极客控制台 — 前端迁移与页面升级计划

> 本文档是迁移计划，不是实现文档。目标是审批通过后进入实施阶段。

---

## 一、当前前端现状评估

### 1.1 文件结构

```
src/main/resources/static/
├── index.html     (654行, 5个页面全部内联)
├── app.js         (2225行, 全部业务逻辑)
├── app.css        (799行, 全部样式)
├── favicon.svg
└── lib/           (8个第三方库手动放置, 无包管理)
```

### 1.2 核心问题

| 问题 | 现状 | 影响 |
|------|------|------|
| 无模块化 | 单 HTML + 单 JS + 单 CSS | 无法按页面拆分, 改一处动全身 |
| 无构建工具 | CDN 引入 Tailwind, lib/ 手放第三方库 | 无 tree-shaking, 无 HMR, 开发体验差 |
| 无路由 | CSS hidden 切换 div | URL 不同步, 刷新丢失状态, 无法分享链接 |
| 无状态管理 | `App.state` 全局对象 | 页面间数据不共享, 响应式缺失 |
| DOM 操作 | `getElementById` + `innerHTML` 拼接 | XSS 风险, 调试困难, 性能差 |
| 行内事件 | `onclick="App.xxx()"` | 模板与逻辑强耦合 |

### 1.3 可复用资产

- 完整的 30+ 后端 API 端点契约（后端不动）
- SSE 流式解析逻辑（聊天 + 入库）
- Markdown → 代码高亮 → Mermaid 渲染链
- 诊断面板数据模型（sources / toolMetadata / duration）
- 评测轮询 + 取消机制
- CSS 设计变量（`#10a37f` 主色、等宽字体、卡片圆角）
- 状态条 badge 设计语言（BM25 / RERANK / QR 开关）

### 1.4 高风险点

1. SSE 流式聊天的 `ReadableStream` 解析 + `requestAnimationFrame` 节流渲染
2. Mermaid 在 Vue `v-html` 中的动态渲染时机
3. 诊断面板的跨组件通信（点击消息 → 展开诊断 → 渲染数据）
4. 入库 SSE 长连接（30 分钟超时）+ 实时进度 + ETA 计算
5. ECharts 实例在 Vue 组件中的 init / resize / dispose 生命周期

---

## 二、推荐技术栈与选型对比

### 2.1 核心框架

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.5+ | Composition API + `<script setup>` |
| Vite | 6.x | 开发服务器 + 构建 |
| Vue Router | 4.x | hash 模式路由 |
| Pinia | 3.x | 状态管理 |
| TypeScript | 5.x | 类型安全 |

### 2.2 UI 库选型：为什么选 Naive UI

| 对比维度 | Naive UI | Element Plus | Ant Design Vue |
|----------|----------|-------------|----------------|
| 产品气质 | 技术工具感, 信息密度高 | 偏企业后台, 间距大 | 偏企业 SaaS, 偏正式 |
| 暗色主题 | 原生支持, 定制极强 | 需额外配置 | 需额外配置 |
| TypeScript | 原生编写, 类型推导极好 | 支持但非原生 | 支持但非原生 |
| Vue 3 原生 | 从设计之初就是 Vue 3 | Vue 2 迁移 | Vue 2 迁移 |
| 默认密度 | 紧凑, 适合数据密集工具 | 圆润, 适合表单密集系统 | 稳重, 适合管理后台 |
| 包体积 | ~80KB gzip | ~120KB gzip | ~100KB gzip |

**结论**：Naive UI 的默认设计语言最接近"极客控制台"——紧凑、高对比、工具感强。Element Plus 需要大量覆盖样式才能脱离"后台管理"感，Ant Design 偏企业级。Naive UI 的主题定制能力（CSS 变量全覆盖）让我们能精确控制视觉输出。

### 2.3 第三方能力方案

| 能力 | 方案 | 说明 |
|------|------|------|
| Markdown 渲染 | markdown-it + 插件链 | 替代 marked.js, 扩展性更强 |
| 代码高亮 | highlight.js | 保留, 通过 markdown-it 插件集成 |
| Mermaid | mermaid | 保留, 自定义 markdown-it 插件处理 |
| 图表 | ECharts | 保留, 封装为 Vue 组件 |
| 流式渲染 | 自定义 `useStreamChat` composable | 封装 SSE ReadableStream 解析 |
| HTTP 客户端 | Axios | 替代原生 fetch, 统一拦截器 |
| 工具函数 | date-fns | 时间格式化 |
| UI 增强 | @vueuse/core | 组合式工具库 (useResizeObserver 等) |

### 2.4 前后端集成

**开发阶段**：Vite dev server (5173) → proxy `/api/*` → Spring Boot (8080)
**生产构建**：Vite 构建 → `frontend/dist/` → Maven 插件复制到 `static/dist/`

---

## 三、新前端目标架构

### 3.1 项目结构

```
frontend/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── tailwind.config.ts
├── postcss.config.js
├── index.html
│
├── public/
│   └── favicon.svg
│
└── src/
    ├── main.ts                          # 入口
    ├── App.vue                          # 根组件
    │
    ├── router/index.ts                  # 路由 (hash 模式)
    │
    ├── stores/                          # Pinia 状态
    │   ├── app.ts                       # 全局: sidebar, toast
    │   ├── chat.ts                      # 对话: 会话, 消息, 模型
    │   ├── dashboard.ts                 # 仪表盘: 统计, 图表
    │   ├── ingestion.ts                 # 入库: 项目, 进度, 步骤
    │   ├── evaluation.ts                # 评估: 测试集, 结果
    │   └── settings.ts                  # 设置: LLM/Embed/Rerank
    │
    ├── api/                             # API 层
    │   ├── http.ts                      # Axios 实例
    │   ├── types.ts                     # 请求/响应类型
    │   ├── chat.ts
    │   ├── session.ts
    │   ├── dashboard.ts
    │   ├── ingestion.ts
    │   ├── evaluation.ts
    │   └── settings.ts
    │
    ├── composables/                     # 组合式函数
    │   ├── useStreamChat.ts             # SSE 聊天
    │   ├── useStreamIngest.ts           # SSE 入库
    │   ├── useMarkdown.ts               # MD 渲染链
    │   ├── useMermaid.ts                # Mermaid 渲染
    │   ├── useChart.ts                  # ECharts 生命周期
    │   └── useToast.ts                  # 通知
    │
    ├── components/
    │   ├── layout/
    │   │   ├── AppShell.vue             # 主布局
    │   │   ├── AppSidebar.vue           # 左侧导航
    │   │   └── StatusBar.vue            # 聊天页状态条
    │   ├── ui/
    │   │   ├── StatusBadge.vue          # 状态徽章
    │   │   ├── MetricCard.vue           # 指标卡片
    │   │   ├── TerminalLog.vue          # 终端日志
    │   │   └── EmptyState.vue           # 空状态
    │   ├── chat/
    │   │   ├── ChatWelcome.vue          # 就绪面板
    │   │   ├── MessageBubble.vue        # 用户消息
    │   │   ├── AiCard.vue               # AI 回答卡片
    │   │   ├── StreamContent.vue        # 流式内容渲染
    │   │   ├── CitationList.vue         # 引用来源
    │   │   ├── ToolCallList.vue         # 工具调用
    │   │   ├── FeedbackBar.vue          # 反馈栏
    │   │   ├── DiagPanel.vue            # 诊断侧栏
    │   │   └── SessionList.vue          # 会话列表
    │   ├── dashboard/
    │   │   ├── StatCard.vue
    │   │   ├── LangChart.vue
    │   │   ├── QaHistory.vue
    │   │   └── EvalBrief.vue
    │   ├── ingestion/
    │   │   ├── ProjectForm.vue
    │   │   ├── ProjectList.vue
    │   │   ├── FileScanner.vue
    │   │   └── IngestProgress.vue
    │   ├── evaluation/
    │   │   ├── TestsetList.vue
    │   │   ├── CaseList.vue
    │   │   ├── EvalMetrics.vue
    │   │   ├── EvalDetail.vue
    │   │   └── TrendChart.vue
    │   └── settings/
    │       ├── RagConfig.vue
    │       ├── LlmTable.vue
    │       ├── EmbedTable.vue
    │       ├── RerankTable.vue
    │       └── ConfigModal.vue
    │
    ├── views/                           # 页面组件
    │   ├── ChatView.vue
    │   ├── DashboardView.vue
    │   ├── IngestionView.vue
    │   ├── EvaluationView.vue
    │   └── SettingsView.vue
    │
    ├── styles/
    │   ├── variables.css                # 主题 CSS 变量
    │   ├── base.css                     # 重置 + 基础
    │   ├── scrollbar.css                # 滚动条
    │   ├── markdown.css                 # MD 渲染样式
    │   └── terminal.css                 # 终端风格
    │
    └── utils/
        ├── dom.ts                       # HTML 转义, 剪贴板
        ├── format.ts                    # 数字/时间格式化
        └── constants.ts                 # 工具名映射等
```

### 3.2 路由

```typescript
// router/index.ts
const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: () => import('@/views/ChatView.vue') },
  { path: '/dashboard', component: () => import('@/views/DashboardView.vue') },
  { path: '/ingestion', component: () => import('@/views/IngestionView.vue') },
  { path: '/evaluation', component: () => import('@/views/EvaluationView.vue') },
  { path: '/settings', component: () => import('@/views/SettingsView.vue') },
]
// 使用 createWebHashHistory (Spring Boot 静态文件不支持 History fallback)
```

### 3.3 布局方案

聊天页保持三栏布局（会话列 / 对话区 / 诊断栏），其他页面使用全宽 + 卡片网格。`AppShell.vue` 是统一外壳，包含可折叠侧边栏和 `<router-view>`。

### 3.4 样式主题

```css
/* styles/variables.css */
:root {
  --c-primary: #10a37f;
  --c-primary-hover: #0d8a6a;
  --bg-page: #f3f4f6;
  --bg-card: #ffffff;
  --bg-elevated: #f9fafb;
  --bg-terminal: #0d1117;
  --text-1: #111827;
  --text-2: #374151;
  --text-3: #6b7280;
  --text-4: #9ca3af;
  --border: #e5e7eb;
  --border-subtle: #f3f4f6;
  --font-mono: ui-monospace, SFMono-Regular, Menlo, monospace;
  --radius-card: 12px;
  --radius-btn: 8px;
}
```

---

## 四、页面级迁移与升级策略

### 4.1 聊天页 (ChatView)

**现状问题**：SSE 解析与渲染混在一起；诊断面板靠 DOM 操作联动；消息管理在 Map 中手动维护

**迁移目标**：组件化三栏 + 响应式状态 + 流式渲染组件

**架构拆解**：
- `useStreamChat` composable → 封装 SSE 解析，暴露 `stream()`、`abort()`、`fullText`（ref）、`sources`（ref）、`toolMetadata`（ref）
- `AiCard` → 单条 AI 消息的完整展示容器
- `StreamContent` → 响应式渲染 Markdown + 高亮 + Mermaid，使用 `watchEffect` 监听文本变化
- `DiagPanel` → 响应 `selectedMessageId` 变化自动渲染

**信息架构**：状态条 → [会话列 | 对话区 | 诊断栏] → 输入区。保持现有结构，增加会话搜索和对话导出。

**复杂度**：高。SSE 流 + 增量 Markdown + Mermaid + 诊断联动是全项目最难的部分。

**风险**：流式渲染帧率（需 requestAnimationFrame 节流）；Mermaid 在 v-html 中的渲染时机；诊断面板跨组件通信延迟。

---

### 4.2 仪表盘页 (DashboardView)

**现状问题**：ECharts 实例管理不规范；Mermaid 图表硬编码 HTML；数据加载无统一入口

**迁移目标**：可复用 StatCard + 图表组件；ECharts 生命周期管理；响应式网格

**架构拆解**：
- `useChart` composable → 封装 init / resize / dispose
- `StatCard` → 带图标的指标卡片，props: title / value / icon / color
- `LangChart` → 语言分布柱状图
- 数据加载在 store action 中，页面 `onMounted` 触发

**复杂度**：中。结构清晰，主要是组件化 + ECharts 封装。

**风险**：ECharts 在 Vue 组件卸载时需 dispose；窗口 resize 时需更新图表尺寸。

---

### 4.3 入库页 (IngestionView)

**现状问题**：步骤状态机散落在多个方法中；SSE 进度流与 UI 深度耦合；项目列表 innerHTML 渲染

**迁移目标**：步骤状态机组件化；SSE 进度流 composable；响应式列表

**架构拆解**：
- `useStreamIngest` composable → 封装 `/api/ingestion/process` SSE，暴露 `progress`、`currentFile`、`logs`（reactive array）、`eta`
- 步骤切换用 `ref(1)` 控制，三步内容用 `v-show`（保持 DOM 不销毁，进度不丢失）
- `TerminalLog` 组件 → 终端风格日志，自动滚动
- `ProjectList` 用 `v-for` 响应式渲染

**复杂度**：中高。SSE 进度流 + 步骤状态机有复杂度，但交互模式固定。

**风险**：SSE 长连接超时（30 分钟）；日志区大量 DOM 节点的性能；ETA 计算精度。

---

### 4.4 评估页 (EvaluationView)

**现状问题**：测试集 CRUD 用 prompt 弹窗；轮询定时器手动管理；结果表格 innerHTML 拼接

**迁移目标**：正式 Modal 表单；轮询 composable 化；结果表格组件化

**架构拆解**：
- `useEvalPoll` composable → 封装轮询逻辑，暴露 `isPolling`、`progress`、`result`，组件卸载自动 clearTimer
- 测试集创建/添加用 `n-modal` + `n-form` 替代 `prompt()`
- 逐题明细用 Naive UI `n-data-table`，大数据量时虚拟滚动
- 趋势图复用 `useChart` composable

**复杂度**：中。功能明确，主要是 UI 规范化 + 状态管理。

**风险**：轮询定时器泄漏（组件卸载未清除）；导入文件类型校验；大测试集表格性能。

---

### 4.5 设置页 (SettingsView)

**现状问题**：RAG 配置动态生成表单逻辑复杂（根据 type 渲染不同 input）；三个配置表（LLM/Embed/Rerank）操作模式重复但代码不复用；弹窗复用不足

**迁移目标**：配置表单组件化；操作模式统一；Modal 复用

**架构拆解**：
- `RagConfig` → 动态表单渲染，根据后端返回的 `type` 字段映射到 Naive UI 表单组件
- `LlmTable` / `EmbedTable` / `RerankTable` → 统一操作模式（列表 + 激活/停用/编辑/删除/测试）
- `ConfigModal` → 通用配置弹窗，props 控制字段定义
- 所有配置在 `settings.ts` store 中统一管理

**复杂度**：中低。结构清晰，主要是 CRUD 操作的组件化。

**风险**：RAG 配置的动态表单渲染需兼容后端返回的多种 type（boolean/number/text/textarea/select）；reranking 模型为空时的联动禁用逻辑。

---

## 五、分阶段实施计划

### 阶段一：基础设施搭建（3-4天）

**目标**：Vite + Vue 3 + Naive UI + Tailwind + Router + Pinia 项目跑通，空壳页面可切换

**交付物**：
- `frontend/` 目录初始化（package.json, vite.config.ts, tsconfig.json）
- Tailwind CSS 集成 + 主题变量定义
- Naive UI 集成 + 全局主题配置
- Vue Router 路由配置（5 个空页面）
- Pinia store 骨架（6 个 store 文件）
- API 层骨架（http.ts + 各模块空文件）
- `AppShell` 布局组件（侧边栏 + router-view）
- Vite proxy 配置对接后端
- Spring Boot 构建集成（Maven 插件复制 dist）

**验收标准**：`npm run dev` 启动，5 个页面可通过侧边栏切换，Tailwind 样式生效，API proxy 通后端。

---

### 阶段二：设置页迁移（2-3天）

**目标**：完成最简单的 CRUD 页面，验证技术栈 + API 层 + 状态管理的完整性

**为什么先做设置页**：
- 纯 CRUD，无 SSE，无流式渲染，复杂度最低
- 能快速验证 API 层、Pinia store、Naive UI 表单/表格/弹窗的使用模式
- 后续页面可复用 ConfigModal、表格操作模式

**交付物**：
- `SettingsView.vue` + 4 个子组件（RagConfig / LlmTable / EmbedTable / RerankTable）
- `settings.ts` store
- `api/settings.ts` 完整 API 调用
- `ConfigModal.vue` 通用配置弹窗

**验收标准**：设置页所有 CRUD 操作正常（LLM/Embed/Rerank 增删改查 + 激活停用 + 测试连接 + RAG 策略保存）。

---

### 阶段三：仪表盘页迁移（1-2天）

**目标**：验证 ECharts 集成 + 图表组件 + 数据加载模式

**交付物**：
- `DashboardView.vue` + 4 个子组件
- `dashboard.ts` store
- `useChart` composable（ECharts 生命周期）
- `StatCard` / `LangChart` 通用组件

**验收标准**：仪表盘统计数据正确展示，ECharts 柱状图渲染正常，窗口 resize 图表自适应。

---

### 阶段四：入库页迁移（3-4天）

**目标**：验证 SSE 进度流 + 步骤状态机 + 终端日志组件

**交付物**：
- `IngestionView.vue` + 4 个子组件
- `ingestion.ts` store
- `useStreamIngest` composable
- `TerminalLog` 组件
- 完整三步工作流（添加项目 → 扫描 → 入库）

**验收标准**：添加项目、扫描文件类型、入库进度展示、实时日志输出、重新入库全部正常。

---

### 阶段五：聊天页迁移（5-7天）— 核心难点

**目标**：完成最复杂的页面，验证 SSE 流式 + Markdown 增量渲染 + Mermaid + 诊断联动

**交付物**：
- `ChatView.vue` + 9 个子组件
- `chat.ts` store
- `useStreamChat` composable（完整 SSE 解析）
- `useMarkdown` composable（markdown-it + 高亮 + Mermaid）
- `StreamContent` / `AiCard` / `DiagPanel` 核心组件
- `SessionList` 会话管理

**分步实施**：
1. 先实现会话列表 + 消息渲染（非流式历史消息）
2. 再实现 SSE 流式发送 + Markdown 增量渲染
3. 然后实现引用来源 + 工具调用展示
4. 接着实现诊断面板联动
5. 最后实现反馈栏 + 状态条 + 就绪面板

**验收标准**：流式聊天完整可用，Markdown/代码高亮/Mermaid 渲染正确，诊断面板联动正常，会话管理正常。

---

### 阶段六：评估页迁移（2-3天）

**交付物**：
- `EvaluationView.vue` + 5 个子组件
- `evaluation.ts` store
- `useEvalPoll` composable
- 完整评估流程（测试集管理 → 执行 → 查看结果 → 历史趋势）

**验收标准**：测试集 CRUD、评估执行与轮询、结果展示、趋势图全部正常。

---

### 阶段七：打磨与收尾（2-3天）

**目标**：全局体验优化 + 旧前端清理

**交付物**：
- 全局加载态/空态/错误态统一
- Toast 通知系统
- 页面切换动画
- 响应式适配（小屏诊断栏抽屉化）
- 删除旧 `static/app.js` + `static/app.css` + `static/lib/`
- 构建脚本验证（Maven 打包包含前端产物）

**验收标准**：所有页面功能完整，全局体验一致，`mvn clean package` 成功包含前端构建产物。

---

### 里程碑总览

| 阶段 | 天数 | 累计 | 交付 |
|------|------|------|------|
| 一：基础设施 | 3-4d | 4d | 项目骨架 + 布局 + 路由 |
| 二：设置页 | 2-3d | 7d | 第一个可用页面 |
| 三：仪表盘 | 1-2d | 9d | 图表 + 数据展示 |
| 四：入库页 | 3-4d | 13d | SSE 进度流 + 工作流 |
| 五：聊天页 | 5-7d | 20d | 核心对话功能 |
| 六：评估页 | 2-3d | 23d | 实验评估功能 |
| 七：打磨收尾 | 2-3d | 26d | 全局优化 + 清理 |

**总预估**：20-26 个工作日（单人）。

---

## 六、风险清单

| # | 风险 | 概率 | 影响 | 缓解措施 |
|---|------|------|------|----------|
| 1 | SSE 流式聊天在 Vue 中的渲染性能不如原生 | 中 | 高 | 保持 requestAnimationFrame 节流；用 `v-html` 而非组件递归渲染；性能测试 |
| 2 | Mermaid 在 Vue 模板中的渲染时机问题 | 中 | 中 | 使用 `nextTick` + `watchEffect`；封装为独立 composable 便于调试 |
| 3 | Naive UI 默认样式与"极客控制台"气质有差距 | 低 | 中 | 提前做主题定制 prototype；CSS 变量覆盖 Naive UI 主题 |
| 4 | 评估页 prompt() 改 Modal 后交互路径变长 | 低 | 低 | 保持操作步骤简洁；必要时支持快捷键 |
| 5 | 入库页 SSE 长连接超时 | 低 | 中 | Vite proxy 设置合理超时；前端做断线重连提示 |
| 6 | Vite 构建产物与 Spring Boot 集成问题 | 低 | 高 | 阶段一提前验证 Maven 插件复制 dist 的流程 |
| 7 | 迁移周期超预期 | 中 | 中 | 分阶段交付；每个阶段结束都能独立使用 |
| 8 | 旧前端功能遗漏 | 低 | 高 | 逐功能对照迁移清单，每页完成后 manual QA |

### 回滚思路

每个阶段结束时，旧前端代码仍保留在 `static/` 下。如果新前端有严重问题，可以通过 Spring Boot 配置回退到旧前端（删除 `dist/` 目录，恢复旧文件即可）。直到阶段七确认全部功能正常后，才删除旧前端代码。

---

## 七、我建议你先做的第一步

**在 `frontend/` 目录中初始化 Vite + Vue 3 + Naive UI + Tailwind 项目，并验证 Vite proxy 对接 Spring Boot 后端。**

具体操作：
1. `npm create vite@latest frontend -- --template vue-ts`
2. 安装 Naive UI + Pinia + Vue Router
3. 安装 Tailwind CSS (Vite 插件)
4. 配置 Vite proxy 指向 localhost:8080
5. 写一个空页面尝试调用 `/api/dashboard/stats` 确认代理通

这一步大约 0.5 天，但能验证整条技术链路是否可行，后续所有阶段都建立在此基础上。

---

## 需要确认的决策项

1. **TypeScript 引入程度**：全量 TS（所有组件和 store 都有类型）还是渐进式（先 JS 后补类型）？建议全量 TS，Naive UI 的类型体验很好。

2. **是否保留 Tailwind**：Naive UI 自带样式系统，Tailwind 可能有冲突。建议保留 Tailwind 处理布局和间距，Naive UI 处理组件样式。

3. **旧前端保留多久**：建议在阶段七完成前一直保留，作为回退方案。

4. **聊天页是否支持多窗口/多标签**：当前是单会话模型，是否需要支持同时打开多个会话对比？

5. **是否需要暗色主题**：Naive UI 原生支持，如果需要，可在阶段一就配置好亮/暗主题切换。
