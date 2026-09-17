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

    /** 후보함 5개 제한(Stage 2·10·17) — idx_assets_favorited로 뒷받침되는 카운트 조회. */
    long countByProjectIdAndFavoritedTrueAndDeletedAtIsNull(UUID projectId);
}
