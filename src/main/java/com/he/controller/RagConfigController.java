package com.he.controller;

import com.he.entity.RagConfigEntity;
import com.he.service.RagConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 RAG 配置 REST API。
 * GET  /api/configs   —— 获取所有配置（含描述和类型提示，供前端渲染表单）
 * PUT  /api/configs   —— 批量更新配置
 */
@RestController
@RequestMapping("/api/configs")
public class RagConfigController {

    /** 前端字段类型映射：number / boolean / text / textarea */
    private static final Map<String, String> FIELD_TYPES = Map.ofEntries(
            Map.entry("max_segment_size",    "number"),
            Map.entry("max_overlap_size",    "number"),
            Map.entry("semantic_threshold",  "number"),
            Map.entry("merge_min_length",    "number"),
            Map.entry("noise_min_length",    "number"),
            Map.entry("enable_noise_filter", "boolean"),
            Map.entry("filter_pure_numbers", "boolean"),
            Map.entry("max_results",         "number"),
            Map.entry("min_score",           "number"),
            Map.entry("enable_bm25",         "boolean"),
            Map.entry("enable_reranking",    "boolean"),
            Map.entry("reranking_model",     "text"),
            Map.entry("reranking_top_n",     "number"),
            Map.entry("reranking_pool_size", "number"),
            Map.entry("system_prompt",       "textarea")
    );

    /** 允许的配置键白名单 */
    private static final java.util.Set<String> ALLOWED_KEYS = FIELD_TYPES.keySet();

    private final RagConfigService service;

    public RagConfigController(RagConfigService service) {
        this.service = service;
    }

    /**
     * 获取所有配置项，附加 type 字段供前端渲染不同输入控件。
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        return service.getAllConfigs().stream()
                .map(entity -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key",         entity.getConfigKey());
                    item.put("value",       entity.getConfigValue());
                    item.put("description", entity.getDescription());
                    item.put("type",        FIELD_TYPES.getOrDefault(entity.getConfigKey(), "text"));
                    return item;
                })
                .toList();
    }

    /**
     * 批量更新配置。
     * 请求体: { "max_segment_size": "1200", "semantic_threshold": "0.70", ... }
     * 校验：仅允许已注册的 key；number 类型必须为正数。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, String> updates) {
        // 白名单过滤：只保留已注册的 key
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (ALLOWED_KEYS.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }

        // 值校验：number 类型必须为正数
        for (Map.Entry<String, String> entry : filtered.entrySet()) {
            String type = FIELD_TYPES.get(entry.getKey());
            if ("number".equals(type)) {
                try {
                    double val = Double.parseDouble(entry.getValue().trim());
                    if (val <= 0) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", 1, "message",
                                        "配置项 '" + entry.getKey() + "' 必须为正数，当前值: " + entry.getValue()));
                    }
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", 1, "message",
                                    "配置项 '" + entry.getKey() + "' 不是有效数字: " + entry.getValue()));
                }
            }
        }

        int count = service.updateConfigs(filtered);
        return ResponseEntity.ok(Map.of("updated", count));
    }
}

