package com.he.service;

import com.he.entity.ChatMessageEntity;
import com.he.entity.ChatMessageRepository;
import com.he.entity.ChatSessionEntity;
import com.he.entity.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文管理服务（内存 + DB 双写）。
 * <p>
 * 内存缓存保证低延迟，DB 持久化保证重启不丢失。
 * 读取时优先命中内存缓存，未命中则从 DB 加载。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final int maxRounds;
    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;

    /** 内存缓存：sessionId → 历史消息队列 */
    private final ConcurrentHashMap<String, LinkedList<Message>> sessions = new ConcurrentHashMap<>();

    public ConversationService(
            @Value("${app.conversation.max-rounds:10}") int maxRounds,
            ChatSessionRepository sessionRepo,
            ChatMessageRepository messageRepo) {
        this.maxRounds = maxRounds;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
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
     * 优先从内存加载，未命中则从 DB 加载到内存。
     */
    public List<Message> getHistory(String sessionId) {
        LinkedList<Message> queue = sessions.get(sessionId);
        if (queue == null) {
            // 内存没有 → 从 DB 加载
            queue = loadFromDb(sessionId);
            if (queue != null) {
                sessions.put(sessionId, queue);
            }
            return queue != null ? List.copyOf(queue) : List.of();
        }
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    /**
     * 向指定会话添加一条用户消息（双写）。
     */
    public void addUserMessage(String sessionId, String content) {
        addMessage(sessionId, "user", new UserMessage(content), content);
    }

    /**
     * 向指定会话添加一条 AI 助手消息（双写）。
     */
    public void addAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, "assistant", new AssistantMessage(content), content);
    }

    /**
     * 获取所有会话列表（按更新时间倒序）。
     */
    public List<ChatSessionEntity> listSessions() {
        return sessionRepo.findAllByOrderByUpdatedAtDesc();
    }

    /**
     * 获取会话的所有消息。
     */
    public List<ChatMessageEntity> getSessionMessages(UUID sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /**
     * 删除会话及其所有消息（DB + 内存）。
     */
    @Transactional
    public void deleteSession(UUID sessionId) {
        messageRepo.deleteBySessionId(sessionId);
        sessionRepo.deleteById(sessionId);
        sessions.remove(sessionId.toString());
        log.info("删除会话: {}", sessionId);
    }

    /**
     * 判断会话是否存在（DB 或内存）。
     */
    public boolean hasSession(String sessionId) {
        if (sessions.containsKey(sessionId)) return true;
        try {
            return sessionRepo.findById(UUID.fromString(sessionId)).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取或创建会话——若存在则返回，否则创建新会话。
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId != null && hasSession(sessionId)) {
            return sessionId;
        }
        return createSession();
    }

    // ─── 内部方法 ───

    private void addMessage(String sessionId, String role, Message message, String rawContent) {
        LinkedList<Message> queue = sessions.computeIfAbsent(sessionId, k -> {
            log.debug("自动创建会话: {}", sessionId);
            return new LinkedList<>();
        });

        // 1. 写内存
        synchronized (queue) {
            queue.addLast(message);
            int maxMessages = maxRounds * 2;
            while (queue.size() > maxMessages) {
                queue.removeFirst();
            }
        }

        // 2. 写 DB（异步容错）
        try {
            UUID sessionUuid = UUID.fromString(sessionId);

            // 首条用户消息 → 创建会话并设标题
            if ("user".equals(role) && queue.size() == 1) {
                ChatSessionEntity session = new ChatSessionEntity();
                session.setId(sessionUuid);
                String title = rawContent.length() > 30 ? rawContent.substring(0, 30) + "..." : rawContent;
                session.setTitle(title);
                sessionRepo.save(session);
            }

            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setSessionId(sessionUuid);
            entity.setRole(role);
            entity.setContent(rawContent);
            messageRepo.save(entity);

            // 更新会话的 updatedAt
            sessionRepo.findById(sessionUuid).ifPresent(s -> {
                s.setUpdatedAt(java.time.Instant.now());
                sessionRepo.save(s);
            });
        } catch (Exception e) {
            log.warn("消息持久化失败（内存已写入）: {}", e.getMessage());
        }
    }

    /**
     * 从 DB 加载会话历史到内存。
     */
    private LinkedList<Message> loadFromDb(String sessionId) {
        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            List<ChatMessageEntity> messages = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionUuid);
            if (messages.isEmpty()) return null;

            LinkedList<Message> queue = new LinkedList<>();
            for (ChatMessageEntity entity : messages) {
                if ("user".equals(entity.getRole())) {
                    queue.add(new UserMessage(entity.getContent()));
                } else if ("assistant".equals(entity.getRole())) {
                    queue.add(new AssistantMessage(entity.getContent()));
                }
            }

            // 滑动窗口裁剪
            int maxMessages = maxRounds * 2;
            while (queue.size() > maxMessages) {
                queue.removeFirst();
            }

            log.debug("从 DB 加载会话 {} 的 {} 条消息", sessionId, queue.size());
            return queue;
        } catch (Exception e) {
            log.warn("从 DB 加载会话失败: {}", e.getMessage());
            return null;
        }
    }
}
