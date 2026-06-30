package com.he.controller;

import com.he.entity.RagConfigEntity;
import com.he.service.RagConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagConfigControllerTest {

    private MockMvc mockMvc;
    private RagConfigService ragConfigService;

    @BeforeEach
    void setup() {
        ragConfigService = mock(RagConfigService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RagConfigController(ragConfigService))
                .build();
    }

    @Test
    void listConfigs_returnsAll() throws Exception {
        RagConfigEntity entity = new RagConfigEntity();
        entity.setConfigKey("max_results");
        entity.setConfigValue("5");
        entity.setDescription("最大检索结果数");
        when(ragConfigService.getAllConfigs()).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("max_results"))
                .andExpect(jsonPath("$[0].value").value("5"))
                .andExpect(jsonPath("$[0].type").value("number"))
                .andExpect(jsonPath("$[0].description").value("最大检索结果数"));
    }

    @Test
    void updateConfigs_filtersUnregisteredKeys() throws Exception {
        when(ragConfigService.updateConfigs(any())).thenReturn(1);

        mockMvc.perform(put("/api/configs")
                        .contentType("application/json")
                        .content("{\"max_results\":\"10\",\"unknown_key\":\"value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        verify(ragConfigService).updateConfigs(argThat(m -> m.containsKey("max_results") && !m.containsKey("unknown_key")));
    }

    @Test
    void updateConfigs_rejectsNegativeNumber() throws Exception {
        mockMvc.perform(put("/api/configs")
                        .contentType("application/json")
                        .content("{\"max_results\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("必须为正数")));
    }

    @Test
    void updateConfigs_rejectsNonNumeric() throws Exception {
        mockMvc.perform(put("/api/configs")
                        .contentType("application/json")
                        .content("{\"max_results\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不是有效数字")));
    }

    @Test
    void updateConfigs_booleanTypeAllowed() throws Exception {
        when(ragConfigService.updateConfigs(any())).thenReturn(1);

        mockMvc.perform(put("/api/configs")
                        .contentType("application/json")
                        .content("{\"enable_bm25\":\"true\",\"enable_reranking\":\"false\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
    }

    @Test
    void listConfigs_booleanFieldType() throws Exception {
        RagConfigEntity entity = new RagConfigEntity();
        entity.setConfigKey("enable_bm25");
        entity.setConfigValue("true");
        entity.setDescription("启用 BM25 检索");
        when(ragConfigService.getAllConfigs()).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("boolean"));
    }

    @Test
    void listConfigs_textareaFieldType() throws Exception {
        RagConfigEntity entity = new RagConfigEntity();
        entity.setConfigKey("system_prompt");
        entity.setConfigValue("你是一个助手");
        entity.setDescription("系统提示词");
        when(ragConfigService.getAllConfigs()).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("textarea"));
    }
}
