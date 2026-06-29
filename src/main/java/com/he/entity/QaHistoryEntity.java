package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 问答历史实体 —— 持久化用户与 AI 的对话记录。
 */
@Entity
@Table(name = "qa_history")
public class QaHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 用户提问 */
    @Column(nullable = false, length = 4096)
    private String question;

    /** AI 回答 */
    @Column(nullable = false, length = 65536)
    private String answer;

    /** 使用的模型名称 */
    @Column(name = "model_name")
    private String modelName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Instant getCreatedAt() { return createdAt; }
}

