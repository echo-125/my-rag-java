package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Reranking 模型配置实体 —— 支持 Ollama 本地和远程 API 两种接入方式。
 */
@Entity
@Table(name = "config_reranking")
public class RerankingConfigEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 配置名称，如 "BGE-Reranker-v2-m3" */
    @Column(nullable = false)
    private String name;

    /** 接入方式：ollama / api */
    @Column(nullable = false)
    private String provider = "ollama";

    /** 模型名称，如 "bge-reranker-v2-m3" */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** 服务地址（Ollama 地址或 API endpoint） */
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** API 密钥（API 模式下使用） */
    @Column(name = "api_key", length = 1024)
    private String apiKey;

    /** 是否激活 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间 */
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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
