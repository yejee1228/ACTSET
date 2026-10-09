package com.actset.external.conversion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.List;

/** FAL_KEY가 없을 때(테스트 포함 — CLAUDE.md 규칙 9): 포스터 통짜 1장을 배경 레이어로 돌려준다(분해 실패 폴백과 같은 모양). */
@Component
@ConditionalOnExpression("'${actset.external.fal.api-key:}'.isEmpty()")
public class MockPosterLayerSplitAdapter implements PosterLayerSplitAdapter {

    @Override
    public List<BufferedImage> decompose(BufferedImage poster, int numLayers) {
        return List.of(poster);
    }
}
