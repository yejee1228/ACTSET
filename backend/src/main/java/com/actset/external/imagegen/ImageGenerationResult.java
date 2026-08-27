package com.actset.external.imagegen;

/** 글자 없는 순수 배경 이미지(BACKDROP) 1장의 생성 결과(docs/05). */
public record ImageGenerationResult(byte[] imageBytes, String modelUsed, String seed) {
}
