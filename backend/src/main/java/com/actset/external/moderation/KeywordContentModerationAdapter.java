package com.actset.external.moderation;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Set;

/**
 * 실제로 동작하는 텍스트 필터 — 외부 API가 아니라 자체 블록리스트다. 정교한 의미
 * 판정은 아니지만(오탐·미탐 존재), LLM 모더레이션 API가 붙기 전까지의 최소 방어선.
 * 이미지는 checkImage()가 항상 통과시킨다 — 실제 비전 모더레이션 벤더 미선정
 * (OVERNIGHT-LOG 기록).
 */
@Service
public class KeywordContentModerationAdapter implements ContentModerationAdapter {

    // 예시 수준의 최소 블록리스트 — 운영 전 실제 정책에 맞춰 검수·확장 필요.
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
            "혐오", "폭력적인 살해", "불법 마약", "아동 성", "테러 조장"
    );

    @Override
    public ModerationResult checkText(String text) {
        if (text == null || text.isBlank()) {
            return ModerationResult.allow();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        for (String keyword : BLOCKED_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return ModerationResult.block("부적절한 표현이 포함되어 있습니다: \"" + keyword + "\"");
            }
        }
        return ModerationResult.allow();
    }

    @Override
    public ModerationResult checkImage(byte[] imageBytes) {
        return ModerationResult.allow();
    }
}
