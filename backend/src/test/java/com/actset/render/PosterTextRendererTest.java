package com.actset.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * poc-java/PosterRenderer.java와 동일한 결과를 내는지 확인한다(1-10 완료기준).
 * 배경·블록 구성은 PoC의 placeholderVisual()·blocks()를 그대로 옮겨 조건을 맞췄다.
 */
class PosterTextRendererTest {

    /** poc-java/PosterRenderer.placeholderVisual과 동일한 그라디언트+원형 장식 배경. */
    private BufferedImage placeholderVisual(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color top = new Color(58, 44, 92), bottom = new Color(14, 18, 34);
        for (int y = 0; y < h; y++) {
            float p = Math.min(y / (float) h * 1.25f, 1f);
            g.setColor(new Color(
                    (int) (top.getRed() + (bottom.getRed() - top.getRed()) * p),
                    (int) (top.getGreen() + (bottom.getGreen() - top.getGreen()) * p),
                    (int) (top.getBlue() + (bottom.getBlue() - top.getBlue()) * p)));
            g.drawLine(0, y, w, y);
        }
        double cx = w * 0.5, cy = h * 0.33, r = Math.min(w, h) * 0.30;
        g.setStroke(new BasicStroke(Math.max(w * 0.004f, 2f)));
        for (int k = 0; k < 7; k++) {
            double rr = r * (1 - k * 0.10);
            double off = Math.sin(1 + k) * r * 0.12;
            g.setColor(new Color(150, 140, 210, 150 - k * 8));
            g.draw(new Ellipse2D.Double(cx - rr + off, cy - rr, rr * 2, rr * 2));
        }
        g.dispose();
        return img;
    }

    @Test
    void rendersSameLayoutAsPoc() throws Exception {
        FontRegistry fonts = new FontRegistry();
        fonts.load();
        PosterTextRenderer renderer = new PosterTextRenderer(fonts, new ObjectMapper());

        int w = 1240, h = 1754;
        BufferedImage base = placeholderVisual(w, h);

        List<TextBlockSpec> infoBlocks = List.of(
                new TextBlockSpec("subtitle", "슈베르트 연가곡 전곡", 0.42f, false, 0.55f),
                new TextBlockSpec("date", "2026. 3. 14 (토) 19:30", 0.34f, true, 0.08f),
                new TextBlockSpec("venue", "예술의전당 리사이틀홀", 0.34f, false, 0.45f),
                new TextBlockSpec("running", "러닝타임 70분 (인터미션 없음)", 0.26f, false, 0.10f),
                new TextBlockSpec("cast", "김서현(바리톤)  이지우(피아노)", 0.26f, false, 0.10f),
                new TextBlockSpec("price", "R석 50,000원  S석 30,000원", 0.26f, false, 0.10f),
                new TextBlockSpec("age", "8세 이상 관람", 0.26f, false, 0.10f),
                new TextBlockSpec("organizer", "주최 반달컴퍼니   주관 ACTSET", 0.26f, false, 0.08f),
                new TextBlockSpec("inquiry", "문의 02-000-0000", 0.26f, false, 0.35f),
                new TextBlockSpec("notice", "본 공연은 한국문화예술위원회의 후원으로 제작되었습니다.", 0.20f, false, 0.10f)
        );

        double[] titleArea = {0.08, 0.60, 0.92, 0.66};
        double[] infoArea = {0.08, 0.60, 0.92, 0.95};

        PosterTextRenderer.Result result = renderer.render(base, "겨울 나그네", infoBlocks, titleArea, infoArea);

        assertThat(result.image().getWidth()).isEqualTo(w);
        assertThat(result.image().getHeight()).isEqualTo(h);
        // title 1개 + info 10개 = poc-java 출력 로그("object_map 항목 수 = 11")와 동일
        assertThat(result.objectMap().size()).isEqualTo(11);
        assertThat(result.objectMap().get("title").get("layer").asText()).isEqualTo("TITLE");
        assertThat(result.objectMap().get("date").get("layer").asText()).isEqualTo("INFO");

        File outDir = new File("target/render-test-out");
        outDir.mkdirs();
        ImageIO.write(result.image(), "jpg", new File(outDir, "java_poster_ported.jpg"));
    }
}
