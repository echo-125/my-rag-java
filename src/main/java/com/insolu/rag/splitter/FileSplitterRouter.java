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

        // ─── Excel/PPT 使用 POI 结构化解析（替代 Tika 扁平化提取） ───
        String content;
        if ("xlsx".equals(extension)) {
            ExcelStructuredParser excelParser = new ExcelStructuredParser();
            List<String> markdownChunks = excelParser.parse(bytes, fileName);
            if (markdownChunks.isEmpty()) {
                log.warn("Excel 结构化解析结果为空，跳过: {}", file.getFileName());
                return List.of();
            }
            content = String.join("\n\n---\n\n", markdownChunks);
        } else if ("pptx".equals(extension)) {
            PptxStructuredParser pptParser = new PptxStructuredParser();
            List<String> slideMarkdowns = pptParser.parse(bytes, fileName);
            if (slideMarkdowns.isEmpty()) {
                log.warn("PPT 结构化解析结果为空，跳过: {}", file.getFileName());
                return List.of();
            }
            content = String.join("\n\n---\n\n", slideMarkdowns);
        } else {
            // 其他文件类型统一使用 Tika 读取文本内容
            try {
                content = TIKA.parseToString(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                log.warn("Tika 解析失败，跳过: {} - {}", file.getFileName(), e.getMessage());
                return List.of();
            }
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
            case "cs" -> new CSharpSplitter(projectName, filePath, "csharp").split(doc);
            case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> {
                String lang = extension.startsWith("ts") ? "typescript" : "javascript";
                yield new RegexSplitter(projectName, filePath, lang).split(doc);
            }
            case "py" -> new RegexSplitter(projectName, filePath, "python").split(doc);
            case "go" -> new RegexSplitter(projectName, filePath, "go").split(doc);
            case "vue" -> new VueSplitter(projectName, filePath, "vue").split(doc);
            case "qml" -> new QmlSplitter(projectName, filePath, "qml").split(doc);
            case "html", "htm" -> new HtmlSplitter(projectName, filePath, "html").split(doc);
            case "css", "scss" -> new CssSplitter(projectName, filePath, extension).split(doc);
            case "md" -> splitDocumentWithSemantics(doc, projectName, filePath, "markdown");
            case "xlsx", "pptx" ->
                    splitDocumentWithSemantics(doc, projectName, filePath, "document");
            case "txt", "csv", "json", "xml", "yaml", "yml",
                 "properties", "sql", "sh", "bat", "cmd", "gradle" ->
                    splitAndInjectMetadata(doc, projectName, filePath, "text");
            case "pdf", "doc", "docx" ->
                    splitDocumentWithSemantics(doc, projectName, filePath, "document");
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
     * 使用 SemanticStructureSplitter 处理 PDF/Word/Markdown 文档。
     * 所有切分参数从 RagConfigService 动态读取。
     * 如果 EmbeddingModel 不可用（未激活配置），自动降级为递归切分器。
     *
     * @param type 文档类型，用于元数据注入（如 "document"、"markdown"）
     */
    private List<TextSegment> splitDocumentWithSemantics(Document doc, String projectName,
                                                          String filePath, String type) {
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
                            meta.put("type", type);
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
        return splitAndInjectMetadata(doc, projectName, filePath, type);
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
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".qml")) return "qml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css")) return "css";
        if (lower.endsWith(".scss")) return "scss";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh") || lower.endsWith(".bat")) return "shell";
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")) return "document";
        return "text";
    }

    /** 产物包/二进制/媒体文件扩展名（始终排除） */
    private static final java.util.Set<String> EXCLUDED_EXTENSIONS = java.util.Set.of(
            // 压缩包
            "zip", "rar", "tar", "gz", "7z", "bz2", "xz", "cab",
            // 二进制库/可执行文件
            "jar", "war", "ear", "xjar", "dll", "exe", "so", "dylib", "msi", "cpl",
            // 编译产物
            "class", "obj", "o", "a", "lib", "pdb",
            // 媒体文件
            "mp3", "mp4", "avi", "mov", "mkv", "wmv", "flv",
            // 图片
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "ico", "webp",
            // 字体
            "ttf", "otf", "woff", "woff2", "eot",
            // 日志/临时文件
            "log", "tmp", "temp", "swp", "swo", "bak", "orig",
            // Python 缓存
            "pyc", "pyo",
            // source maps
            "map", "ts.map", "js.map", "css.map",
            // IDE 配置
            "iml",
            // 其他不可读取/无意义格式
            "pf", "jfc", "ps", "pcl", "xps", "psd", "access", "bfc", "dat", "data",
            "cfg", "src", "lic", "policy", "jsa", "template"
    );

    /** 构建产物/IDE/日志目录关键词（路径包含任一关键词则跳过） */
    private static final java.util.Set<String> EXCLUDED_PATH_KEYWORDS = java.util.Set.of(
            // Java 构建产物
            "target/", "BOOT-INF/",
            // .NET 构建产物
            "bin/", "obj/", "packages/",
            // 前端构建产物
            "node_modules/", "dist/", "build/", "unpackage/", ".next/", ".nuxt/",
            // IDE 配置
            ".idea/", ".vscode/", ".settings/",
            // VCS
            ".git/", ".svn/", ".hg/",
            // 日志
            "logs/",
            // Python 缓存
            "__pycache__/", ".mypy_cache/",
            // Gradle 缓存
            ".gradle/"
    );

    /**
     * 需在递归扫描时跳过的目录名（无尾部斜杠，用于 {@code preVisitDirectory} 匹配）。
     * 由 {@link com.insolu.rag.service.IngestionService} 引用，保持过滤规则一致。
     */
    public static final java.util.Set<String> SKIP_DIR_NAMES = java.util.Set.of(
            "node_modules", ".git", ".svn", ".hg",
            "target", "build", "dist", "bin", "obj", "packages",
            ".idea", ".vscode", ".settings",
            "logs", "unpackage", ".next", ".nuxt",
            "__pycache__", ".mypy_cache", ".gradle",
            "BOOT-INF", "out"
    );

    public boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String ext = getExtension(lower);

        // 1. 排除产物包和二进制文件（按扩展名）
        if (EXCLUDED_EXTENSIONS.contains(ext)) return false;

        // 2. 排除构建目录/IDE/VCS/日志中的文件（按路径关键词）
        for (String keyword : EXCLUDED_PATH_KEYWORDS) {
            if (lower.contains(keyword)) return false;
        }

        // 3. 白名单检查
        return switch (ext) {
            // 代码文件
            case "java", "cs", "js", "jsx", "ts", "tsx", "mjs", "cjs", "py", "go",
                 "vue", "qml", "html", "htm", "css", "scss",
            // 文档文件
                 "md", "txt", "pdf", "doc", "docx", "pptx", "xlsx", "url",
            // 配置/数据文件
                 "csv", "json", "xml", "yaml", "yml", "properties", "conf",
            // 脚本文件
                 "sql", "sh", "bat", "cmd", "gradle" -> true;
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
