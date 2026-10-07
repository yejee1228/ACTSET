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

/** 포스터에서 분리·생성한 요소 1개(V8 design_elements). 규격 변환은 이 행들을 재배치만 한다. */
@Entity
@Table(name = "design_elements")
@Getter
@Setter
@NoArgsConstructor
public class DesignElement {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** BACKDROP / SUBJECT / DECOR / PHOTO / TITLE / COPY / INFO / MARK / NOISE */
    @Column(nullable = false)
    private String role;

    /** decomposed / split / backdrop_residual / regenerated / rendered */
    @Column(nullable = false)
    private String origin;

    private String label;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "source_width", nullable = false)
    private int sourceWidth;

    @Column(name = "source_height", nullable = false)
    private int sourceHeight;

    @Column(name = "z_order", nullable = false)
    private int layerOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode meta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
