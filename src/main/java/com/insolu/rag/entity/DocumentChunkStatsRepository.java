package com.insolu.rag.entity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 使用 JdbcTemplate 查询 document_chunks 表统计数据。
 * 不通过 JPA 管理此表（表由 LangChain4j PgVectorEmbeddingStore 创建）。
 */
@Repository
public class DocumentChunkStatsRepository {

    private final JdbcTemplate jdbc;

    public DocumentChunkStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM document_chunks", Long.class);
        return result != null ? result : 0;
    }

    public List<Map<String, Object>> countByLanguage() {
        return jdbc.queryForList(
                "SELECT COALESCE(language, 'unknown') AS language, COUNT(*) AS count " +
                "FROM document_chunks GROUP BY language ORDER BY count DESC");
    }

    public List<Map<String, Object>> countByProject() {
        return jdbc.queryForList(
                "SELECT COALESCE(project_name, 'unknown') AS project, COUNT(*) AS count " +
                "FROM document_chunks GROUP BY project_name ORDER BY count DESC");
    }

    public List<Map<String, Object>> countByFilePath() {
        return jdbc.queryForList(
                "SELECT file_path, COUNT(*) AS count " +
                "FROM document_chunks GROUP BY file_path");
    }
}
