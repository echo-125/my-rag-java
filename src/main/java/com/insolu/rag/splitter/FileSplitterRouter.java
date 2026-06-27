package com.insolu.rag.splitter;

import com.insolu.rag.service.RagConfigService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件切分路由器：根据文件扩展名选择合适的切分器。
 * 所有路径最终都会注入业务元数据（project_name, file_path, language 等）。
 */
@Component
public class FileSplitterRouter {

    private static final Logger log = LoggerFactory.getLogger(FileSplitterRouter.class);
    private static final Tika TIKA = new Tika();

    private final EmbeddingModel embeddingModel;
    private final RagConfigService ragConfigService;

    public FileSplitterRouter(EmbeddingModel embeddingModel, RagConfigService ragConfigService) {
        this.embeddingModel = embeddingModel;
        this.ragConfigService = ragConfigService;
    }

    public List<TextSegment> split(Path file, String projectName) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = getExtension(fileName);
        String filePath = file.toString();
        byte[] bytes = Files.readAllBytes(file);

        // 统一使用 Tika 读取文本内容
        String content;
        try {
            content = TIKA.parseToString(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.warn("Tika 解析失败，跳过: {} - {}", file.getFileName(), e.getMessage());
            return List.of();
        }

        if (content == null || content.isBlank()) {
            log.warn("文档内容为空，跳过: {}", file.getFileName());
            return List.of();
        }

        // PDF/Word 文档预处理：将单换行替换为空格，保留双换行作为段落分隔
        if ("pdf".equals(extension) || "doc".equals(extension) || "docx".equals(extension)) {
            content = preprocessDocumentText(content);
        }

        Document doc = Document.from(content, createFileMetadata(file));
        List<TextSegment> segments;

        segments = switch (extension) {
            case "java" -> new JavaAstDocumentSplitter(projectName, filePath).split(doc);
            case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> {
                String lang = extension.startsWith("ts") ? "typescript" : "javascript";
                yield new RegexSplitter(projectName, filePath, lang).split(doc);
            }
            case "py" -> new RegexSplitter(projectName, filePath, "python").split(doc);
            case "go" -> new RegexSplitter(projectName, filePath, "go").split(doc);
            case "md", "txt", "csv", "json", "xml", "yaml", "yml",
                 "properties", "sql", "sh", "bat", "cmd", "gradle" ->
                    splitAndInjectMetadata(doc, projectName, filePath, "text");
            case "pdf", "doc", "docx" ->
                    splitDocumentWithSemantics(doc, projectName, filePath);
            default -> splitAndInjectMetadata(doc, projectName, filePath, "text");
        };

        return segments;
    }

    /**
     * 预处理文档文本：将连续的单行换行替换为空格，仅保留双换行作为段落分隔。
     * 减少 PDF 视觉换行造成的碎片化。
     * 同时去除页眉页脚水印。
     */
    private String preprocessDocumentText(String content) {
        // 1. 将 \r\n 统一为 \n
        content = content.replace("\r\n", "\n");

        // 2. 去除页眉页脚水印（重复出现的短行）
        content = removeHeaderFooterWatermarks(content);

        // 3. 将连续单换行（非双换行）替换为空格
        //    例如 "line1\nline2\n\nline3" -> "line1 line2\n\nline3"
        content = content.replaceAll("(?<!\n)\n(?!\n)", " ");

        // 4. 将 3 个及以上连续换行压缩为 2 个（段落分隔）
        content = content.replaceAll("\n{3,}", "\n\n");

        // 5. 将连续多个空格压缩为单个空格
        content = content.replaceAll("[ \\t]+", " ");

        return content;
    }

    /**
     * 去除页眉页脚水印：移除重复出现的短行（如公司名、产品名）
     */
    private String removeHeaderFooterWatermarks(String content) {
        String[] lines = content.split("\n");
        List<String> cleanedLines = new ArrayList<>();

        // 统计每行出现的频率
        Map<String, Integer> lineFrequency = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() > 0 && trimmed.length() < 80) {
                lineFrequency.merge(trimmed, 1, Integer::sum);
            }
        }

        // 标记高频短行为水印（出现 5 次以上且长度 < 80 字符）- 提高阈值避免误判
        Set<String> watermarks = new HashSet<>();
        for (Map.Entry<String, Integer> entry : lineFrequency.entrySet()) {
            if (entry.getValue() >= 5 && entry.getKey().length() < 80) {
                watermarks.add(entry.getKey());
            }
        }

        // 移除水印行
        for (String line : lines) {
            String trimmed = line.trim();
            if (!watermarks.contains(trimmed)) {
                cleanedLines.add(line);
            }
        }

        return String.join("\n", cleanedLines);
    }

    /**
     * 使用 SemanticStructureSplitter 处理 PDF/Word 文档。
     * 所有切分参数从 RagConfigService 动态读取。
     * 如果 EmbeddingModel 不可用（未激活配置），自动降级为递归切分器。
     */
    private List<TextSegment> splitDocumentWithSemantics(Document doc, String projectName, String filePath) {
        try {
            double threshold  = ragConfigService.getDouble("semantic_threshold", 0.65);
            int triggerSize   = ragConfigService.getInt("max_segment_size", 1000) + 200;
            int fallbackMax   = ragConfigService.getInt("max_segment_size", 1000);
            int fallbackOverlap = ragConfigService.getInt("max_overlap_size", 200);

            SemanticStructureSplitter semanticSplitter = new SemanticStructureSplitter(
                    embeddingModel, threshold, triggerSize, fallbackMax, fallbackOverlap);

            List<TextSegment> segments = semanticSplitter.split(doc);

            if (!segments.isEmpty()) {
                return segments.stream()
                        .map(seg -> {
                            Metadata meta = new Metadata();
                            try {
                                meta.putAll(seg.metadata().toMap());
                            } catch (IllegalArgumentException e) {
                                log.warn("部分元数据类型不受支持，已跳过: {}", e.getMessage());
                                seg.metadata().toMap().forEach((k, v) -> {
                                    try { meta.put(k, v.toString()); } catch (Exception ignored) {}
                                });
                            }
                            meta.put("project_name", projectName);
                            meta.put("file_path", filePath);
                            meta.put("language", detectLanguage(filePath));
                            meta.put("type", "document");
                            return TextSegment.from(seg.text(), meta);
                        })
                        .toList();
            }
        } catch (IllegalStateException e) {
            log.warn("EmbeddingModel 未就绪，降级为递归切分器: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("语义切分异常，降级为递归切分器: {}", e.getMessage());
        }

        // 降级：使用标准递归切分器
        log.info("使用递归切分器处理: {}", filePath);
        return splitAndInjectMetadata(doc, projectName, filePath, "document");
    }

    /**
     * 使用递归切分器（段落 → 换行 → 句子 → 单词 → 字符），尊重段落和代码块边界。
     * maxSegmentSize=1000 字符，overlap=200 字符，确保充足上下文重叠。
     */
    private List<TextSegment> splitAndInjectMetadata(Document doc, String projectName,
                                                      String filePath, String type) {
        // 使用递归切分器，参数从 RagConfigService 动态读取
        int maxSize   = ragConfigService.getInt("max_segment_size", 1000);
        int overlap   = ragConfigService.getInt("max_overlap_size", 200);
        dev.langchain4j.data.document.DocumentSplitter recursiveSplitter =
                DocumentSplitters.recursive(maxSize, overlap);
        List<TextSegment> segments = recursiveSplitter.split(doc);

        // LangChain4j 的切分器不携带自定义 metadata，需要手动注入业务字段
        return segments.stream()
                .map(seg -> {
                    Metadata meta = new Metadata(seg.metadata().toMap());
                    meta.put("project_name", projectName);
                    meta.put("file_path", filePath);
                    meta.put("language", detectLanguage(filePath));
                    meta.put("type", type);
                    meta.put("signature", "");
                    return TextSegment.from(seg.text(), meta);
                })
                .toList();
    }

    /** 根据文件扩展名推断语言 */
    private String detectLanguage(String filePath) {
        String lower = filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh") || lower.endsWith(".bat")) return "shell";
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")) return "document";
        return "text";
    }

    public boolean isSupported(String fileName) {
        String ext = getExtension(fileName.toLowerCase(Locale.ROOT));
        return switch (ext) {
            case "java", "js", "jsx", "ts", "tsx", "mjs", "cjs", "py", "go",
                 "md", "txt", "csv", "json", "xml", "yaml", "yml",
                 "properties", "sql", "sh", "bat", "cmd", "gradle",
                 "pdf", "doc", "docx" -> true;
            default -> false;
        };
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    private Metadata createFileMetadata(Path file) {
        Metadata metadata = new Metadata();
        metadata.put(Document.FILE_NAME, file.getFileName().toString());
        metadata.put(Document.ABSOLUTE_DIRECTORY_PATH, file.getParent().toAbsolutePath().toString());
        return metadata;
    }
}
