package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS / SCSS 切分器：按 CSS 规则块切分。
 * <p>
 * 切分边界包括：
 * <ul>
 *   <li>CSS 规则选择器（含嵌套选择器）</li>
 *   <li>@规则（media, keyframes, supports, font-face 等）</li>
 *   <li>SCSS 嵌套规则（& 选择器）</li>
 *   <li>SCSS mixin / function / include / extend</li>
 * </ul>
 * 跟踪大括号嵌套深度，在深度归零时切分。
 */
public class CssSplitter implements DocumentSplitter {

    /** CSS 规则选择器 */
    private static final Pattern CSS_SELECTOR = Pattern.compile(
            "^\\s*[@.#]?[a-zA-Z#.\\[\\]:\\s>+~*,_\"'=-]+\\s*\\{", Pattern.MULTILINE);

    /** @规则 */
    private static final Pattern AT_RULE = Pattern.compile(
            "^\\s*@(media|keyframes|supports|container|layer|scope|font-face|import|charset|namespace|page)\\b",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** SCSS 嵌套规则（& 选择器） */
    private static final Pattern SCSS_NESTED = Pattern.compile(
            "^\\s*&[.:\\[\\w-]+", Pattern.MULTILINE);

    /** SCSS mixin / function / include / extend / 控制流 */
    private static final Pattern SCSS_DIRECTIVE = Pattern.compile(
            "^\\s*@(mixin|function|include|extend|if|else|for|each|while|return|error|warn|debug)\\b",
            Pattern.MULTILINE);

    /** CSS 注释 */
    private static final Pattern CSS_COMMENT = Pattern.compile(
            "^\\s*/\\*", Pattern.MULTILINE);

    private final String projectName;
    private final String filePath;
    private final String language;

    public CssSplitter(String projectName, String filePath, String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.language = language;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String sourceCode = document.text();
        String[] lines = sourceCode.split("\n", -1);

        // 找到切分点（跟踪大括号深度）
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);

        int braceDepth = 0;
        boolean inComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 跟踪多行注释状态
            if (trimmed.startsWith("/*")) inComment = true;
            if (inComment && trimmed.contains("*/")) {
                inComment = false;
                continue;
            }
            if (inComment) continue;

            // 更新大括号深度
            int depthBefore = braceDepth;
            for (char c : trimmed.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') braceDepth = Math.max(0, braceDepth - 1);
            }

            // 在顶层或 SCSS 嵌套层级识别切分点
            // SCSS 嵌套（&__title, &--modifier）在 depth 1→2 时也作为切分点
            if (depthBefore <= 1) {
                boolean isSplitPoint = CSS_SELECTOR.matcher(line).find()
                        || AT_RULE.matcher(line).find()
                        || SCSS_NESTED.matcher(line).find()
                        || SCSS_DIRECTIVE.matcher(line).find();

                if (isSplitPoint && (splitPoints.isEmpty() || splitPoints.get(splitPoints.size() - 1) != i)) {
                    splitPoints.add(i);
                }
            }
        }

        if (splitPoints.size() <= 1) {
            return splitByLines(sourceCode, lines, 100);
        }

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;

            // 向前扩展包含注释
            int expandedStart = expandToIncludeComments(lines, start);

            StringBuilder sb = new StringBuilder();
            for (int j = expandedStart; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) continue;

            String signature = extractSignature(lines[start]);
            segments.add(createSegment(text, signature, expandedStart + 1, end));
        }

        return segments.isEmpty() ? splitByLines(sourceCode, lines, 100) : segments;
    }

    private int expandToIncludeComments(String[] lines, int startLine) {
        int expanded = startLine;
        for (int i = startLine - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("/*") || trimmed.startsWith("*")
                    || trimmed.startsWith("//") || trimmed.isEmpty()) {
                expanded = i;
            } else {
                break;
            }
        }
        return expanded;
    }

    private String extractSignature(String line) {
        String trimmed = line.trim();
        int end = trimmed.indexOf('{');
        if (end < 0) end = trimmed.length();
        return trimmed.substring(0, end).trim();
    }

    private List<TextSegment> splitByLines(String sourceCode, String[] lines, int chunkSize) {
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, lines.length);
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                segments.add(createSegment(text, "chunk_" + (i / chunkSize), i + 1, end));
            }
        }
        return segments;
    }

    private TextSegment createSegment(String text, String signature, int startLine, int endLine) {
        Metadata metadata = new Metadata();
        metadata.put("project_name", projectName);
        metadata.put("file_path", filePath);
        metadata.put("language", language);
        metadata.put("type", "code");
        metadata.put("signature", signature);
        metadata.put("start_line", String.valueOf(startLine));
        metadata.put("end_line", String.valueOf(endLine));
        return TextSegment.from(text, metadata);
    }
}
