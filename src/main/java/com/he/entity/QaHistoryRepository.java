package com.he.entity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface QaHistoryRepository extends JpaRepository<QaHistoryEntity, UUID> {
    List<QaHistoryEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

