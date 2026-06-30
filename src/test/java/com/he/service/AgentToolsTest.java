package com.he.service;

import com.he.entity.ProjectConfigEntity;
import com.he.entity.ProjectConfigRepository;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AgentToolsTest {

    @Autowired
    private AgentTools agentTools;

    @Autowired
    private ProjectConfigRepository projectConfigRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        // 清理 ThreadLocal
        AgentToolMetadata.collectAndClear();

        // 创建测试文件
        Files.writeString(tempDir.resolve("test.txt"), "Hello, World!");
        Files.writeString(tempDir.resolve(".env"), "SECRET=abc123");
        Files.writeString(tempDir.resolve("id_rsa"), "private-key-content");
        Files.createDirectories(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("subdir/nested.txt"), "nested content");
    }

    @Test
    void searchKnowledge_returnsResults() {
        String result = agentTools.searchKnowledge("惠普");
        assertNotNull(result);
        // 应该返回非空结果（如果知识库有数据）
    }

    @Test
    void readFile_validPath() throws IOException {
        // 确保 tempDir 在项目白名单中
        String result = agentTools.readFile(tempDir.resolve("test.txt").toString());
        assertNotNull(result);
        assertTrue(result.contains("Hello, World!"));
    }

    @Test
    void readFile_blockedExtension() {
        String result = agentTools.readFile(tempDir.resolve(".env").toString());
        assertNotNull(result);
        assertTrue(result.contains("禁止读取") || result.contains("安全策略"));
    }

    @Test
    void readFile_blockedFilename() {
        String result = agentTools.readFile(tempDir.resolve("id_rsa").toString());
        assertNotNull(result);
        assertTrue(result.contains("禁止读取") || result.contains("安全策略"));
    }

    @Test
    void listDirectory_validPath() {
        String result = agentTools.listDirectory(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("test.txt"));
        assertTrue(result.contains("subdir"));
    }

    @Test
    void getKnowledgeBaseStats() {
        String result = agentTools.getKnowledgeBaseStats();
        assertNotNull(result);
        assertTrue(result.contains("知识库统计"));
    }

    @Test
    void metadata_collection() {
        agentTools.getKnowledgeBaseStats();
        List<AgentToolMetadata.ToolCallRecord> calls = AgentToolMetadata.collectAndClear();
        assertEquals(1, calls.size());
        assertEquals("getKnowledgeBaseStats", calls.get(0).toolName());
        assertTrue(calls.get(0).durationMs() >= 0);
    }

    @Test
    void metadata_threadLocal_isolation() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            agentTools.getKnowledgeBaseStats();
        });
        Thread t2 = new Thread(() -> {
            agentTools.getKnowledgeBaseStats();
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // 主线程的 ThreadLocal 应为空
        List<AgentToolMetadata.ToolCallRecord> mainCalls = AgentToolMetadata.collectAndClear();
        assertTrue(mainCalls.isEmpty());
    }
}
