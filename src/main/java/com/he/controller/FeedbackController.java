package com.he.controller;

import com.he.service.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("qaHistoryId") || body.get("qaHistoryId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "qaHistoryId 必填"));
        }
        UUID qaHistoryId;
        try {
            qaHistoryId = UUID.fromString((String) body.get("qaHistoryId"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "qaHistoryId 格式非法"));
        }
        short rating = ((Number) body.get("rating")).shortValue();
        if (rating != 1 && rating != -1) {
            return ResponseEntity.badRequest().body(Map.of("error", "rating 只能为 1 或 -1"));
        }
        String comment = body.containsKey("comment") ? (String) body.get("comment") : null;
        service.submit(qaHistoryId, rating, comment);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(service.getStats());
    }

    @GetMapping("/low-quality")
    public ResponseEntity<?> lowQuality() {
        return ResponseEntity.ok(service.getLowQuality());
    }
}
