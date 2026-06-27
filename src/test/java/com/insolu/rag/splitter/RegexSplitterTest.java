package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegexSplitterTest {

    @Test
    @DisplayName("Go 语言：按 func 切分")
    void splitGo_byFunc() {
        String code = """
                package main

                import "fmt"

                // Hello prints a greeting
                func Hello(name string) {
                    fmt.Println("Hello", name)
                }

                func main() {
                    Hello("World")
                }
                """;
        RegexSplitter splitter = new RegexSplitter("proj", "main.go", "go");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        // 第一个 segment 包含 Hello 函数（含注释），第二个包含 main
        assertThat(segments.get(0).text()).contains("Hello");
        assertThat(segments.get(segments.size() - 1).text()).contains("main");
    }

    @Test
    @DisplayName("Go 语言：按 type 切分")
    void splitGo_byType() {
        String code = """
                package model

                type User struct {
                    Name string
                    Age  int
                }

                func (u User) Greet() string {
                    return "Hi " + u.Name
                }
                """;
        RegexSplitter splitter = new RegexSplitter("proj", "model.go", "go");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Python：按 class/def 切分")
    void splitPython_byClassAndDef() {
        String code = """
                class Calculator:
                    def add(self, a, b):
                        return a + b

                    def subtract(self, a, b):
                        return a - b

                def helper():
                    pass
                """;
        RegexSplitter splitter = new RegexSplitter("proj", "calc.py", "python");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("JS/TS：按 function/class 切分")
    void splitJs_byFunctionAndClass() {
        String code = """
                export function hello() {
                    return 'hello';
                }

                export class MyService {
                    doWork() {
                        return 42;
                    }
                }
                """;
        RegexSplitter splitter = new RegexSplitter("proj", "app.ts", "typescript");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("无分割点时按行切分")
    void splitByLines_whenNoSplitPoints() {
        String code = "line1\nline2\nline3\n";
        RegexSplitter splitter = new RegexSplitter("proj", "data.txt", "text");
        List<TextSegment> segments = splitter.split(Document.from(code));

        assertThat(segments).isNotEmpty();
    }

    @Test
    @DisplayName("元数据包含正确的字段")
    void metadata_containsCorrectFields() {
        String code = "func Foo() {}\nfunc Bar() {}\n";
        RegexSplitter splitter = new RegexSplitter("myproject", "lib.go", "go");
        List<TextSegment> segments = splitter.split(Document.from(code));

        TextSegment first = segments.get(0);
        assertThat(first.metadata().getString("project_name")).isEqualTo("myproject");
        assertThat(first.metadata().getString("file_path")).isEqualTo("lib.go");
        assertThat(first.metadata().getString("language")).isEqualTo("go");
        assertThat(first.metadata().getString("type")).isEqualTo("code");
    }
}
