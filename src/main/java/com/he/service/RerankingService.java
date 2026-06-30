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
 * 支持 Ollama /api/rerank 和远程 API（Jina/Cohere 等）两种接入方式。
 * 降级策略：不可用时返回原始顺序（log.warn）。
 */
@Service
public class RerankingService {

    private static final Logger log = LoggerFactory.getLogger(RerankingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private static final int MAX_DOC_LENGTH = 2000;

    private final RerankingConfigRepository rerankingConfigRepo;
    private final OkHttpClient httpClient;

    public RerankingService(RerankingConfigRepository rerankingConfigRepo) {
        this.rerankingConfigRepo = rerankingConfigRepo;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public List<Content> rerank(String query, List<Content> candidates) {
        if (candidates == null || candidates.size() <= 1) return candidates;

        RerankingConfigEntity config = rerankingConfigRepo.findByIsActiveTrue().stream().findFirst().orElse(null);
        if (config == null) {
            log.warn("Reranking 降级：无激活的 reranking 模型配置");
            return candidates;
        }

        List<String> documents = new ArrayList<>();
        for (Content content : candidates) {
            String text = content.textSegment().text();
            if (text.length() > MAX_DOC_LENGTH) text = text.substring(0, MAX_DOC_LENGTH) + "...";
            documents.add(text);
        }

        long start = System.nanoTime();
        List<Double> scores = "ollama".equals(config.getProvider())
                ? callOllamaRerank(config, query, documents)
                : callApiRerank(config, query, documents);
        if (scores != null) {
            log.debug("Reranking ({}) 耗时: {}ms", config.getProvider(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        if (scores == null || scores.size() != candidates.size()) {
            log.warn("Reranking 降级：分数数组大小不匹配");
            return candidates;
        }

        List<ScoredContent> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) scored.add(new ScoredContent(candidates.get(i), scores.get(i)));
        scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());

        log.debug("Reranking 完成: {} 个候选 → [{}, {}]", candidates.size(),
                String.format("%.4f", scored.get(0).score()),
                String.format("%.4f", scored.get(scored.size() - 1).score()));

        return scored.stream().map(ScoredContent::content).toList();
    }

    private List<Double> callOllamaRerank(RerankingConfigEntity config, String query, List<String> documents) {
        String url = (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) ? config.getBaseUrl() : "http://localhost:11434";
        url = url.replaceAll("/$", "");
        try {
            String body = MAPPER.writeValueAsString(Map.of("model", config.getModelName(), "query", query, "documents", documents));
            Request request = new Request.Builder().url(url + "/api/rerank").post(RequestBody.create(body, JSON_TYPE)).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) { log.warn("Ollama /api/rerank HTTP {}", response.code()); return null; }
                return parseScores(response.body() != null ? response.body().string() : "");
            }
        } catch (Exception e) { log.warn("Ollama /api/rerank 异常: {}", e.getMessage()); return null; }
    }

    private List<Double> callApiRerank(RerankingConfigEntity config, String query, List<String> documents) {
        String url = config.getBaseUrl();
        if (url == null || url.isBlank()) { log.warn("API reranking 未配置 baseUrl"); return null; }
        url = url.replaceAll("/$", "");
        try {
            // 通用 reranking API 格式（Jina/Cohere 兼容）
            String body = MAPPER.writeValueAsString(Map.of("model", config.getModelName(), "query", query, "documents", documents));
            Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(body, JSON_TYPE))
                    .addHeader("Content-Type", "application/json");
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                builder.addHeader("Authorization", "Bearer " + config.getApiKey());
            }
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) { log.warn("API reranking HTTP {}", response.code()); return null; }
                return parseScores(response.body() != null ? response.body().string() : "");
            }
        } catch (Exception e) { log.warn("API reranking 异常: {}", e.getMessage()); return null; }
    }

    private List<Double> parseScores(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode scoresNode = root.path("scores");
            if (scoresNode.isMissingNode() || !scoresNode.isArray()) return null;
            List<Double> scores = new ArrayList<>();
            for (JsonNode n : scoresNode) scores.add(n.asDouble());
            return scores;
        } catch (Exception e) { log.warn("解析 reranking 响应失败: {}", e.getMessage()); return null; }
    }

    private record ScoredContent(Content content, double score) {}
}
