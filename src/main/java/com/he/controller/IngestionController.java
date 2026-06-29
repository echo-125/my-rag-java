package com.he.controller;

import com.he.service.IngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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
     * 入库请求体。
     */
    public record IngestionRequest(List<String> paths, String projectName) {}
}

