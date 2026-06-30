package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResultEntity, UUID> {

    List<EvaluationResultEntity> findByBatchIdOrderByCreatedAtAsc(UUID batchId);
}
