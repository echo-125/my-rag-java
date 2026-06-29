package com.he.controller;

import com.he.entity.DocumentChunkStatsRepository;
import com.he.entity.QaHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 统计接口 —— 通过 JdbcTemplate 查询 document_chunks 表。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DocumentChunkStatsRepository chunkStats;
    private final QaHistoryRepository qaRepo;

    public DashboardController(DocumentChunkStatsRepository chunkStats, QaHistoryRepository qaRepo) {
        this.chunkStats = chunkStats;
        this.qaRepo = qaRepo;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        long totalChunks = chunkStats.count();
        long projectCount = chunkStats.countByProject().size();
        long fileCount = chunkStats.countByFilePath().size();
        return Map.of(
                "totalChunks", totalChunks,
                "projectCount", projectCount,
                "fileCount", fileCount
        );
    }

    @GetMapping("/language-stats")
    public List<Map<String, Object>> languageStats() {
        return chunkStats.countByLanguage();
    }

    @GetMapping("/project-stats")
    public List<Map<String, Object>> projectStats() {
        return chunkStats.countByProject();
    }

    @GetMapping("/recent-qa")
    public List<Map<String, Object>> recentQa() {
        return qaRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20)).stream()
                .map(qa -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", qa.getId());
                    m.put("question", qa.getQuestion());
                    m.put("answer", qa.getAnswer().length() > 200
                            ? qa.getAnswer().substring(0, 200) + "..."
                            : qa.getAnswer());
                    m.put("modelName", qa.getModelName());
                    m.put("createdAt", qa.getCreatedAt().toString());
                    m.put("date", qa.getCreatedAt().toString()); // 兼容前端 date 字段
                    return m;
                })
                .toList();
    }
}

