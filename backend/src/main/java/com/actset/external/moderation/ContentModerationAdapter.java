package com.actset.external.moderation;

/**
 * 부적절 콘텐츠 필터 경계(1-23). 텍스트(자유서술 프롬프트)는 자체 키워드 룰로 실제
 * 동작하고, 이미지(업로드 사진)는 실제 비전 모더레이션 API가 없어 통과 처리한다
 * (OVERNIGHT-LOG 참고 — 업로드 사진은 CLAUDE.md 규칙 1에 따라 애초에 외부로 나가지
 * 않으므로, 이미지 모더레이션도 우리가 통제하는 실행 환경 안에서만 해야 한다).
 */
public interface ContentModerationAdapter {
    ModerationResult checkText(String text);
    ModerationResult checkImage(byte[] imageBytes);
}
