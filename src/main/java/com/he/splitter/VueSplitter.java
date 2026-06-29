package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 单文件组件（SFC）切分器。
 * <p>
 * 切分策略：
 * <ol>
 *   <li>第一级：按 {@code <template>} / {@code <script>} / {@code <style>} 标签边界切分</li>
 *   <li>第二级：对 {@code <script>} 块按 JS/TS 函数/类定义进一步切分</li>
 *   <li>第三级：对 {@code <style>} 块按 CSS 规则进一步切分</li>
 * </ol>
 * {@code <template>} 块保持完整，不切分 HTML 模板。
 */
public class VueSplitter implements DocumentSplitter {

    /** Vue SFC 顶层块标签 */
    private static final Pattern SFC_BLOCK = Pattern.compile(
            "^\\s*</?(template|script|style)\\b", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** JS/TS 函数和类定义（复用 RegexSplitter 的 JS_TS 模式） */
    private static final List<Pattern> JS_TS_PATTERNS = List.of(
            Pattern.compile("^(export\\s+)?(default\\s+)?(async\\s+)?function\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^(export\\s+)?(default\\s+)?class\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s+(async\\s+)?\\w+\\s*\\([^)]*\\)\\s*\\{", Pattern.MULTILINE),
            Pattern.compile("^(export\\s+)?(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?\\(", Pattern.MULTILINE)
    );

    /** CSS 规则选择器 */
    private static final Pattern CSS_RULE = Pattern.compile(
            "^\\s*[@.#]?[a-zA-Z#.\\[\\]:\\s>+~*,_\"'=-]+\\s*\\{", Pattern.MULTILINE);

    private final String projectName;
    private final String filePath;
    private final String language;

    public VueSplitter(String projectName, String filePath, String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.language = language;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String sourceCode = document.text();
        String[] lines = sourceCode.split("\n", -1);

        // 第一级：按 template/script/style 标签切分
        List<Block> blocks = splitBySfcBlocks(lines);
        if (blocks.isEmpty()) {
            return SplitterUtils.splitByLines(lines, 100, projectName, filePath, language);
        }

        // 第二/三级：对 script 和 style 块内部进一步切分
        List<TextSegment> segments = new ArrayList<>();
        for (Block block : blocks) {
            if ("script".equalsIgnoreCase(block.tag)) {
                segments.addAll(splitScriptBlock(block));
            } else if ("style".equalsIgnoreCase(block.tag)) {
                segments.addAll(splitStyleBlock(block));
            } else {
                // template 块保持完整
                String text = block.text().trim();
                if (!text.isEmpty()) {
                    segments.add(SplitterUtils.createSegment(text, "template",
                        block.startLine, block.endLine, projectName, filePath, language));
                }
            }
        }

        return segments.isEmpty() ? SplitterUtils.splitByLines(lines, 100, projectName, filePath, language) : segments;
    }

    /** 按 SFC 顶层块切分 */
    private List<Block> splitBySfcBlocks(String[] lines) {
        List<Block> blocks = new ArrayList<>();
        String currentTag = null;
        int blockStart = -1;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = SFC_BLOCK.matcher(lines[i]);
            if (m.find()) {
                String tag = m.group(1).toLowerCase();
                boolean isOpening = !lines[i].trim().startsWith("</");

                if (isOpening) {
                    // 保存前一个块
                    if (currentTag != null && blockStart >= 0) {
                        blocks.add(buildBlock(lines, blockStart, i, currentTag));
                    }
                    currentTag = tag;
                    blockStart = i;
                } else {
                    // 闭合标签：结束当前块
                    if (currentTag != null && currentTag.equals(tag) && blockStart >= 0) {
                        blocks.add(buildBlock(lines, blockStart, i + 1, currentTag));
                    }
                    currentTag = null;
                    blockStart = -1;
                }
            }
        }

        // 未闭合的块
        if (currentTag != null && blockStart >= 0) {
            blocks.add(buildBlock(lines, blockStart, lines.length, currentTag));
        }

        // 无 SFC 块 → 整个文件作为 template
        if (blocks.isEmpty()) {
            blocks.add(buildBlock(lines, 0, lines.length, "template"));
        }

        return blocks;
    }

    private Block buildBlock(String[] lines, int start, int end, String tag) {
        StringBuilder sb = new StringBuilder();
        for (int j = start; j < end; j++) {
            sb.append(lines[j]).append("\n");
        }
        return new Block(tag, sb.toString(), start + 1, end);
    }

    /** 对 script 块按 JS/TS 函数/类定义切分 */
    private List<TextSegment> splitScriptBlock(Block block) {
        String[] lines = block.text().split("\n", -1);
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);

        for (int i = 0; i < lines.length; i++) {
            for (Pattern pattern : JS_TS_PATTERNS) {
                if (pattern.matcher(lines[i]).find()) {
                    if (splitPoints.get(splitPoints.size() - 1) != i) {
                        splitPoints.add(i);
                    }
                    break;
                }
            }
        }

        if (splitPoints.size() <= 1) {
            String text = block.text().trim();
            if (!text.isEmpty()) {
                return List.of(SplitterUtils.createSegment(text, "script",
                        block.startLine, block.endLine, projectName, filePath, language));
            }
            return List.of();
        }

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;

            int expandedStart = SplitterUtils.expandToIncludeComments(lines, start, COMMENT_PREFIXES);

            StringBuilder sb = new StringBuilder();
            for (int j = expandedStart; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                String sig = SplitterUtils.extractSignature(lines[start]);
                segments.add(SplitterUtils.createSegment(text, "script:" + sig,
                        block.startLine + expandedStart, block.startLine + end - 1, projectName, filePath, language));
            }
        }
        return segments;
    }

    /** 对 style 块按 CSS 规则切分 */
    private List<TextSegment> splitStyleBlock(Block block) {
        String[] lines = block.text().split("\n", -1);
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);

        for (int i = 0; i < lines.length; i++) {
            if (CSS_RULE.matcher(lines[i]).find()) {
                if (splitPoints.get(splitPoints.size() - 1) != i) {
                    splitPoints.add(i);
                }
            }
        }

        if (splitPoints.size() <= 1) {
            String text = block.text().trim();
            if (!text.isEmpty()) {
                return List.of(SplitterUtils.createSegment(text, "style",
                        block.startLine, block.endLine, projectName, filePath, language));
            }
            return List.of();
        }

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;

            StringBuilder sb = new StringBuilder();
            for (int j = start; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                String sig = SplitterUtils.extractSignature(lines[start]);
                segments.add(SplitterUtils.createSegment(text, "style:" + sig,
                        block.startLine + start, block.startLine + end - 1, projectName, filePath, language));
            }
        }
        return segments;
    }

    /** 注释/装饰器行前缀（用于 expandToIncludeComments） */
    private static final java.util.Set<String> COMMENT_PREFIXES = java.util.Set.of(
            "//", "/*", "*", "/**", "@");

    /** SFC 块内部表示 */
    private record Block(String tag, String text, int startLine, int endLine) {}
}

