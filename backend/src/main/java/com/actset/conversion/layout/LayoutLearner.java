package com.actset.conversion.layout;

import com.actset.conversion.layout.FormatClassRule.Align;
import com.actset.conversion.layout.FormatClassRule.BlockRule;
import com.actset.format.FormatClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [E-A] 규격 학습기 — 레퍼런스 포스터의 블록 배치(LayoutSample)를 모아 규격 분류별 배치 규칙을 산출한다.
 *
 * <p>레퍼런스 1건 = {@link LayoutAnnotation}(블록별 상대좌표 영역). 주석은 VisionAnalysisAdapter.annotateLayout이
 * 만들고, 사람이 고칠 수도 있다. 집계는 docs/12 "값을 채우는 방법"을 따른다:
 * <ul>
 *   <li>표시 여부 = 해당 블록이 등장한 표본 비율 ≥ 0.5</li>
 *   <li>영역 = 좌표별 중앙값</li>
 *   <li>정렬 = 영역이 한쪽으로 치우쳤으면 그쪽, 아니면 가운데(임시 규칙)</li>
 * </ul>
 * 표본이 0건인 분류는 {@link TempLayoutPriors}로 채운다. 집계 방법(중앙값·이상치 처리)은 docs/12대로
 * 표본 분포를 본 뒤 다시 정한다.
 */
public final class LayoutLearner {

    /** 레퍼런스 1건의 배치 주석. blocks 값은 캔버스 대비 [x0,y0,x1,y1]. 없는 블록은 맵에 없다. */
    public record LayoutAnnotation(String sourceRef, FormatClass formatClass, int width, int height,
                                   Map<LayoutBlock, double[]> blocks, boolean hasCopy) {
    }

    /** confidence = 표본 수 / 이 값(최대 1). 임시값 — docs/05 부트스트랩 단계 목표(분류당 약 20건)에서 잡았다. */
    static final int FULL_CONFIDENCE_SAMPLES = 20;

    public Map<FormatClass, FormatClassRule> learn(List<LayoutAnnotation> samples) {
        Map<FormatClass, List<LayoutAnnotation>> byClass = new EnumMap<>(FormatClass.class);
        for (LayoutAnnotation a : samples) byClass.computeIfAbsent(a.formatClass(), k -> new ArrayList<>()).add(a);

        Map<FormatClass, FormatClassRule> rules = new EnumMap<>(FormatClass.class);
        for (FormatClass c : FormatClass.values()) {
            List<LayoutAnnotation> list = byClass.get(c);
            rules.put(c, list == null || list.isEmpty() ? TempLayoutPriors.forClass(c) : aggregate(c, list));
        }
        return rules;
    }

    private FormatClassRule aggregate(FormatClass c, List<LayoutAnnotation> list) {
        FormatClassRule prior = TempLayoutPriors.forClass(c);
        Map<LayoutBlock, BlockRule> blocks = new EnumMap<>(LayoutBlock.class);
        for (LayoutBlock b : LayoutBlock.values()) {
            List<double[]> regions = list.stream().map(a -> a.blocks().get(b)).filter(r -> r != null).toList();
            boolean show = regions.size() * 2 >= list.size() && !regions.isEmpty();
            if (!show) {
                blocks.put(b, BlockRule.hidden());
                continue;
            }
            double[] median = new double[4];
            for (int i = 0; i < 4; i++) {
                final int k = i;
                median[i] = median(regions.stream().mapToDouble(r -> r[k]).toArray());
            }
            blocks.put(b, new BlockRule(median, true, alignX(median), Align.CENTER));
        }
        long copyCount = list.stream().filter(LayoutAnnotation::hasCopy).count();
        double confidence = Math.min(1.0, list.size() / (double) FULL_CONFIDENCE_SAMPLES);
        return new FormatClassRule(c, blocks, copyCount * 2 >= list.size(), prior.backdropAnchorY(),
                prior.minTextPx(), list.size(), confidence, "LEARNED");
    }

    /** 영역이 캔버스 왼쪽 60% 안에 있으면 왼쪽 정렬, 오른쪽 60% 안이면 오른쪽, 그 외 가운데(임시 규칙). */
    private static Align alignX(double[] r) {
        if (r[2] <= 0.6 && r[0] < 0.2) return Align.START;
        if (r[0] >= 0.4 && r[2] > 0.8) return Align.END;
        return Align.CENTER;
    }

    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }
}
