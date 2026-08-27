package com.actset.external.layerdecomp;

import java.awt.image.BufferedImage;

/**
 * Qwen-Image-Layered 경계(docs/09). 실제 서버리스 GPU 키가 준비되면 이 인터페이스를
 * 구현하는 QwenLayeredAdapter로 교체한다. 입력에 업로드 사진(cast_photo 등)이 없다는
 * 점에 주의 — 분해 대상은 항상 생성 이미지(base_image)뿐이다(CLAUDE.md 규칙 1).
 */
public interface LayerDecompositionAdapter {
    LayerDecompositionResult decompose(BufferedImage source);
}
