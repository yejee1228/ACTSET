package com.actset.external.conversion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OPENAI 키가 없을 때(테스트 포함 — CLAUDE.md 규칙 9). 판단 없이 고정 응답:
 * 배경은 깨끗함, 1번 요소는 BACKDROP·나머지는 SUBJECT, 배치 주석·평가는 비어 있음.
 */
@Component
@ConditionalOnExpression("'${actset.external.openai.api-key:}'.isEmpty()")
public class MockVisionAnalysisAdapter implements VisionAnalysisAdapter {

    @Override
    public BackdropCheck checkBackdrop(BufferedImage backdrop) {
        return new BackdropCheck(true, List.of(), "", "mock");
    }

    @Override
    public Classification classifyElements(BufferedImage poster, BufferedImage sheet, int count, String elementSummary) {
        List<ElementLabel> labels = new ArrayList<>();
        for (int i = 1; i <= count; i++) labels.add(new ElementLabel(i, i == 1 ? "BACKDROP" : "SUBJECT", "mock"));
        return new Classification(labels, List.of());
    }

    @Override
    public LayoutNotes annotateLayout(BufferedImage image) {
        return new LayoutNotes(Map.of(), false, "mock");
    }

    @Override
    public Evaluation evaluate(BufferedImage ours, BufferedImage reference, String formatLabel) {
        return new Evaluation(Map.of(), List.of(), List.of(), List.of(), "mock");
    }
}
