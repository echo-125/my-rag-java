package com.he.service;

import com.he.entity.RerankingConfigEntity;
import com.he.entity.RerankingConfigRepository;
import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RerankingConfigService {

    private static final Logger log = LoggerFactory.getLogger(RerankingConfigService.class);

    private final RerankingConfigRepository repository;
    private final OkHttpClient httpClient;
    private final JdbcTemplate jdbcTemplate;

    public RerankingConfigService(RerankingConfigRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 启动时校验 config_reranking 表 schema，旧表（ollama_url 列）自动重建。
     */
    @PostConstruct
    public void ensureTableSchema() {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'config_reranking')",
                    Boolean.class);
            if (Boolean.TRUE.equals(exists)) {
                Boolean hasOldColumn = jdbcTemplate.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'config_reranking' AND column_name = 'ollama_url')",
                        Boolean.class);
                if (Boolean.TRUE.equals(hasOldColumn)) {
                    log.warn("config_reranking 表为旧 schema（含 ollama_url 列），自动重建");
                    jdbcTemplate.execute("DROP TABLE config_reranking");
                    // JPA 会在下次访问时自动重建
                }
            }
        } catch (Exception e) {
            log.warn("config_reranking 表 schema 校验失败: {}", e.getMessage());
        }
    }

    public List<RerankingConfigEntity> findAll() { return repository.findAll(); }
    public Optional<RerankingConfigEntity> findById(UUID id) { return repository.findById(id); }
    public RerankingConfigEntity save(RerankingConfigEntity entity) { return repository.save(entity); }

    @Transactional
    public RerankingConfigEntity update(UUID id, RerankingConfigEntity updated) {
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setName(updated.getName());
        entity.setProvider(updated.getProvider());
        entity.setModelName(updated.getModelName());
        entity.setBaseUrl(updated.getBaseUrl());
        if (updated.getApiKey() != null && !updated.getApiKey().isBlank()) {
            entity.setApiKey(updated.getApiKey());
        }
        return repository.save(entity);
    }

    public void delete(UUID id) { repository.deleteById(id); }

    @Transactional
    public RerankingConfigEntity activate(UUID id) {
        repository.findByIsActiveTrue().forEach(e -> { e.setIsActive(false); repository.save(e); });
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(true);
        return repository.save(entity);
    }

    @Transactional
    public RerankingConfigEntity deactivate(UUID id) {
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(false);
        return repository.save(entity);
    }

    /**
     * 测试连接：Ollama 检查 /api/tags，API 模式尝试轻量请求。
     */
    public TestResult testConnection(UUID id) {
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        try {
            String baseUrl = entity.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:11434";
            }
            baseUrl = baseUrl.replaceAll("/$", "");

            if ("ollama".equals(entity.getProvider())) {
                return testOllama(baseUrl, entity.getModelName());
            } else {
                return testApi(baseUrl, entity.getApiKey());
            }
        } catch (Exception e) {
            return new TestResult(false, "连接失败: " + e.getMessage());
        }
    }

    private TestResult testOllama(String url, String model) throws Exception {
        Request request = new Request.Builder().url(url + "/api/tags").get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new TestResult(false, "Ollama 服务不可达 HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            // 精确匹配：解析 JSON 中 models[].name
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            boolean found = false;
            if (root.has("models")) {
                for (com.fasterxml.jackson.databind.JsonNode m : root.get("models")) {
                    if (model.equals(m.path("name").asText())) { found = true; break; }
                }
            }
            if (found) {
                return new TestResult(true, "模型 " + model + " 已就绪");
            } else {
                return new TestResult(false, "模型 " + model + " 未找到，请先 pull 模型");
            }
        }
    }

    private TestResult testApi(String url, String apiKey) throws Exception {
        // POST 空请求体测试端点可达性（reranking API 只接受 POST）
        String body = "{\"model\":\"test\",\"query\":\"test\",\"documents\":[\"test\"]}";
        Request.Builder builder = new Request.Builder().url(url)
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/json")));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            int code = response.code();
            // 2xx 或 4xx（参数错误但端点可达）都视为成功
            if (code >= 200 && code < 500) {
                return new TestResult(true, "API 端点可达 HTTP " + code);
            } else {
                return new TestResult(false, "API 端点返回 HTTP " + code);
            }
        }
    }

    public record TestResult(boolean success, String message) {}
}
