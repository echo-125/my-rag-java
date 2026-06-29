package com.he.service;

import com.he.entity.DocumentChunkStatsRepository;
import com.he.entity.ProjectConfigRepository;
import com.he.splitter.FileSplitterRouter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * IngestionService.cleanSegments 噪声过滤差异化测试。
 * 验证 code/markdown 类型的短文本保留逻辑。
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceCleanTest {

    @Mock
    private FileSplitterRouter splitterRouter;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private RagConfigService ragConfigService;
    @Mock
    private DocumentChunkStatsRepository chunkStatsRepo;
    @Mock
    private ProjectConfigRepository projectConfigRepo;
    @Mock
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(splitterRouter, embeddingModel, embeddingStore, ragConfigService, chunkStatsRepo, projectConfigRepo, jdbcTemplate);
        // 默认配置：噪声过滤开启，最小长度 30
        lenient().when(ragConfigService.getBoolean("enable_noise_filter", true)).thenReturn(true);
        lenient().when(ragConfigService.getInt("noise_min_length", 30)).thenReturn(30);
        lenient().when(ragConfigService.getBoolean("filter_pure_numbers", true)).thenReturn(true);
    }

    private TextSegment createSegment(String text, String type) {
        Metadata meta = new Metadata();
        meta.put("type", type);
        meta.put("project_name", "test");
        meta.put("file_path", "test.txt");
        meta.put("language", "text");
        meta.put("signature", "");
        return TextSegment.from(text, meta);
    }

    // ─────────────────────────────────────────────
    //  code 类型：短文本保留
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("code 类型短文本过滤")
    class CodeTypeShortText {

        @Test
        @DisplayName("短代码方法不被过滤（< 30 字符）")
        void cleanSegments_codeType_preservesShortMethods() {
            List<TextSegment> segments = List.of(
                    createSegment("if (x > 0) return;", "code"),   // 20 字符
                    createSegment("return age;", "code"),           // 11 字符
                    createSegment("正常的长代码方法定义，超过三十个字符不会被过滤", "code")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.java");

            // 短代码方法应保留
            assertThat(cleaned).hasSize(3);
        }

        @Test
        @DisplayName("纯标点代码仍被过滤")
        void cleanSegments_codeType_stillFiltersPurePunctuation() {
            List<TextSegment> segments = List.of(
                    createSegment("{ } ;", "code"),    // 纯标点（< 30 字符）
                    createSegment("正常代码语句超过最小长度", "code")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.java");

            // 纯标点被过滤，正常代码保留
            assertThat(cleaned).hasSize(1);
            assertThat(cleaned.getFirst().text()).contains("正常代码语句");
        }
    }

    // ─────────────────────────────────────────────
    //  markdown 类型：短文本保留
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("markdown 类型短文本过滤")
    class MarkdownTypeShortText {

        @Test
        @DisplayName("短 Markdown 标题不被过滤")
        void cleanSegments_markdownType_preservesShortHeaders() {
            List<TextSegment> segments = List.of(
                    createSegment("# 概述", "markdown"),       // 3 字符（trim 后）
                    createSegment("## API", "markdown"),      // 5 字符
                    createSegment("正常的 Markdown 段落内容，超过三十个字符的阈值", "markdown")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.md");

            assertThat(cleaned).hasSize(3);
        }

        @Test
        @DisplayName("短代码示例不被过滤")
        void cleanSegments_markdownType_preservesShortCodeExamples() {
            List<TextSegment> segments = List.of(
                    createSegment("```python", "markdown"),   // 9 字符
                    createSegment("print('hi')", "markdown"), // 12 字符
                    createSegment("Markdown 正文内容超过最小长度阈值", "markdown")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.md");

            assertThat(cleaned).hasSize(3);
        }

        @Test
        @DisplayName("纯标点 Markdown 仍被过滤")
        void cleanSegments_markdownType_stillFiltersPurePunctuation() {
            List<TextSegment> segments = List.of(
                    createSegment("---", "markdown"),  // 纯标点（分隔线）
                    createSegment("***", "markdown"),  // 纯标点
                    createSegment("Markdown 正文超过最小长度阈值", "markdown")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.md");

            // 纯标点被过滤
            assertThat(cleaned).hasSize(1);
            assertThat(cleaned.getFirst().text()).contains("Markdown 正文");
        }
    }

    // ─────────────────────────────────────────────
    //  text/document 类型：行为不变
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("text/document 类型保持原有行为")
    class TextAndDocumentType {

        @Test
        @DisplayName("text 类型短文本仍被过滤")
        void cleanSegments_textType_filtersShortText() {
            List<TextSegment> segments = List.of(
                    createSegment("短文本", "text"),  // 3 字符
                    createSegment("正常的长文本内容，超过三十个字符的阈值不会被过滤，这是额外的填充内容", "text")  // 35 字符
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.txt");

            // 短文本被过滤
            assertThat(cleaned).hasSize(1);
            assertThat(cleaned.getFirst().text()).contains("正常的长文本");
        }

        @Test
        @DisplayName("document 类型短文本仍被过滤")
        void cleanSegments_documentType_filtersShortText() {
            List<TextSegment> segments = List.of(
                    createSegment("短内容", "document"),
                    createSegment("正常文档段落内容超过最小长度阈值的文本，这是额外的填充字符确保超过阈值", "document")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.pdf");

            assertThat(cleaned).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────
    //  通用噪声过滤（所有类型均生效）
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("通用噪声过滤规则")
    class CommonNoiseFiltering {

        @Test
        @DisplayName("纯大写字母水印对所有类型均过滤（长文本）")
        void cleanSegments_allTypes_filtersUpperCaseWatermark() {
            List<TextSegment> segments = List.of(
                    createSegment("JH_GAMMA_LONG_UPPERCASE_WATERMARK_TEXT", "code"),     // 38 字符 > 30
                    createSegment("ABC_DEF_LONG_UPPERCASE_WATERMARK_TEXT", "markdown"),  // 39 字符 > 30
                    createSegment("正常内容超过最小长度阈值", "code")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.java");

            // 纯大写+下划线+数字的长文本被过滤（水印/字体名）
            assertThat(cleaned).hasSize(1);
        }

        @Test
        @DisplayName("纯数字对所有类型均过滤（长数字串）")
        void cleanSegments_allTypes_filtersPureDigits() {
            List<TextSegment> segments = List.of(
                    createSegment("123456789012345678901234567890", "code"),     // 30 字符纯数字
                    createSegment("987654321098765432109876543210", "markdown"), // 30 字符纯数字
                    createSegment("正常的长文本内容超过三十个字符最小长度阈值不会被过滤这是额外的填充字符确保超过阈值", "text")  // 39 字符
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.txt");

            assertThat(cleaned).hasSize(1);
        }

        @Test
        @DisplayName("纯 URL 对所有类型均过滤（长 URL）")
        void cleanSegments_allTypes_filtersPureURL() {
            List<TextSegment> segments = List.of(
                    createSegment("https://example.com/very/long/path/to/resource/page", "code"),    // 52 字符 > 30
                    createSegment("http://example.org/another/very/long/path/here", "text"),          // 48 字符 > 30
                    createSegment("正常的长文本内容超过三十个字符最小长度阈值不会被过滤这是额外的填充字符确保超过阈值", "text")  // 39 字符
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.txt");

            assertThat(cleaned).hasSize(1);
        }

        @Test
        @DisplayName("噪声过滤关闭时全部保留")
        void cleanSegments_disabled_returnsAll() {
            lenient().when(ragConfigService.getBoolean("enable_noise_filter", true)).thenReturn(false);

            List<TextSegment> segments = List.of(
                    createSegment("x", "text"),
                    createSegment("JH_GAMMA", "code"),
                    createSegment("42", "text")
            );

            List<TextSegment> cleaned = service.cleanSegments(segments, "test.txt");

            assertThat(cleaned).hasSize(3);
        }
    }
}

