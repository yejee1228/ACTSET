package com.actset.external.kopis;

/**
 * KOPIS가 요청을 막았다("Request Blocked", HTTP 400). 호출 한도를 넘긴 것으로 보고 이번 동기화를 바로 멈춘다 —
 * 계속 부르면 차단이 길어질 수 있다(2026-10-11: 0.1초 간격 상세 호출 약 50건 뒤 차단됨).
 */
public class KopisBlockedException extends RuntimeException {
    public KopisBlockedException(Throwable cause) {
        super("KOPIS가 요청을 차단했습니다(호출 한도 초과로 추정)", cause);
    }
}
