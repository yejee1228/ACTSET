package com.actset.external.llm;

/**
 * 이미지 생성용 긍정 프롬프트를 GPT로 다듬는 어댑터 경계(docs/09·14). 실패해도 시안
 * 생성 자체를 막지 않도록, 호출부(DraftPromptBuilder)는 실패 시 결정적 조합 프롬프트로
 * 폴백한다 — GPT는 "더 나은 프롬프트"를 만드는 보조 단계이지 필수 관문이 아니다.
 */
public interface PromptWritingAdapter {
    String write(PromptWritingRequest request) throws Exception;
}
