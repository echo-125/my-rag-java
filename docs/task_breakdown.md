# 任务分解（全部已完成 ✅）

## 原始任务

- **Task 1** ✅ 基础工程与双框架依赖配置
  - Spring Boot 4.1.0 + LangChain4j 1.17.0 + Spring AI 2.0.0 + PostgreSQL + JavaParser + Tika
  - 额外添加：LangChain4j Ollama（本地 Embedding）、Spring AI Anthropic

- **Task 2** ✅ 数据库实体与 LangChain4j EmbeddingStore
  - `document_chunks` 表由 LangChain4j PgVectorEmbeddingStore 自动管理（`createTable=true`）
  - 元数据使用 `COLUMN_PER_KEY` 模式存储为独立列
  - 统计查询使用 JdbcTemplate（`DocumentChunkStatsRepository`）
  - 额外创建：`LlmConfigEntity`、`EmbeddingConfigEntity`、`ProjectConfigEntity`、`QaHistoryEntity`

- **Task 3** ✅ Java AST 切分器与其他文档处理器
  - `JavaAstDocumentSplitter`：JavaParser 按类/方法/构造器切分
  - `RegexSplitter`：JS/TS/Python/Go 各有独立正则模式
  - `FileSplitterRouter`：按扩展名路由 + Tika 解析 + 元数据注入
  - PDF/Word/MD 使用 DocumentByParagraphSplitter(1500, 150)

- **Task 4** ✅ Spring AI 动态模型路由
  - `SpringAiModelRouterService`：从数据库读取 LLM 配置，按 UUID 缓存 ChatModel
  - `ChatModelBuilder`：支持 OpenAI 和 Anthropic 两种 API 格式
  - `DatabaseBackedEmbeddingModel`：懒加载 EmbeddingModel，首次 embed 时从数据库读取配置

- **Task 5** ✅ 离线入库与 SSE 可视化
  - `IngestionController` + `IngestionService`：文件扫描 → 切分 → 批量 embedAll → 存储
  - SSE 推送详细进度：进度条百分比、成功/失败/跳过统计、预计剩余时间

- **Task 6** ✅ 在线问答与混合检索
  - `RagChatService`：EmbeddingStoreContentRetriever Top-5 检索 + ChatClient 流式回答
  - Retriever 实例缓存，避免重复创建

- **Task 7** ✅ 前端页面开发
  - 4 标签页：对话 / 仪表盘 / 文档入库 / 设置
  - Toast 通知替代 alert、进度条、Markdown 渲染、ECharts 图表、Mermaid 架构图

## 额外任务

- **Task 8** ✅ LLM API 配置管理
  - `LlmConfigEntity` + `LlmConfigService` + `LlmConfigController`
  - API 密钥明文存储、API 响应脱敏、测试连接、激活/停用

- **Task 9** ✅ Embedding 配置前端可管理化
  - `EmbeddingConfigEntity` + `EmbeddingConfigService` + `EmbeddingConfigController`
  - 支持 Ollama（本地）和 OpenAI 兼容两种 provider
  - 前端设置页 Embedding 配置表格 + 模态框

- **Task 10** ✅ Dashboard 真实数据 + QA 历史持久化
  - `DashboardController` 通过 JdbcTemplate 查询真实统计
  - `QaHistoryEntity` 问答历史持久化

- **Task 11** ✅ 项目配置持久化
  - `ProjectConfigEntity` + `ProjectConfigController`

## 已验证功能

- ✅ 文档入库：25 个文件（PDF/DOCX/XML）全部成功，84 chunks 入库
- ✅ LLM API 测试连接：Anthropic 格式（mimo-v2.5-pro）连接成功
- ✅ Embedding 测试连接：本地 Ollama qwen3-embedding:4b_Q6 向量维度 2560
- ✅ 单元测试：16/16 通过
- ✅ 前端 UI：4 标签页、Toast 通知、进度条、Markdown 渲染
