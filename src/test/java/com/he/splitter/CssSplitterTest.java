package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CssSplitter 单元测试。
 */
class CssSplitterTest {

    private final CssSplitter cssSplitter = new CssSplitter("test", "styles.css", "css");
    private final CssSplitter scssSplitter = new CssSplitter("test", "styles.scss", "scss");

    @Test
    @DisplayName("按 CSS 选择器切分")
    void split_cssRules_splitsBySelector() {
        String code = String.join("\n",
                "/* Reset */",
                "* { margin: 0; padding: 0; }",
                "",
                ".header {",
                "  font-size: 24px;",
                "  color: #333;",
                "}",
                "",
                ".content {",
                "  padding: 16px;",
                "  line-height: 1.5;",
                "}",
                "",
                ".footer {",
                "  margin-top: 24px;",
                "  text-align: center;",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = cssSplitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains(".header");
        assertThat(allText).contains(".content");
        assertThat(allText).contains(".footer");
    }

    @Test
    @DisplayName("SCSS 嵌套规则保留")
    void split_scssNesting_preservesNestedRules() {
        String code = String.join("\n",
                ".card {",
                "  padding: 16px;",
                "  border: 1px solid #eee;",
                "",
                "  &__title {",
                "    font-size: 18px;",
                "    font-weight: bold;",
                "  }",
                "",
                "  &__body {",
                "    margin-top: 8px;",
                "  }",
                "",
                "  &--active {",
                "    border-color: blue;",
                "  }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = scssSplitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("card");
        assertThat(allText).contains("__title");
        assertThat(allText).contains("__body");
    }

    @Test
    @DisplayName("媒体查询切分")
    void split_mediaQuery_splitsCorrectly() {
        String code = String.join("\n",
                ".container {",
                "  width: 100%;",
                "}",
                "",
                "@media (min-width: 768px) {",
                "  .container {",
                "    max-width: 720px;",
                "  }",
                "}",
                "",
                "@media (min-width: 1024px) {",
                "  .container {",
                "    max-width: 960px;",
                "  }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = cssSplitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("@media");
        assertThat(allText).contains("768px");
        assertThat(allText).contains("1024px");
    }

    @Test
    @DisplayName("SCSS mixin 切分")
    void split_scssMixin_splitsCorrectly() {
        String code = String.join("\n",
                "@mixin flex-center {",
                "  display: flex;",
                "  justify-content: center;",
                "  align-items: center;",
                "}",
                "",
                "@mixin card-shadow {",
                "  box-shadow: 0 2px 8px rgba(0,0,0,0.1);",
                "}",
                "",
                ".centered-card {",
                "  @include flex-center;",
                "  @include card-shadow;",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = scssSplitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("flex-center");
        assertThat(allText).contains("card-shadow");
        assertThat(allText).contains("centered-card");
    }
}

