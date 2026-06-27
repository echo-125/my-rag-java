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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 文件切分路由器：根据文件扩展名选择合适的切分器。
 * 使用 Tika 解析文档（自带编码探测），解决 GBK/UTF-8 混合编码问题。
 */
@Component
public class FileSplitterRouter {

    private static final Logger log = LoggerFactory.getLogger(FileSplitterRouter.class);
    private static final Tika TIKA = new Tika();

    /**
     * 根据文件路径判断语言/类型，选择合适的切分器进行切分。
     */
    public List<TextSegment> split(Path file, String projectName) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = getExtension(fileName);
        byte[] bytes = Files.readAllBytes(file);

        // 统一使用 Tika 读取文本内容（自动探测编码：UTF-8/GBK/GB2312 等）
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

        return switch (extension) {
            case "java" -> new JavaAstDocumentSplitter(projectName, file.toString()).split(doc);
            case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> {
                String lang = extension.startsWith("ts") ? "typescript" : "javascript";
                yield new RegexSplitter(projectName, file.toString(), lang).split(doc);
            }
            case "py" -> new RegexSplitter(projectName, file.toString(), "python").split(doc);
            case "go" -> new RegexSplitter(projectName, file.toString(), "go").split(doc);
            case "md", "txt", "csv", "json", "xml", "yaml", "yml",
                 "properties", "sql", "sh", "bat", "cmd", "gradle" ->
                    new DocumentByParagraphSplitter(500, 50).split(doc);
            case "pdf", "doc", "docx" -> new DocumentByParagraphSplitter(500, 50).split(doc);
            default -> new DocumentByLineSplitter(50, 10).split(doc);
        };
    }

    /**
     * 判断文件是否为可处理的文本/文档类型。
     */
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
