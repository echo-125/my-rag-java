package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HtmlSplitter 单元测试。
 */
class HtmlSplitterTest {

    private final HtmlSplitter splitter = new HtmlSplitter("test", "index_back.html", "html");

    @Test
    @DisplayName("按语义标签切分")
    void split_htmlWithSections_splitsBySemanticTags() {
        String code = String.join("\n",
                "<!DOCTYPE html>",
                "<html>",
                "<head><title>Test</title></head>",
                "<body>",
                "<header>",
                "  <h1>Site Title</h1>",
                "  <nav><a href=\"/\">Home</a></nav>",
                "</header>",
                "<main>",
                "  <section>",
                "    <h2>Section 1</h2>",
                "    <p>Content here.</p>",
                "  </section>",
                "  <section>",
                "    <h2>Section 2</h2>",
                "    <p>More content.</p>",
                "  </section>",
                "</main>",
                "<footer>",
                "  <p>Copyright 2024</p>",
                "</footer>",
                "</body>",
                "</html>");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        // 至少应有 header + main + footer 等切分
        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("Site Title");
        assertThat(allText).contains("Section 1");
        assertThat(allText).contains("Section 2");
        assertThat(allText).contains("Copyright");
    }

    @Test
    @DisplayName("按标题标签切分")
    void split_htmlWithHeadings_splitsByHeading() {
        String code = String.join("\n",
                "<!DOCTYPE html>",
                "<html><body>",
                "<h1>Introduction</h1>",
                "<p>Intro text.</p>",
                "<h2>Getting Started</h2>",
                "<p>Setup instructions.</p>",
                "<h2>Advanced Usage</h2>",
                "<p>Advanced content.</p>",
                "</body></html>");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("Introduction");
        assertThat(allText).contains("Getting Started");
        assertThat(allText).contains("Advanced Usage");
    }

    @Test
    @DisplayName("无语义标签时兜底切分")
    void split_plainHtml_fallsBackToLines() {
        String code = "<p>Line 1</p>\n<p>Line 2</p>\n<p>Line 3</p>\n".repeat(50);

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments).isNotEmpty();
    }
}
