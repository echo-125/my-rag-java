package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 项目配置实体 —— 持久化入库项目的路径配置。
 */
@Entity
@Table(name = "config_project")
public class ProjectConfigEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 项目名称 */
    @Column(nullable = false)
    private String name;

    /** 本地路径 */
    @Column(nullable = false, length = 1024)
    private String path;

    /** 状态：pending(待入库) / completed(已入库) */
    @Column
    private String status = "completed";

    /** 入库完成时间 */
    private Instant ingestedAt;

    /** LLM 生成的项目简介 */
    @Column(length = 2000)
    private String description;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
}

