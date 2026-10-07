package com.actset.external.conversion;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 완성 포스터 한 장 → RGBA 레이어 N장(Qwen-Image-Layered). 규격변환 파이프라인 ② 분해 단계.
 *
 * <p>입력은 포스터 이미지뿐이다. 업로드 사진·로고 파일이나 공연정보 필드는 이 타입에 없다(CLAUDE.md 규칙 1).
 * 다만 고객이 올린 포스터 안에 사진·로고가 이미 합성돼 있으면 그 픽셀은 전송된다 — 제품 적용 전 결정 필요
 * (FORMAT-CONVERSION-REPORT.md "확인 필요 사항").
 *
 * @return 아래(0)에서 위로 쌓는 순서의 레이어. 해상도는 벤더 출력 그대로(입력보다 작을 수 있다)
 */
public interface PosterLayerSplitAdapter {
    List<BufferedImage> decompose(BufferedImage poster, int numLayers) throws Exception;
}
