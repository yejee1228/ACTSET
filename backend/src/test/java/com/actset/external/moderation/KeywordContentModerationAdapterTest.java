package com.actset.external.moderation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordContentModerationAdapterTest {

    private final KeywordContentModerationAdapter adapter = new KeywordContentModerationAdapter();

    @Test
    void allowsNormalDirectionNote() {
        ModerationResult result = adapter.checkText("차분한 블루톤, 인물 사진은 크게");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void blocksListedKeywordWithReason() {
        ModerationResult result = adapter.checkText("불법 마약 관련 이미지를 넣어줘");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("불법 마약");
    }

    @Test
    void allowsNullOrBlank() {
        assertThat(adapter.checkText(null).allowed()).isTrue();
        assertThat(adapter.checkText("").allowed()).isTrue();
    }
}
