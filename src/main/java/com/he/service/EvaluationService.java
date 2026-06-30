package com.he.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.he.entity.*;
import dev.langchain4j.rag.content.Content;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagChatService ragChatService;
    private final RagConfigService ragConfigService;
    private final EvaluationTestsetRepository testsetRepo;
    private final EvaluationTestcaseRepository testcaseRepo;
    private final EvaluationBatchRepository batchRepo;
    private final EvaluationResultRepository resultRepo;

    public EvaluationService(RagChatService ragChatService, RagConfigService ragConfigService,
                             EvaluationTestsetRepository testsetRepo,
                             EvaluationTestcaseRepository testcaseRepo,
                             EvaluationBatchRepository batchRepo,
                             EvaluationResultRepository resultRepo) {
        this.ragChatService = ragChatService;
        this.ragConfigService = ragConfigService;
        this.testsetRepo = testsetRepo;
        this.testcaseRepo = testcaseRepo;
        this.batchRepo = batchRepo;
        this.resultRepo = resultRepo;
    }

    /**
     * 启动时导入种子测试集（幂等：已有数据不重复）。
     */
    @PostConstruct
    public void importSeedTestset() {
        try {
            if (testsetRepo.count() > 0) return; // 已有数据，跳过

            ClassPathResource resource = new ClassPathResource("eval/testset.json");
            if (!resource.exists()) return;

            JsonNode cases = MAPPER.readTree(resource.getInputStream());
            if (!cases.isArray() || cases.isEmpty()) return;

            EvaluationTestsetEntity testset = new EvaluationTestsetEntity();
            testset.setName("默认测试集");
            testset.setDescription("系统自动导入的种子测试集");
            testsetRepo.save(testset);

            int count = 0;
            for (JsonNode c : cases) {
                EvaluationTestcaseEntity tc = new EvaluationTestcaseEntity();
                tc.setTestsetId(testset.getId());
                tc.setQuestion(c.get("question").asText());
                tc.setExpectedFiles(c.get("expected_files").toString());
                tc.setTags(c.has("tags") ? c.get("tags").toString() : "[]");
                testcaseRepo.save(tc);
                count++;
            }
            log.info("导入种子测试集: {} 条用例", count);
        } catch (Exception e) {
            log.warn("种子测试集导入失败: {}", e.getMessage());
        }
    }

    /**
     * 启动异步评估任务，立即返回 batchId。
     */
    public UUID startAsync(UUID testsetId, int k) {
        List<EvaluationTestcaseEntity> cases = testcaseRepo.findByTestsetIdOrderByCreatedAtAsc(testsetId);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("测试集为空，无法执行评估");
        }

        EvaluationBatchEntity batch = new EvaluationBatchEntity();
        batch.setTestsetId(testsetId);
        batch.setConfigSnapshot(buildConfigSnapshot(k));
        batch.setStatus("running");
        batch.setTotalCases(cases.size());
        batch.setCompletedCases(0);
        batchRepo.save(batch);

        UUID batchId = batch.getId();
        log.info("启动评估任务 batchId={}, testsetId={}, cases={}, k={}", batchId, testsetId, cases.size(), k);

        // 异步执行
        UUID finalBatchId = batchId;
        Thread.startVirtualThread(() -> executeBatch(finalBatchId, cases, k));

        return batchId;
    }

    private void executeBatch(UUID batchId, List<EvaluationTestcaseEntity> cases, int k) {
        EvaluationBatchEntity batch = batchRepo.findById(batchId).orElseThrow();
        int totalHits = 0;
        int totalRetrieved = 0;
        double totalMrr = 0;
        long totalLatency = 0;

        try {
            for (int i = 0; i < cases.size(); i++) {
                EvaluationTestcaseEntity tc = cases.get(i);
                long start = System.nanoTime();

                // 执行检索
                List<Content> contents = ragChatService.retrieve(tc.getQuestion());
                List<String> retrievedFiles = extractFilePaths(contents, k);
                List<String> expectedFiles = parseJsonArray(tc.getExpectedFiles());

                // 计算指标
                Set<String> expectedSet = new HashSet<>(expectedFiles);
                int hits = 0;
                int firstHitRank = -1;
                for (int rank = 0; rank < retrievedFiles.size(); rank++) {
                    if (expectedSet.contains(retrievedFiles.get(rank))) {
                        hits++;
                        if (firstHitRank == -1) firstHitRank = rank + 1;
                    }
                }

                boolean hit = hits > 0;
                double mrr = firstHitRank > 0 ? 1.0 / firstHitRank : 0;
                long latencyMs = (System.nanoTime() - start) / 1_000_000;

                totalHits += hits;
                totalRetrieved += retrievedFiles.size();
                totalMrr += mrr;
                totalLatency += latencyMs;

                // 保存结果
                EvaluationResultEntity result = new EvaluationResultEntity();
                result.setBatchId(batchId);
                result.setTestcaseId(tc.getId());
                result.setQuestion(tc.getQuestion());
                result.setRetrievedFiles(MAPPER.writeValueAsString(retrievedFiles));
                result.setExpectedFiles(tc.getExpectedFiles());
                result.setHit(hit);
                result.setFirstHitRank(firstHitRank > 0 ? firstHitRank : null);
                result.setLatencyMs(latencyMs);
                resultRepo.save(result);

                // 更新进度
                batch.setCompletedCases(i + 1);
                batchRepo.save(batch);

                log.debug("评估 [{}/{}] '{}' → hit={}, rank={}, latency={}ms",
                        i + 1, cases.size(), tc.getQuestion(), hit,
                        firstHitRank > 0 ? firstHitRank : "N/A", latencyMs);
            }

            // 聚合指标
            int n = cases.size();
            batch.setPrecisionAtK(totalRetrieved > 0 ? (double) totalHits / totalRetrieved : 0);
            batch.setRecallScore(calculateRecall(batchId));
            batch.setMrr(totalMrr / n);
            batch.setHitRate((double) countHits(batchId) / n);
            batch.setAvgLatencyMs(totalLatency / n);
            batch.setStatus("completed");
            batch.setEvaluatedAt(Instant.now());
            batchRepo.save(batch);

            log.info("评估完成 batchId={}: P@{}={}, Recall={}, MRR={}, HitRate={}",
                    batchId, k, batch.getPrecisionAtK(), batch.getRecallScore(),
                    batch.getMrr(), batch.getHitRate());

        } catch (Exception e) {
            log.error("评估任务失败 batchId={}: {}", batchId, e.getMessage(), e);
            batch.setStatus("failed");
            batch.setErrorMessage(e.getMessage());
            batchRepo.save(batch);
        }
    }

    private List<String> extractFilePaths(List<Content> contents, int limit) {
        List<String> paths = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Content c : contents) {
            if (paths.size() >= limit) break;
            String path = c.textSegment().metadata().getString("file_path");
            if (path != null && !path.isBlank() && seen.add(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    private List<String> parseJsonArray(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode n : node) result.add(n.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private double calculateRecall(UUID batchId) {
        List<EvaluationResultEntity> results = resultRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
        if (results.isEmpty()) return 0;
        int totalExpected = 0;
        int totalHits = 0;
        for (EvaluationResultEntity r : results) {
            List<String> expected = parseJsonArray(r.getExpectedFiles());
            List<String> retrieved = parseJsonArray(r.getRetrievedFiles());
            Set<String> retrievedSet = new HashSet<>(retrieved);
            totalExpected += expected.size();
            for (String e : expected) {
                if (retrievedSet.contains(e)) totalHits++;
            }
        }
        return totalExpected > 0 ? (double) totalHits / totalExpected : 0;
    }

    private long countHits(UUID batchId) {
        return resultRepo.findByBatchIdOrderByCreatedAtAsc(batchId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getHit())).count();
    }

    private String buildConfigSnapshot(int k) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("k", k);
            snapshot.put("max_results", ragConfigService.getInt("max_results", 5));
            snapshot.put("min_score", ragConfigService.getDouble("min_score", 0.5));
            snapshot.put("enable_bm25", ragConfigService.getBoolean("enable_bm25", true));
            snapshot.put("enable_reranking", ragConfigService.getBoolean("enable_reranking", false));
            snapshot.put("enable_query_rewrite", ragConfigService.getBoolean("enable_query_rewrite", false));
            return MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ─── 查询方法 ───

    public EvaluationBatchEntity getBatch(UUID batchId) {
        return batchRepo.findById(batchId).orElse(null);
    }

    public EvaluationBatchEntity getLatestCompletedBatch() {
        return batchRepo.findFirstByStatusOrderByCreatedAtDesc("completed");
    }

    public List<EvaluationResultEntity> getBatchResults(UUID batchId) {
        return resultRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    public List<EvaluationTestsetEntity> listTestsets() {
        return testsetRepo.findAll();
    }

    public EvaluationTestsetEntity createTestset(String name, String description) {
        EvaluationTestsetEntity ts = new EvaluationTestsetEntity();
        ts.setName(name);
        ts.setDescription(description);
        return testsetRepo.save(ts);
    }

    public void deleteTestset(UUID id) {
        testcaseRepo.deleteByTestsetId(id);
        testsetRepo.deleteById(id);
    }

    public List<EvaluationTestcaseEntity> listTestcases(UUID testsetId) {
        return testcaseRepo.findByTestsetIdOrderByCreatedAtAsc(testsetId);
    }

    public EvaluationTestcaseEntity addTestcase(UUID testsetId, String question, String expectedFiles, String tags) {
        EvaluationTestcaseEntity tc = new EvaluationTestcaseEntity();
        tc.setTestsetId(testsetId);
        tc.setQuestion(question);
        tc.setExpectedFiles(expectedFiles);
        tc.setTags(tags);
        return testcaseRepo.save(tc);
    }

    public void deleteTestcase(UUID id) {
        testcaseRepo.deleteById(id);
    }
}
