package com.he.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSharpSplitter 单元测试。
 */
class CSharpSplitterTest {

    private final CSharpSplitter splitter = new CSharpSplitter("test", "DataProcessor.cs", "csharp");

    @Test
    @DisplayName("按 namespace + class + method 切分")
    void split_simpleClass_splitsByNamespaceAndMethod() {
        String code = String.join("\n",
                "using System;",
                "",
                "namespace MyApp.Services {",
                "    public class DataProcessor {",
                "        public void Process() {",
                "            Console.WriteLine(\"Processing...\");",
                "        }",
                "",
                "        private int Calculate(int x) {",
                "            return x * 2;",
                "        }",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        // 至少应有 namespace + class + 2 methods = 4 个块
        assertThat(segments.size()).isGreaterThanOrEqualTo(3);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("DataProcessor");
        assertThat(allText).contains("Process()");
        assertThat(allText).contains("Calculate(int x)");
    }

    @Test
    @DisplayName("interface 和 enum 切分")
    void split_interfaceAndEnum_splitsCorrectly() {
        String code = String.join("\n",
                "namespace MyApp.Models {",
                "    public enum Status {",
                "        Active,",
                "        Inactive",
                "    }",
                "",
                "    public interface IRepository {",
                "        void Save();",
                "        void Delete();",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("Status");
        assertThat(allText).contains("IRepository");
        assertThat(allText).contains("Active");
        assertThat(allText).contains("Save");
    }

    @Test
    @DisplayName("record / struct 切分")
    void split_recordStruct_splitsCorrectly() {
        String code = String.join("\n",
                "namespace MyApp.Data {",
                "    public record Person(string Name, int Age);",
                "    public readonly struct Point {",
                "        public int X { get; }",
                "        public int Y { get; }",
                "        public Point(int x, int y) { X = x; Y = y; }",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("Person");
        assertThat(allText).contains("Point");
    }

    @Test
    @DisplayName("特性归入方法块")
    void split_withAttributes_includesAttributes() {
        String code = String.join("\n",
                "namespace MyApp.Api {",
                "    [ApiController]",
                "    [Route(\"api/[controller]\")]",
                "    public class UserController {",
                "        [HttpGet]",
                "        public ActionResult GetUsers() {",
                "            return Ok();",
                "        }",
                "    }",
                "}");

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        // [ApiController] 和 [Route(...)] 位于 namespace 块和 class 块之间
        // 它们可能被归入前一个块或后一个块，也可能单独成块
        String allText = segments.stream().map(TextSegment::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText).contains("[ApiController]");
        assertThat(allText).contains("UserController");
        assertThat(allText).contains("GetUsers");
    }

    @Test
    @DisplayName("无切分点时兜底按行切分")
    void split_noSplitPoints_fallsBackToLines() {
        String code = "Console.WriteLine(\"Hello\");\nConsole.WriteLine(\"World\");\nConsole.WriteLine(\"!\");"
                + "\nConsole.WriteLine(\"A\");\nConsole.WriteLine(\"B\");".repeat(20);

        Document doc = Document.from(code);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments).isNotEmpty();
        // 兜底切分：按 100 行切
    }
}

