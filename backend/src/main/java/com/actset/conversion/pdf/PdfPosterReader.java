package com.actset.conversion.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 포스터 PDF(캔바 "PDF 인쇄용" 등) 판독 — 추정이 아니라 PDF에 들어 있는 텍스트를 그대로 읽는다(LLM·폰트 대조 불필요).
 *
 * <ul>
 *   <li>글자 수집: 렌더링 엔진이 글자를 그리는 순간(showFontGlyph)을 가로채 문구·폰트 이름·크기·색·위치를 기록한다</li>
 *   <li>줄 묶기: 같은 폰트·크기·색이 같은 기준선에 이어지면 한 줄(run)</li>
 *   <li>글자 제외 렌더: 그림 요소 분해용(텍스트가 섞이지 않는다)</li>
 *   <li>줄별 렌더: 그 줄의 글자만 투명 배경에 그린 정확한 텍스트 레이어(벡터라 해상도 무관)</li>
 * </ul>
 * 글자를 윤곽선(패스)으로 바꿔 내보낸 PDF는 글자가 수집되지 않는다 — 그때는 이미지 경로로 분석한다(runs가 비어 있음).
 */
public final class PdfPosterReader {

    /** PDF에서 그려지는 글자 1개(그려지는 순서 = index). 좌표는 렌더 이미지 px. */
    public record Glyph(int index, String unicode, String fontName, float sizePx, int rgb,
                        double x, double baseline, double advance, double ascent, double descent) {
    }

    /** 한 줄(같은 폰트·크기·색, 같은 기준선). bounds는 이 줄만 렌더한 레이어의 알파 bbox(px). */
    public record TextRun(int order, String text, String fontName, float sizePx, int rgb, Set<Integer> glyphs,
                          Rectangle bounds, BufferedImage layer) {
    }

    public record Result(int width, int height, BufferedImage full, BufferedImage noText, List<TextRun> runs) {
    }

    /** @param longSide 렌더 이미지 긴 변(px) */
    public Result read(byte[] pdf, int longSide) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (doc.getNumberOfPages() == 0) throw new IOException("페이지가 없는 PDF");
            PDPage page = doc.getPage(0);
            PDRectangle box = page.getCropBox();
            float scale = longSide / Math.max(box.getWidth(), box.getHeight());

            BufferedImage full = new PDFRenderer(doc).renderImage(0, scale, ImageType.RGB);
            ModeRenderer noTextRenderer = new ModeRenderer(doc, Mode.NO_TEXT, null, null, scale, box);
            BufferedImage noText = noTextRenderer.renderImage(0, scale, ImageType.RGB);
            List<Glyph> glyphs = new ArrayList<>();
            new ModeRenderer(doc, Mode.COLLECT, null, glyphs, scale, box).renderImage(0, scale, ImageType.ARGB);

            List<TextRun> runs = new ArrayList<>();
            List<List<Glyph>> groups = group(glyphs);
            for (int i = 0; i < groups.size(); i++) {
                List<Glyph> g = groups.get(i);
                Set<Integer> ids = new HashSet<>();
                g.forEach(x -> ids.add(x.index()));
                BufferedImage layer = new ModeRenderer(doc, Mode.ONLY_GLYPHS, ids, null, scale, box)
                        .renderImage(0, scale, ImageType.ARGB);
                Rectangle bounds = alphaBounds(layer);
                if (bounds == null) continue; // 보이지 않는 글자(흰 바탕 흰 글자·숨은 텍스트 레이어)
                runs.add(new TextRun(i, text(g), g.get(0).fontName(), g.get(0).sizePx(), g.get(0).rgb(), ids, bounds,
                        layer.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height)));
            }
            return new Result(full.getWidth(), full.getHeight(), full, noText, runs);
        }
    }

    /** 그려지는 순서대로 이어 붙인다 — 폰트·색이 같고, 크기 ±8%, 기준선 차 ≤ 0.35×크기, 앞 글자와의 간격이 −0.3~1.0×크기면 같은 줄(임시값). */
    static List<List<Glyph>> group(List<Glyph> glyphs) {
        List<List<Glyph>> out = new ArrayList<>();
        List<Glyph> cur = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (!cur.isEmpty()) {
                Glyph p = cur.get(cur.size() - 1);
                boolean same = g.fontName().equals(p.fontName()) && g.rgb() == p.rgb()
                        && Math.abs(g.sizePx() - p.sizePx()) <= 0.08 * p.sizePx()
                        && Math.abs(g.baseline() - p.baseline()) <= 0.35 * p.sizePx();
                double gap = g.x() - (p.x() + p.advance());
                if (!same || gap < -0.3 * p.sizePx() || gap > 1.0 * p.sizePx()) {
                    out.add(cur);
                    cur = new ArrayList<>();
                }
            }
            cur.add(g);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    /** 글자 문구. 공백 글리프가 없는데 간격이 0.25×크기보다 넓으면 공백을 넣는다. */
    static String text(List<Glyph> g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.size(); i++) {
            if (i > 0) {
                Glyph p = g.get(i - 1);
                double gap = g.get(i).x() - (p.x() + p.advance());
                if (gap > 0.25 * p.sizePx() && !p.unicode().endsWith(" ") && !g.get(i).unicode().startsWith(" ")) {
                    sb.append(' ');
                }
            }
            sb.append(g.get(i).unicode());
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** 서브셋 접두어(ABCDEF+)를 뗀 폰트 이름. */
    static String cleanFontName(PDFont font) {
        String n = font.getName() != null ? font.getName() : "unknown";
        int plus = n.indexOf('+');
        return plus == 6 ? n.substring(7) : n;
    }

    static Rectangle alphaBounds(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] p = img.getRGB(0, 0, w, h, null, 0, w);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < p.length; i++) {
            if ((p[i] >>> 24) < 8) continue;
            int x = i % w, y = i / w;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private enum Mode { COLLECT, NO_TEXT, ONLY_GLYPHS }

    /** 같은 PDF를 모드별로 그린다. 글자 index는 모든 모드에서 같은 순서(같은 콘텐츠 스트림 처리)라 서로 맞는다. */
    private static final class ModeRenderer extends PDFRenderer {
        private final Mode mode;
        private final Set<Integer> only;
        private final List<Glyph> sink;
        private final float scale;
        private final PDRectangle box;

        ModeRenderer(PDDocument doc, Mode mode, Set<Integer> only, List<Glyph> sink, float scale, PDRectangle box) {
            super(doc);
            this.mode = mode;
            this.only = only;
            this.sink = sink;
            this.scale = scale;
            this.box = box;
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                private int index = 0;

                @Override
                protected void showFontGlyph(Matrix m, PDFont font, int code, Vector displacement) throws IOException {
                    int i = index++;
                    if (mode == Mode.COLLECT) {
                        record(m, font, code, displacement, i);
                    } else if (mode == Mode.ONLY_GLYPHS && only.contains(i)) {
                        super.showFontGlyph(m, font, code, displacement);
                    }
                }

                @Override
                protected void showType3Glyph(Matrix m, PDType3Font font, int code, Vector displacement) throws IOException {
                    int i = index++;
                    if (mode == Mode.COLLECT) {
                        record(m, font, code, displacement, i);
                    } else if (mode == Mode.ONLY_GLYPHS && only.contains(i)) {
                        super.showType3Glyph(m, font, code, displacement);
                    }
                }

                private void record(Matrix m, PDFont font, int code, Vector displacement, int i) throws IOException {
                    String u = font.toUnicode(code);
                    if (u == null) u = "�";
                    float size = (float) Math.hypot(m.getScaleX(), m.getShearY()) * scale;
                    PDColor color = getGraphicsState().getNonStrokingColor();
                    int rgb;
                    try {
                        rgb = color.toRGB();
                    } catch (Exception e) {
                        rgb = 0;
                    }
                    PDFontDescriptor fd = font.getFontDescriptor();
                    double asc = fd != null && fd.getAscent() != 0 ? fd.getAscent() / 1000.0 : 0.8;
                    double desc = fd != null && fd.getDescent() != 0 ? -fd.getDescent() / 1000.0 : 0.2;
                    double x = (m.getTranslateX() - box.getLowerLeftX()) * scale;
                    double y = (box.getUpperRightY() - m.getTranslateY()) * scale;
                    double adv = displacement.getX() * size;
                    sink.add(new Glyph(i, u, cleanFontName(font), size, rgb, x, y, adv, asc * size, desc * size));
                }

                @Override
                public void fillPath(int windingRule) throws IOException {
                    if (mode == Mode.NO_TEXT) super.fillPath(windingRule);
                }

                @Override
                public void strokePath() throws IOException {
                    if (mode == Mode.NO_TEXT) super.strokePath();
                }

                @Override
                public void fillAndStrokePath(int windingRule) throws IOException {
                    if (mode == Mode.NO_TEXT) super.fillAndStrokePath(windingRule);
                }

                @Override
                public void drawImage(PDImage pdImage) throws IOException {
                    if (mode == Mode.NO_TEXT) super.drawImage(pdImage);
                }

                @Override
                public void shadingFill(COSName shadingName) throws IOException {
                    if (mode == Mode.NO_TEXT) super.shadingFill(shadingName);
                }

                @Override
                public void showAnnotation(PDAnnotation annotation) throws IOException {
                    if (mode == Mode.NO_TEXT) super.showAnnotation(annotation);
                }
            };
        }
    }
}
