package com.he.service;

import com.he.entity.ProjectConfigEntity;
import com.he.entity.ProjectConfigRepository;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);
    private static final int MAX_FILE_CHARS = 8000;
    private static final int MAX_DIR_ENTRIES = 50;
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".env", ".env.local", ".env.production",
            ".pem", ".key", ".p12", ".pfx", ".jks",
            ".keystore", ".truststore",
            "credentials.json", "service-account.json"
    );
    private static final Set<String> BLOCKED_FILENAMES = Set.of(
            ".env", ".env.local", ".env.production", ".env.development",
            "id_rsa", "id_ed25519", "id_dsa", "id_ecdsa",
            "credentials.json", "service-account.json", "secret.json",
            "keystore.jks", "truststore.jks"
    );

    private final RagChatService ragChatService;
    private final ProjectConfigRepository projectConfigRepo;
    private final JdbcTemplate jdbcTemplate;

    public AgentTools(RagChatService ragChatService,
                      ProjectConfigRepository projectConfigRepo,
                      JdbcTemplate jdbcTemplate) {
        this.ragChatService = ragChatService;
        this.projectConfigRepo = projectConfigRepo;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "搜索知识库中与查询相关的代码和文档片段。返回最相关的文件路径、签名和内容摘要。适用于查找特定功能、类、方法的实现位置。")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询文本，可以是自然语言问题或关键词，如 'IngestionService 的入口方法' 或 '如何处理 Excel 文件'") String query) {
        long start = System.nanoTime();
        try {
            // 注意：retrieve() 是纯检索方法，不触发 LLM 调用，不会形成 Agent 递归循环。
            // 如果未来 retrieve() 内部增加了 LLM 调用，需要在此处添加递归深度检查。
            List<Content> contents = ragChatService.retrieve(query);
            if (contents.isEmpty()) {
                return "未找到相关内容。";
            }
            StringBuilder sb = new StringBuilder();
            int count = Math.min(5, contents.size());
            for (int i = 0; i < count; i++) {
                Content c = contents.get(i);
                var meta = c.textSegment().metadata();
                String filePath = meta.getString("file_path");
                String type = meta.getString("type");
                String signature = meta.getString("signature");
                String text = c.textSegment().text();
                if (text.length() > 1500) text = text.substring(0, 1500) + "...";
                sb.append(String.format("[%d] [%s] %s (%s)\n%s\n\n", i + 1, type, signature, filePath, text));
            }
            return sb.toString().trim();
        } finally {
            AgentToolMetadata.record("searchKnowledge", query, (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Tool(description = "读取指定文件的内容。用于查看代码或文档的完整内容。文件路径可以是绝对路径或相对于项目根目录的路径。")
    public String readFile(
            @ToolParam(description = "文件的绝对路径或相对于项目根目录的路径，如 'src/main/java/com/he/service/IngestionService.java'") String filePath) {
        long start = System.nanoTime();
        try {
            Path resolved = resolveAndValidate(filePath);
            if (resolved == null) {
                return "错误：文件路径不合法或不在允许的项目目录内。";
            }
            if (isBlockedFile(resolved)) {
                return "错误：该文件类型被安全策略禁止读取。";
            }
            if (!Files.exists(resolved)) {
                return "错误：文件不存在: " + filePath;
            }
            if (!Files.isRegularFile(resolved)) {
                return "错误：路径不是文件: " + filePath;
            }
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            long totalLines = content.lines().count();
            if (content.length() > MAX_FILE_CHARS) {
                String truncated = content.substring(0, MAX_FILE_CHARS);
                long shownLines = truncated.lines().count();
                content = truncated + String.format("\n\n... (已显示 %d 行，共 %d 行)", shownLines, totalLines);
            }
            return String.format("文件: %s (%d 行)\n\n%s", resolved.getFileName(), totalLines, content);
        } catch (IOException e) {
            return "错误：读取文件失败: " + e.getMessage();
        } finally {
            AgentToolMetadata.record("readFile", filePath, (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Tool(description = "列出指定目录下的文件和子目录。用于浏览项目结构。只返回第一层内容，不递归。")
    public String listDirectory(
            @ToolParam(description = "目录路径，如 'src/main/java/com/he/service' 或项目根目录") String dirPath) {
        long start = System.nanoTime();
        try {
            Path resolved = resolveAndValidate(dirPath);
            if (resolved == null) {
                return "错误：目录路径不合法或不在允许的项目目录内。";
            }
            if (!Files.exists(resolved)) {
                return "错误：目录不存在: " + dirPath;
            }
            if (!Files.isDirectory(resolved)) {
                return "错误：路径不是目录: " + dirPath;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("目录: ").append(resolved).append("\n\n");
            int count = 0;
            try (Stream<Path> entries = Files.list(resolved)) {
                List<Path> sorted = entries.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (Path entry : sorted) {
                    if (count >= MAX_DIR_ENTRIES) {
                        sb.append("... 还有 ").append(sorted.size() - count).append(" 个条目未显示\n");
                        break;
                    }
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        sb.append(String.format("  [DIR]  %s/\n", name));
                    } else {
                        try {
                            long size = Files.size(entry);
                            sb.append(String.format("  [FILE] %s  (%s)\n", name, formatSize(size)));
                        } catch (IOException e) {
                            sb.append(String.format("  [FILE] %s\n", name));
                        }
                    }
                    count++;
                }
            }
            if (count == 0) {
                sb.append("  (空目录)");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "错误：列出目录失败: " + e.getMessage();
        } finally {
            AgentToolMetadata.record("listDirectory", dirPath, (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Tool(description = "获取知识库的统计信息：已入库的项目列表、各项目 chunk 数量、总 chunk 数。")
    public String getKnowledgeBaseStats() {
        long start = System.nanoTime();
        try {
            List<ProjectConfigEntity> projects = projectConfigRepo.findAll();
            List<Map<String, Object>> chunkStats = jdbcTemplate.queryForList(
                    "SELECT project_name, COUNT(*) as cnt FROM document_chunks GROUP BY project_name");

            Map<String, Long> chunkMap = new LinkedHashMap<>();
            for (Map<String, Object> row : chunkStats) {
                String name = (String) row.get("project_name");
                Long cnt = ((Number) row.get("cnt")).longValue();
                chunkMap.put(name != null ? name : "(未命名)", cnt);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("知识库统计:\n\n");
            sb.append(String.format("项目总数: %d\n", projects.size()));
            long totalChunks = chunkMap.values().stream().mapToLong(Long::longValue).sum();
            sb.append(String.format("总 chunk 数: %d\n\n", totalChunks));

            if (!projects.isEmpty()) {
                sb.append("项目列表:\n");
                for (ProjectConfigEntity p : projects) {
                    long chunks = chunkMap.getOrDefault(p.getName(), 0L);
                    sb.append(String.format("  - %s (%s) → %d chunks\n", p.getName(), p.getPath(), chunks));
                }
            } else {
                sb.append("暂无已入库的项目。");
            }
            return sb.toString().trim();
        } finally {
            AgentToolMetadata.record("getKnowledgeBaseStats", "", (System.nanoTime() - start) / 1_000_000);
        }
    }

    // ─── 安全校验 ───

    private Path resolveAndValidate(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) return null;

        // 规范化路径，解析 ..
        Path candidate = Path.of(inputPath).normalize();

        // 禁止绝对路径穿越到系统目录（允许项目内的绝对路径）
        // 如果是相对路径，尝试在各项目目录下查找
        if (!candidate.isAbsolute()) {
            for (ProjectConfigEntity project : projectConfigRepo.findAll()) {
                Path projectDir = Path.of(project.getPath());
                Path resolved = projectDir.resolve(candidate).normalize();
                if (Files.exists(resolved) && isInsideProject(resolved, projectDir)) {
                    return resolved;
                }
            }
            return null;
        }

        // 绝对路径：检查是否在某个项目目录内
        for (ProjectConfigEntity project : projectConfigRepo.findAll()) {
            Path projectDir = Path.of(project.getPath()).normalize();
            if (isInsideProject(candidate, projectDir)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isBlockedFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (BLOCKED_FILENAMES.contains(fileName)) return true;
        for (String ext : BLOCKED_EXTENSIONS) {
            if (fileName.endsWith(ext)) return true;
        }
        // 检查路径中是否包含 .git 子目录
        String pathStr = path.toString().replace('\\', '/');
        if (pathStr.contains("/.git/")) return true;
        return false;
    }

    private boolean isInsideProject(Path target, Path projectDir) {
        try {
            Path realTarget = Files.exists(target) ? target.toRealPath().normalize() : target.normalize();
            Path realProject = projectDir.toRealPath().normalize();
            return realTarget.startsWith(realProject);
        } catch (IOException e) {
            return false;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
