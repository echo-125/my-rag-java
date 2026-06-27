package com.insolu.rag.service;

import com.insolu.rag.entity.LlmConfigEntity;
import com.anthropic.backends.AnthropicBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.http.okhttp.SpringAiAnthropicHttpClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ChatModel 构建工厂。
 * 根据数据库配置的 apiFormat 选择对应的客户端：
 * - openai_chat_completions → OpenAiChatModel
 * - anthropic_messages      → AnthropicChatModel
 */
@Component
public class ChatModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatModelBuilder.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public ChatModel build(LlmConfigEntity entity, String apiKey, Duration timeout) {
        Duration t = timeout != null ? timeout : DEFAULT_TIMEOUT;
        log.info("构建 ChatModel: format={}, baseUrl={}, model={}", entity.getApiFormat(), entity.getBaseUrl(), entity.getModelName());
        return switch (entity.getApiFormat()) {
            case openai_chat_completions -> buildOpenAi(entity, apiKey, t);
            case anthropic_messages -> buildAnthropic(entity, apiKey, t);
        };
    }

    private ChatModel buildOpenAi(LlmConfigEntity entity, String apiKey, Duration timeout) {
        log.info("使用 OpenAI 兼容客户端: baseUrl={}, model={}", entity.getBaseUrl(), entity.getModelName());
        var options = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(entity.getBaseUrl())
                .model(entity.getModelName())
                .timeout(timeout)
                .temperature(0.7)
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }

    private ChatModel buildAnthropic(LlmConfigEntity entity, String apiKey, Duration timeout) {
        String baseUrl = entity.getBaseUrl() != null ? entity.getBaseUrl() : "https://api.anthropic.com";
        log.info("使用 Anthropic 客户端: baseUrl={}, model={}", baseUrl, entity.getModelName());
        var backend = AnthropicBackend.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        var httpClient = SpringAiAnthropicHttpClient.builder()
                .backend(backend)
                .timeout(timeout)
                .build();
        var anthropicClientOptions = com.anthropic.core.ClientOptions.builder()
                .httpClient(httpClient)
                .timeout(timeout)
                .build();
        var anthropicClient = new com.anthropic.client.AnthropicClientImpl(anthropicClientOptions);
        var chatOptions = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                .model(com.anthropic.models.messages.Model.of(entity.getModelName()))
                .temperature(0.7)
                .build();
        return AnthropicChatModel.builder()
                .anthropicClient(anthropicClient)
                .options(chatOptions)
                .build();
    }
}
