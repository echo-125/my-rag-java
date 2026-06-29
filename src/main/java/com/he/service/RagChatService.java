package com.he.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

    /** 缓存各模型的流式支持标记，避免每次聊天查 DB */
    private final Map<UUID, Boolean> streamingCache = new ConcurrentHashMap<>();

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          SpringAiModelRouterService modelRouter,
                          LlmConfigService llmConfigService,
                          RagConfigService ragConfigService,
                          ConversationService conversationService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.modelRouter = modelRouter;
        this.llmConfigService = llmConfigService;
        this.ragConfigService = ragConfigService;
        this.conversationService = conversationService;
    }

    /**
     * 每次调用时动态从配置读取 maxResults / minScore 构建 Retriever。
     * EmbeddingStore 和 EmbeddingModel 是单例 Bean，Builder 开销极低，无需缓存。
     */
    private EmbeddingStoreContentRetriever buildRetriever() {
        int maxResults = ragConfigService.getInt("max_results", 5);
        double minScore = ragConfigService.getDouble("min_score", 0.5);
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
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
        // 1. 检索
        List<Content> contents = buildRetriever().retrieve(Query.from(query));
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

        // 3. 获取历史消息（用于构建 Prompt，但不修改历史）
        List<Message> history = conversationService.getHistory(sessionId);
        log.debug("会话 {}: 历史消息 {} 条", sessionId, history.size());

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

        // 5. 构建 Prompt：System + History + User，保存对话到历史
        if (streaming) {
            log.debug("使用流式对话, 会话: {}", sessionId);

            // 保存用户消息到历史（必须在流开始前执行，确保并发安全）
            conversationService.addUserMessage(sessionId, query);
            StringBuilder fullResponse = new StringBuilder();

            var spec = chatClient.prompt()
                    .system(systemPrompt);
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
                    .system(systemPrompt);
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

                return Flux.just(result);
            });
        }
    }
}

