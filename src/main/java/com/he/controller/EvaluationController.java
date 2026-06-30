package com.he.controller;

import com.he.entity.*;
import com.he.service.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) { this.service = service; }

    // ─── 评估执行 ───

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("testsetId") || body.get("testsetId") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "testsetId 必填"));
        }
        UUID testsetId;
        try {
            testsetId = UUID.fromString((String) body.get("testsetId"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "testsetId 格式非法"));
        }
        int k = body.containsKey("k") ? ((Number) body.get("k")).intValue() : 5;
        if (k <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "k 必须 > 0"));
        }
        UUID batchId = service.startAsync(testsetId, k);
        return ResponseEntity.accepted().body(Map.of("batchId", batchId.toString(), "status", "running"));
    }

    @GetMapping("/run/{batchId}/status")
    public ResponseEntity<Map<String, Object>> runStatus(@PathVariable UUID batchId) {
        EvaluationBatchEntity batch = service.getBatch(batchId);
        if (batch == null) return ResponseEntity.notFound().build();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", batch.getStatus());
        resp.put("progress", batch.getCompletedCases() + "/" + batch.getTotalCases());
        resp.put("totalCases", batch.getTotalCases());
        resp.put("completedCases", batch.getCompletedCases());
        if ("completed".equals(batch.getStatus())) {
            resp.put("result", buildReport(batch));
        }
        if (batch.getErrorMessage() != null) {
            resp.put("error", batch.getErrorMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> latestReport() {
        EvaluationBatchEntity batch = service.getLatestCompletedBatch();
        if (batch == null) return ResponseEntity.ok(Map.of("found", false));
        Map<String, Object> resp = buildReport(batch);
        resp.put("found", true);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/report/{batchId}")
    public ResponseEntity<Map<String, Object>> report(@PathVariable UUID batchId) {
        EvaluationBatchEntity batch = service.getBatch(batchId);
        if (batch == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(buildReport(batch));
    }

    private Map<String, Object> buildReport(EvaluationBatchEntity batch) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("batchId", batch.getId().toString());
        report.put("testsetId", batch.getTestsetId().toString());
        String testsetName = service.getTestsetById(batch.getTestsetId())
                .map(ts -> ts.getName()).orElse("未知");
        report.put("testsetName", testsetName);
        report.put("status", batch.getStatus());
        report.put("totalCases", batch.getTotalCases());
        report.put("completedCases", batch.getCompletedCases());
        report.put("precisionAtK", batch.getPrecisionAtK());
        report.put("recall", batch.getRecallScore());
        report.put("mrr", batch.getMrr());
        report.put("hitRate", batch.getHitRate());
        report.put("avgLatencyMs", batch.getAvgLatencyMs());
        report.put("configSnapshot", batch.getConfigSnapshot());
        report.put("evaluatedAt", batch.getEvaluatedAt() != null ? batch.getEvaluatedAt().toString() : null);
        report.put("results", service.getBatchResults(batch.getId()).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", r.getQuestion());
            m.put("retrievedFiles", r.getRetrievedFiles());
            m.put("expectedFiles", r.getExpectedFiles());
            m.put("hit", r.getHit());
            m.put("firstHitRank", r.getFirstHitRank());
            m.put("latencyMs", r.getLatencyMs());
            if (r.getParseWarning() != null) m.put("parseWarning", r.getParseWarning());
            return m;
        }).toList());
        return report;
    }

    // ─── 测试集管理 ───

    @GetMapping("/testset")
    public List<Map<String, Object>> listTestsets() {
        Map<UUID, Long> countMap = service.getCaseCountMap();
        return service.listTestsets().stream().map(ts -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ts.getId().toString());
            m.put("name", ts.getName());
            m.put("description", ts.getDescription());
            m.put("caseCount", countMap.getOrDefault(ts.getId(), 0L));
            m.put("createdAt", ts.getCreatedAt().toString());
            return m;
        }).toList();
    }

    @PostMapping("/testset")
    public ResponseEntity<Map<String, String>> createTestset(@RequestBody Map<String, String> body) {
        EvaluationTestsetEntity ts = service.createTestset(body.get("name"), body.get("description"));
        return ResponseEntity.ok(Map.of("id", ts.getId().toString(), "name", ts.getName()));
    }

    @DeleteMapping("/testset/{id}")
    public ResponseEntity<Void> deleteTestset(@PathVariable UUID id) {
        service.deleteTestset(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/testset/{id}/cases")
    public List<Map<String, Object>> listCases(@PathVariable UUID id) {
        return service.listTestcases(id).stream().map(tc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tc.getId().toString());
            m.put("question", tc.getQuestion());
            m.put("expectedFiles", tc.getExpectedFiles());
            m.put("tags", tc.getTags());
            return m;
        }).toList();
    }

    @PostMapping("/testset/{id}/cases")
    public ResponseEntity<Map<String, String>> addCase(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        EvaluationTestcaseEntity tc = service.addTestcase(id, body.get("question"),
                body.get("expectedFiles"), body.get("tags"));
        return ResponseEntity.ok(Map.of("id", tc.getId().toString()));
    }

    @DeleteMapping("/testcase/{id}")
    public ResponseEntity<Void> deleteCase(@PathVariable UUID id) {
        service.deleteTestcase(id);
        return ResponseEntity.noContent().build();
    }
}
