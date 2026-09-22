package com.actset.external.imagegen;

import java.util.List;

/**
 * 이미지 생성 API(Ideogram 등) 호출 입력. 필드 목록이 곧 전송 허용 목록이다.
 *
 * CLAUDE.md 규칙 1 / docs/11 1-11 완료기준: "구조화 필드(출연진 이름 등)가 프롬프트
 * 빌더에 입력되지 않도록 타입으로 차단". 이 레코드에는 main_title·cast·venue·price 같은
 * 구조화 필드가 아예 존재하지 않는다 — DraftPromptBuilder가 실수로 채우고 싶어도
 * 컴파일이 되지 않는다. 허용되는 것은 장르(민감정보 아님)와 사용자가 직접 쓴 자유서술
 * image_direction_note·image_avoid_note뿐이며, 후자는 국외이전 고지로 커버된다(docs/15, E0 0-6).
 * styleReferenceImageUrls는 kind='reference_image' 업로드만 담아야 한다 — cast_photo·
 * performance_photo·logo는 여기 절대 들어가지 않는다(docs/05 업로드 사진 처리 원칙).
 *
 * <p>{@code prompt}·{@code negativePrompt}는 DraftPromptBuilder가 최종 조합한 텍스트
 * 그대로다 — 어댑터가 재조합하지 않고 이 값을 그대로 벤더에 보낸다. 실제로 무엇이
 * 전송됐는지 나중에 확인할 수 있도록 DraftGenerateJobHandler가 이 값을 generation_params에
 * 함께 저장한다(사용자 문의 대응용).
 */
public record ImageGenerationRequest(
        String genre,
        String imageDirectionNote,
        List<String> styleReferenceImageUrls,
        int width,
        int height,
        String prompt,
        String negativePrompt,
        /** "참고해서 만들기"(7-6, Stage 5 4-2·17)의 팔레트 힌트. 색상 hex 목록일 뿐 구조화된
         * 공연정보가 아니므로 이 타입에 두어도 안전하다 — 참고 대상의 PerformanceInfo는 여기 없다. */
        List<String> referencePalette,
        /** 재생성(mode=regenerate)일 때 GENERAL/REALISTIC/DESIGN/FICTION 중 하나로 무작위
         * 지정한다 — "다른 방향 보기"가 재생성 버튼 하나로 합쳐지면서 재생성 자체가 항상
         * 새로운 스타일을 시도하는 쪽으로 바뀌었다. initial·more_like는 null(벤더 기본값 AUTO). */
        String styleType,
        /** 이 방향으로 더 보기(more_like)에서 참고 후보의 seed를 그대로 재사용한다 — 벤더가
         * "리믹스" API를 별도로 제공하지 않아 얻을 수 있는 가장 가까운 근사치다(확인필요). */
        String seed
) {
    public ImageGenerationRequest {
        if (styleReferenceImageUrls == null) {
            styleReferenceImageUrls = List.of();
        }
        if (referencePalette == null) {
            referencePalette = List.of();
        }
    }
}
