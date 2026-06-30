package com.he.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写服务。
 * <p>
 * 用 LLM 将用户口语化/模糊的问题改写为更适合向量检索的精确查询，
 * prompt 中注入最近对话历史以解决指代问题。
 * <p>
 * 降级策略：LLM 调用失败时返回 null，由调用方 fallback 到原始 query。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String DEFAULT_REWRITE_PROMPT = """
            你是一个搜索查询优化器。根据对话历史和当前问题，生成一个更适合向量检索的查询。
            规则：
            1. 将指代词（"那个"、"它"、"刚才"、"上一个"）替换为具体名称
            2. 将口语化表达转为技术术语
            3. 只输出改写后的查询，不要解释、不要引号
            4. 如果问题已经足够清晰，原样返回
            """;

    private final SpringAiModelRouterService modelRouter;
    private final RagConfigService ragConfigService;

    public QueryRewriteService(SpringAiModelRouterService modelRouter, RagConfigService ragConfigService) {
        this.modelRouter = modelRouter;
        this.ragConfigService = ragConfigService;
    }

    /**
     * 改写查询。
     *
     * @param query        用户原始查询
     * @param recentHistory 最近对话历史（用于解决指代）
     * @return 改写后的查询，失败时返回 null
     */
    public String rewrite(String query, List<Message> recentHistory) {
        if (query == null || query.isBlank()) return query;

        try {
            String prompt = buildPrompt(query, recentHistory);
            ChatClient client = modelRouter.getActiveChatClient();
            String result = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (result == null || result.isBlank()) {
                log.warn("查询改写返回空结果，使用原始 query");
                return null;
            }

            // 清理 LLM 输出（去除可能的引号、前缀等）
            String rewritten = result.trim()
                    .replaceAll("^['\"]|['\"]$", "")
                    .replaceAll("^改写后的查询[：:]\\s*['\"]?|['\"]?$", "")
                    .replaceAll("^查询[：:]\\s*['\"]?|['\"]?$", "");

            return rewritten;

        } catch (Exception e) {
            log.warn("查询改写失败（降级使用原始 query）: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String query, List<Message> recentHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append(DEFAULT_REWRITE_PROMPT);

        String customPrompt = ragConfigService.getConfig("query_rewrite_prompt");
        if (customPrompt != null && !customPrompt.isBlank()) {
            sb.append("\n").append(customPrompt);
        }

        if (recentHistory != null && !recentHistory.isEmpty()) {
            sb.append("\n\n对话历史：\n");
            for (Message msg : recentHistory) {
                String role = msg instanceof UserMessage ? "用户" : "助手";
                String text = msg.getText();
                // 截取前 150 字避免 prompt 过长
                if (text.length() > 150) text = text.substring(0, 150) + "...";
                sb.append(role).append("：").append(text).append("\n");
            }
        }

        sb.append("\n当前问题：").append(query).append("\n改写后的查询：");
        return sb.toString();
    }
}
