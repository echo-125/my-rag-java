package com.he.controller;

import com.he.entity.ProjectConfigEntity;
import com.he.entity.ProjectConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目配置 CRUD 接口 —— 持久化入库项目的路径配置。
 */
@RestController
@RequestMapping("/api/project-configs")
public class ProjectConfigController {

    private final ProjectConfigRepository repository;

    public ProjectConfigController(ProjectConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ProjectConfigEntity> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public ProjectConfigEntity getById(@PathVariable UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
    }

    @PostMapping
    public ProjectConfigEntity create(@RequestBody ProjectConfigEntity entity) {
        entity.setId(null);
        entity.setStatus("pending");
        return repository.save(entity);
    }

    @PutMapping("/{id}")
    public ProjectConfigEntity update(@PathVariable UUID id, @RequestBody Map<String, Object> updates) {
        ProjectConfigEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));

        if (updates.containsKey("status")) {
            entity.setStatus((String) updates.get("status"));
        }
        if (updates.containsKey("description")) {
            entity.setDescription((String) updates.get("description"));
        }
        if (updates.containsKey("ingestedAt")) {
            entity.setIngestedAt(Instant.now());
        }
        return repository.save(entity);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

