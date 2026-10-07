package com.actset.conversion.layout;

import com.actset.conversion.layout.FormatClassRule.Align;
import com.actset.conversion.layout.FormatClassRule.BlockRule;
import com.actset.format.FormatClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * 임시 사전값(TEMP) — 레퍼런스가 0건인 분류에 쓰는 배치 규칙. CLAUDE.md 규칙 5: 학습 산출물이 아니다.
 *
 * <p>공연 포스터를 여러 규격으로 펼칠 때의 일반적인 관행(세로형은 위에서 아래로 제목→키비주얼→정보, 가로형은 왼쪽 제목·
 * 오른쪽 키비주얼)을 손으로 옮긴 값이며, 2026-10-07 테스트의 기준 이미지(poc/input/test)는 보지 않고 정했다.
 * 레퍼런스가 쌓이면 LayoutLearner가 만든 LEARNED 규칙으로 대체된다.
 */
public final class TempLayoutPriors {

    private TempLayoutPriors() {
    }

    public static FormatClassRule forClass(FormatClass c) {
        Map<LayoutBlock, BlockRule> b = new EnumMap<>(LayoutBlock.class);
        boolean showCopy = true;
        double anchorY = 0.5;
        switch (c) {
            case LONG_PORTRAIT -> {
                b.put(LayoutBlock.MARK, rule(0.30, 0.015, 0.70, 0.05, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.HEADLINE, rule(0.05, 0.06, 0.95, 0.36, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.00, 0.38, 1.00, 0.76, Align.CENTER, Align.END));
                b.put(LayoutBlock.INFO, rule(0.06, 0.80, 0.94, 0.97, Align.CENTER, Align.CENTER));
            }
            case PORTRAIT -> {
                b.put(LayoutBlock.MARK, rule(0.04, 0.025, 0.34, 0.07, Align.START, Align.CENTER));
                b.put(LayoutBlock.HEADLINE, rule(0.06, 0.09, 0.94, 0.40, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.04, 0.41, 0.96, 0.84, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.05, 0.86, 0.95, 0.97, Align.CENTER, Align.CENTER));
            }
            case SQUARE -> {
                b.put(LayoutBlock.MARK, rule(0.02, 0.02, 0.22, 0.08, Align.START, Align.CENTER));
                b.put(LayoutBlock.HEADLINE, rule(0.12, 0.06, 0.88, 0.44, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.15, 0.44, 0.85, 0.88, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.06, 0.89, 0.94, 0.98, Align.CENTER, Align.CENTER));
            }
            case LANDSCAPE -> {
                b.put(LayoutBlock.MARK, rule(0.02, 0.04, 0.12, 0.20, Align.START, Align.START));
                b.put(LayoutBlock.HEADLINE, rule(0.05, 0.10, 0.52, 0.70, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.05, 0.74, 0.52, 0.95, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.54, 0.04, 0.98, 0.98, Align.CENTER, Align.CENTER));
            }
            case LONG_LANDSCAPE -> {
                showCopy = false; // 높이가 낮아 제목만 남긴다
                b.put(LayoutBlock.HEADLINE, rule(0.06, 0.10, 0.34, 0.90, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.38, 0.00, 0.62, 1.00, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.66, 0.20, 0.96, 0.80, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.MARK, BlockRule.hidden());
            }
        }
        return new FormatClassRule(c, b, showCopy, anchorY, 9.0, 0, 0.0, "TEMP_PRIOR");
    }

    private static BlockRule rule(double x0, double y0, double x1, double y1, Align ax, Align ay) {
        return new BlockRule(new double[]{x0, y0, x1, y1}, true, ax, ay);
    }
}
