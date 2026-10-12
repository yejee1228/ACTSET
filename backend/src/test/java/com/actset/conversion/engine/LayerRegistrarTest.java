package com.actset.conversion.engine;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/** 생성 레이어 자체 보정(LayerRegistrar) — 합성 이미지로 각 단계가 의도대로 동작하는지 본다. 외부 호출 없음. */
class LayerRegistrarTest {

    static final int W = 400, H = 500;
    static final Color SKY = new Color(20, 40, 110), GOLD = new Color(240, 190, 60);

    @Test
    void register_returns_enlarged_element_to_original_size_and_position() {
        BufferedImage original = sky();
        fillOval(original, 60, 50, 60, 60, GOLD); // 원본의 "달"
        BufferedImage generated = transparent();
        fillOval(generated, 45, 35, 96, 96, GOLD); // 편집 모델이 1.6배로 키워 그린 달

        LayerRegistrar.Registered r = LayerRegistrar.register(generated, original, null);

        assertThat(r.placement()).isNotNull();
        assertThat(r.placement().scale()).isBetween(0.55, 0.70);
        assertThat(r.placement().toX()).isBetween(56, 64);
        assertThat(r.placement().toY()).isBetween(46, 54);
        assertThat(r.placement().after()).isLessThan(r.placement().before());
        assertThat(alpha(r.layer(), 90, 80)).isGreaterThan(200); // 원본 달 중심
        assertThat(alpha(r.layer(), 135, 125)).isZero(); // 키워 그렸던 바깥쪽
    }

    @Test
    void register_skips_layers_that_cover_most_of_the_canvas() {
        BufferedImage generated = transparent();
        fillRect(generated, 0, 0, W, H / 2 + 50, GOLD);
        assertThat(LayerRegistrar.register(generated, sky(), null).placement()).isNull();
    }

    @Test
    void trim_removes_invented_shape_but_keeps_parts_hidden_behind_other_elements() {
        BufferedImage original = sky();
        fillRect(original, 50, 200, 300, 12, GOLD); // 원본 곡선(가로 띠)
        BufferedImage generated = transparent();
        fillRect(generated, 50, 200, 300, 12, GOLD); // 맞게 그린 곡선
        fillOval(generated, 40, 40, 50, 50, GOLD); // 원본에 없는 모양(허공의 초승달 꼬리)
        fillRect(generated, 200, 212, 12, 120, GOLD); // 곡선에서 이어져 오브젝트 뒤로 지나가는 부분
        float[] occluded = new float[W * H];
        for (int y = 230; y < 340; y++) for (int x = 180; x < 240; x++) occluded[y * W + x] = 1; // 앞쪽 오브젝트

        LayerRegistrar.TrimResult t = LayerRegistrar.trim(generated, original, occluded);

        assertThat(alpha(t.layer(), 200, 205)).isGreaterThan(200); // 원본과 같은 곡선
        assertThat(alpha(t.layer(), 65, 65)).isZero(); // 없는 모양
        assertThat(alpha(t.layer(), 205, 300)).isGreaterThan(200); // 가려진 자리는 이어 그린 것으로 유지
        assertThat(t.keptHiddenPx()).isPositive();
    }

    @Test
    void trim_tolerates_small_offsets_from_the_original_path() {
        BufferedImage original = sky();
        fillRect(original, 50, 200, 300, 12, GOLD);
        BufferedImage generated = transparent();
        fillRect(generated, 50, 204, 300, 12, GOLD); // 4px 어긋남(긴 변 500의 1.2% = 6px 안)

        LayerRegistrar.TrimResult t = LayerRegistrar.trim(generated, original, null);

        assertThat(alpha(t.layer(), 200, 214)).isGreaterThan(200);
    }

    @Test
    void toneMatchThin_shifts_mean_toward_original_but_keeps_alpha() {
        BufferedImage original = sky();
        fillRect(original, 100, 100, 200, 200, GOLD);
        BufferedImage generated = transparent();
        fillRect(generated, 100, 100, 200, 200, new Color(220, 175, 75)); // 조금 어두운 금색

        LayerRegistrar.ToneResult r = LayerRegistrar.toneMatchThin(generated, original, null);

        Color c = new Color(r.layer().getRGB(200, 200), true);
        assertThat(c.getRed()).isBetween(236, 244);
        assertThat(c.getAlpha()).isEqualTo(255);
        assertThat(r.gain()).containsExactly(1.0, 1.0, 1.0);
    }

    @Test
    void blendOriginal_uses_original_where_visible_and_generated_only_where_covered() {
        BufferedImage original = sky();
        fillRect(original, 150, 150, 100, 100, GOLD); // 원본의 오브젝트(배경판에 들어가면 안 된다)
        BufferedImage generated = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        fillRect(generated, 0, 0, W, H, new Color(60, 60, 60)); // 생성 배경(질감이 다름)
        float[] occluded = new float[W * H];
        for (int y = 150; y < 250; y++) for (int x = 150; x < 250; x++) occluded[y * W + x] = 1;

        BufferedImage plate = LayerRegistrar.blendOriginal(generated, original, occluded,
                LayerRegistrar.BLEND_DILATE, LayerRegistrar.BLEND_FEATHER);

        assertThat(new Color(plate.getRGB(30, 30))).isEqualTo(SKY); // 보이는 하늘 = 원본
        assertThat(new Color(plate.getRGB(200, 200))).isEqualTo(new Color(60, 60, 60)); // 덮였던 자리 = 생성
    }

    // ------------------------------------------------------------------

    static BufferedImage sky() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        fillRect(img, 0, 0, W, H, SKY);
        return img;
    }

    static BufferedImage transparent() {
        return new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    }

    static void fillRect(BufferedImage img, int x, int y, int w, int h, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    static void fillOval(BufferedImage img, int x, int y, int w, int h, Color c) {
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillOval(x, y, w, h);
        g.dispose();
    }

    static int alpha(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) >>> 24;
    }
}
