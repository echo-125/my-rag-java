package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QmlSplitter 单元测试。
 */
class QmlSplitterTest {

    private final QmlSplitter splitter = new QmlSplitter("test", "MainWindow.qml", "qml");

    @Test
    @DisplayName("按 Window 和 Item 对象声明切分")
    void split_qmlWindow_splitsByObject() {
        String code = String.join("\n",
                "import QtQuick 2.15",
                "import QtQuick.Controls 2.15",
                "",
                "ApplicationWindow {",
                "    visible: true",
                "    width: 800",
                "    height: 600",
                "",
                "    Rectangle {",
                "        width: 100",
                "        height: 100",
                "        color: \"red\"",
                "    }",
                "",
                "    Button {",
                "        text: \"Click me\"",
                "        onClicked: console.log(\"clicked\")",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("ApplicationWindow");
        assertThat(allText).contains("Rectangle");
        assertThat(allText).contains("Button");
    }

    @Test
    @DisplayName("函数声明切分")
    void split_qmlWithFunctions_splitsByFunction() {
        String code = String.join("\n",
                "import QtQuick 2.15",
                "",
                "Item {",
                "    function calculateValue(x) {",
                "        return x * 2;",
                "    }",
                "",
                "    function formatText(text) {",
                "        return text.toUpperCase();",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("calculateValue");
        assertThat(allText).contains("formatText");
    }

    @Test
    @DisplayName("嵌套对象归入父块")
    void split_qmlNestedObjects_preservesHierarchy() {
        String code = String.join("\n",
                "import QtQuick 2.15",
                "",
                "Column {",
                "    spacing: 10",
                "",
                "    Text { text: \"Hello\" }",
                "    Text { text: \"World\" }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        // Column 作为顶层对象，内部 Text 归入 Column 块
        assertThat(segments.size()).isGreaterThanOrEqualTo(1);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("Column");
        assertThat(allText).contains("Hello");
        assertThat(allText).contains("World");
    }
}

