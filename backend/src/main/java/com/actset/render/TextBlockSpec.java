package com.actset.render;

/**
 * INFO 레이어에 얹을 텍스트 블록 하나. role은 object_map의 키가 된다(docs/02).
 * sizeRatio·gapAfterRatio는 title_scale 대비 상대값 — GenreRule/FormatRule 학습 전까지의
 * 임시값이다(poc/engine.py, poc-java/PosterRenderer.java의 데모 상수를 그대로 옮김).
 * CLAUDE.md 규칙 5: 이 값들은 학습 산출물로 교체될 때까지의 임시값임을 여기 명시한다.
 */
public record TextBlockSpec(
        String role,
        String text,
        float sizeRatio,
        boolean bold,
        float gapAfterRatio
) {
}
