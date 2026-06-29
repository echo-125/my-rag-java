package com.he.controller;

import com.he.entity.RerankingConfigEntity;
import com.he.service.RerankingConfigService;
import com.he.service.RerankingConfigService.TestResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reranking-configs")
public class RerankingConfigController {

    private final RerankingConfigService service;

    public RerankingConfigController(RerankingConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<RerankingConfigEntity> list() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RerankingConfigEntity> getById(@PathVariable UUID id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public RerankingConfigEntity create(@RequestBody RerankingConfigEntity entity) {
        return service.save(entity);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RerankingConfigEntity> update(@PathVariable UUID id,
                                                       @RequestBody RerankingConfigEntity entity) {
        try {
            return ResponseEntity.ok(service.update(id, entity));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<RerankingConfigEntity> activate(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(service.activate(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<RerankingConfigEntity> deactivate(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(service.deactivate(id));
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
