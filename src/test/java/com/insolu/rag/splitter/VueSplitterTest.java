package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VueSplitter 单元测试。
 */
class VueSplitterTest {

    private final VueSplitter splitter = new VueSplitter("test", "MyComponent.vue", "vue");

    @Test
    @DisplayName("Vue SFC 按 template/script/style 切分")
    void split_vueSfc_splitsByTopLevelBlocks() {
        String code = String.join("\n",
                "<template>",
                "  <div class=\"hello\">",
                "    <h1>{{ message }}</h1>",
                "  </div>",
                "</template>",
                "",
                "<script>",
                "export default {",
                "  data() {",
                "    return { message: 'Hello' }",
                "  }",
                "}",
                "</script>",
                "",
                "<style scoped>",
                ".hello { color: red; }",
                "</style>");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        // 至少 3 个块：template + script + style
        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        assertThat(segments.stream().anyMatch(s -> s.text().contains("<template>"))).isTrue();
        assertThat(segments.stream().anyMatch(s -> s.text().contains("<script>"))).isTrue();
        assertThat(segments.stream().anyMatch(s -> s.text().contains("<style"))).isTrue();
    }

    @Test
    @DisplayName("script 块内按函数切分")
    void split_scriptBlock_splitsByFunction() {
        String code = String.join("\n",
                "<template><div>Test</div></template>",
                "<script>",
                "export default {",
                "  methods: {",
                "    handleClick() {",
                "      console.log('clicked');",
                "    },",
                "    handleSubmit() {",
                "      console.log('submitted');",
                "    }",
                "  }",
                "}",
                "</script>");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("handleClick");
        assertThat(allText).contains("handleSubmit");
    }

    @Test
    @DisplayName("style 块内按规则切分")
    void split_styleBlock_splitsByRule() {
        String code = String.join("\n",
                "<template><div /></template>",
                "<style scoped>",
                ".header { font-size: 20px; color: #333; }",
                ".content { padding: 16px; }",
                ".footer { margin-top: 24px; }",
                "</style>");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains(".header");
        assertThat(allText).contains(".content");
        assertThat(allText).contains(".footer");
    }
}
