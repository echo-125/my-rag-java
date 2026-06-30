package com.he.controller;

import com.he.entity.ChatMessageEntity;
import com.he.entity.ChatSessionEntity;
import com.he.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatSessionControllerTest {

    private MockMvc mockMvc;
    private ConversationService conversationService;

    @BeforeEach
    void setup() {
        conversationService = mock(ConversationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatSessionController(conversationService))
                .build();
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @Test
    void TC_P0_008_listSessions_returnsOrderedByUpdatedAtDesc() throws Exception {
        ChatSessionEntity s1 = new ChatSessionEntity();
        s1.setId(UUID.randomUUID());
        s1.setTitle("旧会话");
        setField(s1, "createdAt", Instant.parse("2025-01-01T00:00:00Z"));
        setField(s1, "updatedAt", Instant.parse("2025-01-01T00:00:00Z"));

        ChatSessionEntity s2 = new ChatSessionEntity();
        s2.setId(UUID.randomUUID());
        s2.setTitle("新会话");
        setField(s2, "createdAt", Instant.parse("2025-01-02T00:00:00Z"));
        setField(s2, "updatedAt", Instant.parse("2025-01-02T00:00:00Z"));

        when(conversationService.listSessions()).thenReturn(List.of(s2, s1));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].title").value("新会话"))
                .andExpect(jsonPath("$[1].title").value("旧会话"));
    }

    @Test
    void TC_P0_009_getMessages_emptyForNonExistentSession() throws Exception {
        when(conversationService.getSessionMessages(any(UUID.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/" + UUID.randomUUID() + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void TC_P0_010_getMessages_invalidIdReturns400() throws Exception {
        mockMvc.perform(get("/api/sessions/invalid-id/messages"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void TC_P0_007_deleteSession_returns204() throws Exception {
        UUID sessionId = UUID.randomUUID();
        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isNoContent());
        verify(conversationService).deleteSession(sessionId);
    }

    @Test
    void listSessions_containsRequiredFields() throws Exception {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(UUID.randomUUID());
        session.setTitle("测试会话");
        setField(session, "createdAt", Instant.parse("2025-01-01T00:00:00Z"));
        setField(session, "updatedAt", Instant.parse("2025-01-01T00:00:00Z"));

        when(conversationService.listSessions()).thenReturn(List.of(session));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].title").value("测试会话"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void getMessages_containsRequiredFields() throws Exception {
        ChatSessionEntity session = new ChatSessionEntity();
        UUID sessionId = UUID.randomUUID();
        session.setId(sessionId);
        setField(session, "createdAt", Instant.parse("2025-01-01T00:00:00Z"));
        setField(session, "updatedAt", Instant.parse("2025-01-01T00:00:00Z"));

        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent("测试消息");
        setField(msg, "createdAt", Instant.parse("2025-01-01T00:00:00Z"));

        when(conversationService.getSessionMessages(sessionId)).thenReturn(List.of(msg));

        mockMvc.perform(get("/api/sessions/" + sessionId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("测试消息"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }
}
