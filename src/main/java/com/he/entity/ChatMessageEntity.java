package com.he.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 聊天消息实体 —— 持久化会话中的每条消息。
 */
@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属会话ID */
    @Column(name = "session_id", nullable = false, columnDefinition = "UUID")
    private UUID sessionId;

    /** 角色：user / assistant */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getCreatedAt() { return createdAt; }
}
