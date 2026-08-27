package com.actset.external.layerdecomp;

import java.util.List;

/**
 * @param succeeded false면 분해 품질 검사 실패로 통짜 배경 폴백을 썼다는 뜻이다
 *                  (docs/05 "분해 품질이 단일 실패점" — 1-15 완료기준).
 */
public record LayerDecompositionResult(List<DecomposedLayer> layers, boolean succeeded) {
}
