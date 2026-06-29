package com.he.splitter;

import com.he.service.RagConfigService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文件切分路由器：根据文件扩展名选择合适的切分器。
 * 采用注册表模式——新增语言只需在 {@link #SPLITTER_REGISTRY} 中注册一次。
 * 所有路径最终都会注入业务元数据（project_name, file_path, language 等）。
 */
@Component
public class FileSplitterRouter {

    private static final Logger log = LoggerFactory.getLogger(FileSplitterRouter.class);
    private static final Tika TIKA = new Tika();

    // ═══════════════════════════════════════════════════
    //  切分器注册表
    // ═══════════════════════════════════════════════════

    @FunctionalInterface
    private interface SplitterFactory {
        DocumentSplitter create(String projectName, String filePath);
    }

    /** 扩展名 → 切分器工厂注册表。新增语言只需在此添加一行。 */
    private static final Map<String, SplitterFactory> SPLITTER_REGISTRY = Map.ofEntries(
            // Java（AST 切分）
            Map.entry("java", (p, f) -> new JavaAstDocumentSplitter(p, f)),
            // C#
            Map.entry("cs", (p, f) -> new CSharpSplitter(p, f, "csharp")),
            // JavaScript / TypeScript（正则切分）
            Map.entry("js", (p, f) -> new RegexSplitter(p, f, "javascript")),
            Map.entry("jsx", (p, f) -> new RegexSplitter(p, f, "javascript")),
            Map.entry("mjs", (p, f) -> new RegexSplitter(p, f, "javascript")),
            Map.entry("cjs", (p, f) -> new RegexSplitter(p, f, "javascript")),
            Map.entry("ts", (p, f) -> new RegexSplitter(p, f, "typescript")),
            Map.entry("tsx", (p, f) -> new RegexSplitter(p, f, "typescript")),
            // Python
            Map.entry("py", (p, f) -> new RegexSplitter(p, f, "python")),
            // Go
            Map.entry("go", (p, f) -> new RegexSplitter(p, f, "go")),
            // Vue / QML / HTML / CSS（专用切分器）
            Map.entry("vue", (p, f) -> new VueSplitter(p, f, "vue")),
            Map.entry("qml", (p, f) -> new QmlSplitter(p, f, "qml")),
            Map.entry("html", (p, f) -> new HtmlSplitter(p, f, "html")),
            Map.entry("htm", (p, f) -> new HtmlSplitter(p, f, "html")),
            Map.entry("css", (p, f) -> new CssSplitter(p, f, "css")),
            Map.entry("scss", (p, f) -> new CssSplitter(p, f, "scss"))
    );

    /** 语言检测：扩展名 → 语言标识符 */
    private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("cs", "csharp"),
            Map.entry("py", "python"), Map.entry("go", "go"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("vue", "vue"), Map.entry("qml", "qml"),
            Map.entry("html", "html"), Map.entry("htm", "html"),
            Map.entry("css", "css"), Map.entry("scss", "scss"),
            Map.entry("md", "markdown"), Map.entry("xml", "xml"),
            Map.entry("json", "json"), Map.entry("sql", "sql"),
            Map.entry("sh", "shell"), Map.entry("bat", "shell"),
            Map.entry("pdf", "document"), Map.entry("doc", "document"),
            Map.entry("docx", "document")
    );

    // ═══════════════════════════════════════════════════
    //  构造器
    // ═══════════════════════════════════════════════════

    private final EmbeddingModel embeddingModel;
    private final RagConfigService ragConfigService;

    public FileSplitterRouter(EmbeddingModel embeddingModel, RagConfigService ragConfigService) {
        this.embeddingModel = embeddingModel;
        this.ragConfigService = ragConfigService;
    }

    // ═══════════════════════════════════════════════════
    //  公共 API
    // ═══════════════════════════════════════════════════

    /**
     * 切分单个文件，返回 TextSegment 列表。
     * 流程：内容提取 → 文本预处理 → 注册表路由切分 → 降级兜底。
     */
    public List<TextSegment> split(Path file, String projectName) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = getExtension(fileName);
        String filePath = file.toString();

        // 流式读取，避免大文件一次性加载全部字节到内存
        String content;
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
            content = extractContent(extension, is, file.getFileName().toString());
        }
        if (content == null || content.isBlank()) {
            log.warn("文档内容为空，跳过: {}", file.getFileName());
            return List.of();
        }

        // PDF/Word 预处理
        if ("pdf".equals(extension) || "doc".equals(extension) || "docx".equals(extension)) {
            content = preprocessDocumentText(content);
        }

        Document doc = Document.from(content, createFileMetadata(file));

        // 注册表路由
        SplitterFactory factory = SPLITTER_REGISTRY.get(extension);
        if (factory != null) {
            return factory.create(projectName, filePath).split(doc);
        }

        // 未注册的扩展名：降级处理
        if ("md".equals(extension)) {
            return splitDocumentWithSemantics(doc, projectName, filePath, "markdown");
        }
        return splitAndInjectMetadata(doc, projectName, filePath, "text");
    }

    /** 判断文件是否支持处理（按扩展名白名单，单一决策源：注册表 + 语言映射） */
    public boolean isSupported(String fileName) {
        String ext = getExtension(fileName.toLowerCase(Locale.ROOT));
        if (EXCLUDED_EXTENSIONS.contains(ext)) return false;
        return SPLITTER_REGISTRY.containsKey(ext) || LANGUAGE_MAP.containsKey(ext);
    }

    /** 根据文件扩展名推断语言 */
    public String detectLanguage(String filePath) {
        String ext = getExtension(filePath.toLowerCase(Locale.ROOT));
        return LANGUAGE_MAP.getOrDefault(ext, "text");
    }

    // ═══════════════════════════════════════════════════
    //  内容提取
    // ═══════════════════════════════════════════════════

    /** 根据扩展名选择内容提取方式（流式） */
    private String extractContent(String extension, InputStream is, String fileName) {
        try {
            return switch (extension) {
                case "xlsx" -> {
                    // POI 内部会缓冲流，直接传入即可
                    List<String> chunks = new ExcelStructuredParser().parse(is, fileName);
                    yield chunks.isEmpty() ? null : String.join("\n\n---\n\n", chunks);
                }
                case "pptx" -> {
                    List<String> slides = new PptxStructuredParser().parse(is, fileName);
                    yield slides.isEmpty() ? null : String.join("\n\n---\n\n", slides);
                }
                default -> TIKA.parseToString(is);
            };
        } catch (Exception e) {
            log.warn("内容提取失败，跳过: {} - {}", fileName, e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════
    //  降级切分器（未注册扩展名使用）
    // ═══════════════════════════════════════════════════

    /**
     * 降级：使用标准递归切分器（段落 → 换行 → 句子 → 单词 → 字符）。
     */
    private List<TextSegment> splitAndInjectMetadata(Document doc, String projectName,
                                                      String filePath, String type) {
        int maxSize = ragConfigService.getInt("max_segment_size", 1000);
        int overlap = ragConfigService.getInt("max_overlap_size", 200);
        DocumentSplitter recursiveSplitter = DocumentSplitters.recursive(maxSize, overlap);
        List<TextSegment> segments = recursiveSplitter.split(doc);

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

    // ═══════════════════════════════════════════════════
    //  文档语义切分（PDF/Word/Markdown）
    // ═══════════════════════════════════════════════════

    /**
     * 使用 SemanticStructureSplitter 处理 PDF/Word/Markdown 文档。
     * 如果 EmbeddingModel 不可用，自动降级为递归切分器。
     */
    private List<TextSegment> splitDocumentWithSemantics(Document doc, String projectName,
                                                          String filePath, String type) {
        try {
            double threshold = ragConfigService.getDouble("semantic_threshold", 0.65);
            int triggerSize = ragConfigService.getInt("max_segment_size", 1000) + 200;
            int fallbackMax = ragConfigService.getInt("max_segment_size", 1000);
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

        log.info("使用递归切分器处理: {}", filePath);
        return splitAndInjectMetadata(doc, projectName, filePath, type);
    }

    // ═══════════════════════════════════════════════════
    //  文本预处理
    // ═══════════════════════════════════════════════════

    /**
     * 预处理文档文本：将连续的单行换行替换为空格，仅保留双换行作为段落分隔。
     */
    private String preprocessDocumentText(String content) {
        content = content.replace("\r\n", "\n");
        content = removeHeaderFooterWatermarks(content);
        content = PAT_SINGLE_NEWLINE.matcher(content).replaceAll(" ");
        content = PAT_MULTI_NEWLINE.matcher(content).replaceAll("\n\n");
        content = PAT_EXCESS_SPACE.matcher(content).replaceAll(" ");
        return content;
    }

    /** 去除页眉页脚水印：移除重复出现的短行 */
    private String removeHeaderFooterWatermarks(String content) {
        String[] lines = content.split("\n");
        List<String> cleanedLines = new ArrayList<>();

        Map<String, Integer> lineFrequency = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.length() < 80) {
                lineFrequency.merge(trimmed, 1, Integer::sum);
            }
        }

        Set<String> watermarks = new HashSet<>();
        for (Map.Entry<String, Integer> entry : lineFrequency.entrySet()) {
            if (entry.getValue() >= 5 && entry.getKey().length() < 80) {
                watermarks.add(entry.getKey());
            }
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (!watermarks.contains(trimmed)) {
                cleanedLines.add(line);
            }
        }

        return String.join("\n", cleanedLines);
    }

    // ═══════════════════════════════════════════════════
    //  常量与工具
    // ═══════════════════════════════════════════════════

    // ─── 预处理正则（预编译，避免 replaceAll 热循环中反复编译）───
    private static final Pattern PAT_SINGLE_NEWLINE = Pattern.compile("(?<!\\n)\\n(?!\\n)");
    private static final Pattern PAT_MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    private static final Pattern PAT_EXCESS_SPACE = Pattern.compile("[ \\t]+");

    /** 产物包/二进制/媒体文件扩展名（始终排除） */
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            "zip", "rar", "tar", "gz", "7z", "bz2", "xz", "cab",
            "jar", "war", "ear", "xjar", "dll", "exe", "so", "dylib", "msi", "cpl",
            "class", "obj", "o", "a", "lib", "pdb",
            "mp3", "mp4", "avi", "mov", "mkv", "wmv", "flv",
            "png", "jpg", "jpeg", "gif", "bmp", "svg", "ico", "webp",
            "ttf", "otf", "woff", "woff2", "eot",
            "log", "tmp", "temp", "swp", "swo", "bak", "orig",
            "pyc", "pyo",
            "map", "ts.map", "js.map", "css.map",
            "iml",
            "pf", "jfc", "ps", "pcl", "xps", "psd", "access", "bfc", "dat", "data",
            "lic", "policy", "jsa", "template"
    );

    /** 文档/文本/配置类扩展名（isSupported 白名单的一部分，不走注册表切分器） */
    private static final Set<String> DOCUMENT_TEXT_EXTENSIONS = Set.of(
            "md", "txt", "pdf", "doc", "docx", "pptx", "xlsx", "url",
            "csv", "json", "xml", "yaml", "yml", "properties", "conf",
            "sql", "sh", "bat", "cmd", "gradle"
    );

    /**
     * 需在递归扫描时跳过的目录名。
     * 由 {@link com.he.service.IngestionService} 引用，保持过滤规则一致。
     */
    public static final Set<String> SKIP_DIR_NAMES = Set.of(
            "node_modules", ".git", ".svn", ".hg",
            "target", "build", "dist", "bin", "obj", "packages",
            ".idea", ".vscode", ".settings",
            "logs", "unpackage", ".next", ".nuxt",
            "__pycache__", ".mypy_cache", ".gradle",
            "BOOT-INF", "out"
    );

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

