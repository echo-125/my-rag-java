package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evaluation_testcase")
public class EvaluationTestcaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "testset_id", nullable = false, columnDefinition = "UUID")
    private UUID testsetId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "expected_files", nullable = false, columnDefinition = "TEXT")
    private String expectedFiles; // JSON array

    @Column(columnDefinition = "TEXT")
    private String tags; // JSON array

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
