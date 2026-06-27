package com.insolu.rag.service;

import com.insolu.rag.entity.RagConfigEntity;
import com.insolu.rag.entity.RagConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 RAG 配置服务。
 * 启动时自动初始化默认配置；读取时使用缓存（"ragConfigCache"）避免频繁查库；
 * 更新时清除缓存，下次读取自动刷新。
 */
@Service
public class RagConfigService {

    private static final Logger log = LoggerFactory.getLogger(RagConfigService.class);
    private static final String CACHE_NAME = "ragConfigCache";

    private final RagConfigRepository repository;

    public RagConfigService(RagConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 启动时初始化默认配置（仅插入不存在的 key，不覆盖用户已修改的值）。
     * 数据库不可达时记录警告但不阻止应用启动（其他 Bean 初始化可能更晚才需要数据库）。
     */
    @PostConstruct
    public void initializeDefaults() {
        try {
            List<RagConfigEntity> defaults = List.of(
                    new RagConfigEntity("max_segment_size",  "1000", "切分器最大段长度（字符数）"),
                    new RagConfigEntity("max_overlap_size",  "200",  "相邻 chunk 重叠长度（字符数）"),
                    new RagConfigEntity("semantic_threshold", "0.65", "语义切分相似度阈值（低于此值视为话题转变）"),
                    new RagConfigEntity("merge_min_length",  "300",  "短块自动合并的最小长度阈值"),
                    new RagConfigEntity("enable_noise_filter", "true", "是否开启噪声 chunk 过滤"),
                    new RagConfigEntity("noise_min_length",  "30",   "噪声过滤的最小长度阈值（小于此值视为噪声）"),
                    new RagConfigEntity("filter_pure_numbers", "true", "是否过滤纯数字（如页码）")
            );

            int inserted = 0;
            for (RagConfigEntity def : defaults) {
                if (!repository.existsById(def.getConfigKey())) {
                    repository.save(def);
                    inserted++;
                }
            }
            if (inserted > 0) {
                log.info("初始化 {} 个默认 RAG 配置项", inserted);
            }
        } catch (Exception e) {
            log.warn("RAG 配置初始化失败（数据库可能未就绪），将在首次使用时重试: {}", e.getMessage());
        }
    }

    /**
     * 获取单个配置值（带缓存）。
     * 不存在时返回 null。
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "#key")
    public String getConfig(String key) {
        return repository.findById(key)
                .map(RagConfigEntity::getConfigValue)
                .orElse(null);
    }

    /**
     * 获取所有配置（带缓存）。
     * 返回列表按 configKey 排序，方便前端稳定渲染。
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "'all'")
    public List<RagConfigEntity> getAllConfigs() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getConfigKey().compareTo(b.getConfigKey()))
                .toList();
    }

    /**
     * 批量更新配置（清除缓存）。
     * 接收 configKey → configValue 映射，跳过不存在的 key。
     *
     * @return 实际更新的条数
     */
    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    public int updateConfigs(Map<String, String> updates) {
        int count = 0;
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            RagConfigEntity entity = repository.findById(entry.getKey()).orElse(null);
            if (entity != null) {
                entity.setConfigValue(entry.getValue());
                repository.save(entity);
                count++;
            }
        }
        if (count > 0) {
            log.info("更新 {} 个 RAG 配置项", count);
        }
        return count;
    }

    // ─── 便捷方法：带类型解析和默认值 ───

    /** 获取 int 配置值，不存在或解析失败时返回默认值 */
    public int getInt(String key, int defaultValue) {
        String val = getConfig(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("RAG 配置 '{}' 值 '{}' 无法解析为 int，使用默认值 {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    /** 获取 double 配置值，不存在或解析失败时返回默认值 */
    public double getDouble(String key, double defaultValue) {
        String val = getConfig(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            log.warn("RAG 配置 '{}' 值 '{}' 无法解析为 double，使用默认值 {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    /** 获取 boolean 配置值，不存在或解析失败时返回默认值 */
    public boolean getBoolean(String key, boolean defaultValue) {
        String val = getConfig(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }
}
