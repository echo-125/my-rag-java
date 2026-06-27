package com.insolu.rag.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
    private static final int MAX_RESULTS = 5;
    private static final double MIN_SCORE = 0.5;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final SpringAiModelRouterService modelRouter;
    private final LlmConfigService llmConfigService;

    /** 缓存 Retriever 实例，避免每次查询重建 */
    private volatile EmbeddingStoreContentRetriever cachedRetriever;
    /** 缓存各模型的流式支持标记，避免每次聊天查 DB */
    private final Map<UUID, Boolean> streamingCache = new ConcurrentHashMap<>();

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          SpringAiModelRouterService modelRouter,
                          LlmConfigService llmConfigService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.modelRouter = modelRouter;
        this.llmConfigService = llmConfigService;
    }

    /**
     * 获取或创建缓存的 Retriever。
     * embeddingStore 和 embeddingModel 是单例 Bean，生命周期与应用一致，缓存安全。
     */
    private EmbeddingStoreContentRetriever getRetriever() {
        if (cachedRetriever == null) {
            synchronized (this) {
                if (cachedRetriever == null) {
                    cachedRetriever = EmbeddingStoreContentRetriever.builder()
                            .embeddingStore(embeddingStore)
                            .embeddingModel(embeddingModel)
                            .maxResults(MAX_RESULTS)
                            .minScore(MIN_SCORE)
                            .build();
                }
            }
        }
        return cachedRetriever;
    }

    /** 清除流式支持缓存（测试连接后调用） */
    public void evictStreamingCache(UUID configId) {
        streamingCache.remove(configId);
    }

    /**
     * 执行 RAG 问答：检索相关文档 → 组装 Prompt → 流式生成。
     */
    public Flux<String> chat(String query, String modelKey) {
        // 1. 检索
        List<Content> contents = getRetriever().retrieve(Query.from(query));
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

        // 2. 组装 Prompt
        String systemPrompt = "你是一个代码和文档助手。根据以下检索到的相关代码/文档片段回答用户问题。\n"
                + "如果检索结果中没有相关信息，请如实说明。\n\n"
                + "=== 检索到的相关内容 ===\n"
                + context + "\n"
                + "=== 内容结束 ===";

        // 3. 根据 supportsStreaming 标记精确选择调用方式
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

        if (streaming) {
            log.debug("使用流式对话");
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .stream()
                    .content();
        } else {
            log.debug("使用非流式对话（API 不支持流式）");
            return Flux.defer(() -> {
                String response = chatClient.prompt()
                        .system(systemPrompt)
                        .user(query)
                        .call()
                        .content();
                return Flux.just(response != null ? response : "模型返回为空。");
            });
        }
    }
}
