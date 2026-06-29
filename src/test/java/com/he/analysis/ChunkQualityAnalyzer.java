package com.he.analysis;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chunk 质量分析器 V2 (适配结构化/语义切分)
 * <p>
 * 适配新的 SemanticStructureSplitter，重点检测：
 * 1. 长度分布与异常标记 (放宽短文本限制，适应短小配置项)
 * 2. 切分边界逻辑性检测 (识别标题断点 vs 真正的硬切断)
 * 3. 结构上下文完整性 (检查是否注入了父级标题)
 * 4. 噪声 Chunk 检测 (更精准的纯噪声识别)
 * 5. 元数据完整性 (重点检查 chunk_index/total_chunks)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChunkQualityAnalyzer {

    private static final String DB_URL = System.getProperty("db.url", "jdbc:postgresql://localhost:5432/my_rag");
    private static final String DB_USER = System.getProperty("db.user", System.getenv().getOrDefault("DB_USERNAME", "postgres"));
    private static final String DB_PASS = System.getProperty("db.password", System.getenv().getOrDefault("DB_PASSWORD", "123456"));

    // 阈值调整：适应结构化切分
    private static final int ANOMALY_MAX_CHUNKS = 50; // 结构切分可能产生更多小块
    private static final int ANOMALY_MIN_CHUNKS = 1;
    private static final int ANOMALY_TINY_CHUNK_LEN = 30; // 降低阈值，保留有意义的短配置
    private static final int TOP_N_DANGEROUS_BREAKS = 10;

    // 标题正则（用于识别逻辑断点）
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^(第[0-9一二三四五六七八九十]+章\\s+.*|" + // 第X章
                    "[0-9]+\\.[0-9]+(\\.[0-9]+)?\\s+.*|" +    // 1.1 或 1.1.1
                    "[一二三四五六七八九十]+、\\s+.*|" +         // 一、二、
                    "^[A-Z]\\..*)",                            // A. B.
            Pattern.MULTILINE);

    // ANSI 颜色
    private static final String RESET = "\033[0m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String BOLD = "\033[1m";

    private static final StringBuilder report = new StringBuilder();
    private static int totalChunks = 0;

    record FileStats(String filePath, String language, int chunkCount,
                     double avgLen, int minLen, int maxLen, double stdDev,
                     List<String> issues) {}

    record BoundaryInfo(String filePath, int rn, boolean isLogicalBreak,
                        boolean isHardBreak, String suffixA, String prefixB) {}

    record NoiseChunk(String embeddingId, String filePath, int textLen, String pattern, String textPreview) {}

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.postgresql.Driver");
        report.setLength(0);
        report.append("# Chunk 质量分析报告 V2 (结构化切分版)\n\n");
        report.append("> 生成时间: ").append(java.time.LocalDateTime.now()).append("\n\n");

        try (Connection conn = getConnection()) {
            totalChunks = ensureTable(conn);
            report.append("## 概览\n\n- **总 chunk 数**: ").append(totalChunks).append("\n\n");
        }
    }

    @AfterAll
    static void generateReport() throws IOException {
        report.append("\n## 5. 优化建议\n");
        report.append("- 若硬切断率 > 10%，请检查 `SemanticStructureSplitter` 中的递归兜底逻辑是否生效。\n");
        report.append("- 若短配置项被误杀，请调整 `isNoiseChunk` 中的长度阈值。\n");

        Path reportPath = Paths.get("src", "test", "resources", "chunk_quality_report_v2.md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
        System.out.println("\n" + GREEN + "V2报告已写入: " + reportPath.toAbsolutePath() + RESET);
    }

    private static int ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM document_chunks")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ================================================================
    // 1. 长度分布分析
    // ================================================================
    @Test
    @Order(1)
    @DisplayName("1. 长度分布分析")
    void analyzeLengthDistribution() throws SQLException {
        if (totalChunks == 0) return;
        System.out.println(BOLD + "\n========== 1. 长度分布分析 ==========\n" + RESET);

        String sql = """
                SELECT file_path, MAX(language) AS lang, COUNT(*) AS cnt,
                       ROUND(AVG(LENGTH(text)))::INT AS avg, MIN(LENGTH(text)) AS min, MAX(LENGTH(text)) AS max
                FROM document_chunks GROUP BY file_path ORDER BY cnt DESC
                """;

        List<FileStats> stats = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<String> issues = new ArrayList<>();
                if (rs.getInt("cnt") > ANOMALY_MAX_CHUNKS) issues.add("碎片过多");
                if (rs.getInt("avg") < 50) issues.add("平均长度过低");
                stats.add(new FileStats(rs.getString("file_path"), rs.getString("lang"),
                        rs.getInt("cnt"), rs.getInt("avg"), rs.getInt("min"), rs.getInt("max"), 0, issues));
            }
        }

        report.append("## 1. 长度分布分析\n\n");
        report.append("| 文件 | chunks | avg长度 | min | max | 状态 |\n|---|---|---|---|---|---|\n");
        stats.forEach(s -> report.append(String.format("| %s | %d | %d | %d | %d | %s |\n",
                truncate(s.filePath(), 50), s.chunkCount(), (int)s.avgLen(), s.minLen(), s.maxLen(),
                s.issues().isEmpty() ? "OK" : RED + String.join(",", s.issues()) + RESET)));
        report.append("\n");
    }

    // ================================================================
    // 2. 切分边界逻辑性检测
    // ================================================================
    @Test
    @Order(2)
    @DisplayName("2. 切分边界逻辑性检测")
    void analyzeBoundaryLogic() throws SQLException {
        if (totalChunks == 0) return;
        System.out.println(BOLD + "\n========== 2. 切分边界逻辑性检测 ==========\n" + RESET);

        // 核心逻辑：如果 A 末尾或 B 开头符合标题正则，则是逻辑断点；
        // 如果不是逻辑断点，且 B 开头前60字不在 A 末尾200字中，则是硬切断。
        String sql = """
                WITH ordered AS (
                    SELECT file_path, text,
                           ROW_NUMBER() OVER (PARTITION BY file_path ORDER BY CAST(start_line AS INTEGER)) as rn
                    FROM document_chunks
                )
                SELECT a.file_path, a.rn,
                       RIGHT(a.text, 40) AS suffix_a,
                       LEFT(b.text, 40) AS prefix_b,
                       CASE WHEN a.text ~* ? OR b.text ~* ? THEN TRUE ELSE FALSE END AS is_logical,
                       CASE WHEN a.text ~* ? OR b.text ~* ? THEN FALSE
                            WHEN POSITION(LEFT(b.text, 60) IN RIGHT(a.text, 200)) > 0 THEN FALSE
                            ELSE TRUE END AS is_hard_break
                FROM ordered a JOIN ordered b ON a.file_path = b.file_path AND a.rn + 1 = b.rn
                """;

        List<BoundaryInfo> boundaries = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 参数设置：匹配标题
            String titleRegex = "^(第[0-9一二三四五六七八九十]+章|[0-9]+\\.[0-9]+|[一二三四五六七八九十]+、).*$";
            ps.setString(1, titleRegex);
            ps.setString(2, titleRegex);
            ps.setString(3, titleRegex);
            ps.setString(4, titleRegex);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boundaries.add(new BoundaryInfo(
                            rs.getString("file_path"), rs.getInt("rn"),
                            rs.getBoolean("is_logical"), rs.getBoolean("is_hard_break"),
                            rs.getString("suffix_a"), rs.getString("prefix_b")));
                }
            }
        }

        long logicalBreaks = boundaries.stream().filter(BoundaryInfo::isLogicalBreak).count();
        long hardBreaks = boundaries.stream().filter(BoundaryInfo::isHardBreak).count();
        long totalPairs = boundaries.size();

        System.out.printf("  总边界数: %d, 逻辑断点: %s%d%s, 硬切断: %s%d%s%n%n",
                totalPairs, GREEN, logicalBreaks, RESET, hardBreaks > 0 ? RED : GREEN, hardBreaks, RESET);

        report.append("## 2. 切分边界逻辑性检测\n\n");
        report.append(String.format("- 总边界: %d\n- 逻辑断点(标题/章节): %d (%.1f%%)\n- **硬切断(非标题且无重叠): %d**\n\n",
                totalPairs, logicalBreaks, totalPairs > 0 ? logicalBreaks * 100.0 / totalPairs : 0, hardBreaks));

        if (hardBreaks > 0) {
            report.append("### 危险的硬切断点\n\n");
            boundaries.stream().filter(BoundaryInfo::isHardBreak).limit(TOP_N_DANGEROUS_BREAKS).forEach(b -> {
                String info = String.format("[%s] A末尾: \"%s\" | B开头: \"%s\"", b.filePath(), b.suffixA(), b.prefixB());
                System.out.println(RED + "  " + info + RESET);
                report.append("- ").append(info).append("\n");
            });
            report.append("\n");
        }
    }

    // ================================================================
    // 3. 结构上下文完整性检查
    // ================================================================
    @Test
    @Order(3)
    @DisplayName("3. 结构上下文完整性检查")
    void analyzeStructureContext() throws SQLException {
        if (totalChunks == 0) return;
        System.out.println(BOLD + "\n========== 3. 结构上下文完整性检查 ==========\n" + RESET);

        // 检查文本中是否包含典型的标题结构（说明 SemanticStructureSplitter 注入成功）
        String sql = """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE text ~* '^(第[0-9一二三四五六七八九十]+章|[0-9]+\\.[0-9]+|[一二三四五六七八九十]+、).*') AS has_structure
                FROM document_chunks
                """;
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            int total = rs.getInt("total");
            int hasStruct = rs.getInt("has_structure");
            double rate = total > 0 ? hasStruct * 100.0 / total : 0;
            String color = rate > 60 ? GREEN : rate > 30 ? YELLOW : RED;

            System.out.printf("  携带结构上下文的 chunk: %s%d/%d (%.1f%%)%s%n", color, hasStruct, total, rate, RESET);
            report.append("## 3. 结构上下文完整性\n\n");
            report.append(String.format("- 携带父级标题的 chunk: %d/%d (%.1f%%)\n\n", hasStruct, total, rate));
        }
    }

    // ================================================================
    // 4. 噪声 Chunk 检测
    // ================================================================
    @Test
    @Order(4)
    @DisplayName("4. 噪声 Chunk 检测")
    void detectNoiseChunks() throws SQLException {
        if (totalChunks == 0) return;
        System.out.println(BOLD + "\n========== 4. 噪声 Chunk 检测 ==========\n" + RESET);

        // 调整：只过滤极短且无意义的符号、纯数字
        String sql = """
                SELECT embedding_id, file_path, LENGTH(text) AS len, text
                FROM document_chunks
                WHERE LENGTH(TRIM(text)) < 20
                   AND (TRIM(text) ~ '^[0-9]+$' OR TRIM(text) ~ '^[\\s\\p{Punct}]+$')
                """;
        List<NoiseChunk> noises = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                noises.add(new NoiseChunk(rs.getString("embedding_id"), rs.getString("file_path"),
                        rs.getInt("len"), "纯符号/数字", rs.getString("text")));
            }
        }

        System.out.printf("  检测到 %s%d%s 个纯符号/数字噪声 chunk%n",
                noises.isEmpty() ? GREEN : RED, noises.size(), RESET);

        report.append("## 4. 噪声 Chunk 检测\n\n");
        report.append(String.format("共检测到 **%d** 个纯噪声 chunk\n\n", noises.size()));
        if (!noises.isEmpty()) {
            report.append("| ID | 文件 | 内容 |\n|---|---|---|\n");
            noises.forEach(n -> report.append(String.format("| %s | %s | `%s` |\n",
                    truncate(n.embeddingId(), 8), truncate(n.filePath(), 40), n.textPreview())));
            report.append("\n");
        }
    }

    // ================================================================
    // 5. 元数据完整性
    // ================================================================
    @Test
    @Order(5)
    @DisplayName("5. 元数据完整性检查")
    void analyzeMetadata() throws SQLException {
        if (totalChunks == 0) return;
        System.out.println(BOLD + "\n========== 5. 元数据完整性检查 ==========\n" + RESET);

        try (Connection conn = getConnection()) {
            // 检查 chunk_index 和 total_chunks
            String sql = """
                    SELECT
                        COUNT(*) AS total,
                        COUNT(chunk_index) AS has_idx,
                        COUNT(total_chunks) AS has_total,
                        COUNT(*) FILTER (WHERE start_line IS NULL OR start_line = '0') AS invalid_start
                    FROM document_chunks
                    """;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                int total = rs.getInt("total");
                int hasIdx = rs.getInt("has_idx");
                int invalidStart = rs.getInt("invalid_start");

                System.out.printf("  chunk_index 填充率: %.1f%%%n", total > 0 ? hasIdx * 100.0 / total : 0);
                System.out.printf("  start_line 有效率: %.1f%%%n", total > 0 ? (total - invalidStart) * 100.0 / total : 0);

                report.append("## 5. 元数据完整性\n\n");
                report.append(String.format("- chunk_index: %d/%d 有效\n", hasIdx, total));
                report.append(String.format("- start_line: %d/%d 有效\n", total - invalidStart, total));
            }
        }
        report.append("\n");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "NULL";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}

