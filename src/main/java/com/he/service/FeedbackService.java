package com.he.service;

import com.he.entity.QaFeedbackEntity;
import com.he.entity.QaFeedbackRepository;
import com.he.entity.QaHistoryEntity;
import com.he.entity.QaHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeedbackService {

    private final QaFeedbackRepository feedbackRepo;
    private final QaHistoryRepository qaHistoryRepo;

    public FeedbackService(QaFeedbackRepository feedbackRepo, QaHistoryRepository qaHistoryRepo) {
        this.feedbackRepo = feedbackRepo;
        this.qaHistoryRepo = qaHistoryRepo;
    }

    public QaFeedbackEntity submit(UUID qaHistoryId, short rating, String comment) {
        QaFeedbackEntity existing = feedbackRepo.findByQaHistoryId(qaHistoryId).orElse(null);

        if (existing != null) {
            existing.setRating(rating);
            existing.setComment(comment);
            return feedbackRepo.save(existing);
        }

        QaFeedbackEntity feedback = new QaFeedbackEntity();
        feedback.setQaHistoryId(qaHistoryId);
        feedback.setRating(rating);
        feedback.setComment(comment);
        return feedbackRepo.save(feedback);
    }

    public Map<String, Object> getStats() {
        long total = feedbackRepo.totalCount();
        long positive = feedbackRepo.positiveCount();
        long negative = feedbackRepo.countByRating((short) -1);
        double rate = total > 0 ? (double) positive / total * 100 : 0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("positive", positive);
        stats.put("negative", negative);
        stats.put("positiveRate", Math.round(rate * 10.0) / 10.0);
        return stats;
    }

    public List<Map<String, Object>> getLowQuality() {
        List<QaFeedbackEntity> feedbacks = feedbackRepo.findByRatingOrderByCreatedAtDesc((short) -1);
        if (feedbacks.isEmpty()) return List.of();

        // 批量获取 qa_history（避免 N+1）
        List<UUID> qaIds = feedbacks.stream().map(QaFeedbackEntity::getQaHistoryId).toList();
        Map<UUID, QaHistoryEntity> qaMap = qaHistoryRepo.findAllById(qaIds).stream()
                .collect(Collectors.toMap(QaHistoryEntity::getId, q -> q));

        return feedbacks.stream().limit(20).map(f -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("feedbackId", f.getId().toString());
            map.put("qaHistoryId", f.getQaHistoryId().toString());
            map.put("comment", f.getComment());
            map.put("createdAt", f.getCreatedAt().toString());
            QaHistoryEntity qa = qaMap.get(f.getQaHistoryId());
            if (qa != null) {
                map.put("question", qa.getQuestion());
                map.put("answer", qa.getAnswer().length() > 200
                        ? qa.getAnswer().substring(0, 200) + "..." : qa.getAnswer());
                map.put("modelName", qa.getModelName());
            }
            return map;
        }).toList();
    }
}
