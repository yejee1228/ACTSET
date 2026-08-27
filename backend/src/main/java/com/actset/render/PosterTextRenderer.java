package com.actset.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * poc-java/PosterRenderer.java의 Layer2(텍스트) 합성을 서비스 계층으로 이식한다(1-10).
 * PoC와의 차이는 폰트 출처(리눅스 시스템 TTC → 번들 Pretendard)와 TextBlockSpec을
 * PerformanceInfo에서 만든다는 점뿐이며, 배치 알고리즘(세로 중앙 정렬 스택)은 동일하다.
 *
 * 렌더링 순서는 BACKDROP → SUBJECT → DECOR → PHOTO → TITLE → INFO → MARK다(docs/05).
 * 이 클래스는 TITLE·INFO만 담당한다 — PHOTO는 PhotoLayerRenderer(1-10b), SUBJECT·DECOR·
 * BACKDROP은 생성 파이프라인(1-11)·레이어 분해(1-15)가 담당한다.
 */
@Service
public class PosterTextRenderer {

    private static final float LINE_RATIO = 1.26f;

    private final FontRegistry fonts;
    private final ObjectMapper objectMapper;

    public PosterTextRenderer(FontRegistry fonts, ObjectMapper objectMapper) {
        this.fonts = fonts;
        this.objectMapper = objectMapper;
    }

    public record Result(BufferedImage image, ObjectNode objectMap) {
    }

    /**
     * base 위에 TITLE(공연명)과 INFO(나머지) 레이어를 합성한다.
     *
     * @param base      BACKDROP(+SUBJECT+DECOR+PHOTO)까지 합성된 원본 캔버스 — 변경하지 않고 복사해 그린다
     * @param titleText 공연명(TITLE 레이어, 항상 유지 — docs/05)
     * @param infoBlocks INFO 레이어에 쌓을 블록 목록(PerformanceInfoTextMapper 결과)
     * @param titleArea 0~1 상대좌표 [x0,y0,x1,y1] — 타이틀 배치 영역
     * @param infoArea  0~1 상대좌표 [x0,y0,x1,y1] — 정보 배치 영역
     */
    public Result render(BufferedImage base, String titleText, List<TextBlockSpec> infoBlocks,
                          double[] titleArea, double[] infoArea) {
        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, null);
        applyQualityHints(g);

        ObjectNode objectMap = objectMapper.createObjectNode();

        drawReadabilityBand(g, w, h, infoArea);
        drawTitle(g, w, h, titleText, titleArea, objectMap);
        drawInfoBlocks(g, w, h, infoBlocks, infoArea, objectMap);

        g.dispose();
        return new Result(out, objectMap);
    }

    private void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /** INFO 영역 위에 어두운 그라디언트 밴드를 깔아 배경이 밝아도 글자가 읽히게 한다(poc 동일). */
    private void drawReadabilityBand(Graphics2D g, int w, int h, double[] infoArea) {
        int ay0 = (int) (infoArea[1] * h);
        int bandTop = Math.max((int) (ay0 - h * 0.06), 0);
        for (int y = bandTop; y < h; y++) {
            float p = (y - bandTop) / (float) Math.max(h - bandTop, 1);
            g.setColor(new Color(8, 10, 18, (int) (200 * Math.min(p * 1.6f, 1f))));
            g.drawLine(0, y, w, y);
        }
    }

    /** TITLE — 항상 유지, 외곽선+그림자 효과(모드 A 기본, docs/05). */
    private void drawTitle(Graphics2D g, int w, int h, String titleText, double[] titleArea, ObjectNode objectMap) {
        int ax0 = (int) (titleArea[0] * w), ay0 = (int) (titleArea[1] * h);
        int ax1 = (int) (titleArea[2] * w), ay1 = (int) (titleArea[3] * h);
        int shortSide = Math.min(w, h);
        int titlePx = Math.max((int) (shortSide * 0.085f), 12);

        FontRenderContext frc = g.getFontRenderContext();
        Font font = fonts.black(titlePx);
        TextLayout tl = new TextLayout(titleText, font, frc);
        float tw = tl.getAdvance();
        float x = ax0 + ((ax1 - ax0) - tw) / 2f;
        float baseline = ay0 + (ay1 - ay0 + tl.getAscent() - tl.getDescent()) / 2f;

        Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, baseline));
        g.setColor(new Color(0, 0, 0, 150));
        g.fill(AffineTransform.getTranslateInstance(titlePx * 0.035, titlePx * 0.045).createTransformedShape(outline));
        g.setStroke(new BasicStroke(titlePx * 0.05f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(18, 12, 24));
        g.draw(outline);
        g.setColor(Color.WHITE);
        g.fill(outline);

        ObjectNode entry = objectMap.putObject("title");
        entry.put("layer", "TITLE");
        entry.put("x", ax0);
        entry.put("y", ay0);
        entry.put("w", ax1 - ax0);
        entry.put("h", ay1 - ay0);
        entry.put("font_px", titlePx);
        entry.put("bold", true);
        entry.put("effect", "outline_shadow");
    }

    /** INFO — 세로 중앙 정렬 스택. poc-java PosterRenderer.renderLayer2와 동일한 배치 로직. */
    private void drawInfoBlocks(Graphics2D g, int w, int h, List<TextBlockSpec> blocks,
                                 double[] infoArea, ObjectNode objectMap) {
        int ax0 = (int) (infoArea[0] * w), ay0 = (int) (infoArea[1] * h);
        int ax1 = (int) (infoArea[2] * w), ay1 = (int) (infoArea[3] * h);
        int shortSide = Math.min(w, h);
        int titlePx = Math.max((int) (shortSide * 0.085f), 12); // TITLE 크기를 기준으로 상대비율 적용
        FontRenderContext frc = g.getFontRenderContext();

        int totalH = 0;
        for (TextBlockSpec b : blocks) {
            int px = Math.max((int) (titlePx * b.sizeRatio()), 8);
            totalH += (int) (px * LINE_RATIO) + (int) (px * b.gapAfterRatio());
        }
        int y = ay0 + Math.max((ay1 - ay0 - totalH) / 2, 0);

        for (TextBlockSpec b : blocks) {
            int px = Math.max((int) (titlePx * b.sizeRatio()), 8);
            Font font = b.bold() ? fonts.bold(px) : fonts.regular(px);
            TextLayout tl = new TextLayout(b.text(), font, frc);
            int lineH = (int) (px * LINE_RATIO);
            float tw = tl.getAdvance();
            float x = ax0 + ((ax1 - ax0) - tw) / 2f;
            float baseline = y + (lineH - (tl.getAscent() + tl.getDescent())) / 2f + tl.getAscent();

            g.setColor("notice".equals(b.role()) ? new Color(215, 218, 228, 235) : Color.WHITE);
            tl.draw(g, x, baseline);

            ObjectNode entry = objectMap.putObject(b.role());
            entry.put("layer", "INFO");
            entry.put("x", ax0);
            entry.put("y", y);
            entry.put("w", ax1 - ax0);
            entry.put("h", lineH);
            entry.put("font_px", px);
            entry.put("bold", b.bold());

            y += lineH + (int) (px * b.gapAfterRatio());
        }
    }
}
