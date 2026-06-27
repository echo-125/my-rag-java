package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 文件切分路由器：根据文件扩展名选择合适的切分器。
 * 所有路径最终都会注入业务元数据（project_name, file_path, language 等）。
 */
@Component
public class FileSplitterRouter {

    private static final Logger log = LoggerFactory.getLogger(FileSplitterRouter.class);
    private static final Tika TIKA = new Tika();

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
                    splitAndInjectMetadata(doc, projectName, filePath, "document");
            default -> splitAndInjectMetadata(doc, projectName, filePath, "text");
        };

        return segments;
    }

    /**
     * 使用 DocumentByParagraphSplitter 切分，并为每个 segment 注入业务元数据。
     * maxSegmentSize=1500 字符（约 500-750 汉字），overlap=150 字符。
     */
    private List<TextSegment> splitAndInjectMetadata(Document doc, String projectName,
                                                      String filePath, String type) {
        var splitter = new DocumentByParagraphSplitter(1500, 150);
        List<TextSegment> segments = splitter.split(doc);

        // LangChain4j 的 DocumentByParagraphSplitter 不携带自定义 metadata，
        // 需要手动注入业务字段
        return segments.stream()
                .map(seg -> {
                    Metadata meta = new Metadata(seg.metadata().toMap());
                    meta.put("project_name", projectName);
                    meta.put("file_path", filePath);
                    meta.put("language", detectLanguage(filePath));
                    meta.put("type", type);
                    meta.put("signature", "");
                    meta.put("start_line", "0");
                    meta.put("end_line", "0");
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
