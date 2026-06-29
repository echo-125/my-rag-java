package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 项目配置实体 —— 持久化入库项目的路径配置。
 */
@Entity
@Table(name = "project_config")
public class ProjectConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 项目名称 */
    @Column(nullable = false)
    private String name;

    /** 本地路径 */
    @Column(nullable = false, length = 1024)
    private String path;

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
    public Instant getCreatedAt() { return createdAt; }
}

