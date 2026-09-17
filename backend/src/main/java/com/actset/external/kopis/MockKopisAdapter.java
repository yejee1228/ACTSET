package com.actset.external.kopis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * data.go.kr 서비스키가 없을 때의 껍데기 — 장르가 섞인 결정적 샘플을 돌려준다.
 * 실제 키(KOPIS_SERVICE_KEY)가 생기면 RealKopisAdapter로 자동 전환된다 — 다른 벤더
 * mock-mode와 무관하게 이 벤더만의 키 존재 여부로 독립 판단한다(0-1·0-1b와 같은 패턴).
 */
@Service
@ConditionalOnExpression("'${actset.external.kopis.service-key:}'.isEmpty()")
public class MockKopisAdapter implements KopisAdapter {

    private static final List<KopisPerformance> SAMPLE = List.of(
            new KopisPerformance("mock-001", "겨울 나그네", "클래식", LocalDate.now().plusDays(20), "롯데콘서트홀", null),
            new KopisPerformance("mock-002", "호두까기 인형", "무용", LocalDate.now().plusDays(15), "예술의전당", null),
            new KopisPerformance("mock-003", "리어왕", "연극", LocalDate.now().plusDays(10), "국립극장", null),
            new KopisPerformance("mock-004", "노트르담 드 파리", "뮤지컬", LocalDate.now().plusDays(30), "블루스퀘어", null),
            new KopisPerformance("mock-005", "인형의 꿈", "어린이공연", LocalDate.now().plusDays(8), "세종문화회관", null),
            new KopisPerformance("mock-006", "야간비행", "인디밴드", LocalDate.now().plusDays(5), "롤링홀", null),
            new KopisPerformance("mock-007", "여름밤의 콘서트", "대중음악", LocalDate.now().plusDays(25), "올림픽공원", null),
            new KopisPerformance("mock-008", "백조의 호수", "무용", LocalDate.now().plusDays(40), "예술의전당", null),
            new KopisPerformance("mock-009", "심포니 No.9", "클래식", LocalDate.now().plusDays(12), "롯데콘서트홀", null)
    );

    @Override
    public List<KopisPerformance> fetchLatest(int count) {
        return SAMPLE.stream().limit(Math.max(count, 0)).toList();
    }
}
