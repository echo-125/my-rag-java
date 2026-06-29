package com.he.controller;

import com.he.entity.ChatMessageEntity;
import com.he.entity.ChatSessionEntity;
import com.he.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天会话管理端点。
 */
@RestController
@RequestMapping("/api/sessions")
public class ChatSessionController {

    private final ConversationService conversationService;

    public ChatSessionController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * GET /api/sessions — 返回所有会话列表（按更新时间倒序）。
     */
    @GetMapping
    public List<Map<String, Object>> listSessions() {
        return conversationService.listSessions().stream()
                .map(s -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("id", s.getId().toString());
                    map.put("title", s.getTitle());
                    map.put("createdAt", s.getCreatedAt().toString());
                    map.put("updatedAt", s.getUpdatedAt().toString());
                    return map;
                })
                .toList();
    }

    /**
     * GET /api/sessions/{id}/messages — 返回会话的所有消息。
     */
    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable UUID id) {
        return conversationService.getSessionMessages(id).stream()
                .map(m -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("id", m.getId().toString());
                    map.put("role", m.getRole());
                    map.put("content", m.getContent());
                    map.put("createdAt", m.getCreatedAt().toString());
                    return map;
                })
                .toList();
    }

    /**
     * DELETE /api/sessions/{id} — 删除会话及其消息。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID id) {
        conversationService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }
}
