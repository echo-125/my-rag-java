package com.insolu.rag.service;

import com.insolu.rag.entity.EmbeddingConfigEntity;
import com.insolu.rag.entity.EmbeddingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigService.class);

    private final EmbeddingConfigRepository repository;

    public EmbeddingConfigService(EmbeddingConfigRepository repository) {
        this.repository = repository;
    }

    public List<EmbeddingConfigEntity> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<EmbeddingConfigEntity> findActive() {
        return repository.findFirstByIsActiveTrue();
    }

    public Optional<EmbeddingConfigEntity> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public EmbeddingConfigEntity save(EmbeddingConfigEntity entity) {
        return repository.save(entity);
    }

    @Transactional
    public EmbeddingConfigEntity update(UUID id, EmbeddingConfigEntity updated) {
        EmbeddingConfigEntity existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        existing.setName(updated.getName());
        existing.setProvider(updated.getProvider());
        existing.setBaseUrl(updated.getBaseUrl());
        existing.setModelName(updated.getModelName());
        existing.setDimension(updated.getDimension());

        // 只有传了新密钥才更新（排除前端回传的脱敏值）
        String newKey = updated.getApiKey();
        if (newKey != null && !newKey.isBlank() && !newKey.startsWith("***")) {
            existing.setApiKey(newKey);
        }

        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    @Transactional
    public EmbeddingConfigEntity activate(UUID id) {
        repository.deactivateAll();
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(true);
        return repository.save(entity);
    }

    @Transactional
    public EmbeddingConfigEntity deactivate(UUID id) {
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(false);
        return repository.save(entity);
    }

    /** 测试 Embedding 连接 */
    public TestResult testConnection(UUID id) {
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        long start = System.currentTimeMillis();
        try {
            dev.langchain4j.model.embedding.EmbeddingModel model = buildModel(entity);
            var response = model.embed("test connection");
            long elapsed = System.currentTimeMillis() - start;
            int dim = response.content().vector().length;
            boolean success = dim > 0;
            return new TestResult(success,
                    success ? "连接成功，向量维度: " + dim : "响应为空",
                    elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Embedding 测试失败: {}", entity.getName(), e);
            String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return new TestResult(false, "连接失败: " + detail, elapsed);
        }
    }

    /** 根据 provider 构建 EmbeddingModel */
    public dev.langchain4j.model.embedding.EmbeddingModel buildModel(EmbeddingConfigEntity entity) {
        return switch (entity.getProvider()) {
            case "ollama" -> dev.langchain4j.model.ollama.OllamaEmbeddingModel.builder()
                    .baseUrl(entity.getBaseUrl())
                    .modelName(entity.getModelName())
                    .build();
            case "openai" -> dev.langchain4j.model.openai.OpenAiEmbeddingModel.builder()
                    .apiKey(entity.getApiKey())
                    .baseUrl(entity.getBaseUrl())
                    .modelName(entity.getModelName())
                    .build();
            default -> throw new IllegalArgumentException("不支持的 provider: " + entity.getProvider());
        };
    }

    /** 脱敏副本返回给前端 */
    public EmbeddingConfigEntity toMaskedCopy(EmbeddingConfigEntity source) {
        EmbeddingConfigEntity copy = new EmbeddingConfigEntity();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setProvider(source.getProvider());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setModelName(source.getModelName());
        copy.setDimension(source.getDimension());
        copy.setIsActive(source.getIsActive());
        String key = source.getApiKey();
        if (key != null && key.length() > 4) {
            copy.setApiKey("***" + key.substring(key.length() - 4));
        } else if (key != null && !key.isBlank()) {
            copy.setApiKey("***");
        }
        return copy;
    }

    public record TestResult(boolean success, String message, long responseTimeMs) {}
}
