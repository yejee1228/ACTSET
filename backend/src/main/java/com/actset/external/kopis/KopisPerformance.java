package com.actset.external.kopis;

import java.time.LocalDate;

/** KOPIS 공연목록 API 응답 1건을 옮긴 것(docs/14 1-4, 확인필요 — 실제 필드명 재검증 필요). */
public record KopisPerformance(
        String externalId,
        String title,
        String genre,
        LocalDate startDate,
        String venueName,
        String posterUrl
) {
}
