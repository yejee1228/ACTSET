package com.actset.external.kopis;

import java.util.List;

/**
 * KOPIS(공연예술통합전산망) 공연목록 조회 어댑터 경계(docs/13 7-7·docs/14 1-4).
 * MVP 범위 밖 항목이라 실제 연동은 서비스키 확보 후에만 활성화된다.
 */
public interface KopisAdapter {
    List<KopisPerformance> fetchLatest(int count) throws Exception;
}
