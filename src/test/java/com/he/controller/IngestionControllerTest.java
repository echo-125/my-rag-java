package com.he.controller;

import com.he.service.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IngestionControllerTest {

    private MockMvc mockMvc;
    private IngestionService ingestionService;

    @BeforeEach
    void setup() {
        ingestionService = mock(IngestionService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IngestionController(ingestionService))
                .build();
    }

    @Test
    void TC_P1_scanFiles_returnsExtensionCounts() throws Exception {
        when(ingestionService.scanExtensions(anyList())).thenReturn(Map.of(".java", 5, ".md", 3));

        mockMvc.perform(post("/api/ingestion/scan")
                        .contentType("application/json")
                        .content("{\"projects\":[{\"name\":\"test\",\"path\":\"/tmp/test\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.extensions").isArray())
                .andExpect(jsonPath("$.extensions.length()").value(2));
    }

    @Test
    void clearChunksByProject() throws Exception {
        when(ingestionService.clearChunksByProject("test-project")).thenReturn(10);

        mockMvc.perform(delete("/api/ingestion/chunks/test-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(10))
                .andExpect(jsonPath("$.project").value("test-project"));
    }

    @Test
    void clearAllChunks() throws Exception {
        when(ingestionService.clearAllChunks()).thenReturn(50);

        mockMvc.perform(delete("/api/ingestion/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(50));
    }

    @Test
    void countChunks() throws Exception {
        when(ingestionService.countChunksByProject("test-project")).thenReturn(42L);

        mockMvc.perform(get("/api/ingestion/chunks/test-project/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project").value("test-project"))
                .andExpect(jsonPath("$.count").value(42));
    }
}
