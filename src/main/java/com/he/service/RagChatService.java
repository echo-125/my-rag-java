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

    /** 缓存各模型的流式支持标记，避免每次聊天查 DB */
    private final Map<UUID, Boolean> streamingCache = new ConcurrentHashMap<>();

    public RagChatService(EmbeddingStore<TextSegment> embeddingStore,
                          EmbeddingModel embeddingModel,
                          SpringAiModelRouterService modelRouter,
                          LlmConfigService llmConfigService,
                          RagConfigService ragConfigService) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.modelRouter = modelRouter;
        this.llmConfigService = llmConfigService;
        this.ragConfigService = ragConfigService;
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
     * 执行 RAG 问答：检索相关文档 → 组装 Prompt → 流式生成。
     */
    public Flux<String> chat(String query, String modelKey) {
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

        // 2. 组装 Prompt（从配置读取系统提示词，支持动态调整）
        String systemPrompt = ragConfigService.getSystemPrompt()
                + "\n\n=== 检索到的相关内容 ===\n"
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

