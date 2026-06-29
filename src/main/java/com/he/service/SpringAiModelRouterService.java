package com.he.service;

import com.he.entity.LlmConfigEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI 动态模型路由服务。
 * 优先从数据库 llm_config 表读取激活的配置，提供 ChatClient。
 */
@Service
public class SpringAiModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelRouterService.class);

    private final LlmConfigService llmConfigService;
    private final ChatModelBuilder chatModelBuilder;
    private final Map<UUID, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<UUID, ChatClient> chatClientCache = new ConcurrentHashMap<>();

    public SpringAiModelRouterService(LlmConfigService llmConfigService, ChatModelBuilder chatModelBuilder) {
        this.llmConfigService = llmConfigService;
        this.chatModelBuilder = chatModelBuilder;
    }

    /**
     * 获取指定配置的 ChatClient（按 ID）。
     */
    public ChatClient getChatClient(UUID configId) {
        return chatClientCache.computeIfAbsent(configId, id -> {
            ChatModel model = getOrCreateChatModel(id);
            return ChatClient.create(model);
        });
    }

    /**
     * 获取当前激活配置的 ChatClient（无参，使用默认激活模型）。
     */
    public ChatClient getActiveChatClient() {
        LlmConfigEntity active = llmConfigService.findActiveRaw()
                .orElseThrow(() -> new IllegalStateException("没有激活的 LLM 配置，请先在 LLM 配置页面激活一个模型"));
        return getChatClient(active.getId());
    }

    /**
     * 获取所有可用配置的名称列表（供前端下拉框）。
     */
    public List<ModelInfo> getAvailableModels() {
        return llmConfigService.findActive().stream()
                .map(e -> new ModelInfo(e.getId().toString(), e.getName(), e.getModelName()))
                .toList();
    }

    /**
     * 配置变更后清除缓存，下次使用时重建。
     */
    public void evictCache(UUID configId) {
        chatModelCache.remove(configId);
        chatClientCache.remove(configId);
    }

    public void evictAllCache() {
        chatModelCache.clear();
        chatClientCache.clear();
    }

    private ChatModel getOrCreateChatModel(UUID configId) {
        return chatModelCache.computeIfAbsent(configId, id -> {
            LlmConfigEntity entity = llmConfigService.findRawById(id)
                    .orElseThrow(() -> new IllegalArgumentException("LLM 配置不存在: " + id));
            return chatModelBuilder.build(entity, entity.getApiKey(), null);
        });
    }

    /** 前端下拉框用的简化模型信息 */
    public record ModelInfo(String id, String name, String modelName) {}
}

