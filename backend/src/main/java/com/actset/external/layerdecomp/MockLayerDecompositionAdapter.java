package com.actset.external.layerdecomp;

import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 0-1b(Qwen-Image-Layered 서버리스 GPU 환경)가 아직 없어 실제 분해 대신 항상
 * "통짜 배경 폴백" 경로를 탄다(OVERNIGHT-LOG 참고, docs/05 실패 시 대응 그대로).
 * 실제 어댑터가 준비되면 피사체·장식 분리 로직으로 교체하되, 여기서 이미
 * succeeded=false 폴백 처리 계약을 세워뒀으므로 호출부(DecomposeLayersJobHandler)는
 * 그대로 재사용된다.
 */
@Service
public class MockLayerDecompositionAdapter implements LayerDecompositionAdapter {

    @Override
    public LayerDecompositionResult decompose(BufferedImage source) {
        DecomposedLayer backdrop = new DecomposedLayer(
                "BACKDROP", source, 0, 0, source.getWidth(), source.getHeight(), false);
        return new LayerDecompositionResult(List.of(backdrop), false);
    }
}
