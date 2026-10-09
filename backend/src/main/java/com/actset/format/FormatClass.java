package com.actset.format;

/**
 * MVP 규격 5분류(2026-10-07 사용자 확정 — docs/12 "MVP 5분류").
 * 배치 규칙(FormatClassRule)은 이 분류에 붙는다. docs/12의 6구간(RatioBucket)은 그대로 두고 그 위에 얹는다:
 * 세로 = TALL + EXTRA_TALL(비율 2.5 미만), 긴 세로 = EXTRA_TALL(2.5 이상), 긴 가로 = EXTRA_WIDE + ULTRA_WIDE.
 */
public enum FormatClass {
    LONG_PORTRAIT("긴 세로"),
    PORTRAIT("세로"),
    SQUARE("정사각"),
    LANDSCAPE("가로"),
    LONG_LANDSCAPE("긴 가로");

    /** 임시값 — 기준 이미지 5장의 비율(1:3, 9:16, 1:1, 2.14:1, 8:1) 사이를 가른 값. 레퍼런스 분포를 본 뒤 조정한다. */
    static final double LONG_THRESHOLD = 2.5;
    static final double SQUARE_THRESHOLD = 1.15;

    private final String label;

    FormatClass(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static FormatClass fromDimensions(int width, int height) {
        double wide = width / (double) height;
        double tall = height / (double) width;
        if (wide >= LONG_THRESHOLD) return LONG_LANDSCAPE;
        if (wide >= SQUARE_THRESHOLD) return LANDSCAPE;
        if (tall >= LONG_THRESHOLD) return LONG_PORTRAIT;
        if (tall >= SQUARE_THRESHOLD) return PORTRAIT;
        return SQUARE;
    }

    /** 테스트·데모용 대표 치수. 기본 규격 상수(FormatPreset)에서 각 분류의 기준 이미지와 같은 비율을 고른다. */
    public FormatPreset representativePreset() {
        return switch (this) {
            case LONG_PORTRAIT -> FormatPreset.X_BANNER;
            case PORTRAIT -> FormatPreset.STORY;
            case SQUARE -> FormatPreset.SNS_1X1;
            case LANDSCAPE -> FormatPreset.WEB_THUMB;
            case LONG_LANDSCAPE -> FormatPreset.BANNER_WIDE;
        };
    }
}
