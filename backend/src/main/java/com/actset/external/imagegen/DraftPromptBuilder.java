package com.actset.external.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * PerformanceInfo → ImageGenerationRequest. main_title·cast·venue·price 등은 의도적으로
 * 읽지 않는다 — ImageGenerationRequest 타입 자체에 그런 필드가 없어 실수로라도 넣을 수
 * 없다(1-11 완료기준 "타입으로 차단"). reference_image 업로드 URL만 스타일 참고로 전달한다.
 */
@Component
public class DraftPromptBuilder {

    public ImageGenerationRequest build(JsonNode performanceInfo, java.util.List<String> referenceImageUrls,
                                          int width, int height) {
        String genre = performanceInfo.path("genre").asText(null);
        String note = performanceInfo.path("image_direction_note").asText(null);
        return new ImageGenerationRequest(
                genre,
                note,
                referenceImageUrls,
                width,
                height,
                // 텍스트 레이어를 따로 얹으므로 배경에 글자가 생기지 않도록 억제한다(docs/05).
                "text, letters, words, typography, watermark, signature, caption"
        );
    }
}
