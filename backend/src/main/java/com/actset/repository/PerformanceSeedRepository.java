package com.actset.repository;

import com.actset.domain.PerformanceSeed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerformanceSeedRepository extends JpaRepository<PerformanceSeed, UUID> {

    Optional<PerformanceSeed> findBySourceAndExternalId(String source, String externalId);

    long countBySource(String source);

    /** 목업 어댑터가 넣은 공연(external_id mock-…). 실제 키로 동기화할 때 지운다. */
    long countByExternalIdStartingWith(String prefix);

    @org.springframework.transaction.annotation.Transactional
    long deleteByExternalIdStartingWith(String prefix);

    /**
     * Gallery 목록 — 끝나지 않은 공연만. 공연중을 먼저, 그 안에서는 최근 개막순, 이어서 공연예정을 가까운 개막순으로
     * (둘 다 "오늘과 개막일의 거리" 오름차순). genre가 null이면 전체, kids=true면 아동 공연만.
     */
    @Query(value = """
            SELECT * FROM performance_seeds s
             WHERE (s.end_date IS NULL OR s.end_date >= :today)
               AND (CAST(:genre AS text) IS NULL OR s.genre = CAST(:genre AS text))
               AND (:kids = false OR s.kid_friendly)
             ORDER BY (s.primary_date > :today), abs(s.primary_date - :today) NULLS LAST, s.id
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<PerformanceSeed> findActive(@Param("today") LocalDate today, @Param("genre") String genre,
                                     @Param("kids") boolean kids, @Param("limit") int limit, @Param("offset") int offset);
}
