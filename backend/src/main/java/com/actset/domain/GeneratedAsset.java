package com.actset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generated_assets")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** 시안후보 / 포스터 / 규격변환 / 추가제작물 */
    @Column(nullable = false)
    private String category;

    @Column(name = "format_code", nullable = false)
    private String formatCode;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(name = "variant_index")
    private Short variantIndex;

    @Column(name = "base_image_url")
    private String baseImageUrl;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "preview_image_url")
    private String previewImageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "object_map")
    private JsonNode objectMap;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_params")
    private JsonNode generationParams;

    @Column(name = "auto_sync_text", nullable = false)
    private boolean autoSyncText = false;

    /** 제안됨 / 선택됨 / 보관 / 삭제됨 */
    @Column(nullable = false)
    private String status;

    @Column(name = "info_synced_at")
    private Instant infoSyncedAt;

    @Column(name = "design_synced_at")
    private Instant designSyncedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "download_expires_at")
    private Instant downloadExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
