package com.insolu.rag.controller;

import com.insolu.rag.entity.ProjectConfigEntity;
import com.insolu.rag.entity.ProjectConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    @PostMapping
    public ProjectConfigEntity create(@RequestBody ProjectConfigEntity entity) {
        entity.setId(null);
        return repository.save(entity);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
