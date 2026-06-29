package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 三级降级文档切分器：标题物理切分 → 段落语义切分 → 递归字符兜底。
 * <p>
 * 设计目标：在检索效果和切分性能之间取得平衡。
 * <ul>
 *   <li>第一级：按标题层级（中文章节 / 数字编号 / Markdown）拆分为章节块，注入父级标题上下文</li>
 *   <li>第二级：仅对超长章节块（>1200 字符）按段落语义相似度切分，节省短文本的向量化开销</li>
 *   <li>第三级：对仍然超长的块使用递归切分器兜底</li>
 * </ul>
 * 适用于 PDF / Word / Markdown 等结构化文档，代码文件应使用专用切分器。
 */
public class SemanticStructureSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticStructureSplitter.class);

    // ─── 元数据键 ───
    private static final String META_PROJECT = "project_name";
    private static final String META_FILE_PATH = "file_path";
    private static final String META_LANGUAGE = "language";
    private static final String META_TYPE = "type";
    private static final String META_SIGNATURE = "signature";

    // ─── 切分参数 ───
    /** 语义相似度阈值：低于此值视为话题转变 */
    private final double semanticThreshold;
    /** 触发第二级语义切分的最小字符数 */
    private final int semanticTriggerSize;
    /** 第三级兜底的最大 chunk 尺寸 */
    private final int fallbackMaxChunkSize;
    /** 第三级兜底的重叠尺寸 */
    private final int fallbackOverlapSize;

    private final EmbeddingModel embeddingModel;

    // ─── 标题检测模式 ───
    private static final List<HeaderPattern> HEADER_PATTERNS;

    static {
        List<HeaderPattern> patterns = new ArrayList<>();

        // Markdown 标题
        patterns.add(new HeaderPattern(Pattern.compile("^#{1,6}\\s+.*"), 1, ""));

        // 中文章节
        patterns.add(new HeaderPattern(
                Pattern.compile("^第[0-9一二三四五六七八九十百千万零壹贰叁肆伍陆柒捌玖拾佰仟]+[章节篇部卷]\\s+.*"), 1, ""));
        patterns.add(new HeaderPattern(
                Pattern.compile("^附录\\s*[A-Za-z0-9]?\\s*[：:]?.*"), 1, ""));

        // 数字编号（1.1, 1.1.1, 2.3.4.5 等）
        patterns.add(new HeaderPattern(
                Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+\\s+.*"), 4, ""));
        patterns.add(new HeaderPattern(
                Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+\\s+.*"), 3, ""));
        patterns.add(new HeaderPattern(
                Pattern.compile("^[0-9]+\\.[0-9]+\\s+.*"), 2, ""));

        // 第 N 节
        patterns.add(new HeaderPattern(
                Pattern.compile("^第\\s*[0-9一二三四五六七八九十]+\\s*[节部分]\\s*.*"), 1, ""));

        // 中文圆括号序号：（一）（二）等
        patterns.add(new HeaderPattern(
                Pattern.compile("^（[一二三四五六七八九十百]+）\\s*.*"), 2, ""));

        // 有序列表项（仅顶层用作标题）
        patterns.add(new HeaderPattern(
                Pattern.compile("^[0-9]+[.、)]\\s+.{2,}"), 2, ""));

        HEADER_PATTERNS = List.copyOf(patterns);
    }

    /** 标题模式：正则 + 层级深度 + Markdown 前缀（用于去重） */
    private record HeaderPattern(Pattern pattern, int level, String markdownPrefix) {
        boolean matches(String line) {
            return pattern.matcher(line).matches();
        }

        /**
         * 返回指定行的实际层级。
         * 对 Markdown 标题（# ## ### 等）根据 # 数量动态计算，其余使用静态 level。
         */
        int levelFor(String line) {
            if (!line.startsWith("#")) return level;
            int count = 0;
            for (int i = 0; i < line.length() && line.charAt(i) == '#'; i++) {
                count++;
            }
            return (count >= 1 && count <= 6) ? count : level;
        }
    }

    /**
     * 标题信息
     */
    private record Header(int level, String title, int startOffset) {}

    /**
     * 标题章节块：包含标题层级、纯文本内容、所有父级标题路径
     */
    private record HeaderBlock(int level, String title, String content, List<String> parentTitles) {}

    /**
     * 语义切分中间结果：纯文本 + 上下文前缀（在注入阶段才合并）
     */
    private record SemanticChunk(String text, String contextPrefix) {}

    // ─── 构造器 ───

    /**
     * @param embeddingModel    用于计算段落语义相似度的 EmbeddingModel
     * @param semanticThreshold 话题转变阈值（推荐 0.60-0.70）
     */
    public SemanticStructureSplitter(EmbeddingModel embeddingModel, double semanticThreshold) {
        this(embeddingModel, semanticThreshold, 1200, 1000, 200);
    }

    /**
     * 完整构造器，可调所有参数
     */
    public SemanticStructureSplitter(EmbeddingModel embeddingModel,
                                     double semanticThreshold,
                                     int semanticTriggerSize,
                                     int fallbackMaxChunkSize,
                                     int fallbackOverlapSize) {
        Objects.requireNonNull(embeddingModel, "embeddingModel 不能为 null");
        this.embeddingModel = embeddingModel;
        this.semanticThreshold = semanticThreshold;
        this.semanticTriggerSize = semanticTriggerSize;
        this.fallbackMaxChunkSize = fallbackMaxChunkSize;
        this.fallbackOverlapSize = fallbackOverlapSize;
    }

    // ═══════════════════════════════════════════════════
    //  核心入口：DocumentSplitter.split()
    // ═══════════════════════════════════════════════════

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        log.info("SemanticStructureSplitter 开始切分，文档长度: {} 字符", text.length());

        // 第一级：按标题拆分为章节块
        List<HeaderBlock> blocks = splitByHeaders(text);
        log.info("第一级标题切分: {} 个章节块", blocks.size());

        // 第二级 + 第三级：对每个章节块执行语义切分 + 兜底
        List<SemanticChunk> allChunks = new ArrayList<>();
        for (HeaderBlock block : blocks) {
            String contextPrefix = buildContextPrefix(block);
            List<SemanticChunk> blockChunks = splitBlock(block.content(), contextPrefix);
            allChunks.addAll(blockChunks);
        }
        log.info("第二/三级切分完成: {} 个 chunk", allChunks.size());

        // 组装最终 TextSegment：合并 contextPrefix + text，注入元数据
        List<TextSegment> result = new ArrayList<>(allChunks.size());
        for (SemanticChunk chunk : allChunks) {
            String fullText = chunk.contextPrefix().isEmpty()
                    ? chunk.text()
                    : chunk.contextPrefix() + "\n" + chunk.text();

            // 从 document 的原始 metadata 提取文件级信息（安全复制，忽略不支持的类型）
            Metadata meta = new Metadata();
            try {
                meta.putAll(document.metadata().toMap());
            } catch (IllegalArgumentException e) {
                log.warn("部分元数据类型不受支持，已跳过: {}", e.getMessage());
                // 逐条复制安全的元数据
                document.metadata().toMap().forEach((k, v) -> {
                    try { meta.put(k, v.toString()); } catch (Exception ignored) {}
                });
            }
            meta.put(META_SIGNATURE, chunk.contextPrefix());
            result.add(TextSegment.from(fullText.trim(), meta));
        }

        log.info("SemanticStructureSplitter 切分完成: {} 个 TextSegment", result.size());
        return result;
    }

    // ═══════════════════════════════════════════════════
    //  第一级：标题物理切分
    // ═══════════════════════════════════════════════════

    /**
     * 按标题层级将文档拆分为章节块。
     * 每个块记录其标题、内容、以及所有祖先标题。
     * <p>
     * 对 Markdown 文件会自动识别代码块围栏（``` 或 ~~~），避免将代码块内的
     * 注释（如 Python 的 # comment）误判为 Markdown 标题。
     */
    private List<HeaderBlock> splitByHeaders(String text) {
        String[] lines = text.split("\n", -1);

        // 识别所有标题行
        List<Header> headers = new ArrayList<>();
        boolean inCodeBlock = false;  // 代码块围栏状态
        String fenceMarker = "";     // 当前围栏标记（``` 或 ~~~），用于精确匹配闭合

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            // 检测代码块围栏（``` 或 ~~~），仅匹配同类型围栏的闭合
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String marker = trimmed.startsWith("```") ? "```" : "~~~";
                if (!inCodeBlock) {
                    // 进入代码块
                    inCodeBlock = true;
                    fenceMarker = marker;
                } else if (marker.equals(fenceMarker)) {
                    // 同类型围栏闭合
                    inCodeBlock = false;
                    fenceMarker = "";
                }
                // 不同类型围栏不切换状态（可能是嵌套的 Markdown 示例）
                continue;
            }

            // 代码块内或空行：跳过标题匹配
            if (inCodeBlock || trimmed.isEmpty()) continue;

            for (HeaderPattern hp : HEADER_PATTERNS) {
                if (hp.matches(trimmed)) {
                    int actualLevel = hp.levelFor(trimmed);
                    headers.add(new Header(actualLevel, trimmed, i));
                    break; // 优先匹配第一个（层级最高的模式）
                }
            }
        }

        // 无标题 → 整个文档作为一个块
        if (headers.isEmpty()) {
            return List.of(new HeaderBlock(0, "", text, List.of()));
        }

        List<HeaderBlock> blocks = new ArrayList<>();

        // 标题前的前导文本
        if (headers.getFirst().startOffset() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < headers.getFirst().startOffset(); i++) {
                sb.append(lines[i]).append("\n");
            }
            String preamble = sb.toString().trim();
            if (!preamble.isEmpty()) {
                blocks.add(new HeaderBlock(0, "", preamble, List.of()));
            }
        }

        // 构建标题层级栈（用于计算父子关系）
        Deque<Header> headerStack = new ArrayDeque<>();

        for (int i = 0; i < headers.size(); i++) {
            Header current = headers.get(i);

            // 维护栈：弹出同级或更低层级
            while (!headerStack.isEmpty() && headerStack.peek().level() >= current.level()) {
                headerStack.pop();
            }

            // 收集当前块内容（到下一个同级或更高级标题为止）
            int contentStart = current.startOffset() + 1; // 标题行的下一行
            int contentEnd = (i + 1 < headers.size()) ? headers.get(i + 1).startOffset() : lines.length;
            StringBuilder sb = new StringBuilder();
            for (int j = contentStart; j < contentEnd; j++) {
                sb.append(lines[j]).append("\n");
            }
            String content = sb.toString().trim();

            // 收集所有祖先标题
            List<String> parentTitles = new ArrayList<>();
            for (Header ancestor : headerStack) {
                parentTitles.add(ancestor.title());
            }

            blocks.add(new HeaderBlock(current.level(), current.title(), content, parentTitles));

            headerStack.push(current);
        }

        return blocks;
    }

    // ═══════════════════════════════════════════════════
    //  第二级：段落语义切分
    // ═══════════════════════════════════════════════════

    /**
     * 对单个章节块执行切分：短文本直接返回，长文本触发语义切分。
     *
     * @param content       章节纯文本内容（不含标题）
     * @param contextPrefix 上下文前缀（用于计算实际 chunk 尺寸）
     */
    private List<SemanticChunk> splitBlock(String content, String contextPrefix) {
        if (content.isBlank()) {
            return List.of();
        }

        int prefixLen = contextPrefix.length();

        // 短文本：无需语义切分
        if (content.length() + prefixLen <= semanticTriggerSize) {
            return List.of(new SemanticChunk(content, contextPrefix));
        }

        // 尝试语义切分（容错：失败则降级为纯递归）
        try {
            List<SemanticChunk> semanticChunks = splitBySemantics(content, contextPrefix);

            // 第三级：对仍然超长的 chunk 递归兜底
            List<SemanticChunk> finalChunks = new ArrayList<>();
            for (SemanticChunk chunk : semanticChunks) {
                String fullText = chunk.contextPrefix().isEmpty()
                        ? chunk.text()
                        : chunk.contextPrefix() + "\n" + chunk.text();

                if (fullText.length() > semanticTriggerSize + 300) {
                    // 超长：递归兜底
                    finalChunks.addAll(splitByFallback(chunk.text(), chunk.contextPrefix()));
                } else {
                    finalChunks.add(chunk);
                }
            }
            return finalChunks;

        } catch (Exception e) {
            log.warn("语义切分失败（将降级为递归切分）: {}", e.getMessage());
            return splitByFallback(content, contextPrefix);
        }
    }

    /**
     * 基于段落语义相似度切分。
     * <p>
     * 算法：
     * 1. 按双换行拆分为段落
     * 2. 批量向量化所有段落
     * 3. 计算相邻段落余弦相似度
     * 4. 在相似度低于阈值处切分
     * 5. 合并过小的 chunk，控制在目标尺寸范围内
     */
    private List<SemanticChunk> splitBySemantics(String content, String contextPrefix) {
        String[] paragraphs = content.split("\\n\\n+", -1);
        List<String> paraList = new ArrayList<>();
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                paraList.add(trimmed);
            }
        }

        if (paraList.size() < 2) {
            return List.of(new SemanticChunk(content, contextPrefix));
        }

        int prefixLen = contextPrefix.length();

        // 批量向量化所有段落
        List<TextSegment> segments = paraList.stream()
                .map(p -> TextSegment.from(p))
                .toList();

        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();

        if (embeddings.size() != paraList.size()) {
            log.warn("Embedding 数量 ({}) 与段落数量 ({}) 不匹配，跳过语义切分",
                    embeddings.size(), paraList.size());
            return List.of(new SemanticChunk(content, contextPrefix));
        }

        // 计算相邻段落的余弦相似度，识别话题转变点（跳过 null embedding 条目）
        List<Integer> splitPoints = new ArrayList<>();
        for (int i = 0; i < embeddings.size() - 1; i++) {
            if (embeddings.get(i) == null || embeddings.get(i + 1) == null) continue;
            double similarity = cosineSimilarity(
                    embeddings.get(i).vector(),
                    embeddings.get(i + 1).vector());
            if (similarity < semanticThreshold) {
                splitPoints.add(i + 1); // 在 i+1 之前切分
            }
        }

        if (splitPoints.isEmpty()) {
            // 无话题转变，整块返回
            return List.of(new SemanticChunk(content, contextPrefix));
        }

        // 按转变点拆分为语义 chunk
        List<SemanticChunk> rawChunks = getSemanticChunks(contextPrefix, splitPoints, paraList);

        // 合并过小的 chunk（< 500 字符），避免碎片化
        return mergeSmallChunks(rawChunks, prefixLen);
    }

    private static @NonNull List<SemanticChunk> getSemanticChunks(String contextPrefix, List<Integer> splitPoints, List<String> paraList) {
        List<SemanticChunk> rawChunks = new ArrayList<>();
        int prevSplit = 0;
        for (int sp : splitPoints) {
            StringBuilder sb = new StringBuilder();
            for (int j = prevSplit; j < sp; j++) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(paraList.get(j));
            }
            rawChunks.add(new SemanticChunk(sb.toString(), contextPrefix));
            prevSplit = sp;
        }
        // 最后一段
        StringBuilder last = new StringBuilder();
        for (int j = prevSplit; j < paraList.size(); j++) {
            if (last.length() > 0) last.append("\n\n");
            last.append(paraList.get(j));
        }
        rawChunks.add(new SemanticChunk(last.toString(), contextPrefix));
        return rawChunks;
    }

    /**
     * 合并过小的相邻 chunk，同时保证合并后不超过上限。
     */
    private List<SemanticChunk> mergeSmallChunks(List<SemanticChunk> chunks, int prefixLen) {
        if (chunks.size() <= 1) return chunks;

        List<SemanticChunk> merged = new ArrayList<>();
        SemanticChunk current = chunks.getFirst();

        for (int i = 1; i < chunks.size(); i++) {
            SemanticChunk next = chunks.get(i);
            int currentTotalLen = current.text().length() + prefixLen;
            int nextTotalLen = next.text().length() + prefixLen;

            // 合并条件：
            // 1. 两个都小（均 < 500）→ 合并
            // 2. 当前小（< 500）且下一个极短（< 100）→ 合并，避免碎片
            if ((currentTotalLen < 500 && nextTotalLen < 500)
                    || (currentTotalLen < 500 && nextTotalLen < 100)) {
                current = new SemanticChunk(
                        current.text() + "\n\n" + next.text(),
                        current.contextPrefix());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    // ═══════════════════════════════════════════════════
    //  第三级：递归字符兜底
    // ═══════════════════════════════════════════════════

    /**
     * 对超长 chunk 使用 LangChain4j 递归切分器强制拆分。
     * 直接切分原始内容（不拼入 contextPrefix），每个结果包装时自动带上 contextPrefix。
     * 递归切分器的 maxSize 已扣除前缀长度，确保最终 chunk 总长度不超限。
     */
    private List<SemanticChunk> splitByFallback(String content, String contextPrefix) {
        if (content.length() + contextPrefix.length() <= fallbackMaxChunkSize) {
            return List.of(new SemanticChunk(content, contextPrefix));
        }

        // 扣除前缀长度，确保最终 chunk = contextPrefix + 切分内容 ≤ fallbackMaxChunkSize
        int adjustedMax = Math.max(fallbackMaxChunkSize - contextPrefix.length(), 200);
        int adjustedOverlap = Math.min(fallbackOverlapSize, adjustedMax / 2);

        log.debug("递归兜底切分: 内容 {} 字符, 前缀 {} 字符, 调整后 maxSize={}",
                content.length(), contextPrefix.length(), adjustedMax);

        Document doc = Document.from(content);
        dev.langchain4j.data.document.DocumentSplitter recursiveSplitter =
                DocumentSplitters.recursive(adjustedMax, adjustedOverlap);
        List<TextSegment> segments = recursiveSplitter.split(doc);

        // 每个切分结果直接包装为 SemanticChunk，contextPrefix 在 split() 最终阶段拼入
        return segments.stream()
                .map(seg -> new SemanticChunk(seg.text().trim(), contextPrefix))
                .toList();
    }

    // ═══════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════

    /**
     * 构建上下文前缀：将所有父级标题拼接为 "标题1 > 标题2 > ..." 格式。
     */
    private String buildContextPrefix(HeaderBlock block) {
        List<String> parts = new ArrayList<>(block.parentTitles());
        if (!block.title().isEmpty()) {
            parts.add(block.title());
        }
        return String.join(" > ", parts);
    }

    /**
     * 余弦相似度计算
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}

