package com.actset.format;

import java.util.Map;

/**
 * 비율 구간(RatioBucket)별 TITLE·INFO 배치 임시값(docs/12 FormatRule — 값은 학습 후 확정).
 * CLAUDE.md 규칙 5: GenreRule·FormatRule 값은 학습 산출물이다. E2(학습 파이프라인) 완료
 * 전까지 화면 시연이 가능하도록 넣어둔 임시값이며, 실제 배치 기준이 아니다.
 */
public final class TempFormatLayout {

    public record Areas(double[] title, double[] info) {
    }

    private static final Map<RatioBucket, Areas> BY_BUCKET = Map.of(
            RatioBucket.TALL, new Areas(
                    new double[]{0.08, 0.60, 0.92, 0.66}, new double[]{0.08, 0.60, 0.92, 0.95}),
            RatioBucket.EXTRA_TALL, new Areas(
                    new double[]{0.08, 0.55, 0.92, 0.62}, new double[]{0.08, 0.62, 0.92, 0.90}),
            RatioBucket.SQUARE, new Areas(
                    new double[]{0.08, 0.55, 0.92, 0.65}, new double[]{0.08, 0.65, 0.92, 0.92}),
            RatioBucket.WIDE, new Areas(
                    new double[]{0.05, 0.30, 0.55, 0.55}, new double[]{0.05, 0.55, 0.55, 0.85}),
            RatioBucket.EXTRA_WIDE, new Areas(
                    new double[]{0.03, 0.12, 0.45, 0.55}, new double[]{0.03, 0.55, 0.45, 0.88}),
            RatioBucket.ULTRA_WIDE, new Areas(
                    new double[]{0.02, 0.10, 0.35, 0.55}, new double[]{0.02, 0.55, 0.35, 0.90})
    );

    private TempFormatLayout() {
    }

    public static Areas forBucket(RatioBucket bucket) {
        return BY_BUCKET.get(bucket);
    }
}
