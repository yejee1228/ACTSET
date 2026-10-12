package com.actset.external.kopis;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * KOPIS(공연예술통합전산망) 오픈API 어댑터 경계(docs/13 7-7·docs/14 1-4).
 * 서비스키가 있으면 RealKopisAdapter, 없으면 MockKopisAdapter가 붙는다.
 */
public interface KopisAdapter {

    /** 실제 KOPIS를 부르는지. 목업이면 false — 자동 동기화는 실제 키가 있을 때만 돈다(목업 공연이 Gallery에 섞이지 않게). */
    boolean live();

    /** 공연 기간이 [from, to]와 겹치는 공연 목록 한 쪽(page는 1부터). */
    List<KopisPerformance> fetchPage(LocalDate from, LocalDate to, int page, int rows) throws Exception;

    Optional<KopisDetail> fetchDetail(String externalId) throws Exception;
}
