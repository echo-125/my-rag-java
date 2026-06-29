package com.he.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文管理服务（内存实现）。
 * <p>
 * 使用 {@link ConcurrentHashMap} 维护每个 session 的历史消息队列，
 * 通过滑动窗口策略保留最近 N 轮对话（默认 10 轮 = 20 条消息）。
 * <p>
 * 数据结构兼容 Spring AI 的 {@link Message} 接口，
 * 存储的条目为 {@link UserMessage} 和 {@link AssistantMessage} 交替排列。
 * <p>
 * <b>设计决策</b>：本服务手动管理消息列表，不依赖 LangChain4j 的 ChatMemory
 * （因为生成层使用 Spring AI）也不依赖 Spring AI 的 InMemoryChatMemory
 * （因为检索层使用 LangChain4j），保持体系结构清晰、零耦合。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 每个会话保留的最大对话轮数（一轮 = 一问 + 一答） */
    private final int maxRounds;

    /** 会话存储：sessionId → 历史消息队列（线程安全） */
    private final ConcurrentHashMap<String, LinkedList<Message>> sessions = new ConcurrentHashMap<>();

    public ConversationService(@Value("${app.conversation.max-rounds:10}") int maxRounds) {
        this.maxRounds = maxRounds;
        log.info("ConversationService 初始化完成，最大轮数: {}", maxRounds);
    }

    /**
     * 创建新会话，返回生成的 sessionId。
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new LinkedList<>());
        log.debug("创建新会话: {}", sessionId);
        return sessionId;
    }

    /**
     * 获取指定会话的历史消息列表（不可修改的副本）。
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表，若会话不存在则返回空列表
     */
    public List<Message> getHistory(String sessionId) {
        LinkedList<Message> queue = sessions.get(sessionId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    /**
     * 向指定会话添加一条用户消息。
     */
    public void addUserMessage(String sessionId, String content) {
        addMessage(sessionId, new UserMessage(content));
    }

    /**
     * 向指定会话添加一条 AI 助手消息。
     */
    public void addAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, new AssistantMessage(content));
    }

    /**
     * 向指定会话添加一条消息，超出轮数时自动移除最早的消息对。
     */
    private void addMessage(String sessionId, Message message) {
        LinkedList<Message> queue = sessions.computeIfAbsent(sessionId, k -> {
            log.debug("自动创建会话: {}", sessionId);
            return new LinkedList<>();
        });

        synchronized (queue) {
            queue.addLast(message);
            // 滑动窗口裁剪：保留最近 maxRounds 轮 = maxRounds * 2 条消息
            int maxMessages = maxRounds * 2;
            while (queue.size() > maxMessages) {
                queue.removeFirst();
            }
        }
    }

    /**
     * 清空指定会话的全部历史。
     */
    public void clearSession(String sessionId) {
        LinkedList<Message> queue = sessions.get(sessionId);
        if (queue != null) {
            synchronized (queue) {
                queue.clear();
            }
            log.debug("清空会话: {}", sessionId);
        }
    }

    /**
     * 删除指定会话。
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.debug("删除会话: {}", sessionId);
    }

    /**
     * 获取当前活跃会话数量。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 判断会话是否存在。
     */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 获取或创建会话——若存在则返回，否则创建新会话。
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId != null && sessions.containsKey(sessionId)) {
            return sessionId;
        }
        return createSession();
    }
}
