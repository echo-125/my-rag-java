# My RAG Java

> 基于 LangChain4j + Spring AI 双框架的个人代码与文档知识库问答系统

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1.0-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/LangChain4j-1.17.0-4CAF50?style=flat-square" alt="LangChain4j">
  <img src="https://img.shields.io/badge/Spring_AI-2.0.0-6DB33F?style=flat-square" alt="Spring AI">
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/pgvector-0.8.0-8B9DC3?style=flat-square" alt="pgvector">
</p>

## 简介

My RAG Java 是一个运行在本地的个人知识库问答系统，支持将代码仓库和文档（PDF、Word、Markdown 等）入库到向量数据库，然后通过自然语言提问获取带引用来源的回答。

**核心特色**：针对代码仓库的 AST 级智能切分，能理解 Java、C#、Python 等语言的函数/类结构，而非简单的按字符长度切分。

## 功能特性

### 入库管道

- **多语言 AST 切分** — Java（JavaParser AST）、C#、JS/TS、Python、Go 等语言的代码感知切分
- **语义结构切分** — 基于 Embedding 相似度检测文档话题转变，自动合并短段落
- **文档解析** — PDF、Word、Excel（结构化解析）、PPT、Markdown、HTML
- **5 级噪声过滤** — 自动过滤水印、页码、目录页、页眉页脚、纯标点等无意义内容
- **可视化入库工作流** — 三步流程：路径校验 → 文件类型筛选 → 流式进度

### 对话问答

- **流式响应** — SSE 实时输出，逐 token 渲染 Markdown + 代码高亮
- **引用溯源** — 每个回答附带引用来源标签，可追溯到具体文件
- **多轮对话** — 支持会话内上下文记忆
- **Mermaid 图表** — 自动渲染回答中的 Mermaid 流程图

### 模型管理

- **多 LLM 支持** — 通过 Spring AI 接入 OpenAI、Anthropic 及其兼容 API
- **多 Embedding 支持** — Ollama 本地模型 + OpenAI 兼容 API
- **热切换** — 前端下拉框切换模型，配置存数据库，无需重启

### 系统管理

- **仪表盘** — 文档块统计、语言分布图表、最近问答记录
- **配置管理** — RAG 参数（检索数量、相似度阈值、系统提示词）在线调整
- **全本地运行** — 数据存储在 PostgreSQL + pgvector，不依赖外部云服务

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                     Frontend (HTML/JS)                   │
│  Tailwind CSS · ECharts · Mermaid · Highlight.js        │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP / SSE
┌───────────────────────┴─────────────────────────────────┐
│                  Spring Boot 4.1.0                       │
│                                                         │
│  ┌─────────────────┐    ┌─────────────────────────┐    │
│  │   LangChain4j   │    │       Spring AI          │    │
│  │                 │    │                         │    │
│  │  • 切分器路由    │    │  • ChatClient (流式)     │    │
│  │  • Embedding    │    │  • 模型路由              │    │
│  │  • 向量检索      │    │  • OpenAI/Anthropic     │    │
│  └────────┬────────┘    └────────────┬────────────┘    │
│           │                          │                  │
│  ┌────────┴──────────────────────────┴────────────┐    │
│  │              PostgreSQL + pgvector              │    │
│  │   document_chunks · config_llm · config_rag    │    │
│  └────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**双框架分工**：

| 职责 | 框架 | 说明 |
|------|------|------|
| 文档切分 | LangChain4j | DocumentSplitter + 自定义 AST/语义切分器 |
| 向量化 | LangChain4j | EmbeddingModel (Ollama/OpenAI) |
| 向量存储 | LangChain4j | PgVectorEmbeddingStore |
| 向量检索 | LangChain4j | EmbeddingStoreContentRetriever |
| 对话生成 | Spring AI | ChatClient (流式/非流式) |
| 模型管理 | Spring AI | OpenAiChatModel / AnthropicChatModel |

## 支持的文件类型

### 代码（AST/正则切分）

| 语言 | 扩展名 | 切分方式 |
|------|--------|---------|
| Java | `.java` | JavaParser AST（按类/方法/字段） |
| C# | `.cs` | 正则切分 |
| JavaScript | `.js` `.jsx` `.mjs` `.cjs` | 正则切分 |
| TypeScript | `.ts` `.tsx` | 正则切分 |
| Python | `.py` | 正则切分 |
| Go | `.go` | 正则切分 |
| Vue | `.vue` | 专用切分器 |
| QML | `.qml` | 专用切分器 |
| HTML | `.html` `.htm` | 专用切分器 |
| CSS | `.css` `.scss` | 专用切分器 |

### 文档（语义/递归切分）

| 格式 | 扩展名 | 解析方式 |
|------|--------|---------|
| Markdown | `.md` | 语义结构切分 |
| PDF | `.pdf` | Apache Tika + 语义切分 |
| Word | `.doc` `.docx` | Apache Tika + 语义切分 |
| Excel | `.xlsx` | Apache POI 结构化解析 |
| PPT | `.pptx` | Apache POI 结构化解析 |
| 文本 | `.txt` `.csv` `.json` `.xml` | 递归切分 |
| 配置 | `.yaml` `.yml` `.properties` `.conf` | 递归切分 |
| Shell | `.sh` `.bat` `.cmd` | 递归切分 |
| SQL | `.sql` | 递归切分 |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- PostgreSQL 16+（需安装 pgvector 扩展）
- Ollama（可选，用于本地 Embedding）

### 1. 初始化数据库

```bash
psql -U postgres -f src/main/resources/db/init.sql
```

### 2. 配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/my_rag
    username: postgres
    password: your_password
```

### 3. 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 运行
java -jar target/my-rag-java-0.0.1.jar

# 或开发模式
mvn spring-boot:run
```

### 4. 首次使用

1. 打开浏览器访问 `http://localhost:8080`
2. 进入 **设置** → 添加 Embedding 配置（Ollama 或 OpenAI）
3. 进入 **设置** → 添加 LLM 配置
4. 进入 **文档入库** → 添加项目路径 → 开始入库
5. 进入 **对话** → 开始提问

### 5. 启用 Reranking 精排（可选）

Reranking 通过 Cross-Encoder 模型对检索结果进行二次精排，显著提升回答准确率。

**部署方式**：使用 Ollama 编译的 llama-server 独立启动（官方暂未直接支持）。

```powershell
# 快速启动（Windows）
cd tools
start-qwen3-reranker.bat

# 停止
stop-qwen3-reranker.bat
```

**手动启动（参考）**：

```powershell
# 拉取模型
ollama pull AuditAid/Qwen3_Reranker:0.6B_Q8

# 启动 rerank 服务（固定端口 11435）
$llamaServer = "C:\Program Files\Ollama\lib\ollama\llama-server.exe"
$modelPath = "$env:USERPROFILE\.ollama\models\blobs\sha256-..."

Start-Process $llamaServer --rerank --model $modelPath --port 11435 --host 127.0.0.1 --ctx-size 8192 --no-webui
```

**验证服务**：

```bash
curl -X POST http://localhost:11435/v1/rerank \
  -H "Content-Type: application/json" \
  -d '{
    "model": "Qwen3-reranker",
    "query": "Apple",
    "documents": ["apple", "banana", "fruit", "vegetable"]
  }'
```

**系统集成**：在应用的 Reranking 配置页面添加配置并激活即可。

详细文档参见 `tools/RERANK_INSTALL.md` 和 `tools/RERANK_START.md`。

部署参考：[AuditAIH/audit-tool-skills/Rerank](https://github.com/AuditAIH/audit-tool-skills/tree/main/Rerank)

---

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat/stream` | 流式问答（SSE），返回 JSON 含 text + sources |
| `POST` | `/api/chat/save` | 保存问答记录 |
| `POST` | `/api/ingestion/scan` | 扫描路径，返回文件扩展名统计 |
| `POST` | `/api/ingestion/process` | 执行入库（SSE 流式进度） |
| `GET` | `/api/models` | 获取可用模型列表 |
| `GET/POST/PUT/DELETE` | `/api/llm-configs` | LLM 配置 CRUD |
| `GET/POST/PUT/DELETE` | `/api/embedding-configs` | Embedding 配置 CRUD |
| `GET/PUT` | `/api/configs` | RAG 参数配置 |
| `GET/POST/PUT/DELETE` | `/api/reranking-configs` | Reranking 配置 CRUD |
| `POST` | `/api/reranking-configs/{id}/test` | 测试 Reranking 连接 |
| `POST` | `/api/reranking-configs/{id}/activate` | 激活 Reranking 配置 |
| `GET` | `/api/dashboard/stats` | 仪表盘统计数据 |
| `GET` | `/api/dashboard/language-stats` | 语言分布统计 |
| `GET` | `/api/dashboard/recent-qa` | 最近问答记录 |

## 项目结构

```
my-rag-java/
├── tools/
│   ├── RERANK_INSTALL.md            # Reranking 安装手册
│   ├── RERANK_START.md              # Reranking 启动手册
│   ├── start-qwen3-reranker.bat     # Windows 启动脚本
│   └── stop-qwen3-reranker.bat      # Windows 停止脚本
├── src/main/java/com/he/
│   ├── RagApplication.java              # 启动类
│   ├── config/
│   │   └── LangChain4jConfig.java       # LangChain4j 配置（懒加载代理）
│   ├── controller/                      # REST 控制器
│   │   ├── RagChatController.java       # 对话端点
│   │   ├── IngestionController.java     # 入库端点
│   │   ├── LlmConfigController.java     # LLM 配置
│   │   ├── EmbeddingConfigController.java
│   │   ├── RagConfigController.java     # RAG 参数
│   │   ├── RerankingConfigController.java # Reranking 配置
│   │   ├── EvaluationController.java    # 离线评估
│   │   ├── FeedbackController.java      # 在线反馈
│   │   ├── ChatSessionController.java   # 会话管理
│   │   ├── DashboardController.java     # 仪表盘
│   │   └── ModelController.java         # 模型列表
│   ├── service/                         # 业务逻辑
│   │   ├── RagChatService.java          # RAG 问答核心（含 Agent 工具注册）
│   │   ├── IngestionService.java        # 入库管道
│   │   ├── ConversationService.java     # 对话历史管理
│   │   ├── SpringAiModelRouterService.java  # 模型路由
│   │   ├── RagConfigService.java        # 配置管理
│   │   ├── EmbeddingConfigService.java  # Embedding 配置
│   │   ├── RerankingService.java        # Reranking 精排
│   │   ├── RerankingConfigService.java  # Reranking 配置管理
│   │   ├── EvaluationService.java       # 离线评估引擎
│   │   ├── FeedbackService.java         # 在线反馈
│   │   ├── QueryRewriteService.java     # 查询改写
│   │   ├── AgentTools.java              # Agent 工具（4 个 @Tool）
│   │   └── AgentToolMetadata.java       # 工具调用元数据
│   ├── splitter/                        # 切分器（核心亮点）
│   │   ├── FileSplitterRouter.java      # 切分器路由
│   │   ├── JavaAstDocumentSplitter.java # Java AST 切分
│   │   ├── SemanticStructureSplitter.java # 语义结构切分
│   │   ├── RegexSplitter.java           # 正则切分（JS/TS/Python/Go）
│   │   ├── CSharpSplitter.java          # C# 切分
│   │   ├── VueSplitter.java             # Vue 切分
│   │   ├── HtmlSplitter.java            # HTML 切分
│   │   ├── CssSplitter.java             # CSS 切分
│   │   ├── ExcelStructuredParser.java   # Excel 结构化解析
│   │   └── PptxStructuredParser.java    # PPT 结构化解析
│   ├── entity/                          # JPA 实体
│   └── model/                           # 数据模型
├── src/main/resources/
│   ├── application.yml                  # 配置文件
│   ├── db/init.sql                      # 数据库初始化
│   ├── eval/testset.json                # 评估种子测试集
│   └── static/                          # 前端静态资源
│       ├── index.html                   # 主页面
│       ├── app.js                       # 前端逻辑（5 个 Tab）
│       ├── app.css                      # 样式
│       └── lib/                         # 第三方库（本地化）
└── src/test/                            # 测试（77 个 MockMvc + 37 个 Playwright）
    └── java/com/he/controller/         # Controller MockMvc 测试
├── tests/                               # Playwright 测试
│   ├── frontend.spec.ts                 # 前端 DOM/SSE 测试（27 个）
│   ├── integration-api.spec.ts          # API 集成测试（10 个）
│   ├── setup-test-data.js               # 测试数据准备脚本
│   └── playwright.config.ts             # Playwright 配置
```

## 配置说明

### RAG 参数（通过前端设置页或数据库调整）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `max_segment_size` | 1000 | 切分最大段长度（字符） |
| `max_overlap_size` | 200 | 相邻 chunk 重叠长度 |
| `semantic_threshold` | 0.65 | 语义切分相似度阈值 |
| `max_results` | 5 | 检索返回最大结果数 |
| `min_score` | 0.5 | 检索最低相似度分数 |
| `enable_noise_filter` | true | 启用噪声过滤 |
| `system_prompt` | (中文提示词) | RAG 系统提示词 |

### Embedding 模型切换

切换 Embedding 模型时，系统会自动 DROP 并重建 `document_chunks` 表（本地单用户场景可接受）。切换后需重新入库。

## 开发

```bash
# 运行全部测试
mvn test

# 运行单个测试
mvn test -Dtest=类名#方法名

# 构建（跳过测试）
mvn clean package -DskipTests

# Playwright 前端测试（需服务启动）
npx playwright test tests/frontend.spec.ts

# Playwright 集成测试（需服务启动 + 数据已入库）
npx playwright test tests/integration-api.spec.ts

# 准备测试数据
node tests/setup-test-data.js
```

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.1.0 + Spring WebFlux |
| RAG 框架 | LangChain4j 1.17.0 |
| AI 框架 | Spring AI 2.0.0 |
| 向量数据库 | PostgreSQL 16 + pgvector |
| 代码解析 | JavaParser 3.28.2 |
| 文档解析 | Apache Tika 3.3.1 + Apache POI 5.2.5 |
| 前端 | Tailwind CSS + ECharts + Mermaid + Highlight.js |
| 构建工具 | Maven |
| JDK | Java 21（虚拟线程） |

