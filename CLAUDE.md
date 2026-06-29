# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

本地 RAG 系统，用于处理多语言代码和文档。采用**双框架架构**：LangChain4j 负责 RAG 数据流（切分、向量化、存储、检索），Spring AI 负责上层 ChatClient 对话抽象。

## 技术栈

- Java 21 + Spring Boot 4.1.0（Maven 构建）
- LangChain4j 1.17.0（EmbeddingModel, EmbeddingStore, DocumentSplitter, EmbeddingStoreContentRetriever）
- LangChain4j pgvector 1.17.0-beta27
- LangChain4j Ollama 1.17.0（本地 Embedding）
- LangChain4j OpenAI 1.17.0（可选，OpenAI 兼容 Embedding API）
- LangChain4j HTTP Client OkHttp 1.17.0-beta27（替代 JDK HTTP 客户端，解决 Windows 兼容问题）
- Spring AI 2.0.0（ChatClient, ChatModel）
- Spring AI Anthropic 2.0.0（AnthropicChatModel）
- PostgreSQL 18 + pgvector 扩展
- JavaParser 3.28.2（Java AST 切分）
- Apache Tika 3.3.1（PDF/Word 文档解析，自带编码探测）
- 前端：HTML/JS + Tailwind CSS + ECharts + Mermaid.js + marked.js（无构建工具，纯静态引入）

## 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 运行单个测试
mvn test -Dtest=类名#方法名

# 运行全部测试
mvn test
```

本地 Maven 仓库路径（settings.xml 中配置）：`D:\develop\MAVEN`
JDK 路径：`C:\Program Files\Java\jdk-21`
PostgreSQL：localhost，账号 `postgres/123456`

### 首次运行前

1. 确保 PostgreSQL 运行，执行 `src/main/resources/db/init.sql`（创建数据库 + pgvector 扩展）
2. 确保 Ollama 运行并已拉取 embedding 模型（如 `qwen3-embedding:4b_Q6`）
3. 启动应用后，在「设置」页配置并激活 Embedding 模型和 LLM 模型
4. `document_chunks` 表由 LangChain4j PgVectorEmbeddingStore 启动时自动创建（`createTable=true`）
5. `rag_config` 表由 JPA 自动创建，启动时 `RagConfigService` 自动插入 7 个默认配置项

## 架构关键约束（来自 guardrails.md）

### 框架边界（最重要）

- **数据流用 LangChain4j**：切分（DocumentSplitter）、向量化（EmbeddingModel）、入库（EmbeddingStore）、检索（EmbeddingStoreContentRetriever）全部使用 LangChain4j API。
- **对话用 Spring AI**：最终与 LLM 交互生成回复时，必须使用 Spring AI 的 ChatClient。
- **严禁混用**：不能用 Spring AI 的 VectorStore，也不能用 LangChain4j 的 ChatLanguageModel。两套框架各管一段，不交叉。

### 代码切分

- Java 代码必须使用 JavaParser 按 AST 切分（按类和方法边界），严禁简单按 Token 硬截断。
- JS/TS/Python/Go 使用 RegexSplitter（按语言选择不同的正则模式）。
- PDF/Word 文档使用 `SemanticStructureSplitter`（三级降级：标题物理切分 → 段落语义切分 → 递归字符兜底），参数通过 `rag_config` 数据库表动态配置。
- MD/文本等其他文件使用递归切分器 `DocumentSplitters.recursive`，参数同样从 `rag_config` 读取。

### 其他约束

- **无 Docker**：所有环境在 Windows 11 本地直接运行。
- **无前端构建工具**：不使用 Node.js/Webpack/Vue/React。第三方 JS/CSS 库放入 `src/main/resources/static/lib/`。
- **无登录鉴权**：仅供本地单用户使用。
- **动态配置**：LLM、Embedding 和 RAG 切分/清洗参数均存储在数据库，通过前端设置页面管理。`rag_config` 表存储切分参数（带 `@Cacheable` 缓存）。
- **SSE 实时推送**：数据入库和对话回复均通过 SSE 流式返回。
- **Embedding 使用本地 Ollama**：向量化通过本地 Ollama 运行，无需外部 API（也可切换为 OpenAI 兼容 API）。

## 数据模型

### document_chunks 表（LangChain4j 自动创建，`createTable=true`）

由 `PgVectorEmbeddingStore` 管理，主键 `embedding_id UUID`，含 `embedding VECTOR`、`text` 字段及 8 个元数据列（`project_name`、`file_path`、`language`、`type`、`signature`、`start_line`、`end_line`、`chunk_index`）。**不要通过 JPA 实体管理此表**，查询统计使用 JdbcTemplate。

### llm_config 表（JPA 自动创建）

LLM API 配置：name、modelName、baseUrl、apiKey（明文存储，API 响应脱敏）、apiFormat（openai_chat_completions/anthropic_messages）、isActive。

### embedding_config 表（JPA 自动创建）

Embedding 模型配置：name、provider（ollama/openai）、baseUrl、modelName、apiKey、dimension、isActive。

### project_config 表（JPA 自动创建）

项目路径配置：name、path。

### qa_history 表（JPA 自动创建）

问答历史：question、answer、modelName。

### rag_config 表（JPA 自动创建）

全局 RAG 切分与清洗参数：configKey（主键）、configValue、description、updatedAt。含 7 个默认配置项（max_segment_size、max_overlap_size、semantic_threshold、merge_min_length、enable_noise_filter、noise_min_length、filter_pure_numbers）。由 `RagConfigService` 管理，使用 `@Cacheable("ragConfigCache")` 缓存。

## 项目结构

```
src/main/java/com/he/
  InsoluRagApplication.java              启动类
  config/
    LangChain4jConfig.java               EmbeddingModel（DatabaseBackedEmbeddingModel 懒加载）+ PgVectorEmbeddingStore Bean
  controller/
    IngestionController.java             POST /api/ingestion/start (SSE)
    RagChatController.java               POST /api/chat/stream (SSE) + POST /api/chat/save
    LlmConfigController.java             LLM 配置 CRUD + 测试连接 + 激活/停用
    EmbeddingConfigController.java       Embedding 配置 CRUD + 测试连接 + 激活/停用
    ModelController.java                 GET /api/models
    DashboardController.java             Dashboard 统计 + 最近问答（JdbcTemplate 查询）
    ProjectConfigController.java         项目配置 CRUD
    RagConfigController.java             RAG 切分/清洗配置 GET/PUT（带白名单+值校验）
    GlobalExceptionHandler.java          全局异常处理
  entity/
    DocumentChunkStatsRepository.java    JdbcTemplate 统计查询（count/countByLanguage/countByProject）
    LlmConfigEntity.java                 LLM 配置实体（含 ApiFormat 枚举）
    LlmConfigRepository.java             LLM 配置仓库
    EmbeddingConfigEntity.java           Embedding 配置实体（含 provider 字段）
    EmbeddingConfigRepository.java       Embedding 配置仓库
    ProjectConfigEntity.java             项目配置实体
    ProjectConfigRepository.java         项目配置仓库
    QaHistoryEntity.java                 问答历史实体
    QaHistoryRepository.java             问答历史仓库
    RagConfigEntity.java                 RAG 全局配置实体（configKey 主键）
    RagConfigRepository.java             RAG 配置仓库
  service/
    ChatModelBuilder.java                ChatModel 构建工厂（OpenAI/Anthropic）
    SpringAiModelRouterService.java      动态模型路由（按 UUID 缓存 ChatModel）
    EmbeddingConfigService.java          Embedding 配置 CRUD + 测试连接 + buildModel
    IngestionService.java                入库流程编排（批量 embedAll + SSE 详细进度）
    RagChatService.java                  检索 + ChatClient 问答（Retriever 缓存）
    LlmConfigService.java                LLM 配置 CRUD + 测试连接
    RagConfigService.java                RAG 全局配置 CRUD + @Cacheable 缓存 + @PostConstruct 初始化默认值
  splitter/
    JavaAstDocumentSplitter.java         JavaParser AST 切分（类/方法/构造器）
    RegexSplitter.java                   正则切分（JS/TS/Python/Go 各有独立模式）
    FileSplitterRouter.java              按扩展名路由切分器 + Tika 文档解析 + 元数据注入
    SemanticStructureSplitter.java       三级降级文档切分器（标题切分 → 语义切分 → 递归兜底，适用于 PDF/Word）
    CSharpSplitter.java                  C# 代码切分
    CssSplitter.java                     CSS 代码切分
    HtmlSplitter.java                    HTML 代码切分
    QmlSplitter.java                     QML 代码切分
    VueSplitter.java                     Vue 单文件组件切分
    ExcelStructuredParser.java           Excel 结构化解析
    PptxStructuredParser.java            PPTX 结构化解析
    SplitterUtils.java                   切分工具类

src/main/resources/
  application.yml                        Spring Boot + 数据库 + 日志配置
  db/init.sql                            数据库初始化脚本（创建数据库 + pgvector 扩展）
  static/
    index.html                           单页面前端（4 标签页：对话/仪表盘/文档入库/设置）
    lib/                                 echarts.min.js, mermaid.min.js, marked.min.js, tailwind.min.css

src/test/java/com/he/
  analysis/
    ChunkQualityAnalyzer.java            Chunk 质量分析
  service/
    IngestionServiceCleanTest.java       入库服务集成测试
    LlmConfigServiceTest.java            LLM 配置服务单元测试
  splitter/
    RegexSplitterTest.java               Go/Python/JS/TS 切分测试
    JavaAstDocumentSplitterTest.java     Java AST 切分测试
    SemanticStructureSplitterTest.java   三级降级切分器测试（标题/语义/递归/元数据/余弦相似度）
    CSharpSplitterTest.java              C# 切分测试
    CssSplitterTest.java                 CSS 切分测试
    HtmlSplitterTest.java                HTML 切分测试
    QmlSplitterTest.java                 QML 切分测试
    VueSplitterTest.java                 Vue 单文件组件切分测试
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ingestion/start` | 启动入库（SSE） |
| POST | `/api/chat/stream` | 流式问答（SSE） |
| POST | `/api/chat/save` | 保存问答记录 |
| GET | `/api/models` | 获取激活的模型列表 |
| GET/POST/PUT/DELETE | `/api/llm-configs` | LLM 配置 CRUD |
| POST | `/api/llm-configs/{id}/test` | 测试 LLM API 连接 |
| POST | `/api/llm-configs/{id}/activate` | 激活 LLM 配置 |
| GET/POST/PUT/DELETE | `/api/embedding-configs` | Embedding 配置 CRUD |
| POST | `/api/embedding-configs/{id}/test` | 测试 Embedding 连接 |
| POST | `/api/embedding-configs/{id}/activate` | 激活 Embedding 配置 |
| GET | `/api/dashboard/stats` | 系统统计（chunks/projects/files） |
| GET | `/api/dashboard/language-stats` | 各语言代码量统计 |
| GET | `/api/dashboard/project-stats` | 各项目代码量统计 |
| GET | `/api/dashboard/recent-qa` | 最近问答记录 |
| GET/POST/DELETE | `/api/project-configs` | 项目配置 CRUD |
| GET | `/api/configs` | RAG 切分/清洗配置（含类型和描述） |
| PUT | `/api/configs` | 批量更新 RAG 配置（带白名单+值校验） |

## 依赖版本

| 组件 | 版本 | Maven 坐标 |
|------|------|-----------|
| Spring Boot | 4.1.0 | `spring-boot-starter-parent` |
| LangChain4j BOM | 1.17.0 | `dev.langchain4j:langchain4j-bom` |
| LangChain4j pgvector | 1.17.0-beta27 | `dev.langchain4j:langchain4j-pgvector` |
| LangChain4j Ollama | 1.17.0 | `dev.langchain4j:langchain4j-ollama` |
| LangChain4j OpenAI | 1.17.0 | `dev.langchain4j:langchain4j-open-ai` |
| LangChain4j HTTP OkHttp | 1.17.0-beta27 | `dev.langchain4j:langchain4j-http-client-okhttp` |
| Spring AI BOM | 2.0.0 | `org.springframework.ai:spring-ai-bom` |
| Spring AI OpenAI | 2.0.0 | `spring-ai-starter-model-openai` |
| Spring AI Anthropic | 2.0.0 | `spring-ai-starter-model-anthropic` |
| JavaParser | 3.28.2 | `com.github.javaparser:javaparser-core` |
| Apache Tika | 3.3.1 | `org.apache.tika:tika-core` |
