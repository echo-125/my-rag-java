package com.he.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.he.entity.*;
import dev.langchain4j.rag.content.Content;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagChatService ragChatService;
    private final RagConfigService ragConfigService;
    private final LlmConfigService llmConfigService;
    private final EmbeddingConfigService embeddingConfigService;
    private final EvaluationTestsetRepository testsetRepo;
    private final EvaluationTestcaseRepository testcaseRepo;
    private final EvaluationBatchRepository batchRepo;
    private final EvaluationResultRepository resultRepo;

    public EvaluationService(RagChatService ragChatService, RagConfigService ragConfigService,
                             LlmConfigService llmConfigService,
                             EmbeddingConfigService embeddingConfigService,
                             EvaluationTestsetRepository testsetRepo,
                             EvaluationTestcaseRepository testcaseRepo,
                             EvaluationBatchRepository batchRepo,
                             EvaluationResultRepository resultRepo) {
        this.ragChatService = ragChatService;
        this.ragConfigService = ragConfigService;
        this.llmConfigService = llmConfigService;
        this.embeddingConfigService = embeddingConfigService;
        this.testsetRepo = testsetRepo;
        this.testcaseRepo = testcaseRepo;
        this.batchRepo = batchRepo;
        this.resultRepo = resultRepo;
    }

    /**
     * 启动时导入种子测试集（幂等：已有数据不重复）。
     */
    @EventListener(ApplicationReadyEvent.class)
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
            log.warn("种子测试集导入失败", e);
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

    public boolean cancelBatch(UUID batchId) {
        EvaluationBatchEntity batch = batchRepo.findById(batchId).orElse(null);
        if (batch == null || !"running".equals(batch.getStatus())) {
            return false;
        }
        batch.setCancelled(true);
        batch.setStatus("cancelled");
        batch.setErrorMessage("用户取消");
        batchRepo.save(batch);
        log.info("评估任务已取消 batchId={}", batchId);
        return true;
    }

    private void executeBatch(UUID batchId, List<EvaluationTestcaseEntity> cases, int k) {
        EvaluationBatchEntity batch = batchRepo.findById(batchId).orElseThrow();
        int totalHits = 0;
        int totalRetrieved = 0;
        int totalExpected = 0;
        int totalHitsForRecall = 0;
        int casesWithHit = 0;
        double totalMrr = 0;
        long totalLatency = 0;

        try {
            for (int i = 0; i < cases.size(); i++) {
                // 每轮检查取消标记
                batch = batchRepo.findById(batchId).orElseThrow();
                if (Boolean.TRUE.equals(batch.getCancelled())) {
                    batch.setCompletedCases(i);
                    batch.setStatus("cancelled");
                    batch.setErrorMessage("用户取消");
                    batchRepo.save(batch);
                    log.info("评估任务已取消 batchId={}", batchId);
                    return;
                }
                EvaluationTestcaseEntity tc = cases.get(i);
                long start = System.nanoTime();

                // 执行检索
                List<Content> contents = ragChatService.retrieve(tc.getQuestion());
                List<String> retrievedFiles = extractFilePaths(contents, k);

                // 解析期望文件（解析失败时标记警告）
                String parseWarning = null;
                List<String> expectedFiles;
                try {
                    expectedFiles = parseJsonArray(tc.getExpectedFiles());
                } catch (IllegalArgumentException e) {
                    log.warn("用例 '{}' 期望文件解析失败: {}", tc.getQuestion(), e.getMessage());
                    expectedFiles = List.of();
                    parseWarning = "期望文件数据格式异常";
                }

                // 计算指标（按文件名匹配，忽略路径差异）
                Set<String> expectedNames = expectedFiles.stream()
                        .map(EvaluationService::extractFileName).collect(Collectors.toSet());
                int hits = 0;
                int firstHitRank = -1;
                for (int rank = 0; rank < retrievedFiles.size(); rank++) {
                    if (expectedNames.contains(extractFileName(retrievedFiles.get(rank)))) {
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
                if (hit) casesWithHit++;

                // 计算 recall（按文件名归一化匹配）
                totalExpected += expectedFiles.size();
                Set<String> retrievedNames = retrievedFiles.stream()
                        .map(EvaluationService::extractFileName).collect(Collectors.toSet());
                for (String e : expectedFiles) {
                    if (retrievedNames.contains(extractFileName(e))) totalHitsForRecall++;
                }

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
                result.setParseWarning(parseWarning);
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
            batch.setRecallScore(totalExpected > 0 ? (double) totalHitsForRecall / totalExpected : 0);
            batch.setMrr(totalMrr / n);
            batch.setHitRate((double) casesWithHit / n);
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

    private static String extractFileName(String path) {
        if (path == null) return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private List<String> parseJsonArray(String json) throws IllegalArgumentException {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode n : node) result.add(n.asText());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + json, e);
        }
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
            llmConfigService.findActiveRaw().ifPresent(llm ->
                    snapshot.put("activeLlm", llm.getName() + " (" + llm.getModelName() + ")"));
            embeddingConfigService.findActive().ifPresent(emb ->
                    snapshot.put("activeEmbedding", emb.getName() + " (" + emb.getDimension() + "d)"));
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

    public List<EvaluationBatchEntity> getRecentCompleted(int limit) {
        return batchRepo.findByStatusOrderByCreatedAtDesc("completed")
                .stream().limit(limit).collect(Collectors.toList());
    }

    public List<EvaluationResultEntity> getBatchResults(UUID batchId) {
        return resultRepo.findByBatchIdOrderByCreatedAtAsc(batchId);
    }

    public List<EvaluationTestsetEntity> listTestsets() {
        return testsetRepo.findAll();
    }

    public java.util.Optional<EvaluationTestsetEntity> getTestsetById(UUID id) {
        return testsetRepo.findById(id);
    }

    /**
     * 批量获取各测试集的用例数量（避免 N+1）。
     */
    public Map<UUID, Long> getCaseCountMap() {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : testcaseRepo.countCasesGroupByTestset()) {
            map.put((UUID) row[0], (Long) row[1]);
        }
        return map;
    }

    public EvaluationTestsetEntity createTestset(String name, String description) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("测试集名称不能为空");
        EvaluationTestsetEntity ts = new EvaluationTestsetEntity();
        ts.setName(name.trim());
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
        if (question == null || question.isBlank()) throw new IllegalArgumentException("测试问题不能为空");
        if (expectedFiles == null || expectedFiles.isBlank()) throw new IllegalArgumentException("期望文件不能为空");
        EvaluationTestcaseEntity tc = new EvaluationTestcaseEntity();
        tc.setTestsetId(testsetId);
        tc.setQuestion(question.trim());
        tc.setExpectedFiles(expectedFiles);
        tc.setTags(tags);
        return testcaseRepo.save(tc);
    }

    public void deleteTestcase(UUID id) {
        testcaseRepo.deleteById(id);
    }

    public Map<String, Object> exportTestset(UUID testsetId) {
        EvaluationTestsetEntity ts = testsetRepo.findById(testsetId)
                .orElseThrow(() -> new IllegalArgumentException("测试集不存在"));
        List<EvaluationTestcaseEntity> cases = testcaseRepo.findByTestsetIdOrderByCreatedAtAsc(testsetId);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("name", ts.getName());
        export.put("description", ts.getDescription());
        List<Map<String, Object>> caseList = new ArrayList<>();
        for (EvaluationTestcaseEntity tc : cases) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("question", tc.getQuestion());
            c.put("expected_files", parseJsonArray(tc.getExpectedFiles()));
            c.put("tags", parseJsonArray(tc.getTags()));
            caseList.add(c);
        }
        export.put("cases", caseList);
        return export;
    }

    public Map<String, Object> importTestsetWithCount(Map<String, Object> data) {
        String name = (String) data.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("测试集名称不能为空");
        Object casesObj = data.get("cases");
        if (!(casesObj instanceof List)) throw new IllegalArgumentException("cases 字段缺失或格式错误");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) casesObj;

        EvaluationTestsetEntity ts = new EvaluationTestsetEntity();
        ts.setName(name.trim());
        ts.setDescription((String) data.get("description"));
        ts = testsetRepo.save(ts);

        List<EvaluationTestcaseEntity> toSave = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String question = (String) c.get("question");
            if (question == null || question.isBlank()) continue;
            EvaluationTestcaseEntity tc = new EvaluationTestcaseEntity();
            tc.setTestsetId(ts.getId());
            tc.setQuestion(question.trim());
            Object ef = c.get("expected_files");
            tc.setExpectedFiles(ef != null ? ef.toString() : "[]");
            Object tg = c.get("tags");
            tc.setTags(tg != null ? tg.toString() : "[]");
            toSave.add(tc);
        }
        testcaseRepo.saveAll(toSave);
        log.info("导入测试集 '{}'，{} 条用例", ts.getName(), toSave.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testset", ts);
        result.put("count", toSave.size());
        return result;
    }
}
