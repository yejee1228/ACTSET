package com.actset.external.kopis;

import java.time.LocalDate;

/**
 * KOPIS 공연목록 API 응답 1건(pblprfr → db). 태그: mt20id·prfnm·genrenm·prfpdfrom·prfpdto·fcltynm·poster·area·prfstate·openrun.
 *
 * @param state 공연예정 | 공연중 | 공연완료 (KOPIS 원문)
 */
public record KopisPerformance(
        String externalId,
        String title,
        String genre,
        LocalDate startDate,
        LocalDate endDate,
        String venueName,
        String posterUrl,
        String area,
        String state,
        boolean openRun
) {
}
