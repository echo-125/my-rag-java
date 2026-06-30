package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "qa_feedback")
public class QaFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "qa_history_id", nullable = false, columnDefinition = "UUID")
    private UUID qaHistoryId;

    /** 1=👍, -1=👎 */
    @Column(nullable = false)
    private Short rating;

    @Column(length = 1024)
    private String comment;

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
