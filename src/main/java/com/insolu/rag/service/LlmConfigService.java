package com.insolu.rag.service;

import com.insolu.rag.entity.LlmConfigEntity;
import com.insolu.rag.entity.LlmConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Service
public class LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);

    private final LlmConfigRepository repository;
    private final ChatModelBuilder chatModelBuilder;

    public LlmConfigService(LlmConfigRepository repository, ChatModelBuilder chatModelBuilder) {
        this.repository = repository;
        this.chatModelBuilder = chatModelBuilder;
    }

    /** 获取所有配置（API 密钥脱敏） */
    @Transactional(readOnly = true)
    public List<LlmConfigEntity> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toMaskedCopy)
                .toList();
    }

    /** 获取激活的配置（密钥脱敏） */
    @Transactional(readOnly = true)
    public List<LlmConfigEntity> findActive() {
        return repository.findByIsActiveTrue().stream()
                .map(this::toMaskedCopy)
                .toList();
    }

    /** 获取激活配置的原始实体（含明文密钥，内部使用，带缓存） */
    @Transactional(readOnly = true)
    @Cacheable(value = "activeLlmConfig", key = "'current'")
    public Optional<LlmConfigEntity> findActiveRaw() {
        return repository.findFirstByIsActiveTrueOrderByUpdatedAtDesc();
    }

    /** 按 ID 获取（密钥脱敏） */
    @Transactional(readOnly = true)
    public Optional<LlmConfigEntity> findById(UUID id) {
        return repository.findById(id).map(this::toMaskedCopy);
    }

    /** 按 ID 获取原始实体（含明文密钥，内部使用） */
    @Transactional(readOnly = true)
    public Optional<LlmConfigEntity> findRawById(UUID id) {
        return repository.findById(id);
    }

    /** 保存新配置 */
    @Transactional
    @CacheEvict(value = "activeLlmConfig", allEntries = true)
    public LlmConfigEntity save(LlmConfigEntity entity) {
        LlmConfigEntity saved = repository.save(entity);
        repository.flush();
        return toMaskedCopy(saved);
    }

    /** 更新配置 */
    @Transactional
    @CacheEvict(value = "activeLlmConfig", allEntries = true)
    public LlmConfigEntity update(UUID id, LlmConfigEntity updated) {
        LlmConfigEntity existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        existing.setName(updated.getName());
        existing.setModelName(updated.getModelName());
        existing.setBaseUrl(updated.getBaseUrl());
        existing.setApiFormat(updated.getApiFormat());

        // 只有传了新密钥才更新（排除前端回传的脱敏值）
        String newKey = updated.getApiKey();
        if (newKey != null && !newKey.isBlank() && !newKey.startsWith("***")) {
            existing.setApiKey(newKey);
        }

        LlmConfigEntity saved = repository.save(existing);
        repository.flush();
        return toMaskedCopy(saved);
    }

    /** 删除配置 */
    @Transactional
    @CacheEvict(value = "activeLlmConfig", allEntries = true)
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    /** 激活指定配置（允许多个同时激活） */
    @Transactional
    @CacheEvict(value = "activeLlmConfig", allEntries = true)
    public LlmConfigEntity activate(UUID id) {
        LlmConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(true);
        LlmConfigEntity saved = repository.save(entity);
        repository.flush();
        return toMaskedCopy(saved);
    }

    /** 停用指定配置 */
    @Transactional
    @CacheEvict(value = "activeLlmConfig", allEntries = true)
    public LlmConfigEntity deactivate(UUID id) {
        LlmConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));
        entity.setIsActive(false);
        LlmConfigEntity saved = repository.save(entity);
        repository.flush();
        return toMaskedCopy(saved);
    }

    /**
     * 测试 API 连接，同时检测流式支持并存入数据库。
     */
    @Transactional
    public TestResult testConnection(UUID id) {
        LlmConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + id));

        String apiKey = entity.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return new TestResult(false, "API 密钥为空，请编辑配置填入密钥", 0, null, false);
        }

        log.info("开始测试 API 连接: name={}, format={}, baseUrl={}", entity.getName(), entity.getApiFormat(), entity.getBaseUrl());

        long start = System.currentTimeMillis();
        try {
            var chatModel = chatModelBuilder.build(entity, apiKey, Duration.ofSeconds(30));

            // 1. 测试非流式调用
            String response = chatModel.call("Reply with exactly: OK");
            long elapsed = System.currentTimeMillis() - start;
            boolean success = response != null && !response.isBlank();

            // 2. 测试流式调用
            boolean streaming = false;
            try {
                var chatClient = org.springframework.ai.chat.client.ChatClient.create(chatModel);
                var streamFlux = chatClient.prompt().user("Say hi").stream().content();
                // 尝试获取第一个 token
                String first = streamFlux.blockFirst(java.time.Duration.ofSeconds(30));
                streaming = first != null;
                if (streaming) log.info("流式测试通过");
            } catch (Exception se) {
                log.info("流式测试失败: {}", se.getMessage());
                streaming = false;
            }

            // 3. 保存流式支持标记到数据库
            entity.setSupportsStreaming(streaming);
            repository.save(entity);
            repository.flush();

            String msg = success
                    ? (streaming ? "连接成功（支持流式）" : "连接成功（不支持流式，将使用非流式对话）")
                    : "响应为空";
            log.info("API 测试完成: success={}, streaming={}, elapsed={}ms", success, streaming, elapsed);
            return new TestResult(success, msg, elapsed, success ? response.trim() : null, streaming);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("API 测试失败: name={}, elapsed={}ms", entity.getName(), elapsed, e);
            String detail = extractRootCause(e);
            return new TestResult(false, "连接失败: " + detail, elapsed, null, false);
        }
    }

    /** 提取异常链中的根本原因 */
    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /**
     * 创建脱敏副本返回给前端，不修改原始 JPA 托管实体。
     */
    private LlmConfigEntity toMaskedCopy(LlmConfigEntity source) {
        LlmConfigEntity copy = new LlmConfigEntity();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setModelName(source.getModelName());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiFormat(source.getApiFormat());
        copy.setIsActive(source.getIsActive());
        copy.setSupportsStreaming(source.getSupportsStreaming());
        // 密钥仅脱敏展示
        String key = source.getApiKey();
        if (key != null && key.length() > 4) {
            copy.setApiKey("***" + key.substring(key.length() - 4));
        } else {
            copy.setApiKey("***");
        }
        return copy;
    }

    public record TestResult(boolean success, String message, long responseTimeMs, String sampleResponse, boolean supportsStreaming) {}
}
