package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EvaluationBatchRepository extends JpaRepository<EvaluationBatchEntity, UUID> {

    List<EvaluationBatchEntity> findByTestsetIdOrderByCreatedAtDesc(UUID testsetId);

    EvaluationBatchEntity findFirstByStatusOrderByCreatedAtDesc(String status);

    List<EvaluationBatchEntity> findByStatusOrderByCreatedAtDesc(String status);
}
