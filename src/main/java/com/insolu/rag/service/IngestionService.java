package com.insolu.rag.service;

import com.insolu.rag.splitter.FileSplitterRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
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

    public IngestionService(FileSplitterRouter splitterRouter,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            RagConfigService ragConfigService) {
        this.splitterRouter = splitterRouter;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.ragConfigService = ragConfigService;
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
                // ─── 阶段 1：预处理遍历统计 ───
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

                // 只保留支持的文件
                List<Path> supportedFiles = allFiles.stream()
                        .filter(f -> splitterRouter.isSupported(f.getFileName().toString()))
                        .toList();

                int totalFiles = supportedFiles.size();
                if (totalFiles == 0) {
                    sendEvent(emitter, "done", "未发现可处理文件（扫描 " + totalScanned + " 个，全部跳过）",
                            new ProgressStats(0, 0, 0, 0, 0, "", 100, "--"));
                    emitter.complete();
                    return;
                }

                // 发送预处理统计
                int totalSkipped = skippedCount + skippedDirFileCount;
                sendEvent(emitter, "preprocess",
                        String.format("预处理完成：扫描 %d 个文件，支持 %d 个，跳过 %d 个（产物/二进制 %d + 构建目录 %d）",
                                totalScanned, supportedCount, totalSkipped, skippedCount, skippedDirFileCount),
                        new ProgressStats(totalFiles, 0, 0, 0, totalSkipped, "", 0, "计算中..."));
                sendEvent(emitter, "info", "开始处理 " + totalFiles + " 个文件...",
                        new ProgressStats(totalFiles, 0, 0, 0, skippedCount, "", 0, "计算中..."));

                // ─── 阶段 2：逐文件处理 ───
                long startTime = System.currentTimeMillis();
                long lastHeartbeatTime = startTime;
                AtomicInteger processed = new AtomicInteger(0);
                AtomicInteger success = new AtomicInteger(0);
                AtomicInteger failed = new AtomicInteger(0);
                AtomicInteger skipped = new AtomicInteger(0);
                AtomicInteger totalChunks = new AtomicInteger(0);

                for (Path file : supportedFiles) {
                    int current = processed.incrementAndGet();
                    String fileName = file.getFileName().toString();
                    int pct = (int) ((current * 100L) / totalFiles);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String eta = calcEta(current, totalFiles, elapsed);

                    // 每 5 分钟发送心跳提醒
                    if (System.currentTimeMillis() - lastHeartbeatTime > 300_000) {
                        sendEvent(emitter, "heartbeat",
                                String.format("仍在处理... 已完成 %d/%d (%d%%)，耗时 %s",
                                        current, totalFiles, pct, formatDuration(elapsed)),
                                new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                        skipped.get(), fileName, pct, eta));
                        lastHeartbeatTime = System.currentTimeMillis();
                    }

                    // 处理中状态
                    sendEvent(emitter, "processing", fileName,
                            new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                    skipped.get(), fileName, pct, eta));

                    try {
                        List<TextSegment> segments = splitterRouter.split(file, projectName);
                        if (segments.isEmpty()) {
                            skipped.incrementAndGet();
                            sendEvent(emitter, "skip", "跳过（无有效内容）: " + fileName,
                                    new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                            skipped.get(), fileName, pct, eta));
                            continue;
                        }

                        List<TextSegment> cleanedSegments = cleanSegments(segments, fileName);
                        if (cleanedSegments.isEmpty()) {
                            skipped.incrementAndGet();
                            sendEvent(emitter, "skip", "跳过（清洗后无有效内容）: " + fileName,
                                    new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                            skipped.get(), fileName, pct, eta));
                            continue;
                        }

                        injectChunkIndex(cleanedSegments);

                        int stored = embedAndStoreBatched(cleanedSegments, fileName);
                        if (stored == 0) {
                            failed.incrementAndGet();
                            sendEvent(emitter, "error", fileName + ": 向量化存储全部失败（" + cleanedSegments.size() + " chunks）",
                                    new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                            skipped.get(), fileName, pct, eta));
                        } else {
                            totalChunks.addAndGet(stored);
                            int filtered = segments.size() - cleanedSegments.size();
                            String filterMsg = filtered > 0 ? "，过滤 " + filtered + " 个噪声 chunk" : "";
                            if (stored < cleanedSegments.size()) {
                                sendEvent(emitter, "success",
                                        fileName + " (" + stored + "/" + cleanedSegments.size() + " chunks，部分失败" + filterMsg + ")",
                                        new ProgressStats(totalFiles, current, success.incrementAndGet(), failed.get(),
                                                skipped.get(), fileName, pct, eta));
                            } else {
                                success.incrementAndGet();
                                sendEvent(emitter, "success", fileName + " (" + stored + " chunks" + filterMsg + ")",
                                        new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                                skipped.get(), fileName, pct, eta));
                            }
                        }

                    } catch (Exception e) {
                        failed.incrementAndGet();
                        sendEvent(emitter, "error", fileName + ": " + e.getMessage(),
                                new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                        skipped.get(), fileName, pct, eta));
                        log.error("处理文件失败: {}", fileName, e);
                    }
                }

                // ─── 阶段 3：生成详细报告 ───
                long totalTime = System.currentTimeMillis() - startTime;
                String timeStr = formatDuration(totalTime);
                double avgTimePerFile = totalFiles > 0 ? (totalTime / 1000.0) / totalFiles : 0;

                String summary = String.format("处理完成！耗时 %s，成功 %d，失败 %d，跳过 %d，共 %d chunks",
                        timeStr, success.get(), failed.get(), skipped.get(), totalChunks.get());
                sendEvent(emitter, "done", summary,
                        new ProgressStats(totalFiles, totalFiles, success.get(), failed.get(),
                                skipped.get(), "", 100, "0s"));

                // 发送详细报告
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
                        totalScanned, supportedCount, skippedCount, skippedDirFileCount,
                        success.get(), failed.get(), skipped.get(),
                        totalChunks.get(), timeStr, avgTimePerFile);
                sendEvent(emitter, "report", report,
                        new ProgressStats(totalFiles, totalFiles, success.get(), failed.get(),
                                skipped.get(), "", 100, "0s"));

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

    /** 分批向量化并存储 */
    private int embedAndStoreBatched(List<TextSegment> segments, String fileName) {
        int stored = 0;
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, segments.size());
            List<TextSegment> batch = segments.subList(i, end);
            try {
                var response = embeddingModel.embedAll(batch);
                List<Embedding> embeddings = response.content();
                embeddingStore.addAll(embeddings, batch);
                stored += batch.size();
            } catch (Exception e) {
                log.error("批量向量化失败 (batch {}-{}): {} - {}", i, end, fileName, e.getMessage());
                // 降级：逐条向量化并存储，避免重复向量化已成功的部分
                for (int j = 0; j < batch.size(); j++) {
                    TextSegment segment = batch.get(j);
                    try {
                        Embedding emb = embeddingModel.embed(segment).content();
                        embeddingStore.add(emb, segment);
                        stored++;
                    } catch (Exception ex) {
                        log.warn("单条向量化失败 (index {}): {} - {}", i + j, fileName, ex.getMessage());
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
            if (text.length() < noiseMinLength) {
                if ("code".equals(type) || "markdown".equals(type)) {
                    if (PAT_PURE_PUNCT.matcher(text).matches()) {
                        log.debug("过滤纯标点短文本 [{}]: '{}'", fileName, text);
                        filterShort++;
                        continue;
                    }
                    // 代码短方法/MD 短内容：保留
                    cleaned.add(segment);
                    continue;
                }
                log.debug("过滤短文本 [{}]: '{}'", fileName, text);
                filterShort++;
                continue;
            }

            // 2. 过滤纯大写字母+下划线+数字的水印/字体名
            if (PAT_UPPER_UNDERSCORE.matcher(text).matches()) {
                log.debug("过滤水印/字体名 [{}]: '{}'", fileName, text);
                filterWatermark++;
                continue;
            }

            // 3. 过滤纯数字（页码），仅在 filter_pure_numbers 启用时
            if (filterPureNumbers && PAT_PURE_DIGITS.matcher(text).matches()) {
                log.debug("过滤纯数字/页码 [{}]: '{}'", fileName, text);
                filterDigit++;
                continue;
            }

            // 3b. 过滤纯 URL
            if (PAT_PURE_URL.matcher(text).matches()) {
                log.debug("过滤纯 URL [{}]: '{}'", fileName, text);
                filterWatermark++;
                continue;
            }

            // 3c. 过滤纯标点符号/空白
            if (PAT_PURE_PUNCT.matcher(text).matches()) {
                log.debug("过滤纯标点 [{}]: '{}'", fileName, text);
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
     * 将自增索引写入 Metadata 的 chunk_index 字段。
     * 注意：此方法会就地修改传入列表中的元素（替换 TextSegment），调用方需确保列表可变。
     */
    private void injectChunkIndex(List<TextSegment> segments) {
        int total = segments.size();
        for (int i = 0; i < total; i++) {
            TextSegment segment = segments.get(i);
            var meta = new dev.langchain4j.data.document.Metadata(segment.metadata().toMap());
            meta.put("start_line", String.valueOf(i + 1));
            meta.put("end_line", String.valueOf(i + 1));
            meta.put("chunk_index", String.valueOf(i));
            meta.put("total_chunks", String.valueOf(total));
            segments.set(i, TextSegment.from(segment.text(), meta));
        }
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
}
