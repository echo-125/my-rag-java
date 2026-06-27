package com.insolu.rag.service;

import com.insolu.rag.entity.LlmConfigEntity;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
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
 * 根据 LlmConfigEntity 构建 ChatModel 的统一工厂。
 * LlmConfigService（测试连接）和 SpringAiModelRouterService（路由）共用。
 */
@Component
public class ChatModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatModelBuilder.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public ChatModel build(LlmConfigEntity entity, String apiKey, Duration timeout) {
        Duration t = timeout != null ? timeout : DEFAULT_TIMEOUT;
        log.debug("构建 ChatModel: format={}, baseUrl={}, model={}, timeout={}",
                entity.getApiFormat(), entity.getBaseUrl(), entity.getModelName(), t);
        return switch (entity.getApiFormat()) {
            case openai_chat_completions -> buildOpenAi(entity, apiKey, t);
            case anthropic_messages -> buildAnthropic(entity, apiKey, t);
        };
    }

    private ChatModel buildOpenAi(LlmConfigEntity entity, String apiKey, Duration timeout) {
        log.debug("构建 OpenAI ChatModel: baseUrl={}, model={}", entity.getBaseUrl(), entity.getModelName());
        try {
            var clientOptions = ClientOptions.builder()
                    .apiKey(apiKey)
                    .baseUrl(entity.getBaseUrl())
                    .timeout(timeout)
                    .build();
            var client = new OpenAIClientImpl(clientOptions);
            var options = OpenAiChatOptions.builder()
                    .model(entity.getModelName())
                    .temperature(0.7)
                    .build();
            ChatModel model = OpenAiChatModel.builder()
                    .openAiClient(client)
                    .options(options)
                    .build();
            log.debug("OpenAI ChatModel 构建成功");
            return model;
        } catch (Exception e) {
            log.error("构建 OpenAI ChatModel 失败: baseUrl={}, model={}", entity.getBaseUrl(), entity.getModelName(), e);
            throw e;
        }
    }

    private ChatModel buildAnthropic(LlmConfigEntity entity, String apiKey, Duration timeout) {
        String baseUrl = entity.getBaseUrl() != null ? entity.getBaseUrl() : "https://api.anthropic.com";
        log.debug("构建 Anthropic ChatModel: baseUrl={}, model={}", baseUrl, entity.getModelName());
        try {
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

            // 设置模型名（支持自定义模型名，不仅限 Claude 预定义枚举）
            var chatOptions = org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                    .model(com.anthropic.models.messages.Model.of(entity.getModelName()))
                    .temperature(0.7)
                    .build();

            ChatModel model = AnthropicChatModel.builder()
                    .anthropicClient(anthropicClient)
                    .options(chatOptions)
                    .build();
            log.debug("Anthropic ChatModel 构建成功: model={}", entity.getModelName());
            return model;
        } catch (Exception e) {
            log.error("构建 Anthropic ChatModel 失败: baseUrl={}, model={}", baseUrl, entity.getModelName(), e);
            throw e;
        }
    }
}
