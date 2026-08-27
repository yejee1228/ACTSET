package com.actset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "main_title", nullable = false)
    private String mainTitle = "";

    private String genre;

    @Column(name = "primary_date")
    private LocalDate primaryDate;

    @Column(name = "date_undetermined", nullable = false)
    private boolean dateUndetermined = false;

    @Column(name = "venue_undetermined", nullable = false)
    private boolean venueUndetermined = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "performance_info", nullable = false)
    private JsonNode performanceInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "design_assets")
    private JsonNode designAssets;

    @Column(name = "design_updated_at")
    private Instant designUpdatedAt;

    @Column(name = "info_updated_at")
    private Instant infoUpdatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean isDraft() {
        return "draft".equals(status);
    }

    public boolean isActive() {
        return "active".equals(status);
    }
}
