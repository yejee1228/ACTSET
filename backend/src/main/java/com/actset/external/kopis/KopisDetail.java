package com.actset.external.kopis;

import java.util.List;

/**
 * KOPIS 공연상세 API 응답(pblprfr/{mt20id} → db). 목록에 없는 상세 항목만 담는다 — 목록 항목은 KopisPerformance.
 * 값은 KOPIS 원문 그대로다(가공하지 않음). 비어 있으면 null.
 *
 * @param cast        prfcast 출연진
 * @param crew        prfcrew 제작진
 * @param runtime     prfruntime 공연 시간
 * @param age         prfage 관람 연령
 * @param producer    entrpsnmP 제작사(없으면 예전 필드 entrpsnm)
 * @param host        entrpsnmH 주최
 * @param organizer   entrpsnmS 주관
 * @param price       pcseguidance 티켓 가격
 * @param schedule    dtguidance 공연 시간 안내
 * @param synopsis    sty 줄거리
 * @param childFriendly child=Y 아동 공연
 * @param introImages styurls/styurl 소개 이미지
 * @param ticketLinks relates/relate 예매처
 */
public record KopisDetail(
        String externalId,
        String cast,
        String crew,
        String runtime,
        String age,
        String producer,
        String host,
        String organizer,
        String price,
        String schedule,
        String synopsis,
        boolean childFriendly,
        List<String> introImages,
        List<TicketLink> ticketLinks
) {
    public record TicketLink(String name, String url) {
    }
}
