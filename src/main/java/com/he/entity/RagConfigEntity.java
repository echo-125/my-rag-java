package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 全局 RAG 配置实体 —— 存储切分器与清洗器的可调参数。
 * configKey 为主键，如 "max_segment_size"、"semantic_threshold"。
 */
@Entity
@Table(name = "rag_config")
public class RagConfigEntity {

    @Id
    @Column(name = "config_key", length = 1024)
    private String configKey;

    @Column(name = "config_value", nullable = false)
    private String configValue;

    @Column(length = 255)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = Instant.now();
    }

    public RagConfigEntity() {}

    public RagConfigEntity(String configKey, String configValue, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
    }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

