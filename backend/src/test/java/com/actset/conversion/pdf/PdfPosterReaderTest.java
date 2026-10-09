package com.actset.conversion.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 캔바식 포스터 PDF(배경 이미지 + 폰트 텍스트 + 오프셋 그림자 사본)를 만들어 판독기를 검증한다. 외부 API 없음. */
class PdfPosterReaderTest {

    @Test
    void 텍스트_문구_폰트_색_위치를_그대로_읽고_글자_없는_그림을_따로_그린다() throws Exception {
        byte[] pdf = samplePdf();

        PdfPosterReader.Result r = new PdfPosterReader().read(pdf, 1000);

        assertThat(r.height()).isEqualTo(1000);
        List<PdfPosterReader.TextRun> runs = r.runs();
        // 제목(그림자 사본 1 + 본체 1) + 정보 줄 = 3줄
        assertThat(runs).hasSize(3);
        assertThat(runs).extracting(PdfPosterReader.TextRun::text)
                .containsExactly("마술사의 방", "마술사의 방", "주최 인생마술 | 문의 010-0000-0000");
        assertThat(runs.get(0).fontName()).contains("DoHyeon");
        assertThat(runs.get(0).rgb() & 0xffffff).isEqualTo(0xE040A0); // 그림자(분홍)가 먼저 그려진다
        assertThat(runs.get(1).rgb() & 0xffffff).isEqualTo(0xFFDE5E); // 본체(노랑)
        // 그림자는 본체를 (-4,-3)pt 옮긴 사본 — 레이어 bbox도 그만큼 어긋난다(1000px/750pt ≈ 1.333배)
        double dx = runs.get(0).bounds().getX() - runs.get(1).bounds().getX();
        assertThat(dx).isBetween(-7.0, -3.5);
        // 제목 크기 = 60pt → 80px
        assertThat(runs.get(1).sizePx()).isBetween(79f, 81f);

        // 글자 없는 렌더: 제목 자리가 배경색(남색)이어야 한다
        int cx = (int) runs.get(1).bounds().getCenterX(), cy = (int) runs.get(1).bounds().getCenterY();
        Color withText = new Color(r.full().getRGB(cx, cy));
        Color noText = new Color(r.noText().getRGB(cx, cy));
        assertThat(noText.getBlue()).isGreaterThan(noText.getRed() + 40);
        assertThat(withText).isNotEqualTo(noText);
        // 줄 레이어는 투명 배경 + 글자색
        BufferedImage layer = runs.get(1).layer();
        assertThat(layer.getColorModel().hasAlpha()).isTrue();
    }

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(562.5f, 750f)); // 3:4 세로 포스터
            doc.addPage(page);
            BufferedImage bg = new BufferedImage(375, 500, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bg.createGraphics();
            g.setColor(new Color(20, 30, 110));
            g.fillRect(0, 0, 375, 500);
            g.setColor(new Color(200, 210, 240));
            g.fillOval(120, 260, 140, 140); // 달
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bg);
            PDType0Font font;
            try (InputStream in = PdfPosterReaderTest.class.getResourceAsStream("/fonts/DoHyeon-Regular.ttf")) {
                font = PDType0Font.load(doc, in);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 0, 0, 562.5f, 750f);
                // 그림자 사본(분홍, -4,-3pt 이동) → 본체(노랑)
                text(cs, font, 60, new Color(0xE0, 0x40, 0xA0), 60 - 4, 600 + 3, "마술사의 방");
                text(cs, font, 60, new Color(0xFF, 0xDE, 0x5E), 60, 600, "마술사의 방");
                text(cs, font, 14, Color.WHITE, 120, 40, "주최 인생마술 | 문의 010-0000-0000");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void text(PDPageContentStream cs, PDType0Font font, float size, Color c, float x, float y, String s)
            throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(c);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
