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
- Apache POI 5.2.5（Excel/PPT 结构化解析）
- 前端：HTML/JS + Tailwind CSS + ECharts + Mermaid.js + marked.js（无构建工具，纯静态引入）

## 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 运行全部测试
mvn test
```

本地 Maven 仓库路径（settings.xml 中配置）：`D:\develop\MAVEN`
JDK 路径：`C:\Program Files\Java\jdk-21`
PostgreSQL：localhost，账号 `postgres/123456`

## 功能模块

### 检索管线（LangChain4j）
- **向量检索**：`EmbeddingStoreContentRetriever`，支持动态 minScore/maxResults
- **BM25 关键词检索**：`PgVectorKeywordContentRetriever`，基于 PostgreSQL `tsvector/tsquery`
- **混合融合**：`HybridContentRetriever`，RRF（Reciprocal Rank Fusion）排序
- **Reranking 精排**：`RerankingService`，调用 Ollama `/api/rerank` 或远程 API
- **查询改写**：`QueryRewriteService`，LLM 改写模糊查询，注入对话历史解决指代
- **去重**：按 `file_path|type|signature` 三字段去重

### 对话管理
- **持久化**：`ConversationService` 内存+DB 双写，`chat_session`/`chat_message` 表
- **历史**：滑动窗口保留最近 N 轮，启动时按需从 DB 加载
- **前端**：sidebar 显示历史会话列表，支持切换/删除/新建

### 评估体系
- **离线评估**：`EvaluationService`，异步 VirtualThread 执行
- **指标**：Precision@K、Recall、MRR、Hit Rate（文件名归一化匹配）
- **在线反馈**：`FeedbackService`，👍👎 按钮 + 满意率统计
- **测试集**：DB 管理（`evaluation_testset`/`evaluation_testcase`），种子数据自动导入

### Reranking 管理
- **实体**：`RerankingConfigEntity`（provider: ollama/api, modelName, baseUrl, apiKey, isActive）
- **前端**：独立 "Reranking (重排模型) 接入" 区块，支持 Ollama/API 双模式
- **联动**：无激活模型时自动禁用 `enable_reranking` + 折叠相关配置项

## 数据模型

### document_chunks 表（LangChain4j 自动创建，`createTable=true`）
含 `embedding VECTOR`、`text TEXT`、`search_vector tsvector` + 9 个元数据列。不要通过 JPA 实体管理。

### 新增表（JPA `ddl-auto: update` 自动创建）
- **`chat_session`**：id, title, created_at, updated_at
- **`chat_message`**：id, session_id, role, content, created_at
- **`reranking_config`**：id, name, provider, model_name, base_url, api_key, is_active
- **`evaluation_testset`**：id, name, description, created_at, updated_at
- **`evaluation_testcase`**：id, testset_id, question, expected_files, tags, created_at
- **`evaluation_batch`**：id, testset_id, config_snapshot, status, metrics...
- **`evaluation_result`**：id, batch_id, question, retrieved_files, expected_files, hit, parse_warning...
- **`qa_feedback`**：id, qa_history_id, rating, comment, created_at

### 原有表
- **`llm_config`** / **`embedding_config`** / **`project_config`** / **`qa_history`** / **`rag_config`**

## 项目结构

```
src/main/java/com/he/
  config/
    LangChain4jConfig.java           EmbeddingModel 懒加载 + PgVector + search_vector 列
  controller/
    IngestionController.java         入库 API（scan/process/clear）
    RagChatController.java           问答 API（stream/save）
    ChatSessionController.java       会话 CRUD
    LlmConfigController.java         LLM 配置
    EmbeddingConfigController.java   Embedding 配置
    RerankingConfigController.java   Reranking 配置
    EvaluationController.java        评估 API（run/report/testset CRUD）
    FeedbackController.java          反馈 API
    RagConfigController.java         RAG 策略配置
    DashboardController.java         仪表盘统计
    ProjectConfigController.java     项目配置
  entity/
    ChatSessionEntity / ChatMessageEntity       对话历史
    RerankingConfigEntity                       Reranking 模型配置
    EvaluationTestsetEntity / EvaluationTestcaseEntity / EvaluationBatchEntity / EvaluationResultEntity  评估体系
    QaFeedbackEntity                            用户反馈
    LlmConfigEntity / EmbeddingConfigEntity / ProjectConfigEntity / RagConfigEntity / QaHistoryEntity
  service/
    RagChatService.java             检索管线（向量+BM25+去重+改写+reranking）+ 问答
    IngestionService.java           入库（切分+向量化+search_vector 填充）
    ConversationService.java        对话历史（内存+DB 双写）
    EvaluationService.java          离线评估引擎（异步）
    FeedbackService.java            在线反馈
    QueryRewriteService.java        查询改写（LLM）
    RerankingService.java           Reranking（Ollama /api/rerank 或 API）
    RerankingConfigService.java     Reranking 配置 CRUD + 测试连接
    RagConfigService.java           RAG 策略配置
    EmbeddingConfigService.java     Embedding 配置
    LlmConfigService.java           LLM 配置
    SpringAiModelRouterService.java 动态模型路由
  retriever/
    PgVectorKeywordContentRetriever.java   BM25 关键词检索
  splitter/
    FileSplitterRouter.java + 各语言 Splitter

src/main/resources/
  eval/testset.json                 种子测试集（@EventListener 自动导入）
  static/
    app.js                          ~1800 行单体文件（5 个 Tab: 对话/仪表盘/入库/评估/设置）
    index.html                      单页面前端
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | 流式问答（SSE） |
| POST | `/api/chat/save` | 保存问答记录，返回 `{id}` |
| GET/DELETE | `/api/sessions` | 会话列表 / 删除会话 |
| GET | `/api/sessions/{id}/messages` | 获取会话消息 |
| POST | `/api/ingestion/scan` | 扫描路径文件类型 |
| POST | `/api/ingestion/process` | 执行入库（SSE） |
| DELETE | `/api/ingestion/chunks/{projectName}` | 清空项目 chunks |
| DELETE | `/api/ingestion/chunks` | 清空全部 chunks |
| GET/POST/PUT/DELETE | `/api/reranking-configs` | Reranking 配置 CRUD |
| POST | `/api/reranking-configs/{id}/test` | 测试 Reranking 连接 |
| POST | `/api/reranking-configs/{id}/activate` | 激活 Reranking 配置 |
| POST | `/api/evaluation/run` | 启动评估任务 |
| GET | `/api/evaluation/run/{batchId}/status` | 评估进度 |
| GET | `/api/evaluation/report` | 最新评估报告 |
| GET/POST/DELETE | `/api/evaluation/testset` | 测试集 CRUD |
| GET/POST/DELETE | `/api/evaluation/testset/{id}/cases` | 测试用例 CRUD |
| POST | `/api/feedback` | 提交反馈 |
| GET | `/api/feedback/stats` | 反馈统计 |
| GET | `/api/feedback/low-quality` | 低分问答列表 |
| GET/POST/PUT/DELETE | `/api/llm-configs` | LLM 配置 |
| GET/POST/PUT/DELETE | `/api/embedding-configs` | Embedding 配置 |
| GET/PUT | `/api/configs` | RAG 策略配置 |
| GET | `/api/dashboard/*` | 仪表盘统计 |
