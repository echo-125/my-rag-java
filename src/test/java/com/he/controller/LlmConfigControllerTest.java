package com.he.controller;

import com.he.entity.LlmConfigEntity;
import com.he.service.LlmConfigService;
import com.he.service.LlmConfigService.TestResult;
import com.he.service.RagChatService;
import com.he.service.SpringAiModelRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LlmConfigControllerTest {

    private MockMvc mockMvc;
    private LlmConfigService llmConfigService;
    private SpringAiModelRouterService modelRouter;
    private RagChatService ragChatService;
    private UUID testId;
    private LlmConfigEntity testConfig;

    @BeforeEach
    void setup() throws Exception {
        llmConfigService = mock(LlmConfigService.class);
        modelRouter = mock(SpringAiModelRouterService.class);
        ragChatService = mock(RagChatService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LlmConfigController(llmConfigService, modelRouter, ragChatService))
                .build();

        testId = UUID.randomUUID();
        testConfig = new LlmConfigEntity();
        testConfig.setId(testId);
        testConfig.setName("Test LLM");
        testConfig.setModelName("gpt-4o-mini");
        testConfig.setBaseUrl("https://api.openai.com/v1");
        testConfig.setApiKey("sk-test");
        testConfig.setApiFormat(LlmConfigEntity.ApiFormat.openai_chat_completions);
        setField(testConfig, "isActive", true);
        setField(testConfig, "enableToolCalling", false);
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @Test
    void TC_P5_005_listConfigs() throws Exception {
        when(llmConfigService.findAll()).thenReturn(List.of(testConfig));

        mockMvc.perform(get("/api/llm-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Test LLM"));
    }

    @Test
    void TC_P5_005_getById_found() throws Exception {
        when(llmConfigService.findById(testId)).thenReturn(Optional.of(testConfig));

        mockMvc.perform(get("/api/llm-configs/" + testId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("gpt-4o-mini"));
    }

    @Test
    void TC_P5_005_getById_notFound() throws Exception {
        when(llmConfigService.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/llm-configs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P5_005_createConfig() throws Exception {
        when(llmConfigService.save(any(LlmConfigEntity.class))).thenReturn(testConfig);

        mockMvc.perform(post("/api/llm-configs")
                        .contentType("application/json")
                        .content("{\"name\":\"Test LLM\",\"modelName\":\"gpt-4o-mini\",\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"sk-test\",\"apiFormat\":\"openai_chat_completions\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test LLM"));

        verify(modelRouter).evictAllCache();
    }

    @Test
    void TC_P5_005_updateConfig() throws Exception {
        when(llmConfigService.update(eq(testId), any(LlmConfigEntity.class))).thenReturn(testConfig);

        mockMvc.perform(put("/api/llm-configs/" + testId)
                        .contentType("application/json")
                        .content("{\"name\":\"Updated LLM\"}"))
                .andExpect(status().isOk());

        verify(modelRouter).evictCache(testId);
    }

    @Test
    void TC_P5_005_updateNotFound() throws Exception {
        when(llmConfigService.update(any(UUID.class), any(LlmConfigEntity.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(put("/api/llm-configs/" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P5_005_deleteConfig() throws Exception {
        mockMvc.perform(delete("/api/llm-configs/" + testId))
                .andExpect(status().isNoContent());

        verify(llmConfigService).delete(testId);
        verify(modelRouter).evictCache(testId);
    }

    @Test
    void TC_P5_005_activateConfig() throws Exception {
        when(llmConfigService.activate(testId)).thenReturn(testConfig);

        mockMvc.perform(post("/api/llm-configs/" + testId + "/activate"))
                .andExpect(status().isOk());

        verify(modelRouter).evictAllCache();
    }

    @Test
    void TC_P5_005_activateNotFound() throws Exception {
        when(llmConfigService.activate(any(UUID.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(post("/api/llm-configs/" + UUID.randomUUID() + "/activate"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P5_005_deactivateConfig() throws Exception {
        when(llmConfigService.deactivate(testId)).thenReturn(testConfig);

        mockMvc.perform(post("/api/llm-configs/" + testId + "/deactivate"))
                .andExpect(status().isOk());

        verify(modelRouter).evictAllCache();
    }

    @Test
    void TC_P5_005_testConnection() throws Exception {
        TestResult result = new TestResult(true, "OK", 200L, "hello", true);
        when(llmConfigService.testConnection(testId)).thenReturn(result);

        mockMvc.perform(post("/api/llm-configs/" + testId + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.responseTimeMs").value(200));

        verify(ragChatService).evictStreamingCache(testId);
        verify(modelRouter).evictCache(testId);
    }

    @Test
    void TC_P5_005_testConnectionNotFound() throws Exception {
        when(llmConfigService.testConnection(any(UUID.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(post("/api/llm-configs/" + UUID.randomUUID() + "/test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P5_005_updateToolCallingField() throws Exception {
        LlmConfigEntity updated = new LlmConfigEntity();
        updated.setId(testId);
        updated.setName("Test LLM");
        updated.setModelName("gpt-4o-mini");
        updated.setBaseUrl("https://api.openai.com/v1");
        updated.setApiKey("sk-test");
        updated.setApiFormat(LlmConfigEntity.ApiFormat.openai_chat_completions);
        setField(updated, "isActive", true);
        setField(updated, "enableToolCalling", true);

        when(llmConfigService.update(eq(testId), any(LlmConfigEntity.class))).thenReturn(updated);

        mockMvc.perform(put("/api/llm-configs/" + testId)
                        .contentType("application/json")
                        .content("{\"enableToolCalling\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void listApiFormats() throws Exception {
        mockMvc.perform(get("/api/llm-configs/api-formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
