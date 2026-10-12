package com.actset.external.kopis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * KOPIS 서비스키가 없을 때의 껍데기 — 장르가 섞인 결정적 샘플을 돌려준다(관리자 수동 동기화·테스트용).
 * live()=false라 자동 동기화는 돌지 않는다. 실제 키(KOPIS_SERVICE_KEY)가 생기면 RealKopisAdapter로 자동 전환된다.
 */
@Service
@ConditionalOnExpression("'${actset.external.kopis.service-key:}'.isEmpty()")
public class MockKopisAdapter implements KopisAdapter {

    private static List<KopisPerformance> sample() {
        LocalDate t = LocalDate.now();
        return List.of(
                p("mock-001", "겨울 나그네", "서양음악(클래식)", t.plusDays(20), "롯데콘서트홀"),
                p("mock-002", "호두까기 인형", "무용(서양/한국무용)", t.plusDays(15), "예술의전당"),
                p("mock-003", "리어왕", "연극", t.minusDays(10), "국립극장"),
                p("mock-004", "노트르담 드 파리", "뮤지컬", t.plusDays(30), "블루스퀘어"),
                p("mock-005", "인형의 꿈", "복합", t.plusDays(8), "세종문화회관"),
                p("mock-006", "야간비행", "대중음악", t.plusDays(5), "롤링홀"),
                p("mock-007", "아리랑 판타지", "한국음악(국악)", t.plusDays(25), "국립국악원"));
    }

    private static KopisPerformance p(String id, String title, String genre, LocalDate start, String venue) {
        return new KopisPerformance(id, title, genre, start, start.plusDays(14), venue, null, "서울특별시",
                start.isAfter(LocalDate.now()) ? "공연예정" : "공연중", false);
    }

    @Override
    public boolean live() {
        return false;
    }

    @Override
    public List<KopisPerformance> fetchPage(LocalDate from, LocalDate to, int page, int rows) {
        return page == 1 ? sample().stream().limit(Math.max(rows, 0)).toList() : List.of();
    }

    @Override
    public Optional<KopisDetail> fetchDetail(String externalId) {
        boolean child = externalId.equals("mock-005");
        return Optional.of(new KopisDetail(externalId, "출연진 미정", null, "1시간 30분", child ? "만 4세 이상" : "만 7세 이상",
                "목업 제작사", null, null, "전석 30,000원", "화요일 ~ 금요일(19:30)", null, child, List.of(), List.of()));
    }
}
