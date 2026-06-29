package com.he.service;

import com.he.splitter.FileSplitterRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import com.he.controller.IngestionController.ProjectInfo;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 离线入库服务：遍历路径、切分文件、向量化、存储。
 * 通过 SseEmitter 实时推送详细的处理进度。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BATCH_SIZE = 100;

    // 预编译噪声过滤正则（避免热循环中反复编译）
    private static final Pattern PAT_UPPER_UNDERSCORE = Pattern.compile("^[A-Z0-9_]+$");
    private static final Pattern PAT_PURE_DIGITS = Pattern.compile("^\\d+$");
    private static final Pattern PAT_PURE_URL = Pattern.compile("https?://\\S+");
    private static final Pattern PAT_PURE_PUNCT = Pattern.compile("^[\\s\\p{Punct}]+$");
    private static final Pattern PAT_CHINESE_CHAPTER = Pattern.compile(".*第[0-9一二三四五六七八九十]+章.*");
    private static final Pattern PAT_NUMBERED_SECTION = Pattern.compile(".*[0-9]+\\.[0-9]+\\s+\\S+.*");

    private final FileSplitterRouter splitterRouter;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagConfigService ragConfigService;
    private final com.he.entity.DocumentChunkStatsRepository chunkStatsRepo;
    private final com.he.entity.ProjectConfigRepository projectConfigRepo;

    public IngestionService(FileSplitterRouter splitterRouter,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            RagConfigService ragConfigService,
                            com.he.entity.DocumentChunkStatsRepository chunkStatsRepo,
                            com.he.entity.ProjectConfigRepository projectConfigRepo) {
        this.splitterRouter = splitterRouter;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.ragConfigService = ragConfigService;
        this.chunkStatsRepo = chunkStatsRepo;
        this.projectConfigRepo = projectConfigRepo;
    }

    /**
     * 异步处理多个路径，通过 SSE 推送详细进度。
     * <p>
     * 流程：
     * <ol>
     *   <li>预处理遍历：统计文件总数、支持/跳过数量，发送预处理结果到前端</li>
     *   <li>逐文件处理：切分 → 清洗 → 向量化存储</li>
     *   <li>长时间处理提醒（每 5 分钟发送"仍在处理"状态）</li>
     *   <li>处理完成后生成详细报告</li>
     * </ol>
     */
    public void ingest(List<String> paths, String projectName, SseEmitter emitter) {
        Thread.startVirtualThread(() -> {
            try {
                // 阶段 1：预处理遍历统计
                IngestionState state = preprocessFiles(paths, emitter);
                if (state == null) return;

                int totalFiles = state.supportedFiles().size();
                sendEvent(emitter, "info", "开始处理 " + totalFiles + " 个文件...",
                        new ProgressStats(totalFiles, 0, 0, 0, state.skippedCount(), "", 0, "计算中..."));

                // 阶段 2：逐文件处理
                long startTime = System.currentTimeMillis();
                ProcessingCounters counters = new ProcessingCounters();
                processFiles(state.supportedFiles(), projectName, emitter, counters, startTime);

                // 阶段 3：生成详细报告
                generateReport(state, counters, startTime, emitter);
                emitter.complete();

            } catch (Exception e) {
                log.error("入库过程异常", e);
                try {
                    sendEvent(emitter, "error", "入库异常: " + e.getMessage(), null);
                    emitter.completeWithError(e);
                } catch (IOException ignored) {}
            }
        });
    }

    /**
     * 阶段 1：预处理遍历，统计文件数量，发送预处理事件。
     *
     * @return 预处理结果，如果无支持文件则返回 null（已发送 done 事件并关闭 emitter）
     */
    private IngestionState preprocessFiles(List<String> paths, SseEmitter emitter) throws IOException {
        List<Path> allFiles = new ArrayList<>();
        int totalScanned = 0;
        int supportedCount = 0;
        int skippedCount = 0;
        int skippedDirFileCount = 0;

        for (String pathStr : paths) {
            Path rootPath = Path.of(pathStr);
            if (!Files.exists(rootPath)) {
                sendEvent(emitter, "error", "路径不存在: " + pathStr, null);
                continue;
            }
            ScanResult scanResult = scanAllFiles(rootPath);
            List<Path> files = scanResult.files();
            skippedDirFileCount += scanResult.skippedDirFileCount();
            totalScanned += files.size();
            long sup = files.stream().filter(f -> splitterRouter.isSupported(f.getFileName().toString())).count();
            supportedCount += (int) sup;
            skippedCount += (int) (files.size() - sup);
            allFiles.addAll(files);
        }

        List<Path> supportedFiles = allFiles.stream()
                .filter(f -> splitterRouter.isSupported(f.getFileName().toString()))
                .toList();

        int totalFiles = supportedFiles.size();
        if (totalFiles == 0) {
            sendEvent(emitter, "done", "未发现可处理文件（扫描 " + totalScanned + " 个，全部跳过）",
                    new ProgressStats(0, 0, 0, 0, 0, "", 100, "--"));
            emitter.complete();
            return null;
        }

        int totalSkipped = skippedCount + skippedDirFileCount;
        sendEvent(emitter, "preprocess",
                String.format("预处理完成：扫描 %d 个文件，支持 %d 个，跳过 %d 个（产物/二进制 %d + 构建目录 %d）",
                        totalScanned, supportedCount, totalSkipped, skippedCount, skippedDirFileCount),
                new ProgressStats(totalFiles, 0, 0, 0, totalSkipped, "", 0, "计算中..."));

        return new IngestionState(supportedFiles, totalScanned, supportedCount, skippedCount, skippedDirFileCount);
    }

    /**
     * 阶段 2：逐文件处理（切分 → 清洗 → 向量化存储），实时推送 SSE 进度。
     */
    private void processFiles(List<Path> supportedFiles, String projectName,
                              SseEmitter emitter, ProcessingCounters counters, long startTime) throws IOException {
        int totalFiles = supportedFiles.size();
        long lastHeartbeatTime = startTime;

        for (Path file : supportedFiles) {
            int current = counters.processed.incrementAndGet();
            String fileName = file.getFileName().toString();
            int pct = (int) ((current * 100L) / totalFiles);
            long elapsed = System.currentTimeMillis() - startTime;
            String eta = calcEta(current, totalFiles, elapsed);

            // 每 5 分钟发送心跳提醒
            if (System.currentTimeMillis() - lastHeartbeatTime > 300_000) {
                sendEvent(emitter, "heartbeat",
                        String.format("仍在处理... 已完成 %d/%d (%d%%)，耗时 %s",
                                current, totalFiles, pct, formatDuration(elapsed)),
                        new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                                counters.skipped.get(), fileName, pct, eta));
                lastHeartbeatTime = System.currentTimeMillis();
            }

            sendEvent(emitter, "processing", fileName,
                    new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                            counters.skipped.get(), fileName, pct, eta));

            try {
                processSingleFile(file, projectName, emitter, counters, totalFiles, current, fileName, pct, eta);
            } catch (Exception e) {
                counters.failed.incrementAndGet();
                sendEvent(emitter, "error", fileName + ": " + e.getMessage(),
                        new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                                counters.skipped.get(), fileName, pct, eta));
                log.error("处理文件失败: {}", fileName, e);
            }
        }
    }

    /**
     * 处理单个文件：切分 → 清洗 → 向量化存储。
     */
    private void processSingleFile(Path file, String projectName, SseEmitter emitter,
                                    ProcessingCounters counters, int totalFiles,
                                    int current, String fileName, int pct, String eta) throws IOException {
        List<TextSegment> segments = splitterRouter.split(file, projectName);
        if (segments.isEmpty()) {
            counters.skipped.incrementAndGet();
            sendEvent(emitter, "skip", "跳过（无有效内容）: " + fileName,
                    new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                            counters.skipped.get(), fileName, pct, eta));
            return;
        }

        List<TextSegment> cleanedSegments = cleanSegments(segments, fileName);
        if (cleanedSegments.isEmpty()) {
            counters.skipped.incrementAndGet();
            sendEvent(emitter, "skip", "跳过（清洗后无有效内容）: " + fileName,
                    new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                            counters.skipped.get(), fileName, pct, eta));
            return;
        }

        injectChunkIndex(cleanedSegments);

            int stored = embedAndStoreBatched(cleanedSegments, fileName);
            if (stored == 0) {
                counters.failed.incrementAndGet();
                sendEvent(emitter, "error", fileName + ": 向量化存储全部失败（" + cleanedSegments.size() + " chunks）",
                        new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                                counters.skipped.get(), fileName, pct, eta));
            } else {
                counters.totalChunks.addAndGet(stored);
                int filtered = segments.size() - cleanedSegments.size();
                String filterMsg = filtered > 0 ? "，过滤 " + filtered + " 个噪声 chunk" : "";
                boolean isPartialFailure = stored < cleanedSegments.size();
                counters.success.incrementAndGet();
                if (isPartialFailure) {
                    sendEvent(emitter, "success",
                            fileName + " (" + stored + "/" + cleanedSegments.size() + " chunks，部分失败" + filterMsg + ")",
                            new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                                    counters.skipped.get(), fileName, pct, eta));
                } else {
                    sendEvent(emitter, "success", fileName + " (" + stored + " chunks" + filterMsg + ")",
                            new ProgressStats(totalFiles, current, counters.success.get(), counters.failed.get(),
                                    counters.skipped.get(), fileName, pct, eta));
                }
            }
    }

    /**
     * 阶段 3：生成并发送详细处理报告。
     */
    private void generateReport(IngestionState state, ProcessingCounters counters,
                                long startTime, SseEmitter emitter) throws IOException {
        int totalFiles = state.supportedFiles().size();
        long totalTime = System.currentTimeMillis() - startTime;
        String timeStr = formatDuration(totalTime);
        double avgTimePerFile = totalFiles > 0 ? (totalTime / 1000.0) / totalFiles : 0;

        String summary = String.format("处理完成！耗时 %s，成功 %d，失败 %d，跳过 %d，共 %d chunks",
                timeStr, counters.success.get(), counters.failed.get(), counters.skipped.get(), counters.totalChunks.get());
        sendEvent(emitter, "done", summary,
                new ProgressStats(totalFiles, totalFiles, counters.success.get(), counters.failed.get(),
                        counters.skipped.get(), "", 100, "0s"));

        String report = String.format(
                "━━━ 处理报告 ━━━\n" +
                "扫描文件总数：%d\n" +
                "支持处理文件：%d\n" +
                "跳过文件（产物/二进制）：%d\n" +
                "跳过文件（构建目录树）：%d\n" +
                "─── 处理结果 ───\n" +
                "处理成功：%d\n" +
                "处理失败：%d\n" +
                "内容跳过：%d\n" +
                "生成 Chunk 总数：%d\n" +
                "总耗时：%s\n" +
                "平均每文件：%.2f 秒",
                state.totalScanned(), state.supportedCount(), state.skippedCount(), state.skippedDirFileCount(),
                counters.success.get(), counters.failed.get(), counters.skipped.get(),
                counters.totalChunks.get(), timeStr, avgTimePerFile);
        sendEvent(emitter, "report", report,
                new ProgressStats(totalFiles, totalFiles, counters.success.get(), counters.failed.get(),
                        counters.skipped.get(), "", 100, "0s"));
    }

    /**
     * 分批向量化并存储。
     * 将嵌入和存储分离：先统一嵌入，再逐批存储；存储失败时逐条重试，
     * 避免对已成功存储的 segment 重复嵌入。
     */
    private int embedAndStoreBatched(List<TextSegment> segments, String fileName) {
        int stored = 0;
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, segments.size());
            List<TextSegment> batch = segments.subList(i, end);

            // 步骤 1：统一嵌入
            List<Embedding> embeddings;
            try {
                embeddings = embeddingModel.embedAll(batch).content();
            } catch (Exception e) {
                log.error("批量嵌入失败 (batch {}-{}): {} - {}", i, end, fileName, e.getMessage());
                // 嵌入失败：逐条嵌入+存储
                for (int j = 0; j < batch.size(); j++) {
                    try {
                        Embedding emb = embeddingModel.embed(batch.get(j)).content();
                        embeddingStore.add(emb, batch.get(j));
                        stored++;
                    } catch (Exception ex) {
                        log.warn("单条嵌入失败 (index {}): {} - {}", i + j, fileName, ex.getMessage());
                    }
                }
                continue;
            }

            // 步骤 2：尝试批量存储
            try {
                embeddingStore.addAll(embeddings, batch);
                stored += batch.size();
            } catch (Exception e) {
                log.error("批量存储失败 (batch {}-{}): {} - {}", i, end, fileName, e.getMessage());
                // 存储失败：逐条存储（嵌入已完成，不再重复嵌入）
                for (int j = 0; j < batch.size(); j++) {
                    try {
                        embeddingStore.add(embeddings.get(j), batch.get(j));
                        stored++;
                    } catch (Exception ex) {
                        log.warn("单条存储失败 (index {}): {} - {}", i + j, fileName, ex.getMessage());
                    }
                }
            }
        }
        return stored;
    }

    /**
     * 清洗 segments：移除无意义的短文本、噪声。
     * 过滤规则：
     * 1. 超短无意义文本（如页码、水印名 "JH_Gamma"）
     * 2. 纯大写字母+下划线的水印/字体名
     * 3. 纯数字（页码）
     * 4. 目录页（包含大量点状分隔符）
     * 5. 页眉页脚水印（重复出现的公司名等）
     */
    List<TextSegment> cleanSegments(List<TextSegment> segments, String fileName) {
        // 从数据库动态读取清洗参数
        boolean enableNoiseFilter = ragConfigService.getBoolean("enable_noise_filter", true);
        if (!enableNoiseFilter) {
            return segments;
        }
        int noiseMinLength = ragConfigService.getInt("noise_min_length", 30);
        boolean filterPureNumbers = ragConfigService.getBoolean("filter_pure_numbers", true);

        List<TextSegment> cleaned = new ArrayList<>();
        int originalSize = segments.size();
        int filterShort = 0, filterWatermark = 0, filterDigit = 0, filterTOC = 0, filterHeader = 0;

        for (TextSegment segment : segments) {
            String text = segment.text().trim();
            String type = segment.metadata().getString("type");

            // 1. 过滤超短无意义文本（阈值从配置读取）
            //    代码和 Markdown 类型放宽：保留短方法/短内容，仅过滤纯标点
            String logSafeText = text.replace("\n", "\\n").replace("\r", "\\r");
            if (text.length() < noiseMinLength) {
                if ("code".equals(type) || "markdown".equals(type)) {
                    if (PAT_PURE_PUNCT.matcher(text).matches()) {
                        log.debug("过滤纯标点短文本 [{}]: '{}'", fileName, logSafeText);
                        filterShort++;
                        continue;
                    }
                    // 代码短方法/MD 短内容：保留
                    cleaned.add(segment);
                    continue;
                }
                log.debug("过滤短文本 [{}]: '{}'", fileName, logSafeText);
                filterShort++;
                continue;
            }

            // 2. 过滤纯大写字母+下划线+数字的水印/字体名
            if (PAT_UPPER_UNDERSCORE.matcher(text).matches()) {
                log.debug("过滤水印/字体名 [{}]: '{}'", fileName, logSafeText);
                filterWatermark++;
                continue;
            }

            // 3. 过滤纯数字（页码），仅在 filter_pure_numbers 启用时
            if (filterPureNumbers && PAT_PURE_DIGITS.matcher(text).matches()) {
                log.debug("过滤纯数字/页码 [{}]: '{}'", fileName, logSafeText);
                filterDigit++;
                continue;
            }

            // 3b. 过滤纯 URL
            if (PAT_PURE_URL.matcher(text).matches()) {
                log.debug("过滤纯 URL [{}]: '{}'", fileName, logSafeText);
                filterWatermark++;
                continue;
            }

            // 3c. 过滤纯标点符号/空白
            if (PAT_PURE_PUNCT.matcher(text).matches()) {
                log.debug("过滤纯标点 [{}]: '{}'", fileName, logSafeText);
                filterWatermark++;
                continue;
            }

            // 4. 过滤目录页（包含大量点状分隔符 "......"）
            if (isTableOfContents(text)) {
                log.debug("过滤目录页 [{}]: '{}...'", fileName, text.substring(0, Math.min(80, text.length())));
                filterTOC++;
                continue;
            }

            // 5. 过滤页眉页脚水印（短文本中包含重复的公司名/产品名）
            if (isHeaderFooterWatermark(text)) {
                log.debug("过滤页眉页脚 [{}]: '{}'", fileName, text.substring(0, Math.min(80, text.length())));
                filterHeader++;
                continue;
            }

            cleaned.add(segment);
        }

        int filteredCount = originalSize - cleaned.size();
        if (filteredCount > 0) {
            log.info("文本清洗 [{}]: {} → {} chunks，过滤 {} 个噪声 (短文本:{}, 水印:{}, 数字:{}, 目录:{}, 页眉:{})",
                    fileName, originalSize, cleaned.size(), filteredCount,
                    filterShort, filterWatermark, filterDigit, filterTOC, filterHeader);
        }

        return cleaned;
    }

    /**
     * 判断是否为目录页：包含大量点状分隔符（如 "......6"、"......22"）。
     * 点号占比超过 50% 才视为目录页；若文本中包含实际章节标题则保留。
     */
    private boolean isTableOfContents(String text) {
        long dotCount = text.chars().filter(c -> c == '.').count();
        if (dotCount <= text.length() * 0.50) {
            return false;
        }
        // 高点号占比，但包含章节标题格式 → 保留（非纯目录页）
        if (PAT_CHINESE_CHAPTER.matcher(text).matches()
                || PAT_NUMBERED_SECTION.matcher(text).matches()) {
            return false;
        }
        return true;
    }

    /**
     * 判断是否为页眉页脚水印：短文本中只包含公司名、产品名等重复内容
     */
    private boolean isHeaderFooterWatermark(String text) {
        // 常见的页眉页脚模式
        String[] watermarkPatterns = {
            "广州市享印畅链信息技术有限公司",
            "享印畅链",
            "版权所有",
            "机密",
            "Confidential"
        };

        // 如果文本很短（< 50 字符）且完全由水印内容组成（降低阈值）
        if (text.length() < 50) {
            for (String pattern : watermarkPatterns) {
                if (text.contains(pattern)) {
                    // 移除水印内容后，检查是否还有实质内容
                    String cleaned = text.replace(pattern, "").trim();
                    // 如果移除水印后剩余内容很少，说明是纯水印
                    if (cleaned.length() < 10) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 为 segments 注入 chunk_index 和 total_chunks，用于保证检索时的文档逻辑顺序。
     * 保留切分器设置的原始 start_line / end_line，仅新增 chunk_index 和 total_chunks 字段。
     * 注意：此方法会就地修改传入列表中的元素（替换 TextSegment），调用方需确保列表可变。
     */
    private void injectChunkIndex(List<TextSegment> segments) {
        int total = segments.size();
        for (int i = 0; i < total; i++) {
            TextSegment segment = segments.get(i);
            var meta = new dev.langchain4j.data.document.Metadata(segment.metadata().toMap());
            meta.put("chunk_index", String.valueOf(i));
            meta.put("total_chunks", String.valueOf(total));
            segments.set(i, TextSegment.from(segment.text(), meta));
        }
    }

    /** 入库流程共享状态 */
    private record IngestionState(
            List<Path> supportedFiles,
            int totalScanned,
            int supportedCount,
            int skippedCount,
            int skippedDirFileCount
    ) {}

    /** 处理计数器 */
    private static class ProcessingCounters {
        final AtomicInteger processed = new AtomicInteger(0);
        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicInteger skipped = new AtomicInteger(0);
        final AtomicInteger totalChunks = new AtomicInteger(0);
    }

    /** 扫描所有文件（不过滤），用于预处理统计 */
    /** 需跳过的目录名集合（引用 FileSplitterRouter 的共享常量，保持过滤规则一致） */
    private static final java.util.Set<String> SKIP_DIR_NAMES = FileSplitterRouter.SKIP_DIR_NAMES;

    /** 文件扫描结果 */
    private record ScanResult(List<Path> files, int skippedDirFileCount) {}

    private ScanResult scanAllFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger skippedDirFiles = new java.util.concurrent.atomic.AtomicInteger(0);
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    result.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("无法访问文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString().toLowerCase();
                    if (SKIP_DIR_NAMES.contains(dirName)) {
                        // 统计跳过的目录中的文件数（粗略估算，避免额外遍历）
                        try {
                            long count = Files.walk(dir).filter(Files::isRegularFile).count();
                            skippedDirFiles.addAndGet((int) count);
                        } catch (IOException ignored) {}
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } else if (Files.isRegularFile(root)) {
            result.add(root);
        }
        return new ScanResult(result, skippedDirFiles.get());
    }

    /** 计算预计剩余时间 */
    private static String calcEta(int current, int total, long elapsedMs) {
        if (current == 0 || elapsedMs == 0) return "计算中...";
        double avgMs = (double) elapsedMs / current;
        long remainingMs = (long) (avgMs * (total - current));
        return formatDuration(remainingMs);
    }

    /** 格式化时长 */
    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        seconds %= 60;
        if (minutes < 60) return minutes + "m " + seconds + "s";
        long hours = minutes / 60;
        minutes %= 60;
        return hours + "h " + minutes + "m";
    }

    /** 发送 SSE 事件（带 Emitter 状态检查，避免重复发送导致异常） */
    private void sendEvent(SseEmitter emitter, String status, String message, ProgressStats stats) throws IOException {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(new ProgressEvent(status, message, stats)));
        } catch (IllegalStateException e) {
            log.warn("Emitter 状态异常，跳过发送: status={}, message={}", status, message);
        }
    }

    /** SSE 进度事件 */
    public record ProgressEvent(String status, String message, ProgressStats stats) {}

    /** 详细进度统计 */
    public record ProgressStats(
            int totalFiles,
            int processedFiles,
            int successFiles,
            int failedFiles,
            int skippedFiles,
            String currentFile,
            int progressPercentage,
            String estimatedRemainingTime
    ) {}

    // ═══════════════════════════════════════════════════
    //  知识库清理
    // ═══════════════════════════════════════════════════

    /**
     * 清空指定项目的所有 chunks。
     * @return 删除的 chunk 数量
     */
    public int clearChunksByProject(String projectName) {
        int deleted = chunkStatsRepo.deleteByProjectName(projectName);
        log.info("清空项目 '{}' 的 chunks: 删除 {} 条", projectName, deleted);
        return deleted;
    }

    /**
     * 清空整个知识库（所有项目的 chunks）。
     * @return 删除的 chunk 数量
     */
    public int clearAllChunks() {
        int deleted = chunkStatsRepo.deleteAll();
        log.info("清空全部知识库: 删除 {} 条 chunks", deleted);
        return deleted;
    }

    /**
     * 获取指定项目的 chunk 数量。
     */
    public long countChunksByProject(String projectName) {
        return chunkStatsRepo.countByProjectName(projectName);
    }

    /**
     * 入库完成后更新项目状态。
     * @param projectName 项目名称
     * @param fileCount 处理的文件数
     * @param chunkCount 生成的 chunk 数
     */
    public void markProjectCompleted(String projectName, int fileCount, int chunkCount) {
        projectConfigRepo.findByName(projectName).ifPresent(entity -> {
            entity.setStatus("completed");
            entity.setIngestedAt(java.time.Instant.now());
            // 生成简单简介
            String desc = String.format("已入库 %d 个文件，生成 %d 个文档块。", fileCount, chunkCount);
            entity.setDescription(desc);
            projectConfigRepo.save(entity);
            log.info("项目 '{}' 状态更新为 completed", projectName);
        });
    }

    // ═══════════════════════════════════════════════════
    //  扫描校验端点
    // ═══════════════════════════════════════════════════

    /**
     * 扫描路径下的文件，按扩展名聚合统计（仅统计 isSupported 的文件）。
     * 返回 Map&lt;扩展名, 数量&gt;，按数量降序排列。
     */
    public java.util.Map<String, Integer> scanExtensions(List<String> paths) throws IOException {
        java.util.Map<String, Integer> extCounts = new java.util.TreeMap<>();

        for (String pathStr : paths) {
            Path rootPath = Path.of(pathStr);
            if (!Files.exists(rootPath)) {
                throw new IllegalArgumentException("路径不存在: " + pathStr);
            }
            ScanResult scanResult = scanAllFiles(rootPath);
            for (Path file : scanResult.files()) {
                String fileName = file.getFileName().toString();
                if (splitterRouter.isSupported(fileName)) {
                    String ext = getExtensionFromPath(fileName);
                    extCounts.merge(ext, 1, Integer::sum);
                }
            }
        }

        // 按数量降序排列
        return extCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .collect(java.util.LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        java.util.LinkedHashMap::putAll);
    }

    private String getExtensionFromPath(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    // ═══════════════════════════════════════════════════
    //  新增：带扩展名过滤的入库处理
    // ═══════════════════════════════════════════════════

    /**
     * 带扩展名过滤的入库流程，通过 SSE 推送前端期望的进度 JSON 格式。
     * <p>
     * SSE 数据格式: {@code {"current": N, "total": M, "currentFile": "xx.java", "status": "processing", "message": "..."}}
     */
    public void processWithFilter(List<ProjectInfo> projects, List<String> exts, SseEmitter emitter) {
        Thread.startVirtualThread(() -> {
            try {
                // 1. 扫描并过滤文件
                List<PathEntry> allSupportedFiles = new ArrayList<>();
                for (ProjectInfo project : projects) {
                    Path rootPath = Path.of(project.path());
                    if (!Files.exists(rootPath)) {
                        sendFilterEvent(emitter, "error", "路径不存在: " + project.path(), 0, 0, "", "error");
                        continue;
                    }
                    ScanResult scanResult = scanAllFiles(rootPath);
                    for (Path file : scanResult.files()) {
                        String fileName = file.getFileName().toString();
                        if (splitterRouter.isSupported(fileName)) {
                            String ext = getExtensionFromPath(fileName);
                            if (exts == null || exts.isEmpty() || exts.contains(ext)) {
                                allSupportedFiles.add(new PathEntry(file, project.name()));
                            }
                        }
                    }
                }

                int totalFiles = allSupportedFiles.size();
                if (totalFiles == 0) {
                    sendFilterEvent(emitter, "done", "未发现匹配的可处理文件", 0, 0, "", "done");
                    emitter.complete();
                    return;
                }

                sendFilterEvent(emitter, "info", "开始处理 " + totalFiles + " 个文件...", 0, totalFiles, "", "processing");

                // 2. 逐文件处理
                long startTime = System.currentTimeMillis();
                ProcessingCounters counters = new ProcessingCounters();

                for (PathEntry entry : allSupportedFiles) {
                    int current = counters.processed.incrementAndGet();
                    Path file = entry.path();
                    String fileName = file.getFileName().toString();
                    int pct = (int) ((current * 100L) / totalFiles);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String eta = calcEta(current, totalFiles, elapsed);

                    sendFilterEvent(emitter, "processing", fileName, current, totalFiles, fileName, "processing");

                    try {
                        List<TextSegment> segments = splitterRouter.split(file, entry.projectName());
                        if (segments.isEmpty()) {
                            counters.skipped.incrementAndGet();
                            sendFilterEvent(emitter, "skip", "跳过（无有效内容）: " + fileName, current, totalFiles, fileName, "skip");
                            continue;
                        }

                        List<TextSegment> cleanedSegments = cleanSegments(segments, fileName);
                        if (cleanedSegments.isEmpty()) {
                            counters.skipped.incrementAndGet();
                            sendFilterEvent(emitter, "skip", "跳过（清洗后无有效内容）: " + fileName, current, totalFiles, fileName, "skip");
                            continue;
                        }

                        injectChunkIndex(cleanedSegments);
                        int stored = embedAndStoreBatched(cleanedSegments, fileName);

                        if (stored == 0) {
                            counters.failed.incrementAndGet();
                            sendFilterEvent(emitter, "error", fileName + ": 向量化存储全部失败", current, totalFiles, fileName, "error");
                        } else {
                            counters.totalChunks.addAndGet(stored);
                            counters.success.incrementAndGet();
                            sendFilterEvent(emitter, "success", fileName + " (" + stored + " chunks)", current, totalFiles, fileName, "success");
                        }
                    } catch (Exception e) {
                        counters.failed.incrementAndGet();
                        sendFilterEvent(emitter, "error", fileName + ": " + e.getMessage(), current, totalFiles, fileName, "error");
                        log.error("处理文件失败: {}", fileName, e);
                    }
                }

                // 3. 完成
                long totalTime = System.currentTimeMillis() - startTime;
                String summary = String.format("处理完成！成功 %d，失败 %d，跳过 %d，共 %d chunks，耗时 %s",
                        counters.success.get(), counters.failed.get(), counters.skipped.get(),
                        counters.totalChunks.get(), formatDuration(totalTime));
                sendFilterEvent(emitter, "done", summary, totalFiles, totalFiles, "", "done");

                // 更新项目状态为已完成
                for (ProjectInfo project : projects) {
                    markProjectCompleted(project.name(), counters.success.get(), counters.totalChunks.get());
                }

                emitter.complete();

            } catch (Exception e) {
                log.error("入库过程异常", e);
                try {
                    sendFilterEvent(emitter, "error", "入库异常: " + e.getMessage(), 0, 0, "", "error");
                    emitter.completeWithError(e);
                } catch (IOException ignored) {}
            }
        });
    }

    /** 发送前端期望的进度事件格式 */
    private void sendFilterEvent(SseEmitter emitter, String status, String message,
                                  int current, int total, String currentFile, String msgType) throws IOException {
        try {
            var event = Map.of(
                    "current", current,
                    "total", total,
                    "currentFile", currentFile != null ? currentFile : "",
                    "status", status,
                    "message", message != null ? message : "",
                    "progressPercentage", total > 0 ? (int) ((current * 100L) / total) : 0,
                    "estimatedRemainingTime", ""
            );
            emitter.send(SseEmitter.event().name("progress").data(event));
        } catch (IllegalStateException e) {
            log.warn("Emitter 状态异常，跳过发送: status={}, message={}", status, message);
        }
    }

    /** 文件路径 + 所属项目名 */
    private record PathEntry(Path path, String projectName) {}
}

