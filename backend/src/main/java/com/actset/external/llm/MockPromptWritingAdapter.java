package com.actset.external.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * 0-9류 벤더 키가 없을 때의 껍데기 — 실제 GPT 호출 대신 결정적 조합 문장을 돌려준다.
 * DraftPromptBuilder의 폴백 로직과 같은 조합 규칙을 쓴다(목업이든 폴백이든 결과가
 * 같아야 "실제 키가 생기면 어댑터만 바뀐다"는 원칙이 지켜진다).
 */
@Service
@ConditionalOnExpression("'${actset.external.openai.api-key:}'.isEmpty()")
public class MockPromptWritingAdapter implements PromptWritingAdapter {

    @Override
    public String write(PromptWritingRequest request) {
        return PromptFallback.compose(request);
    }
}
