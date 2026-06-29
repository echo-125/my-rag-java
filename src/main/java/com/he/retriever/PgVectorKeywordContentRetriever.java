package com.he.retriever;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PostgreSQL 全文检索（tsvector/tsquery）的 BM25 关键词检索器。
 * <p>
 * 查询流程：
 * <ol>
 *   <li>将用户 query 转为 tsquery（plainto_tsquery）</li>
 *   <li>用 ts_rank 对匹配结果打分</li>
 *   <li>将结果转为 LangChain4j Content 对象</li>
 * </ol>
 */
public class PgVectorKeywordContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(PgVectorKeywordContentRetriever.class);

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final int maxResults;

    public PgVectorKeywordContentRetriever(JdbcTemplate jdbcTemplate, String table, int maxResults) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query.text();
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            String sql = "SELECT id, text, project_name, file_path, language, type, signature, " +
                    "start_line, end_line, chunk_index, total_chunks, " +
                    "ts_rank(search_vector, plainto_tsquery('simple', ?)) AS rank " +
                    "FROM " + table + " " +
                    "WHERE search_vector @@ plainto_tsquery('simple', ?) " +
                    "AND text IS NOT NULL AND text != '' " +
                    "ORDER BY rank DESC " +
                    "LIMIT ?";

            return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String text = rs.getString("text");
                Metadata metadata = Metadata.from("project_name", rs.getString("project_name"))
                        .put("file_path", rs.getString("file_path"))
                        .put("language", rs.getString("language"))
                        .put("type", rs.getString("type"))
                        .put("signature", rs.getString("signature"))
                        .put("start_line", rs.getString("start_line"))
                        .put("end_line", rs.getString("end_line"))
                        .put("chunk_index", rs.getString("chunk_index"))
                        .put("total_chunks", rs.getString("total_chunks"));

                TextSegment segment = TextSegment.from(text, metadata);
                return Content.from(segment);
            }, queryText, queryText, maxResults);
        } catch (Exception e) {
            log.warn("BM25 全文检索失败（可能 search_vector 列不存在，请重新入库）: {}", e.getMessage());
            return List.of();
        }
    }
}
