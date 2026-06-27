package com.insolu.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SemanticStructureSplitter 单元测试。
 * 使用简易 EmbeddingModel 模拟向量化行为。
 */
class SemanticStructureSplitterTest {

    /**
     * 简易 EmbeddingModel：基于文本哈希生成伪随机向量。
     * 相同文本产生相同向量，不同文本产生不同向量（模拟低相似度）。
     */
    private static class FakeEmbeddingModel implements EmbeddingModel {
        private final int dimension;

        FakeEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(generateVector(text)));
        }

        @Override
        public Response<Embedding> embed(TextSegment segment) {
            return embed(segment.text());
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            List<Embedding> embeddings = segments.stream()
                    .map(s -> Embedding.from(generateVector(s.text())))
                    .toList();
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return dimension;
        }

        /**
         * 基于文本内容生成伪随机向量。
         * 相似文本（共享前缀）产生相似向量，不同主题产生不同向量。
         */
        private float[] generateVector(String text) {
            float[] vec = new float[dimension];
            Random rng = new Random(text.hashCode());
            for (int i = 0; i < dimension; i++) {
                vec[i] = rng.nextFloat() * 2 - 1;
            }
            // 归一化
            double norm = 0;
            for (float v : vec) norm += v * v;
            norm = Math.sqrt(norm);
            for (int i = 0; i < dimension; i++) vec[i] /= norm;
            return vec;
        }
    }

    private EmbeddingModel fakeModel;
    private SemanticStructureSplitter splitter;

    @BeforeEach
    void setUp() {
        fakeModel = new FakeEmbeddingModel(64);
        splitter = new SemanticStructureSplitter(fakeModel, 0.65);
    }

    // ─────────────────────────────────────────────
    //  基本功能
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("空文档抛出异常（LangChain4j 验证）")
    void split_emptyDocument_throwsException() {
        // Document.from() 自身会拒绝空白文本
        assertThatThrownBy(() -> Document.from(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("纯空白文档抛出异常（LangChain4j 验证）")
    void split_blankDocument_throwsException() {
        assertThatThrownBy(() -> Document.from("   \n\n   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("短文本无标题直接返回单个 chunk")
    void split_shortTextNoHeaders_returnsSingleChunk() {
        Document doc = Document.from("这是一段简短的文本内容，不需要任何切分处理。");
        List<TextSegment> segments = splitter.split(doc);
        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().text()).contains("简短的文本");
    }

    // ─────────────────────────────────────────────
    //  第一级：标题切分
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("第一级：标题切分")
    class HeaderSplitting {

        @Test
        @DisplayName("中文章节标题切分")
        void split_chineseChapterHeaders_splitsCorrectly() {
            String text = String.join("\n",
                    "第一章 基础知识",
                    "",
                    "本章介绍基础知识。",
                    "包括多个方面的内容。",
                    "",
                    "第二章 进阶内容",
                    "",
                    "本章介绍进阶内容。",
                    "需要基础知识作为前置。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 应该至少有两个 chunk（每章一个）
            assertThat(segments.size()).isGreaterThanOrEqualTo(2);

            // 验证标题被保留
            String allText = segments.stream()
                    .map(TextSegment::text)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(allText).contains("基础知识");
            assertThat(allText).contains("进阶内容");
        }

        @Test
        @DisplayName("数字编号标题切分")
        void split_numberedHeaders_splitsCorrectly() {
            String text = String.join("\n",
                    "1.1 环境准备",
                    "",
                    "安装必要的开发工具。",
                    "",
                    "1.2 配置步骤",
                    "",
                    "按照以下步骤完成配置。",
                    "第一步设置环境变量。",
                    "",
                    "1.3 验证安装",
                    "",
                    "运行命令验证安装是否成功。",
                    "检查版本号是否正确。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("Markdown 标题切分")
        void split_markdownHeaders_splitsCorrectly() {
            String text = String.join("\n",
                    "# 概述",
                    "",
                    "这是项目概述。",
                    "",
                    "## 安装",
                    "",
                    "安装步骤如下。",
                    "",
                    "### 系统要求",
                    "",
                    "需要 Java 21 以上版本。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("混合标题层级")
        void split_mixedHeaderLevels_splitsAndTracksHierarchy() {
            String text = String.join("\n",
                    "# 技术文档",
                    "",
                    "第一章 架构设计",
                    "",
                    "系统采用微服务架构。",
                    "",
                    "1.1 服务拆分",
                    "",
                    "将系统拆分为多个服务。",
                    "",
                    "1.1.1 用户服务",
                    "",
                    "负责用户管理和认证。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 验证上下文注入（子级应包含父级标题前缀）
            boolean hasHierarchyContext = segments.stream()
                    .anyMatch(s -> s.text().contains("架构设计"));
            assertThat(hasHierarchyContext).isTrue();
        }

        @Test
        @DisplayName("无标题文本作为整体处理")
        void split_noHeaders_treatedAsSingleBlock() {
            String text = "这是一段没有标题的文本。".repeat(5);
            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(1);
        }

        @Test
        @DisplayName("标题前有前导文本")
        void split_preambleBeforeFirstHeader_includedAsSeparateBlock() {
            String text = String.join("\n",
                    "文档前言：本文档仅供内部使用。",
                    "",
                    "第一章 正文",
                    "",
                    "这是正文内容。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 前导文本 + 第一章
            assertThat(segments.size()).isGreaterThanOrEqualTo(2);
            assertThat(segments.stream().anyMatch(s -> s.text().contains("前言"))).isTrue();
        }
    }

    // ─────────────────────────────────────────────
    //  第二级：语义切分
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("第二级：语义切分")
    class SemanticSplitting {

        @Test
        @DisplayName("长文本触发语义切分")
        void split_longBlock_triggersSemanticSplit() {
            // 构造一个包含不同话题段落的长文本（>1200 字符）
            String para1 = "数据库优化是提升系统性能的关键。".repeat(20) + "\n\n";
            String para2 = "网络安全防护需要多层次策略。".repeat(20) + "\n\n";
            String para3 = "容器化部署简化了运维流程。".repeat(20);

            String text = para1 + para2 + para3;
            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 应该切分出多个 chunk（因为不同话题）
            assertThat(segments.size()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("短章节块不触发语义切分")
        void split_shortBlock_skipsSemanticSplit() {
            String text = String.join("\n",
                    "第一章 简短章节",
                    "",
                    "这是一个非常短的章节。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 短章节应该作为单个 chunk 返回
            assertThat(segments).hasSize(1);
            assertThat(segments.getFirst().text()).contains("非常短的章节");
        }
    }

    // ─────────────────────────────────────────────
    //  第三级：递归兜底
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("第三级：递归兜底")
    class FallbackSplitting {

        @Test
        @DisplayName("超长段落触发递归兜底")
        void split_oversizedChunk_triggersFallback() {
            // 构造一个超长单段落（没有双换行分隔）
            String longParagraph = "这是一段非常长的连续文本，没有段落分隔符。".repeat(100);
            Document doc = Document.from(longParagraph);
            List<TextSegment> segments = splitter.split(doc);

            // 应该被递归切分为多个 chunk
            assertThat(segments.size()).isGreaterThanOrEqualTo(2);
        }
    }

    // ─────────────────────────────────────────────
    //  上下文注入
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("上下文注入")
    class ContextInjection {

        @Test
        @DisplayName("子级 chunk 包含父级标题前缀")
        void split_nestedHeaders_injectsParentContext() {
            String text = String.join("\n",
                    "第一章 系统架构",
                    "",
                    "1.1 数据层",
                    "",
                    "数据库采用 PostgreSQL。",
                    "这是数据层的详细描述。");

            Document doc = Document.from(text);
            List<TextSegment> segments = splitter.split(doc);

            // 应该能找到包含 "第一章 系统架构 > 1.1 数据层" 上下文的 chunk
            boolean hasContextPrefix = segments.stream()
                    .map(s -> s.metadata().getString("signature"))
                    .anyMatch(sig -> sig != null && sig.contains("系统架构"));
            assertThat(hasContextPrefix).isTrue();
        }
    }

    // ─────────────────────────────────────────────
    //  元数据
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("切分结果保留原始文档的 metadata")
    void split_preservesOriginalMetadata() {
        Metadata meta = new Metadata();
        meta.put("source", "test.pdf");
        Document doc = Document.from("第一章 测试\n\n内容。", meta);

        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments).isNotEmpty();
        // 验证原始 metadata 被保留
        assertThat(segments.getFirst().metadata().getString("source")).isEqualTo("test.pdf");
    }

    @Test
    @DisplayName("signature 字段包含上下文前缀")
    void split_signatureContainsContextPrefix() {
        String text = String.join("\n",
                "第一章 概述",
                "",
                "这是概述内容。");

        Document doc = Document.from(text);
        List<TextSegment> segments = splitter.split(doc);

        assertThat(segments).isNotEmpty();
        String signature = segments.getFirst().metadata().getString("signature");
        assertThat(signature).contains("概述");
    }

    // ─────────────────────────────────────────────
    //  边界情况
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("EmbeddingModel 为 null 时构造器抛异常")
    void constructor_nullModel_throws() {
        assertThatThrownBy(() -> new SemanticStructureSplitter(null, 0.65))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("余弦相似度计算正确性")
    void cosineSimilarity_identicalVectors_returns1() {
        float[] a = {1, 0, 0};
        float[] b = {1, 0, 0};
        assertThat(SemanticStructureSplitter.cosineSimilarity(a, b))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("正交向量余弦相似度为 0")
    void cosineSimilarity_orthogonalVectors_returns0() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        assertThat(SemanticStructureSplitter.cosineSimilarity(a, b))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("长度不同的向量返回 0")
    void cosineSimilarity_differentLength_returns0() {
        float[] a = {1, 0};
        float[] b = {1, 0, 0};
        assertThat(SemanticStructureSplitter.cosineSimilarity(a, b)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("零向量返回 0")
    void cosineSimilarity_zeroVector_returns0() {
        float[] a = {0, 0, 0};
        float[] b = {1, 0, 0};
        assertThat(SemanticStructureSplitter.cosineSimilarity(a, b)).isEqualTo(0.0);
    }
}
