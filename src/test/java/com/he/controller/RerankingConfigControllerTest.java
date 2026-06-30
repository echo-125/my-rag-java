package com.he.controller;

import com.he.entity.RerankingConfigEntity;
import com.he.service.RerankingConfigService;
import com.he.service.RerankingConfigService.TestResult;
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

class RerankingConfigControllerTest {

    private MockMvc mockMvc;
    private RerankingConfigService rerankingConfigService;
    private UUID testConfigId;
    private RerankingConfigEntity testConfig;

    @BeforeEach
    void setup() throws Exception {
        rerankingConfigService = mock(RerankingConfigService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RerankingConfigController(rerankingConfigService))
                .build();

        testConfigId = UUID.randomUUID();
        testConfig = new RerankingConfigEntity();
        testConfig.setId(testConfigId);
        testConfig.setName("BGE-Reranker-v2-m3");
        testConfig.setProvider("ollama");
        testConfig.setModelName("bge-reranker-v2-m3");
        testConfig.setBaseUrl("http://localhost:11434");
        Field activeField = RerankingConfigEntity.class.getDeclaredField("isActive");
        activeField.setAccessible(true);
        activeField.set(testConfig, false);
    }

    @Test
    void TC_P2_009_listRerankingConfigs() throws Exception {
        when(rerankingConfigService.findAll()).thenReturn(List.of(testConfig));

        mockMvc.perform(get("/api/reranking-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("BGE-Reranker-v2-m3"));
    }

    @Test
    void TC_P2_009_getById_found() throws Exception {
        when(rerankingConfigService.findById(testConfigId)).thenReturn(Optional.of(testConfig));

        mockMvc.perform(get("/api/reranking-configs/" + testConfigId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BGE-Reranker-v2-m3"));
    }

    @Test
    void TC_P2_009_getById_notFound() throws Exception {
        when(rerankingConfigService.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reranking-configs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P2_009_createRerankingConfig() throws Exception {
        when(rerankingConfigService.save(any(RerankingConfigEntity.class))).thenReturn(testConfig);

        mockMvc.perform(post("/api/reranking-configs")
                        .contentType("application/json")
                        .content("{\"name\":\"BGE-Reranker\",\"provider\":\"ollama\",\"modelName\":\"bge-reranker-v2-m3\",\"baseUrl\":\"http://localhost:11434\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BGE-Reranker-v2-m3"));
    }

    @Test
    void TC_P2_009_updateRerankingConfig() throws Exception {
        when(rerankingConfigService.update(eq(testConfigId), any(RerankingConfigEntity.class))).thenReturn(testConfig);

        mockMvc.perform(put("/api/reranking-configs/" + testConfigId)
                        .contentType("application/json")
                        .content("{\"name\":\"BGE-Reranker-v2-m3\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void TC_P2_009_updateNotFound() throws Exception {
        when(rerankingConfigService.update(any(UUID.class), any(RerankingConfigEntity.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(put("/api/reranking-configs/" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P2_009_deleteRerankingConfig() throws Exception {
        mockMvc.perform(delete("/api/reranking-configs/" + testConfigId))
                .andExpect(status().isNoContent());
        verify(rerankingConfigService).delete(testConfigId);
    }

    @Test
    void TC_P2_009_activateRerankingConfig() throws Exception {
        Field activeField = RerankingConfigEntity.class.getDeclaredField("isActive");
        activeField.setAccessible(true);
        activeField.set(testConfig, true);
        when(rerankingConfigService.activate(testConfigId)).thenReturn(testConfig);

        mockMvc.perform(post("/api/reranking-configs/" + testConfigId + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void TC_P2_009_activateNotFound() throws Exception {
        when(rerankingConfigService.activate(any(UUID.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(post("/api/reranking-configs/" + UUID.randomUUID() + "/activate"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P2_009_deactivateRerankingConfig() throws Exception {
        when(rerankingConfigService.deactivate(testConfigId)).thenReturn(testConfig);

        mockMvc.perform(post("/api/reranking-configs/" + testConfigId + "/deactivate"))
                .andExpect(status().isOk());
    }

    @Test
    void TC_P2_010_testConnection() throws Exception {
        TestResult result = new TestResult(true, "连接成功");
        when(rerankingConfigService.testConnection(testConfigId)).thenReturn(result);

        mockMvc.perform(post("/api/reranking-configs/" + testConfigId + "/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("连接成功"));
    }

    @Test
    void TC_P2_010_testConnectionNotFound() throws Exception {
        when(rerankingConfigService.testConnection(any(UUID.class)))
                .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(post("/api/reranking-configs/" + UUID.randomUUID() + "/test"))
                .andExpect(status().isNotFound());
    }
}
