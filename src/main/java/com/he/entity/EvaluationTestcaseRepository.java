package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface EvaluationTestcaseRepository extends JpaRepository<EvaluationTestcaseEntity, UUID> {

    List<EvaluationTestcaseEntity> findByTestsetIdOrderByCreatedAtAsc(UUID testsetId);

    long countByTestsetId(UUID testsetId);

    void deleteByTestsetId(UUID testsetId);

    @Query("SELECT tc.testsetId, COUNT(tc) FROM EvaluationTestcaseEntity tc GROUP BY tc.testsetId")
    List<Object[]> countCasesGroupByTestset();
}
