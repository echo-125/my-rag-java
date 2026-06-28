package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C# 代码切分器：按 namespace / class / struct / interface / record / enum / method / property 边界切分。
 * 遵循 {@link RegexSplitter} 的成熟模式，注入标准元数据。
 */
public class CSharpSplitter implements DocumentSplitter {

    /** C# 切分边界：namespace → 类型声明 → 方法/属性声明 */
    private static final List<Pattern> CSHARP_PATTERNS = List.of(
            // namespace 声明
            Pattern.compile("^namespace\\s+[\\w.]+", Pattern.MULTILINE),
            // class / struct / interface / record 声明（含修饰符）
            Pattern.compile("^\\s*(public|private|protected|internal|static|sealed|abstract|partial|unsafe|readonly|volatile|virtual|override|async|extern|new|fixed|\\s)*\\s*(class|struct|interface|record)\\s+\\w+", Pattern.MULTILINE),
            // 方法声明（含修饰符和返回类型）
            Pattern.compile("^\\s*(public|private|protected|internal|static|sealed|abstract|partial|unsafe|readonly|volatile|virtual|override|async|extern|new|fixed|\\s)*\\s*[\\w<>\\[\\],?]+\\s+\\w+\\s*\\([^)]*\\)", Pattern.MULTILINE),
            // 属性声明（get/set 访问器）
            Pattern.compile("^\\s*(public|private|protected|internal|static|sealed|abstract|partial|unsafe|readonly|volatile|virtual|override|async|extern|new|fixed|\\s)*\\s*[\\w<>\\[\\],?]+\\s+\\w+\\s*\\{\\s*(get|set)", Pattern.MULTILINE),
            // 枚举声明
            Pattern.compile("^\\s*(public|private|protected|internal)*\\s*enum\\s+\\w+", Pattern.MULTILINE)
    );

    private final String projectName;
    private final String filePath;
    private final String language;

    public CSharpSplitter(String projectName, String filePath, String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.language = language;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String sourceCode = document.text();
        String[] lines = sourceCode.split("\n", -1);

        // 找到所有分割点
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0); // 文件开头

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (Pattern pattern : CSHARP_PATTERNS) {
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    if (splitPoints.isEmpty() || splitPoints.get(splitPoints.size() - 1) != i) {
                        splitPoints.add(i);
                    }
                    break;
                }
            }
        }

        // 无分割点或仅文件开头 → 按固定行数兜底
        if (splitPoints.size() <= 1) {
            return splitByLines(sourceCode, lines, 100);
        }

        // 按分割点切分
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;

            // 向前扩展，包含上方注释和特性（[Attribute]）
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

    /** 兜底：按固定行数切分 */
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

    /** 向前扩展包含注释行 */
    private int expandToIncludeComments(String[] lines, int startLine) {
        int expanded = startLine;
        for (int i = startLine - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("///")
                    || trimmed.startsWith("/*") || trimmed.startsWith("*")
                    || trimmed.startsWith("#")
                    || trimmed.isEmpty()) {
                expanded = i;
            } else {
                break;
            }
        }
        return expanded;
    }

    /** 从声明行提取签名 */
    private String extractSignature(String line) {
        String trimmed = line.trim();
        int end = trimmed.indexOf('{');
        if (end < 0) end = trimmed.indexOf(':');
        if (end < 0) end = trimmed.indexOf(';');
        if (end < 0) end = trimmed.length();
        return trimmed.substring(0, end).trim();
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
