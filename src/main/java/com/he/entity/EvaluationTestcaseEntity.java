package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 评估测试用例实体 —— 存储单个测试用例的问题和期望结果。
 */
@Entity
@Table(name = "evaluation_testcase")
public class EvaluationTestcaseEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属测试集ID */
    @Column(name = "testset_id", nullable = false, columnDefinition = "UUID")
    private UUID testsetId;

    /** 测试问题 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 期望匹配的文件列表（JSON数组） */
    @Column(name = "expected_files", nullable = false, columnDefinition = "TEXT")
    private String expectedFiles;

    /** 标签列表（JSON数组） */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTestsetId() { return testsetId; }
    public void setTestsetId(UUID testsetId) { this.testsetId = testsetId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getExpectedFiles() { return expectedFiles; }
    public void setExpectedFiles(String expectedFiles) { this.expectedFiles = expectedFiles; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Instant getCreatedAt() { return createdAt; }
}
