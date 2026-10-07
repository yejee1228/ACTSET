package com.actset.external.conversion;

import java.awt.image.BufferedImage;

/**
 * 참조 이미지 편집 생성(gpt-image). 규격변환 ③ 배경 재생성에 쓴다 — 입력은 분리된 배경 이미지뿐이다.
 * 프롬프트는 사람이 쓰지 않고 VisionAnalysisAdapter(LLM)가 설계한다(이미지 생성은 LLM 경유 원칙).
 */
public interface ImageEditAdapter {
    BufferedImage edit(BufferedImage input, String prompt, boolean transparentBackground) throws Exception;
}
