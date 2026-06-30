package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 用户反馈实体 —— 存储用户对AI回答的评价。
 */
@Entity
@Table(name = "qa_feedback")
public class QaFeedbackEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 关联的问答历史ID */
    @Column(name = "qa_history_id", nullable = false, columnDefinition = "UUID")
    private UUID qaHistoryId;

    /** 评分：1=👍, -1=👎 */
    @Column(nullable = false)
    private Short rating;

    /** 用户评论 */
    @Column(length = 1024)
    private String comment;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getQaHistoryId() { return qaHistoryId; }
    public void setQaHistoryId(UUID qaHistoryId) { this.qaHistoryId = qaHistoryId; }
    public Short getRating() { return rating; }
    public void setRating(Short rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getCreatedAt() { return createdAt; }
}
