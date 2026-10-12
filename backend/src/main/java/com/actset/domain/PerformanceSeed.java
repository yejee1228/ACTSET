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

    /** 앱 장르 버튼 값으로 바꾼 장르(KopisGenres). 앱에 없는 장르는 KOPIS 원문. */
    private String genre;

    @Column(name = "kopis_genre")
    private String kopisGenre;

    @Column(name = "primary_date")
    private LocalDate primaryDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    private String area;

    /** 공연예정 | 공연중 | 공연완료 (KOPIS 원문). */
    private String state;

    @Column(name = "kid_friendly", nullable = false)
    private boolean kidFriendly;

    /** 상세 API 항목(KopisDetail) — 출연진·가격·소개 이미지·예매처 등. 아직 안 받았으면 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode detail;

    @Column(name = "detail_fetched_at")
    private Instant detailFetchedAt;

    /** 목록용 포스터 축소본 저장 경로(StorageService). 못 만들었으면 null — 목록은 원본 URL을 쓴다. */
    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    @Column(name = "thumbnail_source")
    private String thumbnailSource;

    @Column(name = "venue_name")
    private String venueName;

    @Column(name = "poster_url")
    private String posterUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();
}
