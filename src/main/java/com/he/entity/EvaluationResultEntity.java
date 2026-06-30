package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 评估结果实体 —— 存储单个测试用例的评估详情。
 */
@Entity
@Table(name = "evaluation_result")
public class EvaluationResultEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 评估批次ID */
    @Column(name = "batch_id", nullable = false, columnDefinition = "UUID")
    private UUID batchId;

    /** 测试用例ID */
    @Column(name = "testcase_id", nullable = false, columnDefinition = "UUID")
    private UUID testcaseId;

    /** 测试问题 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 检索到的文件列表（JSON数组） */
    @Column(name = "retrieved_files", nullable = false, columnDefinition = "TEXT")
    private String retrievedFiles;

    /** 期望匹配的文件列表（JSON数组） */
    @Column(name = "expected_files", nullable = false, columnDefinition = "TEXT")
    private String expectedFiles;

    /** 是否命中期望文件 */
    @Column(nullable = false)
    private Boolean hit;

    /** 首次命中排名（1-based） */
    @Column(name = "first_hit_rank")
    private Integer firstHitRank;

    /** 检索延迟（毫秒） */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 解析警告信息 */
    @Column(name = "parse_warning", length = 512)
    private String parseWarning;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public UUID getTestcaseId() { return testcaseId; }
    public void setTestcaseId(UUID testcaseId) { this.testcaseId = testcaseId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getRetrievedFiles() { return retrievedFiles; }
    public void setRetrievedFiles(String retrievedFiles) { this.retrievedFiles = retrievedFiles; }
    public String getExpectedFiles() { return expectedFiles; }
    public void setExpectedFiles(String expectedFiles) { this.expectedFiles = expectedFiles; }
    public Boolean getHit() { return hit; }
    public void setHit(Boolean hit) { this.hit = hit; }
    public Integer getFirstHitRank() { return firstHitRank; }
    public void setFirstHitRank(Integer firstHitRank) { this.firstHitRank = firstHitRank; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getParseWarning() { return parseWarning; }
    public void setParseWarning(String parseWarning) { this.parseWarning = parseWarning; }
    public Instant getCreatedAt() { return createdAt; }
}
