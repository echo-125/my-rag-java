package com.insolu.rag.service;

import com.insolu.rag.entity.EmbeddingConfigEntity;
import com.insolu.rag.entity.EmbeddingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfigService.class);

    private final EmbeddingConfigRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public EmbeddingConfigService(EmbeddingConfigRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EmbeddingConfigEntity> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<EmbeddingConfigEntity> findActive() {
        return repository.findFirstByIsActiveTrue();
    }

    public Optional<EmbeddingConfigEntity> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public EmbeddingConfigEntity save(EmbeddingConfigEntity entity) {
        return repository.save(entity);
    }

    @Transactional
    public EmbeddingConfigEntity update(UUID id, EmbeddingConfigEntity updated) {
        EmbeddingConfigEntity existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        existing.setName(updated.getName());
        existing.setProvider(updated.getProvider());
        existing.setBaseUrl(updated.getBaseUrl());
        existing.setModelName(updated.getModelName());
        existing.setDimension(updated.getDimension());

        // 只有传了新密钥才更新（排除前端回传的脱敏值）
        String newKey = updated.getApiKey();
        if (newKey != null && !newKey.isBlank() && !newKey.startsWith("***")) {
            existing.setApiKey(newKey);
        }

        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    @Transactional
    public EmbeddingConfigEntity activate(UUID id) {
        repository.deactivateAll();
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(true);
        EmbeddingConfigEntity saved = repository.save(entity);

        // 切换 Embedding 模型后，用新维度重建向量表（旧数据与新模型不兼容）
        // 注意：DDL 执行期间有极短窗口期，并发检索请求可能报错（本地单用户系统可接受）
        recreateDocumentChunksTable(saved.getDimension());

        return saved;
    }

    /**
     * 重建 document_chunks 向量表。
     * 切换 Embedding 模型时，向量维度可能不同，旧数据无法复用，需要重建。
     */
    private void recreateDocumentChunksTable(int dimension) {
        if (dimension <= 0 || dimension > 65536) {
            throw new IllegalArgumentException("无效的向量维度: " + dimension + "（必须在 1-65536 之间）");
        }
        log.info("重建 document_chunks 表，新维度: {}", dimension);
        jdbcTemplate.execute("DROP TABLE IF EXISTS document_chunks");
        // 表结构与 PgVectorEmbeddingStore.createTable=true 生成的一致
        jdbcTemplate.execute(String.format("""
                CREATE TABLE document_chunks (
                    embedding_id UUID PRIMARY KEY,
                    embedding VECTOR(%d),
                    text TEXT,
                    project_name TEXT,
                    file_path TEXT,
                    language TEXT,
                    type TEXT,
                    signature TEXT,
                    start_line TEXT,
                    end_line TEXT
                )""", dimension));
        log.info("document_chunks 表重建完成（维度={}）", dimension);
    }

    @Transactional
    public EmbeddingConfigEntity deactivate(UUID id) {
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(false);
        return repository.save(entity);
    }

    /** 测试 Embedding 连接，同时检测真实向量维度 */
    public TestResult testConnection(UUID id) {
        EmbeddingConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        long start = System.currentTimeMillis();
        try {
            dev.langchain4j.model.embedding.EmbeddingModel model = buildModel(entity);
            var response = model.embed("test connection");
            long elapsed = System.currentTimeMillis() - start;
            int dim = response.content().vector().length;
            boolean success = dim > 0;
            // 同时更新配置中的维度（确保与模型实际输出一致）
            if (success && entity.getDimension() != dim) {
                entity.setDimension(dim);
                repository.save(entity);
            }
            return new TestResult(success,
                    success ? "连接成功，向量维度: " + dim : "响应为空",
                    elapsed, dim);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Embedding 测试失败: name={}, baseUrl={}, model={}", entity.getName(), entity.getBaseUrl(), entity.getModelName(), e);
            return new TestResult(false, "连接失败，请检查网络或配置", elapsed, 0);
        }
    }

    /** 根据 provider 构建 EmbeddingModel */
    public dev.langchain4j.model.embedding.EmbeddingModel buildModel(EmbeddingConfigEntity entity) {
        return switch (entity.getProvider()) {
            case "ollama" -> dev.langchain4j.model.ollama.OllamaEmbeddingModel.builder()
                    .baseUrl(entity.getBaseUrl())
                    .modelName(entity.getModelName())
                    .build();
            case "openai" -> dev.langchain4j.model.openai.OpenAiEmbeddingModel.builder()
                    .apiKey(entity.getApiKey())
                    .baseUrl(entity.getBaseUrl())
                    .modelName(entity.getModelName())
                    .build();
            default -> throw new IllegalArgumentException("不支持的 provider: " + entity.getProvider());
        };
    }

    /** 脱敏副本返回给前端 */
    public EmbeddingConfigEntity toMaskedCopy(EmbeddingConfigEntity source) {
        EmbeddingConfigEntity copy = new EmbeddingConfigEntity();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setProvider(source.getProvider());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setModelName(source.getModelName());
        copy.setDimension(source.getDimension());
        copy.setIsActive(source.getIsActive());
        String key = source.getApiKey();
        if (key != null && key.length() > 4) {
            copy.setApiKey("***" + key.substring(key.length() - 4));
        } else if (key != null && !key.isBlank()) {
            copy.setApiKey("***");
        }
        return copy;
    }

    public record TestResult(boolean success, String message, long responseTimeMs, int dimension) {}
}
