package com.actset.external.imagegen;

import com.actset.external.llm.PromptFallback;
import com.actset.external.llm.PromptWritingAdapter;
import com.actset.external.llm.PromptWritingRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PerformanceInfo → ImageGenerationRequest. main_title·cast·venue·price 등은 의도적으로
 * 읽지 않는다 — ImageGenerationRequest 타입 자체에 그런 필드가 없어 실수로라도 넣을 수
 * 없다(1-11 완료기준 "타입으로 차단"). reference_image 업로드 URL만 스타일 참고로 전달한다.
 *
 * <p>긍정 프롬프트 문장 자체는 GPT(PromptWritingAdapter)가 다듬는다 — 사용자가 ②에 짧게
 * 적은 자유서술을 장르·팔레트 맥락과 엮어 이미지 생성 모델이 더 잘 알아듣는 문장으로
 * 바꿔준다. GPT 호출이 실패하면 {@link PromptFallback}의 결정적 조합으로 즉시 대체해
 * 시안 생성 자체가 막히지 않는다. "텍스트·글자·워터마크 없이" 지시는 GPT 출력과 무관하게
 * 여기서 항상 덧붙인다 — docs/05 계약을 GPT 응답 품질에 맡기지 않기 위해서다.
 *
 * <p>"피해달라"·"없도록" 같은 회피 지시는 긍정 프롬프트에 섞으면 생성 모델이 오히려
 * 그 대상에 반응하는 경우가 많다(부정문을 잘 못 알아듣는 문제). 그래서 사용자가 별도
 * 입력한 image_avoid_note는 GPT에 보내지 않고, 본문이 아니라 negative_prompt로만 보낸다.
 */
@Component
public class DraftPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(DraftPromptBuilder.class);
    private static final String[] DIRECTION_STYLES = {"GENERAL", "REALISTIC", "DESIGN", "FICTION"};
    private static final String NO_TEXT_SUFFIX = "텍스트·글자·워터마크 없이 순수 배경 비주얼만.";

    private final PromptWritingAdapter promptWritingAdapter;

    public DraftPromptBuilder(PromptWritingAdapter promptWritingAdapter) {
        this.promptWritingAdapter = promptWritingAdapter;
    }

    /**
     * @param mode           initial | regenerate | more_like. "재생성"과 "다른 방향 보기"는
     *                       화면에서 하나의 버튼(재생성)으로 합쳤다 — 사용자 피드백: 둘의 차이가
     *                       불명확했다. 그래서 initial이 아니고 more_like도 아니면(=regenerate)
     *                       항상 스타일을 무작위로 바꾼다. more_like는 유사성을 위해 GPT를 다시
     *                       거치지 않고 결정적 조합을 그대로 쓴다.
     * @param referenceSeed  more_like일 때 참고 후보의 seed(그 후보 generation_params에서 읽어온
     *                       값). 벤더에 별도 "리믹스" API가 없어(확인필요) 같은 seed 재사용으로
     *                       근사한다 — 완전히 같은 이미지가 나올 수도 있다는 한계가 있다.
     * @param referenceStyle more_like일 때 참고 후보가 쓴 styleType을 그대로 물려준다.
     */
    public ImageGenerationRequest build(JsonNode performanceInfo, List<String> referenceImageUrls,
                                          int width, int height, List<String> referencePalette,
                                          String mode, String referenceSeed, String referenceStyle) {
        String genre = performanceInfo.path("genre").asText(null);
        String note = performanceInfo.path("image_direction_note").asText(null);
        String avoidNote = performanceInfo.path("image_avoid_note").asText(null);

        String prompt = "more_like".equals(mode)
                ? PromptFallback.compose(new PromptWritingRequest(genre, note, referencePalette, mode))
                : writeWithGpt(genre, note, referencePalette, mode);
        String negativePrompt = buildNegativePrompt(avoidNote);

        String styleType = "more_like".equals(mode) ? referenceStyle
                : "initial".equals(mode) ? null
                : DIRECTION_STYLES[ThreadLocalRandom.current().nextInt(DIRECTION_STYLES.length)];
        String seed = "more_like".equals(mode) ? referenceSeed : null;

        return new ImageGenerationRequest(
                genre, note, referenceImageUrls, width, height,
                prompt, negativePrompt, referencePalette, styleType, seed
        );
    }

    private String writeWithGpt(String genre, String note, List<String> referencePalette, String mode) {
        PromptWritingRequest request = new PromptWritingRequest(genre, note, referencePalette, mode);
        try {
            String written = promptWritingAdapter.write(request);
            return written + " " + NO_TEXT_SUFFIX;
        } catch (Exception e) {
            log.warn("GPT 프롬프트 작성 실패 — 결정적 조합으로 폴백한다", e);
            return PromptFallback.compose(request);
        }
    }

    /** 회피 지시(image_avoid_note)는 본문이 아니라 여기로만 보낸다. GPT에도 전달하지 않는다. */
    private String buildNegativePrompt(String avoidNote) {
        String base = "text, letters, words, typography, watermark, signature, caption";
        if (avoidNote == null || avoidNote.isBlank()) {
            return base;
        }
        return base + ", " + avoidNote;
    }
}
