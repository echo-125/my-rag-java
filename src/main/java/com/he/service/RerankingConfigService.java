package com.he.service;

import com.he.entity.RerankingConfigEntity;
import com.he.entity.RerankingConfigRepository;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RerankingConfigService {

    private final RerankingConfigRepository repository;
    private final OkHttpClient httpClient;

    public RerankingConfigService(RerankingConfigRepository repository) {
        this.repository = repository;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public List<RerankingConfigEntity> findAll() {
        return repository.findAll();
    }

    public Optional<RerankingConfigEntity> findById(UUID id) {
        return repository.findById(id);
    }

    public RerankingConfigEntity save(RerankingConfigEntity entity) {
        return repository.save(entity);
    }

    @Transactional
    public RerankingConfigEntity update(UUID id, RerankingConfigEntity updated) {
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setName(updated.getName());
        entity.setModelName(updated.getModelName());
        entity.setOllamaUrl(updated.getOllamaUrl());
        return repository.save(entity);
    }

    public void delete(UUID id) {
        repository.deleteById(id);
    }

    @Transactional
    public RerankingConfigEntity activate(UUID id) {
        // 先停用所有
        repository.findByIsActiveTrue().forEach(e -> {
            e.setIsActive(false);
            repository.save(e);
        });
        // 激活指定
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
     * 测试 Ollama 连接和模型可用性。
     */
    public TestResult testConnection(UUID id) {
        RerankingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        try {
            String url = entity.getOllamaUrl();
            if (url == null || url.isBlank()) {
                url = "http://localhost:11434";
            }
            // 检查 Ollama 是否可达
            Request request = new Request.Builder()
                    .url(url + "/api/tags")
                    .get()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new TestResult(false, "Ollama 服务不可达 HTTP " + response.code());
                }
                String body = response.body() != null ? response.body().string() : "";
                if (body.contains(entity.getModelName())) {
                    return new TestResult(true, "模型 " + entity.getModelName() + " 已就绪");
                } else {
                    return new TestResult(false, "模型 " + entity.getModelName() + " 未找到，请先 pull 模型");
                }
            }
        } catch (Exception e) {
            return new TestResult(false, "连接失败: " + e.getMessage());
        }
    }

    public record TestResult(boolean success, String message) {}
}
