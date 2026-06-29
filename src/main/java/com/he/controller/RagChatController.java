package com.he.controller;

import com.he.entity.QaHistoryEntity;
import com.he.entity.QaHistoryRepository;
import com.he.service.ConversationService;
import com.he.service.RagChatService;
import com.he.service.SpringAiModelRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 问答控制器。
 * 流式返回 AI 回答（SSE），同时将完整对话保存到 QA 历史。
 */
@RestController
@RequestMapping("/api/chat")
public class RagChatController {

    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final RagChatService ragChatService;
    private final QaHistoryRepository qaHistoryRepo;
    private final SpringAiModelRouterService modelRouter;
    private final ConversationService conversationService;

    public RagChatController(RagChatService ragChatService,
                             QaHistoryRepository qaHistoryRepo,
                             SpringAiModelRouterService modelRouter,
                             ConversationService conversationService) {
        this.ragChatService = ragChatService;
        this.qaHistoryRepo = qaHistoryRepo;
        this.modelRouter = modelRouter;
        this.conversationService = conversationService;
    }

    /**
     * POST /api/chat/stream — 流式问答。
     * SSE 格式：每行 {@code data: ...\n\n}，前端逐 token 拼接后 Markdown 渲染。
     * 流结束后自动保存问答到 QA 历史。
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        String query = request.query();
        String modelKey = request.modelKey();
        String modelName = resolveModelName(modelKey);

        // 若前端未传 sessionId，后端自动生成（兼容旧版本调用）
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = conversationService.createSession();
            log.info("前端未传 sessionId，后端自动创建: {}", sessionId);
        }

        AtomicInteger tokenCount = new AtomicInteger(0);

        Flux<String> stream = ragChatService.chat(query, modelKey, sessionId)
                .map(token -> {
                    // Spring 的 produces="text/event-stream" 会自动包装每个元素为 SSE data 行
                    // 上游 API 的 SSE 响应可能已经包含 "data: " 前缀，先剥掉
                    tokenCount.incrementAndGet();
                    return token.startsWith("data: ") ? token.substring(6) : token;
                })
                .doOnComplete(() -> {
                    log.info("聊天流结束: model={}, tokens={}", modelName, tokenCount.get());
                })
                .onErrorResume(e -> {
                    String errMsg = extractErrorMessage(e);
                    log.error("聊天流式错误: {}", errMsg, e);
                    return Flux.just("{\"error\":\"stream_error\"}");
                });

        return stream;
    }

    /**
     * POST /api/chat/save — 保存问答记录（由前端在流结束后调用）。
     */
    @PostMapping("/save")
    public void saveQa(@RequestBody QaSaveRequest request) {
        QaHistoryEntity entity = new QaHistoryEntity();
        entity.setQuestion(request.question());
        entity.setAnswer(request.answer());
        entity.setModelName(request.modelName());
        qaHistoryRepo.save(entity);
    }

    /**
     * @param query     用户输入
     * @param modelKey  模型配置 ID
     * @param sessionId 会话 ID（前端生成 UUID，为空时后端自动创建）
     */
    public record ChatRequest(String query, String modelKey, String sessionId) {}
    public record QaSaveRequest(String question, String answer, String modelName) {}

    private String resolveModelName(String modelKey) {
        try {
            return modelRouter.getAvailableModels().stream()
                    .filter(m -> m.id().equals(modelKey))
                    .map(SpringAiModelRouterService.ModelInfo::name)
                    .findFirst().orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String extractErrorMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) msg = cause.getClass().getSimpleName();
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        return msg;
    }
}

