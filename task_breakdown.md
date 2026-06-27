- Task 1: 基础工程与双框架依赖配置
  - Prompt: “根据 claude.md，帮我生成 Spring Boot 3.3 的 pom.xml。引入 LangChain4j 1.0.0-beta1+ 核心包（dev.langchain4j:langchain4j）和 pgvector 包（dev.langchain4j:langchain4j-pgvector），引入 Spring AI 1.0.0 GA+ 的 openai starter（org.springframework.ai:spring-ai-starter-model-openai）。引入 PostgreSQL 驱动、JPA、JavaParser。确保依赖无冲突。配置 application.yml 包含数据库连接和预置的多 LLM API 配置。”
- Task 2: 数据库实体与 LangChain4j EmbeddingStore
  - Prompt: “使用 JPA 创建 DocumentChunkEntity，包含 pgvector 向量字段和 8 个元数据字段。然后实现一个 LangChain4j 的 EmbeddingStore<TextSegment> 接口（dev.langchain4j.store.embedding.EmbeddingStore）的实现类 PgVectorEmbeddingStore，实现 add(Embedding, TextSegment)、addAll(List<Embedding>, List<TextSegment>)、findRelevant(Embedding, int, double) 等方法，使用 JPA 或 JdbcTemplate 进行向量存储和混合检索查询。”
- Task 3: Java AST 切分器与其他文档处理器
  - Prompt: “实现一个 JavaAstDocumentSplitter 实现 LangChain4j 的 DocumentSplitter 接口（dev.langchain4j.data.document.splitter.DocumentSplitter，方法：List<TextSegment> split(Document)），使用 JavaParser 将 Java 代码按类和方法切分，保留 8 个元数据。对于 JS/TS/Python，实现一个通用的 RegexSplitter。对于 PDF/Word/MD，使用 LangChain4j 的 DocumentByParagraphSplitter 或 DocumentByLineSplitter。”
- Task 4: Spring AI 动态模型路由
  - Prompt: “实现一个 SpringAiModelRouterService。读取 application.yml 中的模型列表配置，维护一个 Map<String, ChatModel>（org.springframework.ai.chat.model.ChatModel）。提供方法 getChatClient(String modelKey) 返回对应的 Spring AI ChatClient（通过 ChatClient.builder(chatModel).build() 构建）。”
- Task 5: 离线入库与 SSE 可视化
  - Prompt: “创建 IngestionController。提供一个 POST 接口接收前端传来的多个本地路径数组和处理选项。后端遍历路径，扫描文件，调用相应的 Splitter 切分，调用 EmbeddingModel 向量化并存入 EmbeddingStore。整个处理过程必须通过 SseEmitter 实时推送进度信息给前端，包括：当前处理文件名、切分出的 chunk 数、失败原因等。”
- Task 6: 在线问答与混合检索
  - Prompt: “创建 RagChatController。接收用户 query 和 modelKey。使用 LangChain4j 的 EmbeddingStoreContentRetriever（dev.langchain4j.rag.retriever.EmbeddingStoreContentRetriever，通过 builder 模式构建，设置 embeddingStore、embeddingModel、maxResults(5)）从 pgvector 检索相关代码/文档片段。将检索到的内容作为 context，使用 Spring AI 的 ChatClient（由 ModelRouter 获取）通过 chatClient.prompt().user(context + query).stream().content() 进行流式 (SSE) 返回。”
- Task 7: 前端页面开发 (配置、可视化、对话)
  - Prompt: "在 static 目录开发 index.html。使用本地 Tailwind CSS, ECharts, Mermaid。
    1. 首页顶部是一个配置面板，支持输入多个本地路径，点击’开始入库’按钮触发后端 SSE 接口，并在下方实时显示处理日志流。
    2. 左侧是聊天界面，顶部有模型切换下拉框，使用 fetch 调用后端 SSE 接口并渲染 Markdown。
    3. 右侧是 Dashboard，用 Mermaid 画出系统架构流程图，用 ECharts 画出各项目代码量统计。"

