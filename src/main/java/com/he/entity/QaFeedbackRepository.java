package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QaFeedbackRepository extends JpaRepository<QaFeedbackEntity, UUID> {

    Optional<QaFeedbackEntity> findByQaHistoryId(UUID qaHistoryId);

    long countByRating(short rating);

    List<QaFeedbackEntity> findByRatingOrderByCreatedAtDesc(short rating);

    @Query("SELECT COUNT(f) FROM QaFeedbackEntity f")
    long totalCount();

    @Query("SELECT COUNT(f) FROM QaFeedbackEntity f WHERE f.rating = 1")
    long positiveCount();
}
