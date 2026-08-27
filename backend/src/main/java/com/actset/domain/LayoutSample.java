package com.actset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "layout_samples")
@Getter
@Setter
@NoArgsConstructor
public class LayoutSample {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** collected / user_upload / user_generated */
    @Column(nullable = false)
    private String source;

    private String genre;

    @Column(name = "aspect_ratio", nullable = false)
    private BigDecimal aspectRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode elements;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] palette;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "margin_ratio")
    private JsonNode marginRatio;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "present_roles", columnDefinition = "text[]")
    private String[] presentRoles;

    private BigDecimal confidence;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;
}
