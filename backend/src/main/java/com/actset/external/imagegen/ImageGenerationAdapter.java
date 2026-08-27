package com.actset.external.imagegen;

/**
 * 이미지 생성 API 어댑터 경계(docs/09 Ideogram 4.0). 실제 키가 준비되면 이 인터페이스를
 * 구현하는 IdeogramAdapter로 교체한다 — 호출부(DraftGenerateJobHandler)는 변경 없음.
 */
public interface ImageGenerationAdapter {
    ImageGenerationResult generate(ImageGenerationRequest request) throws Exception;
}
