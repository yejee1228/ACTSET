package com.actset.repository;

import com.actset.domain.GeneratedAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedAssetRepository extends JpaRepository<GeneratedAsset, UUID> {

    List<GeneratedAsset> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID projectId);

    List<GeneratedAsset> findByProjectIdAndCategoryAndDeletedAtIsNull(UUID projectId, String category);

    Optional<GeneratedAsset> findFirstByProjectIdAndCategoryAndDeletedAtIsNull(UUID projectId, String category);
}
