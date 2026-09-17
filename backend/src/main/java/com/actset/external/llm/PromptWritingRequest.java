package com.actset.external.llm;

import java.util.List;

/**
 * GPT가 이미지 생성 프롬프트를 다듬는 입력(docs/14 GPT — 소개문 보조에 이어 두 번째 용도).
 * CLAUDE.md 규칙 1과 동일한 이유로 main_title·cast·venue·price 등 구조화 필드가 이 타입에는
 * 아예 없다 — ImageGenerationRequest와 같은 "타입으로 차단" 원칙을 여기도 적용한다.
 * 회피 지시(image_avoid_note)는 여기 넣지 않는다 — GPT가 쓰는 것은 긍정 프롬프트뿐이고,
 * 제외 지시는 DraftPromptBuilder가 별도로 negative_prompt에 담아 Ideogram에 보낸다.
 */
public record PromptWritingRequest(
        String genre,
        String imageDirectionNote,
        List<String> referencePalette,
        /** initial | regenerate — "재생성"일 때는 이전과 다른 새로운 해석을 요청한다. */
        String mode
) {
    public PromptWritingRequest {
        if (referencePalette == null) {
            referencePalette = List.of();
        }
    }
}
