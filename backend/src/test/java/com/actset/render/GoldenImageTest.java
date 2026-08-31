package com.actset.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-4 렌더링 골든 이미지 테스트. 기준 이미지(src/test/resources/golden/poster_golden.jpg,
 * PosterTextRendererTest와 동일한 입력으로 생성)와 픽셀 diff를 비교해 렌더링 엔진에
 * 의도치 않은 변화가 생기면 이 테스트가 실패한다.
 *
 * JPEG 재인코딩·폰트 힌팅 차는 환경에 따라 미세하게 달라질 수 있어 완전 동일 바이트가
 * 아니라 "색상 거리 임계값을 넘는 픽셀의 비율"로 비교한다 — 그래도 배치·색상·텍스트
 * 내용이 바뀌는 실제 회귀는 이 임계값을 넉넉히 넘는다.
 */
class GoldenImageTest {

    private static final double MAX_DIFFERING_PIXEL_RATIO = 0.02; // 2%
    private static final int COLOR_DISTANCE_THRESHOLD = 30;

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
    void rendersWithinToleranceOfGoldenImage() throws Exception {
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

        BufferedImage rendered = renderer.render(base, "겨울 나그네", infoBlocks, titleArea, infoArea).image();

        BufferedImage golden;
        try (InputStream in = getClass().getResourceAsStream("/golden/poster_golden.jpg")) {
            assertThat(in).as("golden 기준 이미지가 없습니다 — src/test/resources/golden/poster_golden.jpg 확인").isNotNull();
            golden = ImageIO.read(in);
        }

        assertThat(rendered.getWidth()).isEqualTo(golden.getWidth());
        assertThat(rendered.getHeight()).isEqualTo(golden.getHeight());

        long differing = 0;
        long total = (long) rendered.getWidth() * rendered.getHeight();
        for (int y = 0; y < rendered.getHeight(); y++) {
            for (int x = 0; x < rendered.getWidth(); x++) {
                if (colorDistance(rendered.getRGB(x, y), golden.getRGB(x, y)) > COLOR_DISTANCE_THRESHOLD) {
                    differing++;
                }
            }
        }
        double ratio = differing / (double) total;

        if (ratio > MAX_DIFFERING_PIXEL_RATIO) {
            File outDir = new File("target/render-test-out");
            outDir.mkdirs();
            ImageIO.write(rendered, "jpg", new File(outDir, "golden_mismatch_actual.jpg"));
        }
        assertThat(ratio)
                .as("골든 이미지 대비 %.2f%% 픽셀이 임계값(%d)을 넘게 달라짐 — target/render-test-out/golden_mismatch_actual.jpg 확인",
                        ratio * 100, COLOR_DISTANCE_THRESHOLD)
                .isLessThanOrEqualTo(MAX_DIFFERING_PIXEL_RATIO);
    }

    private double colorDistance(int rgb1, int rgb2) {
        int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
        int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
        return Math.sqrt(Math.pow(r1 - r2, 2) + Math.pow(g1 - g2, 2) + Math.pow(b1 - b2, 2));
    }
}
