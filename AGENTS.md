# AGENTS.md

本地 RAG 系统，用于多语言代码和文档的检索增强问答。

**语言偏好**：所有回复、代码注释、git 提交消息均使用**中文**，除非用户明确要求英文。

---

## 构建与运行命令

```bash
# 后端测试
mvn test
mvn test -Dtest=类名#方法名

# 后端构建（跳过测试）
mvn clean package -DskipTests

# 后端运行
mvn spring-boot:run

# 前端开发（独立终端）
cd ui && npm install && npm run dev

# 前端构建
cd ui && npm run build

# Playwright 前端测试（需服务启动）
npx playwright test tests/frontend.spec.ts

# Playwright 集成测试（需服务启动 + 数据已入库）
npx playwright test tests/integration-api.spec.ts
```

**环境路径**（非标准）：本地 Maven 仓库 `D:\develop\MAVEN`（settings.xml 配置），JDK `C:\Program Files\Java\jdk-21`。

---

## 关键架构约束（违反即错）

**双框架边界不可逾越：**
- **LangChain4j**——切分、向量化、入库、检索（`EmbeddingModel` / `EmbeddingStore` / `EmbeddingStoreContentRetriever`）
- **Spring AI**——仅用于对话生成（`ChatClient` / `ChatModel`）
- 严禁用 Spring AI 的 `VectorStore`，严禁用 LangChain4j 的 `ChatLanguageModel`

详细约束见 [`guardrails.md`](file:///D:/HeXin/insolu/my-rag-java/guardrails.md)。

---

## 非显而易见的实现细节

### Embedding 模型懒加载
`LangChain4jConfig.DatabaseBackedEmbeddingModel` 是一个**懒加载代理**，首次调用 `embed()` 时从 `config_embedding` 表读取激活配置构建实际模型。`reset()` 清除代理。切换 Embedding 模型时，`EmbeddingConfigService.activate()` 会自动 **DROP 重建 `document_chunks` 表**（旧数据全量丢失——本地单用户可接受）。

### 启动时维度校验
`LangChain4jConfig.verifyOrRecreateTable()` 检查 `document_chunks` 表的 vector 维度是否匹配 Embedding 配置。不匹配则 **DROP 表**，让 `createTable=true` 重新创建。启动时还会自动添加 `search_vector tsvector` 列用于 BM25 全文检索。

### 混合检索（BM25 + 向量）
`RagChatService.buildRetrieverWithPool()` 构建 `HybridContentRetriever`，融合向量检索（`EmbeddingStoreContentRetriever`）和 BM25 关键词检索（`PgVectorKeywordContentRetriever`）。BM25 通过 PostgreSQL `tsvector/tsquery` 实现，检索后用 RRF（Reciprocal Rank Fusion）融合排序。入库时 `IngestionService` 自动填充 `search_vector` 列。

### Reranking 精排
`RerankingService` 调用 Ollama `/api/rerank` 端点，一次 HTTP 完成所有候选文档评分。支持 Ollama 本地模型和远程 API 两种接入方式（`RerankingConfigEntity.provider`）。激活的 reranking 模型配置存储在 `config_reranking` 表。

### 查询改写
`QueryRewriteService` 调用 LLM 将用户口语化/模糊的问题改写为精确检索 query，prompt 中注入最近 2 轮对话历史解决指代问题。失败时自动 fallback 到原始 query。

### 评估体系
`EvaluationService` 支持离线批量评估（异步 VirtualThread）和在线用户反馈（👍👎）。测试集存储在 `evaluation_testset/evaluation_testcase` 表，评估结果存储在 `evaluation_batch/evaluation_result` 表。种子测试集通过 `@EventListener(ApplicationReadyEvent.class)` 自动导入。评估指标：Precision@K、Recall、MRR、Hit Rate，文件名匹配归一化。

### 评估增强
- **取消机制**：`EvaluationBatchEntity.cancelled` 字段，`executeBatch` 循环头每轮检查，`POST /api/evaluation/run/{batchId}/cancel` 端点
- **历史趋势**：`GET /api/evaluation/history` 返回最近 20 条 completed batch 摘要，前端 ECharts 折线图展示
- **导入导出**：`GET /api/evaluation/testset/{id}/export`（JSON 下载）、`POST /api/evaluation/testset/import`（批量导入）

### Agent 工具调用（Spring AI Tool-Calling）
`AgentTools` 组件提供 4 个 `@Tool` 方法，通过 `ChatClient.prompt().tools()` 注册。per-model 开关 `LlmConfigEntity.enableToolCalling`，每个模型独立控制。`RagChatService` 中用 `ObjectProvider<AgentTools>` 延迟注入打破循环依赖。

4 个工具：
- `searchKnowledge(query)` — 复用 `retrieve()` 混合检索管线
- `readFile(filePath)` — 路径穿越防护 + 白名单 + 敏感文件拦截（`.env`/`.pem`/`.key` 等）+ 8000 字符截断
- `listDirectory(dirPath)` — depth=1 遍历，最多 50 条
- `getKnowledgeBaseStats()` — 查询 `document_chunks` + `config_project` 统计

工具调用元数据通过 `AgentToolMetadata` ThreadLocal 收集，SSE 最后一个事件追加 `toolMetadata` 数组，前端渲染工具指示器（工具名 + 耗时）。

### 对话历史持久化
`ConversationService` 使用内存 + DB 双写策略。`chat_session` 表存储会话元数据，`chat_message` 表存储消息。启动时按需从 DB 加载到内存。前端 sidebar 显示历史会话列表，支持切换和删除。

### OkHttp Windows 兼容
`RagApplication.main()` 中设置系统属性：
```java
System.setProperty("langchain4j.http.clientBuilderFactory",
    "dev.langchain4j.http.client.okhttp.OkHttpClientBuilderFactory");
```
不设置此属性，Windows 上 LangChain4j HTTP 调用可能失败。

### 入库使用 VirtualThread
`IngestionService.ingest()` 使用 `Thread.startVirtualThread()` 异步执行。不是 `@Async`，不是 `CompletableFuture`。

### RAG 配置默认值自动插入
`RagConfigService.initializeDefaults()` 使用 `@PostConstruct` + try-catch。数据库不可用时启动不失败，记录警告后继续。首次数据库可用时插入默认配置项（含 `enable_bm25`、`enable_reranking`、`enable_query_rewrite` 等）。

### 缓存实现
`RagApplication` 中的 `ConcurrentMapCacheManager` 是**进程内内存缓存**（非 Redis）。`ragConfigCache`、`activeLlmConfig`、`activeEmbeddingConfig` 三个缓存分区。应用重启=缓存清空。

---

## 测试现状

70/70 测试通过，包括 **splitter 切分器测试**、**LlmConfigService 测试**、**IngestionServiceClean 测试**。

---

## 数据库表概览

- **`document_chunks`**——LangChain4j 的 `PgVectorEmbeddingStore` 自动创建/管理（`createTable=true`）。含 `embedding`、`text`、`search_vector` 列 + 9 个元数据列。统计用 `JdbcTemplate` 查询
- **`chat_session` / `chat_message`**——对话历史持久化（JPA `ddl-auto: update`）
- **`config_reranking`**——Reranking 模型配置（JPA 自动创建）
- **`evaluation_testset` / `evaluation_testcase` / `evaluation_batch` / `evaluation_result`**——评估体系（JPA 自动创建）
- **`qa_feedback`**——用户反馈（JPA 自动创建）
- **`config_llm` / `config_embedding` / `config_project` / `qa_history` / `config_rag`**——JPA 自动创建

首次运行需执行 `src/main/resources/db/init.sql` 创建数据库和 pgvector 扩展。

---

## 前端约束

- **Vue 3 + Vite**：`ui/` 目录，`npm run dev` 启动开发服务器（端口 3000，代理 `/api` 到后端 8080）
- **UI 组件库**：Nuxt UI v4（`@nuxt/ui` 独立 Vue 模式，Vite 插件 `@nuxt/ui/vite`）
- **暗色主题**：默认深色，主色 `#10a37f`，通过 `@vueuse/core` 的 `useColorMode` 管理
- **状态管理**：Pinia，按领域拆分为 app / chat / ingestion / evaluation / settings 五个 store
- **路由**：Vue Router 4 Hash 模式，5 个 Tab：对话 / 仪表盘 / 文档入库 / 评估 / 设置
- **流式对话**：`@microsoft/fetch-event-source` + `openWhenClosed: false`，`reactive` 数组存储消息
- **Markdown 渲染**：Shiki（代码高亮）+ markdown-it + Mermaid
- **类型安全**：TypeScript strict 模式，所有 `.ts` 和 `.vue` 文件必须显式 `import { ref } from 'vue'`（Nuxt UI auto-import 不注入 Vue 原生 API）
- **构建产物**：`npm run build`（仅 `vite build`，无 vue-tsc 类型检查）

---

## 参考指令文件

- [`CLAUDE.md`](file:///D:/HeXin/insolu/my-rag-java/CLAUDE.md) —— 技术栈、数据模型、项目结构、API 端点、依赖版本
- [`guardrails.md`](file:///D:/HeXin/insolu/my-rag-java/guardrails.md) —— 架构护栏（Docker/前端构建/框架混用的禁止项）
