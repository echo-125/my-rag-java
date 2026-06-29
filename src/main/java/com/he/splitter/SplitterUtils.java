package com.he.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 切分器公共工具方法。
 * <p>
 * 提取了多个切分器中重复的 {@code expandToIncludeComments}、{@code extractSignature}、
 * {@code splitByLines} 方法，避免代码重复。
 */
public final class SplitterUtils {

    private SplitterUtils() {}

    /**
     * 向前扩展包含注释行和装饰器行。
     *
     * @param lines         所有行
     * @param startLine     起始行索引
     * @param commentPrefixes 视为注释/装饰器的行前缀集合（如 "//", "#", "@", "///", "/*", "*"）
     * @return 扩展后的起始行索引
     */
    public static int expandToIncludeComments(String[] lines, int startLine, Set<String> commentPrefixes) {
        int expanded = startLine;
        for (int i = startLine - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                expanded = i;
                continue;
            }
            boolean matched = false;
            for (String prefix : commentPrefixes) {
                if (trimmed.startsWith(prefix)) {
                    expanded = i;
                    matched = true;
                    break;
                }
            }
            if (!matched) break;
        }
        return expanded;
    }

    /**
     * 从声明行提取签名（截取到第一个 { 或 : 或 ; 或行尾）。
     * C# 的 record 声明以 ; 结尾，需要额外处理。
     *
     * @param line 声明行
     * @return 签名文本
     */
    public static String extractSignature(String line) {
        String trimmed = line.trim();
        int end = trimmed.indexOf('{');
        if (end < 0) end = trimmed.indexOf(':');
        if (end < 0) end = trimmed.indexOf(';');
        if (end < 0) end = trimmed.length();
        return trimmed.substring(0, end).trim();
    }

    /**
     * 兜底切分：按固定行数切分。
     *
     * @param lines      所有行
     * @param chunkSize  每块行数
     * @param projectName 项目名
     * @param filePath   文件路径
     * @param language   语言
     * @return 切分后的 TextSegment 列表
     */
    public static List<TextSegment> splitByLines(String[] lines, int chunkSize,
                                                  String projectName, String filePath, String language) {
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < lines.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, lines.length);
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                segments.add(createSegment(text, "chunk_" + (i / chunkSize), i + 1, end,
                        projectName, filePath, language));
            }
        }
        return segments;
    }

    /**
     * 创建带标准元数据的 TextSegment（默认 type="code"）。
     */
    public static TextSegment createSegment(String text, String signature, int startLine, int endLine,
                                             String projectName, String filePath, String language) {
        return createSegment(text, signature, startLine, endLine, projectName, filePath, language, "code");
    }

    /**
     * 创建带标准元数据的 TextSegment（指定 type）。
     */
    public static TextSegment createSegment(String text, String signature, int startLine, int endLine,
                                             String projectName, String filePath, String language, String type) {
        Metadata metadata = new Metadata();
        metadata.put("project_name", projectName);
        metadata.put("file_path", filePath);
        metadata.put("language", language);
        metadata.put("type", type);
        metadata.put("signature", signature);
        metadata.put("start_line", String.valueOf(startLine));
        metadata.put("end_line", String.valueOf(endLine));
        return TextSegment.from(text, metadata);
    }
}

