package com.actset.conversion.layout;

import com.actset.format.FormatClass;

import java.util.Map;

/**
 * 규격 분류(5분류)별 배치 규칙 — docs/12 FormatRule의 MVP 축약판. 값은 레퍼런스 학습 산출물이다(LayoutLearner).
 *
 * @param blocks         블록별 배치 영역(캔버스 대비 상대좌표 [x0,y0,x1,y1])·표시 여부·정렬
 * @param showCopy       COPY(부제·태그라인)를 HEADLINE에 포함할지
 * @param backdropAnchorY 배경판을 cover-fit으로 자를 때 세로 기준점(0=위, 0.5=가운데, 1=아래)
 * @param minTextPx      INFO 글자 덩어리가 이보다 작아지면 블록을 생략(docs/12 min_readable_px)
 * @param source         TEMP_PRIOR(레퍼런스 0건 — 임시 사전값) | LEARNED
 */
public record FormatClassRule(FormatClass formatClass, Map<LayoutBlock, BlockRule> blocks, boolean showCopy,
                              double backdropAnchorY, double minTextPx, int sampleCount, double confidence,
                              String source) {

    public enum Align { START, CENTER, END }

    public record BlockRule(double[] region, boolean show, Align alignX, Align alignY) {
        public static BlockRule hidden() {
            return new BlockRule(new double[]{0, 0, 0, 0}, false, Align.CENTER, Align.CENTER);
        }
    }

    public BlockRule block(LayoutBlock block) {
        return blocks.getOrDefault(block, BlockRule.hidden());
    }
}
