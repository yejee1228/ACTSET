package com.actset.external.conversion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/** OPENAI 키가 없을 때(테스트 포함 — CLAUDE.md 규칙 9): 입력을 그대로 돌려준다(과금 없음). */
@Component
@ConditionalOnExpression("'${actset.external.openai.api-key:}'.isEmpty()")
public class MockImageEditAdapter implements ImageEditAdapter {

    @Override
    public BufferedImage edit(BufferedImage input, String prompt, boolean transparentBackground) {
        return input;
    }
}
