package com.actset.external.llm;

/**
 * GPT 없이도 시안 생성이 항상 되게 하는 결정적 조합 규칙. MockPromptWritingAdapter와
 * DraftPromptBuilder의 예외 폴백이 이 클래스 하나를 공유한다(GptPromptWritingAdapter
 * 호출이 실패해도 이 조합으로 즉시 대체되므로 시안 생성 자체가 막히지 않는다).
 */
public final class PromptFallback {

    private PromptFallback() {
    }

    public static String compose(PromptWritingRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.genre() != null && !request.genre().isBlank()) {
            sb.append(request.genre()).append(" 공연의 포스터 배경 키비주얼. ");
        }
        if (request.imageDirectionNote() != null && !request.imageDirectionNote().isBlank()) {
            sb.append(request.imageDirectionNote()).append(". ");
        }
        if (!request.referencePalette().isEmpty()) {
            sb.append("색감 참고: ").append(String.join(", ", request.referencePalette())).append(". ");
        }
        sb.append("텍스트·글자·워터마크 없이 순수 배경 비주얼만.");
        return sb.toString();
    }
}
