package com.insolu.rag.controller;

import com.insolu.rag.config.LangChain4jConfig.DatabaseBackedEmbeddingModel;
import com.insolu.rag.entity.EmbeddingConfigEntity;
import com.insolu.rag.service.EmbeddingConfigService;
import com.insolu.rag.service.EmbeddingConfigService.TestResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/embedding-configs")
public class EmbeddingConfigController {

    private final EmbeddingConfigService service;
    private final DatabaseBackedEmbeddingModel embeddingModel;

    public EmbeddingConfigController(EmbeddingConfigService service,
                                     DatabaseBackedEmbeddingModel embeddingModel) {
        this.service = service;
        this.embeddingModel = embeddingModel;
    }

    /** 获取所有配置（密钥脱敏） */
    @GetMapping
    public List<EmbeddingConfigEntity> list() {
        return service.findAll().stream()
                .map(service::toMaskedCopy)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmbeddingConfigEntity> getById(@PathVariable UUID id) {
        return service.findById(id)
                .map(e -> ResponseEntity.ok(service.toMaskedCopy(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public EmbeddingConfigEntity create(@RequestBody EmbeddingConfigEntity entity) {
        EmbeddingConfigEntity saved = service.toMaskedCopy(service.save(entity));
        embeddingModel.reset();
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmbeddingConfigEntity> update(@PathVariable UUID id,
                                                         @RequestBody EmbeddingConfigEntity entity) {
        try {
            EmbeddingConfigEntity result = service.toMaskedCopy(service.update(id, entity));
            embeddingModel.reset();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        embeddingModel.reset();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<EmbeddingConfigEntity> activate(@PathVariable UUID id) {
        try {
            EmbeddingConfigEntity result = service.toMaskedCopy(service.activate(id));
            embeddingModel.reset();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<EmbeddingConfigEntity> deactivate(@PathVariable UUID id) {
        try {
            EmbeddingConfigEntity result = service.toMaskedCopy(service.deactivate(id));
            embeddingModel.reset();
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<TestResult> test(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(service.testConnection(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
