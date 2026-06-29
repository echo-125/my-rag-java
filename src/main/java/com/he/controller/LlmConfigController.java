package com.he.controller;

import com.he.entity.LlmConfigEntity;
import com.he.entity.LlmConfigEntity.ApiFormat;
import com.he.service.LlmConfigService;
import com.he.service.LlmConfigService.TestResult;
import com.he.service.RagChatService;
import com.he.service.SpringAiModelRouterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/llm-configs")
public class LlmConfigController {

    private final LlmConfigService service;
    private final SpringAiModelRouterService router;
    private final RagChatService ragChatService;

    public LlmConfigController(LlmConfigService service, SpringAiModelRouterService router,
                               RagChatService ragChatService) {
        this.service = service;
        this.router = router;
        this.ragChatService = ragChatService;
    }

    @GetMapping
    public List<LlmConfigEntity> list() {
        return service.findAll();
    }

    @GetMapping("/active")
    public List<LlmConfigEntity> active() {
        return service.findActive();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LlmConfigEntity> getById(@PathVariable UUID id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public LlmConfigEntity create(@RequestBody LlmConfigEntity entity) {
        LlmConfigEntity saved = service.save(entity);
        router.evictAllCache();
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<LlmConfigEntity> update(@PathVariable UUID id,
                                                   @RequestBody LlmConfigEntity entity) {
        try {
            LlmConfigEntity saved = service.update(id, entity);
            router.evictCache(id);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        router.evictCache(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<LlmConfigEntity> activate(@PathVariable UUID id) {
        try {
            LlmConfigEntity result = service.activate(id);
            router.evictAllCache();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<LlmConfigEntity> deactivate(@PathVariable UUID id) {
        try {
            LlmConfigEntity result = service.deactivate(id);
            router.evictAllCache();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<TestResult> test(@PathVariable UUID id) {
        try {
            TestResult result = service.testConnection(id);
            // 测试成功后清除流式缓存，使新的 supportsStreaming 标记生效
            ragChatService.evictStreamingCache(id);
            router.evictCache(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api-formats")
    public ApiFormat[] formats() {
        return ApiFormat.values();
    }
}

