package com.he.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.he.retriever.PgVectorKeywordContentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 问答服务。
 * 检索：LangChain4j EmbeddingStoreContentRetriever（Top-5）
 * 生成：Spring AI ChatClient（流式输出）
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final SpringAiModelRouterService modelRouter;
    private final LlmConfigService llmConfigService;
    private final RagConfigService ragConfigService;
    private final ConversationService conversationService;
    private final JdbcTemplate jdbcTemplate;
    private final String pgTable;
    private final RerankingService rerankingService;
    private final QueryRewriteService queryRewriteService;
    private final ObjectProvider<AgentTools> agentToolsProvider;

    /** 缓存各模型的流式支持标记，避免每次聊天查 DB */
    private final Map<UUID, Boolean> streamingCache = new ConcurrentHashMap<>();

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          SpringAiModelRouterService modelRouter,
                          LlmConfigService llmConfigService,
                          RagConfigService ragConfigService,
                          ConversationService conversationService,
                          JdbcTemplate jdbcTemplate,
                          @Value("${pgvector.table}") String pgTable,
                          RerankingService rerankingService,
                          QueryRewriteService queryRewriteService,
                          ObjectProvider<AgentTools> agentToolsProvider) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.modelRouter = modelRouter;
        this.llmConfigService = llmConfigService;
        this.ragConfigService = ragConfigService;
        this.conversationService = conversationService;
        this.jdbcTemplate = jdbcTemplate;
        this.pgTable = pgTable;
        this.rerankingService = rerankingService;
        this.queryRewriteService = queryRewriteService;
        this.agentToolsProvider = agentToolsProvider;
    }

    /**
     * 执行检索 + 可选 reranking，返回最终的 Content 列表。
     * 流程：查询改写 → 检索 → 去重 → reranking。
     */
    private List<Content> retrieveAndRerank(String query, List<Message> history) {
        long retrieveStart = System.nanoTime();
        // 1. 查询改写（可选）
        String searchQuery = query;
        if (ragConfigService.getBoolean("enable_query_rewrite", false)) {
            List<Message> recent = history.size() > 2
                    ? history.subList(history.size() - 2, history.size()) : history;
            String rewritten = queryRewriteService.rewrite(query, recent);
            if (rewritten != null && !rewritten.isBlank()) {
                searchQuery = rewritten;
                log.debug("查询改写: '{}' → '{}'", query, searchQuery);
            }
        }

        // 2. 检索
        boolean rerankingEnabled = ragConfigService.getBoolean("enable_reranking", false);
        int maxResults = rerankingEnabled
                ? Math.max(ragConfigService.getInt("reranking_pool_size", 20),
                           ragConfigService.getInt("reranking_top_n", 3))
                : ragConfigService.getInt("max_results", 5);

        List<Content> contents = buildRetrieverWithPool(maxResults).retrieve(Query.from(searchQuery));

        // 3. 按 file_path 去重（同一文件保留第一个）
        contents = deduplicateByFilePath(contents);

        // 4. Reranking 精排（可选）
        if (rerankingEnabled && contents.size() > 1) {
            int topN = ragConfigService.getInt("reranking_top_n", 3);
            int beforeSize = contents.size();
            contents = rerankingService.rerank(searchQuery, contents);
            int actualTopN = Math.min(topN, contents.size());
            contents = contents.subList(0, actualTopN);
            log.debug("Reranking: 候选 {} → 精排后保留 {} 条", beforeSize, contents.size());
        }

        long retrieveMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retrieveStart);
        log.debug("检索管线总耗时: {}ms (query='{}')", retrieveMs, query);
        return contents;
    }

    /**
     * 按 file_path 去重，同一文件保留第一个（最高分）。
     */
    private List<Content> deduplicateByFilePath(List<Content> contents) {
        LinkedHashMap<String, Content> unique = new LinkedHashMap<>();
        for (Content c : contents) {
            String key = contentKey(c);
            unique.putIfAbsent(key, c);
        }
        return new ArrayList<>(unique.values());
    }

    private static String contentKey(Content c) {
        var meta = c.textSegment().metadata();
        String path = meta.getString("file_path");
        String type = meta.getString("type");
        String sig = meta.getString("signature");
        return (path != null ? path : "") + "|" + (type != null ? type : "") + "|" + (sig != null ? sig : "");
    }

    /**
     * 构建指定候选池大小的检索器。
     */
    private ContentRetriever buildRetrieverWithPool(int poolSize) {
        double minScore = ragConfigService.getDouble("min_score", 0.5);
        boolean bm25Enabled = ragConfigService.getBoolean("enable_bm25", true);

        EmbeddingStoreContentRetriever vectorRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(poolSize)
                .minScore(minScore)
                .build();

        if (!bm25Enabled) {
            return vectorRetriever;
        }

        PgVectorKeywordContentRetriever keywordRetriever =
                new PgVectorKeywordContentRetriever(jdbcTemplate, pgTable, poolSize);

        return new HybridContentRetriever(vectorRetriever, keywordRetriever, poolSize);
    }

    /**
     * 仅执行检索（无 LLM 生成），供评估服务调用。
     */
    public List<Content> retrieve(String query) {
        List<Message> history = List.of();
        return retrieveAndRerank(query, history);
    }

    /** 清除流式支持缓存（测试连接后调用） */
    public void evictStreamingCache(UUID configId) {
        streamingCache.remove(configId);
    }

    /**
     * 执行 RAG 问答：检索相关文档 → 构建 Prompt（System + History + User）→ 流式生成。
     * <p>
     * Prompt 构造顺序：
     * <ol>
     *   <li><b>SystemMessage</b> — 系统角色提示 + 检索到的 RAG 上下文（始终保留在 Prompt 中，不入历史）</li>
     *   <li><b>历史消息</b> — 过往的 UserMessage/AssistantMessage 对（从 ConversationService 获取）</li>
     *   <li><b>UserMessage</b> — 当前用户问题</li>
     * </ol>
     *
     * @param query    用户当前输入
     * @param modelKey 模型配置 ID
     * @param sessionId 会话 ID（用于多轮对话隔离）
     */
    public Flux<String> chat(String query, String modelKey, String sessionId) {
        // 1. 获取历史（供改写 + prompt 构建，只查一次）
        List<Message> history = conversationService.getHistory(sessionId);

        // 2. 检索 + 可选改写 + 可选 reranking
        List<Content> contents = retrieveAndRerank(query, history);
        String context = contents.stream()
                .map(Content::textSegment)
                .map(segment -> {
                    String source = segment.metadata().getString("file_path");
                    String type = segment.metadata().getString("type");
                    String signature = segment.metadata().getString("signature");
                    return "[" + type + "] " + signature + " (" + source + ")\n" + segment.text();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        log.debug("检索到 {} 个相关片段", contents.size());

        // 2. 组装 SystemMessage（包含 RAG 检索结果——不入历史）
        String systemPrompt = ragConfigService.getSystemPrompt()
                + "\n\n=== 检索到的相关内容 ===\n"
                + context + "\n"
                + "=== 内容结束 ===";

        log.debug("会话 {}: 历史消息 {} 条", sessionId, history.size());

        // 3. 验证模型配置
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("请选择一个 LLM 模型");
        }
        UUID configId;
        try {
            configId = UUID.fromString(modelKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的模型 ID: " + modelKey);
        }
        ChatClient chatClient = modelRouter.getChatClient(configId);

        boolean streaming = streamingCache.computeIfAbsent(configId,
                id -> llmConfigService.findRawById(id)
                        .map(e -> Boolean.TRUE.equals(e.getSupportsStreaming()))
                        .orElse(false));

        // 5. 构建 Prompt：System + History + User，保存对话到历史
        if (streaming) {
            log.debug("使用流式对话, 会话: {}", sessionId);

            // 保存用户消息到历史（必须在流开始前执行，确保并发安全）
            conversationService.addUserMessage(sessionId, query);
            StringBuilder fullResponse = new StringBuilder();

            var spec = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(agentToolsProvider.getObject());
            if (!history.isEmpty()) {
                spec.messages(history);
            }

            return spec
                    .user(query)
                    .stream()
                    .content()
                    .doOnNext(token -> fullResponse.append(token))
                    .doOnComplete(() -> {
                        String answer = fullResponse.toString();
                        conversationService.addAssistantMessage(sessionId, answer);
                        log.debug("会话 {}: 流式完成，保存回复完成", sessionId);
                    })
                    .doOnError(e -> {
                        log.error("会话 {}: 流式生成异常", sessionId, e);
                    });
        } else {
            log.debug("使用非流式对话, 会话: {}", sessionId);

            var spec = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(agentToolsProvider.getObject());
            if (!history.isEmpty()) {
                spec.messages(history);
            }

            return Flux.defer(() -> {
                String response = spec
                        .user(query)
                        .call()
                        .content();
                String result = response != null ? response : "模型返回为空。";

                // 保存用户问题 + 助手回复到历史
                conversationService.addUserMessage(sessionId, query);
                conversationService.addAssistantMessage(sessionId, result);
                log.debug("会话 {}: 非流式完成，保存回复完成", sessionId);

                String toolMeta = buildToolMetadataJson();
                return Flux.just("{\"text\":" + jsonEscape(result) + toolMeta + "}");
            });
        }
    }

    // ═══════════════════════════════════════════════════
    //  带引用的流式问答（前端 citations 展示用）
    // ═══════════════════════════════════════════════════

    /** 引用文献 */
    public record Citation(int id, String name, String path) {}

    /** 流式 chunk（含文本和引用） */
    public record ChatChunk(String text, List<Citation> citations) {}

    /**
     * 带引用的流式问答。
     * <p>
     * 返回的 Flux 中每个元素都是 JSON 字符串：
     * <ul>
     *   <li>第一个元素: {@code {"text":"","sources":[{"id":1,"name":"file.java","path":"/docs/file.java"}]}}</li>
     *   <li>后续元素: {@code {"text":"token内容"}}</li>
     * </ul>
     */
    public Flux<String> chatWithCitations(String query, String modelKey, String sessionId) {
        // 1. 获取历史（供改写 + prompt 构建）
        List<Message> history = conversationService.getHistory(sessionId);

        // 2. 检索 + 可选改写 + 可选 reranking
        List<Content> contents = retrieveAndRerank(query, history);

        // 2. 构建引用列表（按 file_path 去重，保持顺序）
        List<Citation> citations = buildCitations(contents);

        // 3. 组装 SystemMessage（包含 RAG 检索结果——不入历史）
        String context = contents.stream()
                .map(Content::textSegment)
                .map(segment -> {
                    String source = segment.metadata().getString("file_path");
                    String type = segment.metadata().getString("type");
                    String signature = segment.metadata().getString("signature");
                    return "[" + type + "] " + signature + " (" + source + ")\n" + segment.text();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = ragConfigService.getSystemPrompt()
                + "\n\n=== 检索到的相关内容 ===\n"
                + context + "\n"
                + "=== 内容结束 ===";

        // 4. 验证模型配置
        if (modelKey == null || modelKey.isBlank()) {
            throw new IllegalArgumentException("请选择一个 LLM 模型");
        }
        UUID configId;
        try {
            configId = UUID.fromString(modelKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的模型 ID: " + modelKey);
        }
        ChatClient chatClient = modelRouter.getChatClient(configId);

        boolean streaming = streamingCache.computeIfAbsent(configId,
                id -> llmConfigService.findRawById(id)
                        .map(e -> Boolean.TRUE.equals(e.getSupportsStreaming()))
                        .orElse(false));

        // 6. 构建引用 JSON（发送给前端）
        String sourcesJson = buildSourcesJson(citations);

        if (streaming) {
            log.debug("使用流式对话（带引用）, 会话: {}", sessionId);

            conversationService.addUserMessage(sessionId, query);
            StringBuilder fullResponse = new StringBuilder();

            var spec = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(agentToolsProvider.getObject());
            if (!history.isEmpty()) {
                spec.messages(history);
            }

            final String sourcesJsonFinal = sourcesJson;

            return spec
                    .user(query)
                    .stream()
                    .content()
                    .index((idx, token) -> {
                        fullResponse.append(token);
                        if (idx == 0) {
                            // 第一个 token：携带引用信息
                            return "{\"text\":" + jsonEscape(token) + ",\"sources\":" + sourcesJsonFinal + "}";
                        } else {
                            return "{\"text\":" + jsonEscape(token) + "}";
                        }
                    })
                    .doOnComplete(() -> {
                        String answer = fullResponse.toString();
                        conversationService.addAssistantMessage(sessionId, answer);
                        log.debug("会话 {}: 流式完成（带引用），保存回复完成", sessionId);
                    })
                    .doOnError(e -> {
                        log.error("会话 {}: 流式生成异常", sessionId, e);
                    })
                    .concatWith(Flux.defer(() -> {
                        String toolMeta = buildToolMetadataJson();
                        if (toolMeta.isEmpty()) return Flux.empty();
                        return Flux.just("{\"text\":\"\",\"toolMetadata\":" + toolMeta.substring(1) + "}");
                    }));
        } else {
            log.debug("使用非流式对话（带引用）, 会话: {}", sessionId);

            var spec = chatClient.prompt()
                    .system(systemPrompt)
                    .tools(agentToolsProvider.getObject());
            if (!history.isEmpty()) {
                spec.messages(history);
            }

            return Flux.defer(() -> {
                String response = spec
                        .user(query)
                        .call()
                        .content();
                String result = response != null ? response : "模型返回为空。";

                conversationService.addUserMessage(sessionId, query);
                conversationService.addAssistantMessage(sessionId, result);
                log.debug("会话 {}: 非流式完成（带引用），保存回复完成", sessionId);

                // 非流式：一次性返回完整结果 + 引用 + 工具调用元数据
                String toolMeta = buildToolMetadataJson();
                return Flux.just("{\"text\":" + jsonEscape(result) + ",\"sources\":" + sourcesJson + toolMeta + "}");
            });
        }
    }

    /**
     * 从检索结果构建引用列表（按 file_path 去重，保持首次出现顺序）。
     */
    private List<Citation> buildCitations(List<Content> contents) {
        List<Citation> citations = new ArrayList<>();
        java.util.Map<String, Integer> pathToId = new java.util.LinkedHashMap<>();
        int nextId = 1;

        for (Content content : contents) {
            String filePath = content.textSegment().metadata().getString("file_path");
            if (filePath == null || filePath.isBlank()) continue;

            if (!pathToId.containsKey(filePath)) {
                pathToId.put(filePath, nextId++);
            }

            // 从路径提取文件名
            String name = filePath;
            int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            if (lastSlash >= 0) {
                name = filePath.substring(lastSlash + 1);
            }

            // 避免重复添加同一路径的引用
            String finalName = name;
            if (citations.stream().noneMatch(c -> c.path().equals(filePath))) {
                citations.add(new Citation(pathToId.get(filePath), finalName, filePath));
            }
        }

        return citations;
    }

    /**
     * 构建前端引用 JSON 数组。
     */
    private String buildSourcesJson(List<Citation> citations) {
        if (citations.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(c.id())
              .append(",\"name\":").append(jsonEscape(c.name()))
              .append(",\"path\":").append(jsonEscape(c.path()))
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * JSON 字符串转义（处理引号、换行、反斜杠）。
     */
    private String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    /**
     * 构建工具调用元数据 JSON（追加到 sources 后面）。
     */
    private String buildToolMetadataJson() {
        List<AgentToolMetadata.ToolCallRecord> calls = AgentToolMetadata.collectAndClear();
        if (calls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(",\"toolMetadata\":[");
        for (int i = 0; i < calls.size(); i++) {
            AgentToolMetadata.ToolCallRecord c = calls.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"tool\":").append(jsonEscape(c.toolName()))
              .append(",\"args\":").append(jsonEscape(c.args()))
              .append(",\"duration\":").append(c.durationMs()).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    //  混合检索器：向量 + BM25，RRF 融合
    // ═══════════════════════════════════════════════════

    /**
     * 混合检索器：同时调用向量检索和 BM25 关键词检索，使用 RRF 融合排序。
     */
    private static class HybridContentRetriever implements ContentRetriever {
        private final EmbeddingStoreContentRetriever vectorRetriever;
        private final PgVectorKeywordContentRetriever keywordRetriever;
        private final int maxResults;
        /**
         * RRF 融合常数 k。
         * 标准 RRF（Reciprocal Rank Fusion）论文推荐值 k=60。
         * 值越小，排名靠前的结果权重越高；值越大，排名靠后的结果也有显著贡献。
         * 若需调优，可提取为 rag_config 配置项。
         */
        private static final int RRF_K = 60;

        HybridContentRetriever(EmbeddingStoreContentRetriever vectorRetriever,
                               PgVectorKeywordContentRetriever keywordRetriever,
                               int maxResults) {
            this.vectorRetriever = vectorRetriever;
            this.keywordRetriever = keywordRetriever;
            this.maxResults = maxResults;
        }

        @Override
        public List<Content> retrieve(Query query) {
            List<Content> vectorResults;
            List<Content> keywordResults;

            try {
                vectorResults = vectorRetriever.retrieve(query);
            } catch (Exception e) {
                log.warn("向量检索失败: {}", e.getMessage());
                vectorResults = List.of();
            }

            try {
                keywordResults = keywordRetriever.retrieve(query);
            } catch (Exception e) {
                log.warn("BM25 检索失败: {}", e.getMessage());
                keywordResults = List.of();
            }

            if (vectorResults.isEmpty() && keywordResults.isEmpty()) {
                return List.of();
            }
            if (keywordResults.isEmpty()) {
                return vectorResults;
            }
            if (vectorResults.isEmpty()) {
                return keywordResults;
            }

            // RRF 融合：score = Σ 1/(k + rank_i)
            Map<String, Double> rrfScores = new LinkedHashMap<>();
            Map<String, Content> contentMap = new LinkedHashMap<>();

            int rank = 0;
            for (Content c : vectorResults) {
                String key = contentKey(c);
                rrfScores.merge(key, 1.0 / (RRF_K + rank++), Double::sum);
                contentMap.putIfAbsent(key, c);
            }
            rank = 0;
            for (Content c : keywordResults) {
                String key = contentKey(c);
                rrfScores.merge(key, 1.0 / (RRF_K + rank++), Double::sum);
                contentMap.putIfAbsent(key, c);
            }

            log.debug("RRF 融合: 向量 {} 条, BM25 {} 条, 融合后 {} 条",
                    vectorResults.size(), keywordResults.size(), rrfScores.size());

            return rrfScores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(maxResults)
                    .map(e -> contentMap.get(e.getKey()))
                    .toList();
        }

        /**
         * 生成 Content 的去重 key（file_path + type + signature）。
         * 加入 type 字段防止 class/method/constructor 的 signature 值巧合相同导致误合并。
         */
        private static String contentKey(Content content) {
            TextSegment seg = content.textSegment();
            String filePath = seg.metadata().getString("file_path");
            String type = seg.metadata().getString("type");
            String signature = seg.metadata().getString("signature");
            return (filePath != null ? filePath : "") + "|"
                 + (type != null ? type : "") + "|"
                 + (signature != null ? signature : "");
        }
    }
}

