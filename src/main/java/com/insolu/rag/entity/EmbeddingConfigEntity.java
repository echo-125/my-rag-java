package com.insolu.rag.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Embedding 配置实体 —— 存储向量化模型的连接信息。
 * 支持 Ollama（本地）和 OpenAI 兼容 API 两种 provider。
 */
@Entity
@Table(name = "embedding_config")
public class EmbeddingConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 配置名称，如 "本地 Ollama qwen3-embedding" */
    @Column(nullable = false)
    private String name;

    /** Provider 类型：ollama / openai */
    @Column(nullable = false)
    private String provider = "ollama";

    /** API 基础 URL */
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** 模型名称 */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** API 密钥（Ollama 本地不需要，OpenAI 兼容 API 需要） */
    @Column(name = "api_key", length = 1024)
    private String apiKey;

    /** 向量维度 */
    @Column(nullable = false)
    private Integer dimension = 2560;

    /** 是否激活 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ===== Getters & Setters =====

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Integer getDimension() { return dimension; }
    public void setDimension(Integer dimension) { this.dimension = dimension; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
