package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface EvaluationTestcaseRepository extends JpaRepository<EvaluationTestcaseEntity, UUID> {

    List<EvaluationTestcaseEntity> findByTestsetIdOrderByCreatedAtAsc(UUID testsetId);

    long countByTestsetId(UUID testsetId);

    void deleteByTestsetId(UUID testsetId);
}
