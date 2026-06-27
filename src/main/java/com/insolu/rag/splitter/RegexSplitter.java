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
 * 基于正则的通用代码切分器，适用于 JS/TS/Python 等语言。
 * 按函数/类定义切分，保留元数据。
 */
public class RegexSplitter implements DocumentSplitter {

    /**
     * 支持的代码语言及其函数/类定义的正则模式。
     * 每个模式应匹配一行的开头（带缩进）。
     */
    private static final List<Pattern> JS_TS_PATTERNS = List.of(
            // function declarations, arrow functions, class methods
            Pattern.compile("^(export\\s+)?(default\\s+)?(async\\s+)?function\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^(export\\s+)?(default\\s+)?class\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^\\s+(async\\s+)?\\w+\\s*\\([^)]*\\)\\s*\\{", Pattern.MULTILINE),
            // const/let/var arrow functions at top level
            Pattern.compile("^(export\\s+)?(const|let|var)\\s+\\w+\\s*=\\s*(async\\s+)?\\(", Pattern.MULTILINE)
    );

    private static final List<Pattern> PYTHON_PATTERNS = List.of(
            Pattern.compile("^class\\s+\\w+", Pattern.MULTILINE),
            Pattern.compile("^(\\s*)def\\s+\\w+", Pattern.MULTILINE)
    );

    /** Go 语言模式：func/type/package 级定义 */
    private static final List<Pattern> GO_PATTERNS = List.of(
            Pattern.compile("^func\\s+\\(", Pattern.MULTILINE),       // 方法: func (r Receiver) Method()
            Pattern.compile("^func\\s+[A-Z]\\w*", Pattern.MULTILINE), // 导出函数: func DoSomething()
            Pattern.compile("^func\\s+[a-z]\\w*", Pattern.MULTILINE), // 内部函数: func doSomething()
            Pattern.compile("^type\\s+\\w+", Pattern.MULTILINE),      // 类型定义: type Foo struct
            Pattern.compile("^var\\s+\\(", Pattern.MULTILINE),        // 变量块: var (
            Pattern.compile("^const\\s+\\(", Pattern.MULTILINE)       // 常量块: const (
    );

    private static final Pattern SHEBANG = Pattern.compile("^#!.*$");

    private final String projectName;
    private final String filePath;
    private final String language;

    public RegexSplitter(String projectName, String filePath, String language) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.language = language;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String sourceCode = document.text();
        String[] lines = sourceCode.split("\n", -1);

        List<Pattern> patterns = switch (language.toLowerCase()) {
            case "python" -> PYTHON_PATTERNS;
            case "go" -> GO_PATTERNS;
            default -> JS_TS_PATTERNS; // js, ts, jsx, tsx
        };

        // 找到所有分割点
        List<Integer> splitPoints = new ArrayList<>();
        splitPoints.add(0); // 文件开头

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 跳过 shebang 行
            if (i == 0 && SHEBANG.matcher(line.trim()).matches()) continue;

            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    // 避免连续的分割点
                    if (splitPoints.isEmpty() || splitPoints.get(splitPoints.size() - 1) != i) {
                        splitPoints.add(i);
                    }
                    break;
                }
            }
        }

        // 如果没有找到分割点或只有一个块，按固定行数切分
        if (splitPoints.size() <= 1) {
            return splitByLines(sourceCode, lines, 100);
        }

        // 按分割点切分
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;

            // 向前扩展，包含函数/类上方的注释和装饰器
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

    private int expandToIncludeComments(String[] lines, int startLine) {
        int expanded = startLine;
        for (int i = startLine - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("#")
                    || trimmed.startsWith("/*") || trimmed.startsWith("*")
                    || trimmed.startsWith("'''") || trimmed.startsWith("\"\"\"")
                    || trimmed.startsWith("@") || trimmed.startsWith("//go:")
                    || trimmed.isEmpty()) {
                expanded = i;
            } else {
                break;
            }
        }
        return expanded;
    }

    private String extractSignature(String line) {
        String trimmed = line.trim();
        // 截取到 "{" 或 ":" 或行尾
        int end = trimmed.indexOf('{');
        if (end < 0) end = trimmed.indexOf(':');
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
