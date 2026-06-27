package com.insolu.rag.config;

import com.insolu.rag.entity.EmbeddingConfigEntity;
import com.insolu.rag.service.EmbeddingConfigService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * LangChain4j 组件配置。
 * EmbeddingModel 懒加载：首次使用时从数据库读取激活的 embedding_config。
 * EmbeddingStore 使用 PgVector，维度由激活的 embedding_config 决定。
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    @Bean
    public DatabaseBackedEmbeddingModel embeddingModel(EmbeddingConfigService embeddingConfigService) {
        return new DatabaseBackedEmbeddingModel(embeddingConfigService);
    }

    /** 元数据列定义 */
    private static final List<String> METADATA_COLUMN_DEFS = List.of(
            "project_name TEXT", "file_path TEXT", "language TEXT", "type TEXT",
            "signature TEXT", "start_line TEXT", "end_line TEXT"
    );

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${pgvector.table}") String table,
            EmbeddingConfigService embeddingConfigService) {

        // 从激活的 embedding 配置获取维度
        int dimension = embeddingConfigService.findActive()
                .map(EmbeddingConfigEntity::getDimension)
                .orElse(2560);
        log.info("PgVector 维度: {}", dimension);

        String afterScheme = jdbcUrl.replace("jdbc:postgresql://", "");
        String noQuery = afterScheme.contains("?") ? afterScheme.substring(0, afterScheme.indexOf('?')) : afterScheme;
        String[] hostPortDb = noQuery.split("/");
        String host = "localhost";
        int port = 5432;
        String database = hostPortDb.length > 1 ? hostPortDb[1] : "insolu_rag";
        if (hostPortDb[0].contains(":")) {
            String[] hp = hostPortDb[0].split(":");
            host = hp[0];
            port = Integer.parseInt(hp[1]);
        } else {
            host = hostPortDb[0];
        }

        DefaultMetadataStorageConfig metadataConfig = DefaultMetadataStorageConfig.builder()
                .storageMode(MetadataStorageMode.COLUMN_PER_KEY)
                .columnDefinitions(METADATA_COLUMN_DEFS)
                .build();

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(username)
                .password(password)
                .table(table)
                .dimension(dimension)
                .createTable(true)
                .metadataStorageConfig(metadataConfig)
                .build();
    }

    /**
     * 数据库驱动的 EmbeddingModel。
     * 首次 embed 时从数据库读取激活的 embedding_config 并构建实际的模型。
     * 配置变更后调用 reset() 清除缓存。
     */
    public static class DatabaseBackedEmbeddingModel implements EmbeddingModel {

        private final EmbeddingConfigService configService;
        private volatile EmbeddingModel delegate;

        DatabaseBackedEmbeddingModel(EmbeddingConfigService configService) {
            this.configService = configService;
        }

        private EmbeddingModel getDelegate() {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        EmbeddingConfigEntity config = configService.findActive()
                                .orElseThrow(() -> new IllegalStateException(
                                        "没有激活的 Embedding 配置，请先在设置页面配置并激活一个 Embedding 模型"));
                        log.info("初始化 EmbeddingModel: provider={}, baseUrl={}, model={}",
                                config.getProvider(), config.getBaseUrl(), config.getModelName());
                        delegate = configService.buildModel(config);
                        log.info("EmbeddingModel 初始化成功");
                    }
                }
            }
            return delegate;
        }

        public void reset() {
            synchronized (this) {
                delegate = null;
                log.info("EmbeddingModel 已重置");
            }
        }

        @Override
        public Response<Embedding> embed(String text) {
            return getDelegate().embed(text);
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return getDelegate().embed(textSegment);
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return getDelegate().embedAll(textSegments);
        }
    }
}
