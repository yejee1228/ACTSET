package com.actset.external.conversion;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * 규격변환 파이프라인의 LLM(비전) 판단 지점 모음. 모두 JSON으로 받는다.
 *
 * <p>입력은 이미지(포스터·분리 요소·결과물)뿐이고 공연정보·업로드 파일 필드는 없다(CLAUDE.md 규칙 1 — 타입으로 차단).
 */
public interface VisionAnalysisAdapter {

    /**
     * ③ 배경 검증 — 분리된 배경 이미지에 배경 아닌 요소가 남았는지 판정하고, 남았으면 배경만 다시 만들 편집 지시문을 쓴다.
     *
     * @param residualElements 배경에 남은 요소(이름 + 배경 이미지 대비 상대 bbox)
     * @param editInstruction  clean=false일 때 이미지 편집 모델에 줄 지시문(영문). LLM이 설계한다
     */
    record BackdropCheck(boolean clean, List<Residual> residualElements, String editInstruction, String reason) {
    }

    /** bbox는 [x0,y0,x1,y1] 0..1. 구멍(잘려 나간 자리)은 isHole=true — 원본 픽셀로 떼어낼 요소가 아니다. */
    record Residual(String name, double[] bbox, boolean isHole) {
    }

    BackdropCheck checkBackdrop(BufferedImage backdrop) throws Exception;

    /** 요소 역할 판정 결과. index는 번호표 시트의 번호. */
    record ElementLabel(int index, String role, String label) {
    }

    /** 원본에 보이지만 요소로 떼어지지 않은 텍스트 — 자체 렌더링으로 다시 만든다. bbox는 원본 대비 상대좌표. */
    record MissingText(String text, String role, double[] bbox, String colorHex, boolean bold) {
    }

    record Classification(List<ElementLabel> labels, List<MissingText> missingTexts) {
    }

    /**
     * @param poster 원본 포스터(맥락용)
     * @param sheet  분리 요소를 번호와 함께 늘어놓은 시트
     */
    Classification classifyElements(BufferedImage poster, BufferedImage sheet, int count, String elementSummary)
            throws Exception;

    /** 레퍼런스 1장의 블록 배치 주석(학습용). blocks 값은 캔버스 대비 [x0,y0,x1,y1]. */
    record LayoutNotes(Map<String, double[]> blocks, boolean hasCopy, String description) {
    }

    LayoutNotes annotateLayout(BufferedImage image) throws Exception;

    /** 결과물 vs 기준 이미지 정성 평가. */
    record Evaluation(Map<String, Integer> scores, List<String> matches, List<String> differences,
                      List<String> defects, String summary) {
    }

    Evaluation evaluate(BufferedImage ours, BufferedImage reference, String formatLabel) throws Exception;
}
