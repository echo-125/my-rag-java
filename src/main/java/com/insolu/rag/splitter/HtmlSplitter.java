package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 切分器：按语义标签块切分，保留标签完整性。
 * <p>
 * 切分策略：
 * <ol>
 *   <li>按语义区块标签（section, article, header, footer, nav, aside, main, div, form, table 等）切分</li>
 *   <li>按标题标签（h1-h6）切分</li>
 *   <li>跟踪标签嵌套深度，在深度归零时才切分</li>
 *   <li>无语义标签时按固定行数兜底</li>
 * </ol>
 */
public class HtmlSplitter implements DocumentSplitter {

    /** 语义区块标签（作为切分边界） */
    private static final Pattern SEMANTIC_BLOCK = Pattern.compile(
            "^\\s*<(section|article|header|footer|nav|aside|main|div|form|table|figure|details|dialog|fieldset|ul|ol|dl|blockquote|pre|address|summary)\\b",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 标题标签 */
    private static final Pattern HEADING = Pattern.compile(
            "^\\s*<h[1-6]\\b", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 任意 HTML 标签（用于跟踪嵌套深度） */
    private static final Pattern ANY_TAG = Pattern.compile(
            "</?(\\w+)[^>]*>", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 自闭合标签和不参与深度计算的标签 */
    private static final java.util.Set<String> SKIP_DEPTH_TAGS = java.util.Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr",
            "html", "head", "body", "title", "style", "script");

    private final String projectName;
    private final String filePath;
    private final String language;

    public HtmlSplitter(String projectName, String filePath, String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.language = language;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String sourceCode = document.text();
        String[] lines = sourceCode.split("\n", -1);

        // 找到语义切分点
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0);

        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 保存行处理前的深度
            int depthBefore = depth;

            // 更新标签嵌套深度（跳过结构性标签和自闭合标签）
            Matcher tagMatcher = ANY_TAG.matcher(line);
            while (tagMatcher.find()) {
                String tagName = tagMatcher.group(1).toLowerCase();
                if (SKIP_DEPTH_TAGS.contains(tagName)) continue;

                boolean isClosing = tagMatcher.group().startsWith("</");
                boolean isSelfClosing = tagMatcher.group().endsWith("/>");

                if (isClosing) {
                    depth = Math.max(0, depth - 1);
                } else if (!isSelfClosing) {
                    depth++;
                }
            }

            // 在接近顶层（depthBefore <= 1）时识别切分点
            if (depthBefore <= 1) {
                Matcher blockMatcher = SEMANTIC_BLOCK.matcher(line);
                Matcher headingMatcher = HEADING.matcher(line);
                if ((blockMatcher.find() || headingMatcher.find())
                        && !line.trim().startsWith("</")) {
                    if (splitPoints.isEmpty() || splitPoints.get(splitPoints.size() - 1) != i) {
                        splitPoints.add(i);
                    }
                }
            }
        }

        if (splitPoints.size() <= 1) {
            return SplitterUtils.splitByLines(lines, 100, projectName, filePath, language);
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
            if (text.isEmpty()) continue;

            String signature = extractSignature(lines[start]);
            segments.add(SplitterUtils.createSegment(text, signature, start + 1, end,
                    projectName, filePath, language));
        }

        return segments.isEmpty() ? SplitterUtils.splitByLines(lines, 100, projectName, filePath, language) : segments;
    }

    private String extractSignature(String line) {
        String trimmed = line.trim();
        // 提取标签名和关键属性
        Matcher m = ANY_TAG.matcher(trimmed);
        if (m.find()) {
            String tag = m.group(1);
            // 提取 id 或 class 属性
            Matcher idMatcher = Pattern.compile("id=[\"']([^\"']+)[\"']").matcher(trimmed);
            Matcher classMatcher = Pattern.compile("class=[\"']([^\"']+)[\"']").matcher(trimmed);
            if (idMatcher.find()) {
                return "<" + tag + " id=\"" + idMatcher.group(1) + "\">";
            }
            if (classMatcher.find()) {
                return "<" + tag + " class=\"" + classMatcher.group(1) + "\">";
            }
            return "<" + tag + ">";
        }
        return trimmed.substring(0, Math.min(trimmed.length(), 80));
    }


}
