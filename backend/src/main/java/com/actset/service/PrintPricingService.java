package com.actset.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 5-4 플레이스홀더 단가표. 0-4(인쇄 협력업체 협의)가 아직 끝나지 않아(OVERNIGHT-LOG 기록)
 * 실제 단가가 없다 — docs/13이 이 상태에서도 "플레이스홀더 단가로 화면을 구성한다"고
 * 명시했으므로 여기 값은 전부 임시다. 실제 단가표가 나오면 이 클래스만 교체하면 된다.
 */
@Service
public class PrintPricingService {

    private static final int BASE_WON_PER_CM2 = 50; // 임시값
    private static final Map<String, Double> PAPER_MULTIPLIER = Map.of(
            "광택", 1.0, "무광", 1.1, "패브릭", 1.5
    );

    public record Estimate(int price, java.util.List<Warning> warnings) {
    }

    public record Warning(String code, String message) {
    }

    public int estimate(int widthMm, int heightMm, int quantity, String paper) {
        double areaCm2 = (widthMm / 10.0) * (heightMm / 10.0);
        double paperMultiplier = PAPER_MULTIPLIER.getOrDefault(paper, 1.0);
        double quantityDiscount = quantity >= 50 ? 0.8 : quantity >= 10 ? 0.9 : 1.0;
        double raw = areaCm2 * BASE_WON_PER_CM2 * paperMultiplier * quantityDiscount * Math.max(quantity, 1);
        return (int) (Math.round(raw / 100.0) * 100);
    }

    /** docs/12 인쇄 해상도 기준 — 인쇄물 크기별 권장 dpi(관람 거리에 따라 다름). */
    public int recommendedDpi(int widthMm, int heightMm) {
        int longSide = Math.max(widthMm, heightMm);
        if (longSide >= 3000) return 120; // 현수막(5m 이상) — 100~150 중간값
        if (longSide >= 1000) return 150; // X배너·등신대(1~2m)
        return 300; // 포스터·리플렛·엽서(가까이 봄)
    }
}
