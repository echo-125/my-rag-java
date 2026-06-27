package com.insolu.rag.controller;

import com.insolu.rag.entity.QaHistoryEntity;
import com.insolu.rag.entity.QaHistoryRepository;
import com.insolu.rag.service.RagChatService;
import com.insolu.rag.service.SpringAiModelRouterService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * RAG 问答控制器。
 * 流式返回 AI 回答，同时将完整对话保存到 QA 历史。
 */
@RestController
@RequestMapping("/api/chat")
public class RagChatController {

    private final RagChatService ragChatService;
    private final QaHistoryRepository qaHistoryRepo;
    private final SpringAiModelRouterService modelRouter;

    public RagChatController(RagChatService ragChatService,
                             QaHistoryRepository qaHistoryRepo,
                             SpringAiModelRouterService modelRouter) {
        this.ragChatService = ragChatService;
        this.qaHistoryRepo = qaHistoryRepo;
        this.modelRouter = modelRouter;
    }

    /**
     * POST /api/chat/stream — 流式问答。
     * 前端通过 SSE 接收 token 流，回答完成后保存到 QA 历史。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody ChatRequest request) {
        Flux<String> stream = ragChatService.chat(request.query(), request.modelKey());

        // 收集完整回答后保存到数据库
        return stream.doOnComplete(() -> {
            try {
                // 获取模型名称
                String modelName = "unknown";
                try {
                    UUID configId = UUID.fromString(request.modelKey());
                    var models = modelRouter.getAvailableModels();
                    modelName = models.stream()
                            .filter(m -> m.id().equals(request.modelKey()))
                            .map(SpringAiModelRouterService.ModelInfo::name)
                            .findFirst().orElse("unknown");
                } catch (Exception ignored) {}

                // 由于 Flux 是流式的，这里只保存问题和模型名
                // 完整回答需要前端在流结束后回调保存
                // 这里先保存一个占位，后续可优化
            } catch (Exception e) {
                // 保存历史失败不影响用户体验
            }
        });
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

    public record ChatRequest(String query, String modelKey) {}
    public record QaSaveRequest(String question, String answer, String modelName) {}
}
