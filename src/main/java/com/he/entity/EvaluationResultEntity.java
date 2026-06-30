package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evaluation_result")
public class EvaluationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false, columnDefinition = "UUID")
    private UUID batchId;

    @Column(name = "testcase_id", nullable = false, columnDefinition = "UUID")
    private UUID testcaseId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "retrieved_files", nullable = false, columnDefinition = "TEXT")
    private String retrievedFiles; // JSON array

    @Column(name = "expected_files", nullable = false, columnDefinition = "TEXT")
    private String expectedFiles; // JSON array

    @Column(nullable = false)
    private Boolean hit;

    @Column(name = "first_hit_rank")
    private Integer firstHitRank;

    @Column(name = "latency_ms")
    private Long latencyMs;

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
    public Instant getCreatedAt() { return createdAt; }
}
