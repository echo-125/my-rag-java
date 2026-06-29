package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * LLM 配置实体 —— 存储不同 LLM 的 API 连接信息。
 */
@Entity
@Table(name = "llm_config")
public class LlmConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 配置名称，如 "DeepSeek-Coder" */
    @Column(nullable = false)
    private String name;

    /** 模型名称，如 "deepseek-coder" */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    /** API 基础 URL */
    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** API 密钥（AES 加密存储） */
    @Column(name = "api_key", nullable = false, length = 1024)
    private String apiKey;

    /** API 格式：openai_chat_completions / anthropic_messages */
    @Column(name = "api_format", nullable = false)
    @Enumerated(EnumType.STRING)
    private ApiFormat apiFormat;

    /** 是否激活 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    /** 是否支持流式请求（测试连接时自动检测） */
    @Column(name = "supports_streaming", nullable = false)
    private Boolean supportsStreaming = false;

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

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public ApiFormat getApiFormat() { return apiFormat; }
    public void setApiFormat(ApiFormat apiFormat) { this.apiFormat = apiFormat; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }

    public Boolean getSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(Boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ===== 枚举 =====

    public enum ApiFormat {
        openai_chat_completions,
        anthropic_messages
    }
}

