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

/**
 * 离线入库服务：遍历路径、切分文件、向量化、存储。
 * 通过 SseEmitter 实时推送详细的处理进度。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BATCH_SIZE = 100;

    private final FileSplitterRouter splitterRouter;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public IngestionService(FileSplitterRouter splitterRouter,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore) {
        this.splitterRouter = splitterRouter;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 异步处理多个路径，通过 SSE 推送详细进度。
     */
    public void ingest(List<String> paths, String projectName, SseEmitter emitter) {
        Thread.startVirtualThread(() -> {
            try {
                // 收集所有文件
                List<Path> allFiles = new ArrayList<>();
                for (String pathStr : paths) {
                    Path rootPath = Path.of(pathStr);
                    if (!Files.exists(rootPath)) {
                        sendEvent(emitter, "error", "路径不存在: " + pathStr, null);
                        continue;
                    }
                    allFiles.addAll(collectFiles(rootPath));
                }

                int totalFiles = allFiles.size();
                if (totalFiles == 0) {
                    sendEvent(emitter, "done", "未发现可处理文件", new ProgressStats(0, 0, 0, 0, 0, "", 100, "--"));
                    emitter.complete();
                    return;
                }

                sendEvent(emitter, "info", "发现 " + totalFiles + " 个可处理文件",
                        new ProgressStats(totalFiles, 0, 0, 0, 0, "", 0, calcEta(0, totalFiles, 0)));

                long startTime = System.currentTimeMillis();
                AtomicInteger processed = new AtomicInteger(0);
                AtomicInteger success = new AtomicInteger(0);
                AtomicInteger failed = new AtomicInteger(0);
                AtomicInteger skipped = new AtomicInteger(0);
                AtomicInteger totalChunks = new AtomicInteger(0);

                for (Path file : allFiles) {
                    int current = processed.incrementAndGet();
                    String fileName = file.getFileName().toString();
                    int pct = (int) ((current * 100L) / totalFiles);
                    long elapsed = System.currentTimeMillis() - startTime;
                    String eta = calcEta(current, totalFiles, elapsed);

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

                        int stored = embedAndStoreBatched(segments, fileName);
                        if (stored == 0) {
                            // 所有 chunk 向量化/存储均失败
                            failed.incrementAndGet();
                            sendEvent(emitter, "error", fileName + ": 向量化存储全部失败（" + segments.size() + " chunks）",
                                    new ProgressStats(totalFiles, current, success.get(), failed.get(),
                                            skipped.get(), fileName, pct, eta));
                        } else {
                            totalChunks.addAndGet(stored);
                            if (stored < segments.size()) {
                                // 部分失败：标记为警告，但仍算成功
                                sendEvent(emitter, "success",
                                        fileName + " (" + stored + "/" + segments.size() + " chunks，部分失败)",
                                        new ProgressStats(totalFiles, current, success.incrementAndGet(), failed.get(),
                                                skipped.get(), fileName, pct, eta));
                            } else {
                                success.incrementAndGet();
                                sendEvent(emitter, "success", fileName + " (" + stored + " chunks)",
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

                // 完成
                long totalTime = System.currentTimeMillis() - startTime;
                String timeStr = formatDuration(totalTime);
                String summary = String.format("处理完成！耗时 %s，成功 %d，失败 %d，跳过 %d，共 %d chunks",
                        timeStr, success.get(), failed.get(), skipped.get(), totalChunks.get());
                sendEvent(emitter, "done", summary,
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
                // 降级：逐条向量化
                for (TextSegment segment : batch) {
                    try {
                        Embedding emb = embeddingModel.embed(segment).content();
                        embeddingStore.add(emb, segment);
                        stored++;
                    } catch (Exception ex) {
                        log.warn("单条向量化失败: {} - {}", fileName, ex.getMessage());
                    }
                }
            }
        }
        return stored;
    }

    /** 递归收集支持的文件 */
    private List<Path> collectFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (splitterRouter.isSupported(file.getFileName().toString())) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("无法访问文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else if (Files.isRegularFile(root) && splitterRouter.isSupported(root.getFileName().toString())) {
            result.add(root);
        }
        return result;
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

    /** 发送 SSE 事件 */
    private void sendEvent(SseEmitter emitter, String status, String message, ProgressStats stats) throws IOException {
        emitter.send(SseEmitter.event()
                .name("progress")
                .data(new ProgressEvent(status, message, stats)));
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
