package com.he.controller;

import com.he.entity.QaHistoryEntity;
import com.he.entity.QaHistoryRepository;
import com.he.service.ConversationService;
import com.he.service.RagChatService;
import com.he.service.SpringAiModelRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagChatControllerTest {

    private MockMvc mockMvc;
    private RagChatService ragChatService;
    private QaHistoryRepository qaHistoryRepo;
    private SpringAiModelRouterService modelRouter;
    private ConversationService conversationService;

    @BeforeEach
    void setup() {
        ragChatService = mock(RagChatService.class);
        qaHistoryRepo = mock(QaHistoryRepository.class);
        modelRouter = mock(SpringAiModelRouterService.class);
        conversationService = mock(ConversationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RagChatController(ragChatService, qaHistoryRepo, modelRouter, conversationService))
                .build();
    }

    @Test
    void TC_P0_002_streamChat_autoCreatesSessionWhenEmpty() throws Exception {
        when(modelRouter.getAvailableModels()).thenReturn(List.of());
        when(conversationService.createSession()).thenReturn("auto-session-id");
        when(ragChatService.chatWithCitations(anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("{\"text\":\"你好\"}"));

        mockMvc.perform(post("/api/chat/stream")
                        .contentType("application/json")
                        .content("{\"query\":\"你好\",\"modelKey\":\"test-model\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/event-stream")));

        verify(conversationService).createSession();
    }

    @Test
    void TC_P0_002_streamChat_withSessionId() throws Exception {
        when(modelRouter.getAvailableModels()).thenReturn(List.of());
        when(ragChatService.chatWithCitations(anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("{\"text\":\"回答\"}"));

        mockMvc.perform(post("/api/chat/stream")
                        .contentType("application/json")
                        .content("{\"query\":\"问题\",\"modelKey\":\"m1\",\"sessionId\":\"existing-id\"}"))
                .andExpect(status().isOk());

        verify(conversationService, never()).createSession();
    }

    @Test
    void saveQa_returnsId() throws Exception {
        when(qaHistoryRepo.save(any(QaHistoryEntity.class))).thenAnswer(inv -> {
            QaHistoryEntity e = inv.getArgument(0);
            e.setId(java.util.UUID.randomUUID());
            return e;
        });

        mockMvc.perform(post("/api/chat/save")
                        .contentType("application/json")
                        .content("{\"question\":\"问题\",\"answer\":\"回答\",\"modelName\":\"gpt-4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void streamChat_errorReturnsErrorJson() throws Exception {
        when(modelRouter.getAvailableModels()).thenReturn(List.of());
        when(ragChatService.chatWithCitations(anyString(), anyString(), anyString()))
                .thenReturn(Flux.error(new RuntimeException("模型不可用")));

        mockMvc.perform(post("/api/chat/stream")
                        .contentType("application/json")
                        .content("{\"query\":\"问题\",\"modelKey\":\"m1\",\"sessionId\":\"sid\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stream_error")));
    }
}
