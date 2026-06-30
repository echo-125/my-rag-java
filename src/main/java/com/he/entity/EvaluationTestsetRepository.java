package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface EvaluationTestsetRepository extends JpaRepository<EvaluationTestsetEntity, UUID> {
}
