package com.actset.format;

/**
 * 비율 구간 (docs/12). FormatRule은 규격이 아니라 이 구간에 붙는다.
 * 커스텀 규격도 실제 비율을 계산해 가장 가까운 구간의 규칙을 쓴다.
 */
public enum RatioBucket {
    /** 세로 1:1.15 ~ 1:1.55 */
    TALL,
    /** 세로 1:1.55 이상 */
    EXTRA_TALL,
    /** 0.87 ~ 1.15 */
    SQUARE,
    /** 가로 1.15:1 ~ 2.5:1 */
    WIDE,
    /** 가로 2.5:1 ~ 5:1 */
    EXTRA_WIDE,
    /** 가로 5:1 이상 */
    ULTRA_WIDE;

    /**
     * width/height 비율로 가장 가까운 구간을 판정한다.
     * 경계값은 docs/12의 임시값이며 학습 표본 분포를 본 뒤 조정한다.
     */
    public static RatioBucket fromDimensions(int width, int height) {
        double ratio = width / (double) height; // >1 이면 가로가 김
        if (ratio >= 5.0) return ULTRA_WIDE;
        if (ratio >= 2.5) return EXTRA_WIDE;
        if (ratio >= 1.15) return WIDE;
        if (ratio >= 0.87) return SQUARE;
        // 세로가 긴 경우: height/width 기준으로 판정
        double tallRatio = height / (double) width;
        if (tallRatio >= 1.55) return EXTRA_TALL;
        return TALL;
    }
}
