package com.actset.external.imagegen;

import java.util.List;

/**
 * 이미지 생성 API(Ideogram 등) 호출 입력. 필드 목록이 곧 전송 허용 목록이다.
 *
 * CLAUDE.md 규칙 1 / docs/11 1-11 완료기준: "구조화 필드(출연진 이름 등)가 프롬프트
 * 빌더에 입력되지 않도록 타입으로 차단". 이 레코드에는 main_title·cast·venue·price 같은
 * 구조화 필드가 아예 존재하지 않는다 — DraftPromptBuilder가 실수로 채우고 싶어도
 * 컴파일이 되지 않는다. 허용되는 것은 장르(민감정보 아님)와 사용자가 직접 쓴 자유서술
 * image_direction_note뿐이며, 후자는 국외이전 고지로 커버된다(docs/15, E0 0-6).
 * styleReferenceImageUrls는 kind='reference_image' 업로드만 담아야 한다 — cast_photo·
 * performance_photo·logo는 여기 절대 들어가지 않는다(docs/05 업로드 사진 처리 원칙).
 */
public record ImageGenerationRequest(
        String genre,
        String imageDirectionNote,
        List<String> styleReferenceImageUrls,
        int width,
        int height,
        String negativePrompt
) {
    public ImageGenerationRequest {
        if (styleReferenceImageUrls == null) {
            styleReferenceImageUrls = List.of();
        }
    }
}
