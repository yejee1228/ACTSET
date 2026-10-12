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
 * 레퍼런스가 쌓이면 LayoutLearner가 만든 LEARNED 규칙으로 대체된다. 영역은 출발점일 뿐이다 — LayoutPlanner가 이 영역을
 * 바탕으로 여러 배치 후보(키비주얼 넘침·요소 생략·원본 구도 확대 등)를 만들고 점수로 고른다(2026-10-12).
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
                b.put(LayoutBlock.INFO, rule(0.06, 0.80, 0.94, 0.93, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.CREDIT, rule(0.06, 0.94, 0.94, 0.985, Align.CENTER, Align.CENTER));
            }
            case PORTRAIT -> {
                b.put(LayoutBlock.MARK, rule(0.04, 0.025, 0.34, 0.07, Align.START, Align.CENTER));
                b.put(LayoutBlock.HEADLINE, rule(0.06, 0.09, 0.94, 0.40, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.04, 0.41, 0.96, 0.84, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.05, 0.86, 0.95, 0.94, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.CREDIT, rule(0.05, 0.945, 0.95, 0.985, Align.CENTER, Align.CENTER));
            }
            case SQUARE -> {
                b.put(LayoutBlock.MARK, rule(0.02, 0.02, 0.22, 0.08, Align.START, Align.CENTER));
                b.put(LayoutBlock.HEADLINE, rule(0.12, 0.06, 0.88, 0.44, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.15, 0.44, 0.85, 0.88, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.06, 0.87, 0.94, 0.935, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.CREDIT, rule(0.06, 0.94, 0.94, 0.985, Align.CENTER, Align.CENTER));
            }
            case LANDSCAPE -> {
                b.put(LayoutBlock.MARK, rule(0.02, 0.04, 0.12, 0.20, Align.START, Align.START));
                b.put(LayoutBlock.HEADLINE, rule(0.05, 0.10, 0.52, 0.70, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.05, 0.74, 0.52, 0.88, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.CREDIT, rule(0.05, 0.89, 0.52, 0.96, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.54, 0.04, 0.98, 0.98, Align.CENTER, Align.CENTER));
            }
            case LONG_LANDSCAPE -> {
                showCopy = false; // 높이가 낮아 제목만 남긴다
                b.put(LayoutBlock.HEADLINE, rule(0.06, 0.10, 0.34, 0.90, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.KEYVISUAL, rule(0.38, 0.00, 0.62, 1.00, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.INFO, rule(0.66, 0.15, 0.96, 0.70, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.CREDIT, rule(0.66, 0.74, 0.96, 0.90, Align.CENTER, Align.CENTER));
                b.put(LayoutBlock.MARK, BlockRule.hidden());
            }
        }
        return new FormatClassRule(c, b, showCopy, anchorY, 9.0, 0, 0.0, "TEMP_PRIOR");
    }

    /**
     * 같은 분류의 다른 배치 틀(임시값). 기본 틀과 함께 후보로 만들어 점수로 고른다.
     * 긴 가로 2분할: 정보 칸 없이 제목 | 키비주얼 — 정보가 없는 포스터에서 오른쪽 1/3이 비었다(sandmagicshow).
     */
    public static java.util.List<FormatClassRule> alternatives(FormatClass c) {
        if (c != FormatClass.LONG_LANDSCAPE) return java.util.List.of();
        Map<LayoutBlock, BlockRule> b = new EnumMap<>(LayoutBlock.class);
        b.put(LayoutBlock.HEADLINE, rule(0.06, 0.08, 0.48, 0.92, Align.CENTER, Align.CENTER));
        b.put(LayoutBlock.KEYVISUAL, rule(0.52, 0.00, 0.94, 1.00, Align.CENTER, Align.CENTER));
        b.put(LayoutBlock.INFO, BlockRule.hidden());
        b.put(LayoutBlock.CREDIT, BlockRule.hidden());
        b.put(LayoutBlock.MARK, BlockRule.hidden());
        return java.util.List.of(new FormatClassRule(c, b, false, 0.5, 9.0, 0, 0.0, "TEMP_PRIOR_TWO_ZONE"));
    }

    private static BlockRule rule(double x0, double y0, double x1, double y1, Align ax, Align ay) {
        return new BlockRule(new double[]{x0, y0, x1, y1}, true, ax, ay);
    }
}
