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
@Table(name = "selection_events")
@Getter
@Setter
@NoArgsConstructor
public class SelectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "owner_id")
    private UUID ownerId;

    /** 시안선택 / 규격변환 */
    @Column(nullable = false)
    private String screen;

    /** select / view_more_direction / regenerate / more_like_this */
    @Column(nullable = false)
    private String action;

    @Column(name = "format_code")
    private String formatCode;

    private String genre;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shown_candidates", nullable = false)
    private JsonNode shownCandidates;

    @Column(name = "selected_candidate_id")
    private UUID selectedCandidateId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
