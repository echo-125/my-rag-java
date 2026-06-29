package com.he.controller;

import com.he.service.IngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 入库控制器：接收前端传来的路径数组，触发入库流程，通过 SSE 返回进度。
 */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * POST /api/ingestion/start
     * 请求体：{ "paths": ["D:\\code\\project1", "D:\\docs"], "projectName": "my-project" }
     * 返回 SSE 流，实时推送处理进度。
     */
    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startIngestion(@RequestBody IngestionRequest request) {
        // SSE 超时 30 分钟（大项目入库可能耗时较长）
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        ingestionService.ingest(request.paths(), request.projectName(), emitter);
        return emitter;
    }

    /**
     * POST /api/ingestion/scan
     * 扫描路径下的文件，按扩展名聚合统计。
     * 请求体：{ "projects": [{"name": "项目名", "path": "绝对路径"}] }
     * 返回：{ "success": true, "extensions": [{"ext": ".md", "count": 45}] }
     */
    @PostMapping("/scan")
    public Map<String, Object> scanFiles(@RequestBody ScanRequest request) throws Exception {
        List<String> paths = request.projects().stream()
                .map(ProjectInfo::path)
                .toList();
        Map<String, Integer> extCounts = ingestionService.scanExtensions(paths);

        List<Map<String, Object>> extensions = extCounts.entrySet().stream()
                .map(e -> Map.<String, Object>of("ext", e.getKey(), "count", e.getValue()))
                .toList();

        return Map.of("success", true, "extensions", extensions);
    }

    /**
     * POST /api/ingestion/process
     * 带扩展名过滤的入库处理，SSE 流式返回进度。
     * 请求体：{ "projects": [...], "exts": [".java", ".md"] }
     */
    @PostMapping(value = "/process", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processFiles(@RequestBody ProcessRequest request) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        ingestionService.processWithFilter(request.projects(), request.exts(), emitter);
        return emitter;
    }

    /**
     * 入库请求体（旧版）。
     */
    public record IngestionRequest(List<String> paths, String projectName) {}

    /**
     * 扫描请求体。
     */
    public record ScanRequest(List<ProjectInfo> projects) {}

    /**
     * 处理请求体。
     */
    public record ProcessRequest(List<ProjectInfo> projects, List<String> exts) {}

    /**
     * 项目信息。
     */
    public record ProjectInfo(String name, String path) {}

    // ═══════════════════════════════════════════════════
    //  知识库清理端点
    // ═══════════════════════════════════════════════════

    /**
     * DELETE /api/ingestion/chunks/{projectName}
     * 清空指定项目的所有 chunks。
     */
    @DeleteMapping("/chunks/{projectName}")
    public Map<String, Object> clearChunksByProject(@PathVariable String projectName) {
        int deleted = ingestionService.clearChunksByProject(projectName);
        return Map.of("success", true, "deleted", deleted, "project", projectName);
    }

    /**
     * DELETE /api/ingestion/chunks
     * 清空整个知识库（所有项目的 chunks）。
     */
    @DeleteMapping("/chunks")
    public Map<String, Object> clearAllChunks() {
        int deleted = ingestionService.clearAllChunks();
        return Map.of("success", true, "deleted", deleted);
    }

    /**
     * GET /api/ingestion/chunks/{projectName}/count
     * 获取指定项目的 chunk 数量。
     */
    @GetMapping("/chunks/{projectName}/count")
    public Map<String, Object> countChunks(@PathVariable String projectName) {
        long count = ingestionService.countChunksByProject(projectName);
        return Map.of("project", projectName, "count", count);
    }
}

