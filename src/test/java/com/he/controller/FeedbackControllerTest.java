package com.he.controller;

import com.he.service.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeedbackControllerTest {

    private MockMvc mockMvc;
    private FeedbackService feedbackService;

    @BeforeEach
    void setup() {
        feedbackService = mock(FeedbackService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FeedbackController(feedbackService))
                .build();
    }

    @Test
    void TC_FB_004_invalidRating_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType("application/json")
                        .content("{\"qaHistoryId\":\"" + UUID.randomUUID() + "\",\"rating\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("rating 只能为 1 或 -1"));
    }

    @Test
    void TC_FB_005_missingQaHistoryId_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType("application/json")
                        .content("{\"rating\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("qaHistoryId 必填"));
    }

    @Test
    void TC_FB_008_invalidQaHistoryId_returns400() throws Exception {
        mockMvc.perform(post("/api/feedback")
                        .contentType("application/json")
                        .content("{\"qaHistoryId\":\"invalid\",\"rating\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("qaHistoryId 格式非法"));
    }

    @Test
    void TC_FB_001_submitPositiveFeedback_returns200() throws Exception {
        when(feedbackService.submit(any(UUID.class), eq((short) 1), isNull())).thenReturn(null);

        mockMvc.perform(post("/api/feedback")
                        .contentType("application/json")
                        .content("{\"qaHistoryId\":\"" + UUID.randomUUID() + "\",\"rating\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void TC_FB_002_submitNegativeFeedbackWithComment() throws Exception {
        when(feedbackService.submit(any(UUID.class), eq((short) -1), eq("回答错误"))).thenReturn(null);

        mockMvc.perform(post("/api/feedback")
                        .contentType("application/json")
                        .content("{\"qaHistoryId\":\"" + UUID.randomUUID() + "\",\"rating\":-1,\"comment\":\"回答错误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void TC_FB_006_getStats_returnsAllFields() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", 10L);
        stats.put("positive", 7L);
        stats.put("negative", 3L);
        stats.put("positiveRate", 70.0);
        when(feedbackService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/feedback/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.positive").value(7))
                .andExpect(jsonPath("$.negative").value(3))
                .andExpect(jsonPath("$.positiveRate").value(70.0));
    }

    @Test
    void TC_FB_007_getLowQuality_returns200() throws Exception {
        when(feedbackService.getLowQuality()).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/feedback/low-quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
