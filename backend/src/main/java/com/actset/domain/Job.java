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
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    /** draft_generate / decompose_layers / recompose / resync / render_print / zip_download / analyze_poster */
    @Column(nullable = false)
    private String kind;

    /** pending / running / succeeded / failed / canceled */
    @Column(nullable = false)
    private String status = "pending";

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payload;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode result;

    private String error;

    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "parent_job_id")
    private UUID parentJobId;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
