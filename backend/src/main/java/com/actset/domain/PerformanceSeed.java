package com.actset.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Gallery 부트스트래핑용 KOPIS 시드 데이터(Stage 17·docs/10 8-2). projects와 달리
 * owner_id가 없다 — 공개 공연 정보일 뿐 특정 사용자가 만든 콘텐츠가 아니다.
 */
@Entity
@Table(name = "performance_seeds")
@Getter
@Setter
@NoArgsConstructor
public class PerformanceSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String source = "kopis";

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "main_title", nullable = false)
    private String mainTitle;

    private String genre;

    @Column(name = "primary_date")
    private LocalDate primaryDate;

    @Column(name = "venue_name")
    private String venueName;

    @Column(name = "poster_url")
    private String posterUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();
}
