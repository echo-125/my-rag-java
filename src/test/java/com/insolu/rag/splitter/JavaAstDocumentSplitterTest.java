package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAstDocumentSplitterTest {

    @Test
    @DisplayName("Java：按类和方法切分")
    void splitJava_byClassAndMethods() {
        String code = """
                package com.example;

                public class UserService {
                    private String name;

                    public UserService(String name) {
                        this.name = name;
                    }

                    public String getName() {
                        return name;
                    }

                    public void setName(String name) {
                        this.name = name;
                    }
                }
                """;
        JavaAstDocumentSplitter splitter = new JavaAstDocumentSplitter("proj", "UserService.java");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        // 至少有一个类级别的 segment
        boolean hasClassSegment = segments.stream()
                .anyMatch(s -> s.metadata().getString("type").equals("class"));
        assertThat(hasClassSegment).isTrue();
    }

    @Test
    @DisplayName("Java：元数据包含正确的行号范围")
    void splitJava_metadataContainsLineNumbers() {
        String code = """
                public class Foo {
                    public void bar() {
                        System.out.println("hello");
                    }
                }
                """;
        JavaAstDocumentSplitter splitter = new JavaAstDocumentSplitter("proj", "Foo.java");
        List<TextSegment> segments = splitter.split(Document.from(code));

        for (TextSegment seg : segments) {
            String startLine = seg.metadata().getString("start_line");
            String endLine = seg.metadata().getString("end_line");
            assertThat(startLine).isNotNull();
            assertThat(endLine).isNotNull();
            assertThat(Integer.parseInt(startLine)).isGreaterThan(0);
            assertThat(Integer.parseInt(endLine)).isGreaterThanOrEqualTo(Integer.parseInt(startLine));
        }
    }

    @Test
    @DisplayName("Java：单行注释文件返回切分结果")
    void splitJava_minimalFile_returnsSegments() {
        JavaAstDocumentSplitter splitter = new JavaAstDocumentSplitter("proj", "Empty.java");
        List<TextSegment> segments = splitter.split(Document.from("// just a comment"));

        assertThat(segments).isNotEmpty();
    }

    @Test
    @DisplayName("Java：语法错误时降级为文本切分")
    void splitJava_syntaxError_fallbackToText() {
        String code = "this is not valid java code {{{";
        JavaAstDocumentSplitter splitter = new JavaAstDocumentSplitter("proj", "Bad.java");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).isNotEmpty();
    }
}
