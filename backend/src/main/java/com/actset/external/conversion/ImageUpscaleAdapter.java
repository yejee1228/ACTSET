package com.actset.external.conversion;

import java.awt.image.BufferedImage;

/**
 * 초해상도 확대(인쇄 해상도용, docs/12 "비주얼 레이어: 업스케일"). 입력은 이미지 한 장뿐이다.
 *
 * <p><b>호출부는 업로드 사진(PHOTO)·로고 영역을 지운 이미지만 넘긴다</b>(CLAUDE.md 규칙 1 — 사용자 결정 2026-10-11:
 * 일반 요소는 외부 확대, 업로드 사진은 서버 안에서만). 확대 결과의 알파는 쓰지 않는다(불투명 RGB로 돌려준다).
 */
public interface ImageUpscaleAdapter {

    /** 목업이 아닌 실제 확대인지(목업은 bicubic). 리포트·로그용. */
    boolean live();

    /** @param factor 배율(1~10). 결과 크기는 대략 입력 × factor */
    BufferedImage upscale(BufferedImage image, double factor) throws Exception;
}
