package com.he.controller;

import com.he.entity.EvaluationBatchEntity;
import com.he.entity.EvaluationTestsetEntity;
import com.he.service.EvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvaluationControllerTest {

    private MockMvc mockMvc;
    private EvaluationService evaluationService;
    private UUID testsetId;

    @BeforeEach
    void setup() {
        evaluationService = mock(EvaluationService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvaluationController(evaluationService))
                .build();
        testsetId = UUID.randomUUID();
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    @Test
    void TC_P4_013_runMissingTestset_returns400() throws Exception {
        mockMvc.perform(post("/api/evaluation/run")
                        .contentType("application/json")
                        .content("{\"testsetId\":null,\"k\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("testsetId 必填"));
    }

    @Test
    void TC_P4_014_runInvalidTestsetId_returns400() throws Exception {
        mockMvc.perform(post("/api/evaluation/run")
                        .contentType("application/json")
                        .content("{\"testsetId\":\"invalid\",\"k\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("testsetId 格式非法"));
    }

    @Test
    void TC_P4_003_runEvaluation_returns202() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(evaluationService.startAsync(any(UUID.class), eq(5))).thenReturn(batchId);

        mockMvc.perform(post("/api/evaluation/run")
                        .contentType("application/json")
                        .content("{\"testsetId\":\"" + testsetId + "\",\"k\":5}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void TC_P4_005_cancelEvaluation_returns200() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(evaluationService.cancelBatch(batchId)).thenReturn(true);

        mockMvc.perform(post("/api/evaluation/run/" + batchId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void TC_P4_005_cancelNonExistentBatch_returns400() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(evaluationService.cancelBatch(batchId)).thenReturn(false);

        mockMvc.perform(post("/api/evaluation/run/" + batchId + "/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("任务不存在或已结束"));
    }

    @Test
    void TC_P4_006_runStatus_returnsProgress() throws Exception {
        UUID batchId = UUID.randomUUID();
        EvaluationBatchEntity batch = new EvaluationBatchEntity();
        batch.setId(batchId);
        batch.setTestsetId(testsetId);
        batch.setStatus("running");
        batch.setTotalCases(10);
        batch.setCompletedCases(3);
        when(evaluationService.getBatch(batchId)).thenReturn(batch);

        mockMvc.perform(get("/api/evaluation/run/" + batchId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.completedCases").value(3))
                .andExpect(jsonPath("$.totalCases").value(10));
    }

    @Test
    void TC_P4_006_runStatus_notFound_returns404() throws Exception {
        when(evaluationService.getBatch(any(UUID.class))).thenReturn(null);

        mockMvc.perform(get("/api/evaluation/run/" + UUID.randomUUID() + "/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void TC_P4_007_latestReport_notFound_returnsFoundFalse() throws Exception {
        when(evaluationService.getLatestCompletedBatch()).thenReturn(null);

        mockMvc.perform(get("/api/evaluation/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    void TC_P4_008_history_returnsArray() throws Exception {
        when(evaluationService.getRecentCompleted(20)).thenReturn(List.of());

        mockMvc.perform(get("/api/evaluation/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void TC_P4_009_createTestset() throws Exception {
        EvaluationTestsetEntity ts = new EvaluationTestsetEntity();
        ts.setId(UUID.randomUUID());
        ts.setName("新测试集");
        when(evaluationService.createTestset("新测试集", "描述")).thenReturn(ts);

        mockMvc.perform(post("/api/evaluation/testset")
                        .contentType("application/json")
                        .content("{\"name\":\"新测试集\",\"description\":\"描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("新测试集"));
    }

    @Test
    void TC_P4_009_deleteTestset_returns204() throws Exception {
        mockMvc.perform(delete("/api/evaluation/testset/" + UUID.randomUUID()))
                .andExpect(status().isNoContent());
        verify(evaluationService).deleteTestset(any(UUID.class));
    }

    @Test
    void TC_P4_010_listTestcases() throws Exception {
        when(evaluationService.listTestcases(any(UUID.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/evaluation/testset/" + UUID.randomUUID() + "/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void TC_P4_010_addTestcase() throws Exception {
        com.he.entity.EvaluationTestcaseEntity tc = new com.he.entity.EvaluationTestcaseEntity();
        tc.setId(UUID.randomUUID());
        when(evaluationService.addTestcase(any(UUID.class), eq("测试问题"), eq("[\"test.java\"]"), eq("[]"))).thenReturn(tc);

        mockMvc.perform(post("/api/evaluation/testset/" + UUID.randomUUID() + "/cases")
                        .contentType("application/json")
                        .content("{\"question\":\"测试问题\",\"expectedFiles\":\"[\\\"test.java\\\"]\",\"tags\":\"[]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    void TC_P4_015_exportTestset() throws Exception {
        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("name", "测试集");
        exportData.put("cases", List.of());
        when(evaluationService.exportTestset(any(UUID.class))).thenReturn(exportData);

        mockMvc.perform(get("/api/evaluation/testset/" + UUID.randomUUID() + "/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".json")));
    }

    @Test
    void TC_P4_016_importTestset() throws Exception {
        EvaluationTestsetEntity ts = new EvaluationTestsetEntity();
        ts.setId(UUID.randomUUID());
        ts.setName("导入测试集");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testset", ts);
        result.put("count", 3);
        when(evaluationService.importTestsetWithCount(any(Map.class))).thenReturn(result);

        mockMvc.perform(post("/api/evaluation/testset/import")
                        .contentType("application/json")
                        .content("{\"name\":\"导入测试集\",\"cases\":[{\"question\":\"q1\",\"expected_files\":[\"f1\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("导入测试集"))
                .andExpect(jsonPath("$.importedCases").value(3));
    }

    @Test
    void TC_P4_014_runWithNegativeK_returns400() throws Exception {
        mockMvc.perform(post("/api/evaluation/run")
                        .contentType("application/json")
                        .content("{\"testsetId\":\"" + testsetId + "\",\"k\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("k 必须 > 0"));
    }

    @Test
    void runEvaluation_defaultKIsFive() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(evaluationService.startAsync(any(UUID.class), eq(5))).thenReturn(batchId);

        mockMvc.perform(post("/api/evaluation/run")
                        .contentType("application/json")
                        .content("{\"testsetId\":\"" + testsetId + "\"}"))
                .andExpect(status().isAccepted());
    }
}
