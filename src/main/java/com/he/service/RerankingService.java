package com.he.service;

import com.he.entity.RerankingConfigEntity;
import com.he.entity.RerankingConfigRepository;
import dev.langchain4j.rag.content.Content;
import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cross-Encoder Reranking 服务。
 * <p>
 * 调用 Ollama /api/rerank 端点，一次 HTTP 请求完成所有候选文档的评分，
 * 按相关性分数降序重排。
 * <p>
 * 降级策略：Ollama 不可用或解析失败时，返回原始顺序（log.warn）。
 */
@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    /** 文档文本截断长度，超出部分丢弃（与模型上下文窗口相关） */
    private static final int MAX_DOC_LENGTH = 2000;

    private final RagConfigService ragConfigService;
    private final RerankingConfigRepository rerankingConfigRepo;
    private final OkHttpClient httpClient;

    public RerankingService(RagConfigService ragConfigService, RerankingConfigRepository rerankingConfigRepo) {
        this.ragConfigService = ragConfigService;
        this.rerankingConfigRepo = rerankingConfigRepo;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 对候选文档进行 reranking。
     * 一次 HTTP 调用完成所有候选评分，Ollama 内部 batch 推理。
     *
     * @param query      用户查询
     * @param candidates 候选文档列表（来自混合检索）
     * @return 按相关性降序重排的文档列表
     */
    public List<Content> rerank(String query, List<Content> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return candidates;
        }

        // 从 reranking_config 表读取激活的配置
        RerankingConfigEntity activeConfig = rerankingConfigRepo.findByIsActiveTrue().stream().findFirst().orElse(null);
        if (activeConfig == null) {
            log.warn("Reranking 降级：无激活的 reranking 模型配置");
            return candidates;
        }
        String ollamaUrl = activeConfig.getOllamaUrl();
        if (ollamaUrl == null || ollamaUrl.isBlank()) {
            ollamaUrl = "http://localhost:11434";
        }
        String model = activeConfig.getModelName();

        // 构建 documents 列表（截断过长文本）
        List<String> documents = new ArrayList<>();
        for (Content content : candidates) {
            String text = content.textSegment().text();
            if (text.length() > MAX_DOC_LENGTH) {
                text = text.substring(0, MAX_DOC_LENGTH) + "...";
            }
            documents.add(text);
        }

        // 一次 HTTP 调用完成所有评分
        long start = System.nanoTime();
        List<Double> scores = callRerankApi(ollamaUrl, model, query, documents);
        if (scores != null) {
            log.debug("Reranking 耗时: {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        if (scores == null || scores.size() != candidates.size()) {
            log.warn("Reranking 降级：返回原始排序（分数数组大小不匹配）");
            return candidates;
        }

        // 按分数降序重排
        List<ScoredContent> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new ScoredContent(candidates.get(i), scores.get(i)));
        }
        scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());

        log.debug("Reranking 完成: {} 个候选 → 分数 [{}, {}]",
                candidates.size(),
                String.format("%.4f", scored.get(0).score()),
                String.format("%.4f", scored.get(scored.size() - 1).score()));

        return scored.stream().map(ScoredContent::content).toList();
    }

    /**
     * 调用 Ollama /api/rerank 端点，一次完成所有候选评分。
     */
    private List<Double> callRerankApi(String ollamaUrl, String model, String query, List<String> documents) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "query", query,
                    "documents", documents
            );
            String requestBody = MAPPER.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(ollamaUrl + "/api/rerank")
                    .post(RequestBody.create(requestBody, JSON_TYPE))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Ollama /api/rerank 请求失败 HTTP {}: {}", response.code(), response.message());
                    return null;
                }
                String respBody = response.body() != null ? response.body().string() : "";
                return parseRerankResponse(respBody);
            }
        } catch (IOException e) {
            log.warn("Ollama /api/rerank 连接失败: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Ollama /api/rerank 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 Ollama /api/rerank 响应。
     * 响应格式：{"model":"xxx","scores":[0.95, 0.12, 0.87, ...]}
     */
    private List<Double> parseRerankResponse(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode scoresNode = root.path("scores");
            if (scoresNode.isMissingNode() || !scoresNode.isArray()) {
                log.warn("Ollama /api/rerank 响应中无 scores 数组");
                return null;
            }
            List<Double> scores = new ArrayList<>();
            for (JsonNode scoreNode : scoresNode) {
                scores.add(scoreNode.asDouble());
            }
            return scores;
        } catch (Exception e) {
            log.warn("解析 reranking 响应失败: {}", e.getMessage());
            return null;
        }
    }

    /** 带分数的 Content 包装 */
    private record ScoredContent(Content content, double score) {}
}
