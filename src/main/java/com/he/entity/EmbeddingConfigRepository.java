package com.he.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmbeddingConfigRepository extends JpaRepository<EmbeddingConfigEntity, UUID> {

    List<EmbeddingConfigEntity> findAllByOrderByCreatedAtDesc();

    Optional<EmbeddingConfigEntity> findFirstByIsActiveTrue();

    @Modifying
    @Transactional
    @Query("UPDATE EmbeddingConfigEntity e SET e.isActive = false WHERE e.isActive = true")
    void deactivateAll();
}

