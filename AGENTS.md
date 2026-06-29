# AGENTS.md

本地 RAG 系统，用于多语言代码和文档的检索增强问答。

**语言偏好**：所有回复、代码注释、git 提交消息均使用**中文**，除非用户明确要求英文。

---

## 构建与运行命令

```bash
# 全部测试
mvn test

# 单个测试
mvn test -Dtest=类名#方法名

# 构建（跳过测试）
mvn clean package -DskipTests

# 运行
mvn spring-boot:run
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
`LangChain4jConfig.DatabaseBackedEmbeddingModel` 是一个**懒加载代理**，首次调用 `embed()` 时从 `embedding_config` 表读取激活配置构建实际模型。`reset()` 清除代理。切换 Embedding 模型时，`EmbeddingConfigService.activate()` 会自动 **DROP 重建 `document_chunks` 表**（旧数据全量丢失——本地单用户可接受）。

### 启动时维度校验
`LangChain4jConfig.verifyOrRecreateTable()` 检查 `document_chunks` 表的 vector 维度是否匹配 Embedding 配置。不匹配则 **DROP 表**，让 `createTable=true` 重新创建。

### OkHttp Windows 兼容
`RagApplication.main()` 中设置系统属性：
```java
System.setProperty("langchain4j.http.clientBuilderFactory",
    "dev.langchain4j.http.client.okhttp.OkHttpClientBuilderFactory");
```
不设置此属性，Windows 上 LangChain4j HTTP 调用可能失败。

### 入库使用 VirtualThread
`IngestionService.ingest()` 使用 `Thread.startVirtualThread()` 异步执行。不是 `@Async`，不是 `CompletableFuture`。

### SSE 端点差异
- **入库** `POST /api/ingestion/start` → `SseEmitter`（Servlet 同步流）。前端在 `fetch` 流结束后才发下一个项目。
- **问答** `POST /api/chat/stream` → `Flux<String>`（Reactive 流）。**QA 历史不由后端自动保存**——前端在流结束后单独调用 `POST /api/chat/save`。

### 前端 `/api/chat/save` 缺 modelName
`app.js` 保存问答时未传递 `modelName` 字段，即使后端 `QaSaveRequest` 中定义了该字段。修复需修改前端 `send()` 中的 fetch 调用。

### 文本清洗中的公司水印
`IngestionService.cleanSegments()` 中硬编码了特定中文公司的页眉页脚水印模式（`"广州市享印畅链信息技术有限公司"` 等）。处理其他公司文档时需修改或外部化此列表。

### RAG 配置默认值自动插入
`RagConfigService.initializeDefaults()` 使用 `@PostConstruct` + try-catch。数据库不可用时启动不失败，记录警告后继续。首次数据库可用时插入 7 个默认配置项。

### 缓存实现
`RagApplication` 中的 `ConcurrentMapCacheManager` 是**进程内内存缓存**（非 Redis）。`ragConfigCache`、`activeLlmConfig`、`activeEmbeddingConfig` 三个缓存分区。应用重启=缓存清空。

### 系统提示词硬编码
`RagChatService.chat()` 第 97-101 行的系统提示词、`MAX_RESULTS=5`、`MIN_SCORE=0.5` 都在 Java 代码中硬编码，前端不可配置。修改需改 Java 源码。

---

## 测试现状

16/16 测试通过，全部是 **splitter 切分器测试**。无 Controller 集成测试，无端到端前端测试。`ChunkQualityAnalyzer.java` 在 `test` 目录下但实为分析工具而非测试类。

---

## 数据库表概览

- **`document_chunks`**——LangChain4j 的 `PgVectorEmbeddingStore` 自动创建/管理（`createTable=true`）。统计用 `JdbcTemplate` 查询，**不要通过 JPA 实体管理**
- **`llm_config` / `embedding_config` / `project_config` / `qa_history` / `rag_config`**——JPA 自动创建（`ddl-auto: update`）

首次运行需执行 `src/main/resources/db/init.sql` 创建数据库和 pgvector 扩展。

---

## 前端约束

- **无构建工具**：纯静态 HTML/JS，第三方库放入 `static/lib/`
- Tailwind CSS 通过 CDN 加载（开发版本 ~4MB），离线不可用
- `app.js` 是 ~810 行单体文件，所有功能在 `App` 命名空间下
- 路由无 URL hash 同步（纯 CSS `hidden` 类切换）

---

## 参考指令文件

- [`CLAUDE.md`](file:///D:/HeXin/insolu/my-rag-java/CLAUDE.md) —— 技术栈、数据模型、项目结构、API 端点、依赖版本
- [`guardrails.md`](file:///D:/HeXin/insolu/my-rag-java/guardrails.md) —— 架构护栏（Docker/前端构建/框架混用的禁止项）
