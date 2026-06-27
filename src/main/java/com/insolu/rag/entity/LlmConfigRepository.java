package com.insolu.rag.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmConfigRepository extends JpaRepository<LlmConfigEntity, UUID> {

    List<LlmConfigEntity> findAllByOrderByCreatedAtDesc();

    List<LlmConfigEntity> findByIsActiveTrue();

    Optional<LlmConfigEntity> findFirstByIsActiveTrue();

    boolean existsByName(String name);

    @Modifying
    @Transactional
    @Query("UPDATE LlmConfigEntity e SET e.isActive = false WHERE e.isActive = true")
    void deactivateAll();
}
