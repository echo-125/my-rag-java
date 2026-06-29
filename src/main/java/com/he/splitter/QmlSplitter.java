package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QML 切分器：按 Qt 对象声明和 JavaScript 函数边界切分。
 * <p>
 * QML 是声明式 Qt 对象树，切分边界包括：
 * <ul>
 *   <li>内置 Qt Quick 组件声明（Window, Item, Rectangle 等）</li>
 *   <li>自定义组件声明（大写开头的对象）</li>
 *   <li>JavaScript 函数声明</li>
 *   <li>信号处理器（onXxx）</li>
 * </ul>
 */
public class QmlSplitter implements DocumentSplitter {

    private static final List<Pattern> QML_PATTERNS = List.of(
            // 内置 Qt Quick 组件声明
            Pattern.compile("^\\s*(Window|ApplicationWindow|Item|Rectangle|Column|Row|Grid|Flow|"
                    + "ListView|GridView|Repeater|Loader|Component|Page|Pane|Dialog|Drawer|"
                    + "Popup|Menu|ToolBar|StatusBar|TabBar|SwipeView|StackView|Frame|GroupBox|"
                    + "ScrollView|Flickable|TextArea|TextField|Label|Button|Switch|Slider|"
                    + "ComboBox|SpinBox|CheckBox|RadioButton|Image|Canvas|Shape|"
                    + "AnimatedImage|Video|WebEngineView|Map|Timer|Connections|Binding|"
                    + "PropertyAnimation|NumberAnimation|ColorAnimation|RotationAnimation|"
                    + "SequentialAnimation|ParallelAnimation|State|Transition|DelegateModel|"
                    + "ListModel|XmlListModel|FontLoader|Layout|RowLayout|ColumnLayout|GridLayout|"
                    + "StackLayout|MouseArea|DropArea|DragHandler|TapHandler|PinchHandler|"
                    + "HoverHandler|WheelHandler|PointHandler|Keys|FocusScope|ShaderEffect|"
                    + "OpacityMask|LinearGradient|RadialGradient|ConicalGradient)\\s*\\{", Pattern.MULTILINE),
            // 自定义组件声明（大写开头后跟 {）
            Pattern.compile("^\\s*[A-Z]\\w*\\s*\\{", Pattern.MULTILINE),
            // JavaScript 函数声明
            Pattern.compile("^\\s*function\\s+\\w+\\s*\\(", Pattern.MULTILINE),
            // 信号处理器（onXxx: {）
            Pattern.compile("^\\s*on[A-Z]\\w*:\\s*\\{", Pattern.MULTILINE)
    );

    private static final Pattern IMPORT = Pattern.compile("^import\\s+", Pattern.MULTILINE);

    private final String projectName;
    private final String filePath;
    private final String language;

    public QmlSplitter(String projectName, String filePath, String language) {
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
        splitPoints.add(0);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // import 行不作为分割点（归入下一个组件块）
            if (IMPORT.matcher(line.trim()).find()) continue;

            for (Pattern pattern : QML_PATTERNS) {
                Matcher m = pattern.matcher(line);
                if (m.find()) {
                    if (splitPoints.isEmpty() || splitPoints.get(splitPoints.size() - 1) != i) {
                        splitPoints.add(i);
                    }
                    break;
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

            // 向前扩展包含 import 行和注释
            int expandedStart = SplitterUtils.expandToIncludeComments(lines, start, COMMENT_PREFIXES);

            StringBuilder sb = new StringBuilder();
            for (int j = expandedStart; j < end; j++) {
                sb.append(lines[j]).append("\n");
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) continue;

            String signature = SplitterUtils.extractSignature(lines[start]);
            segments.add(SplitterUtils.createSegment(text, signature, expandedStart + 1, end,
                    projectName, filePath, language));
        }

        return segments.isEmpty() ? SplitterUtils.splitByLines(lines, 100, projectName, filePath, language) : segments;
    }

    /** 注释/import 行前缀（用于 expandToIncludeComments） */
    private static final java.util.Set<String> COMMENT_PREFIXES = java.util.Set.of(
            "//", "/*", "*", "#", "import ");
}

