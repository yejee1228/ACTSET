package com.actset.conversion;

import com.actset.conversion.engine.ElementSplitter;
import com.actset.conversion.engine.LayerCompositor;
import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.layout.FormatClassRule;
import com.actset.conversion.layout.LayoutBlock;
import com.actset.conversion.layout.LayoutLearner;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.layout.TempLayoutPriors;
import com.actset.format.FormatClass;
import com.actset.format.FormatPreset;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 규격변환 자체 엔진 3종 — 외부 API 없이 도는 단위 테스트(CLAUDE.md 규칙 9). */
class ConversionEnginesTest {

    @Test
    void 기준_이미지_비율이_5분류로_정확히_나뉜다() {
        assertThat(FormatClass.fromDimensions(7087, 21260)).isEqualTo(FormatClass.LONG_PORTRAIT);
        assertThat(FormatClass.fromDimensions(1080, 1920)).isEqualTo(FormatClass.PORTRAIT);
        assertThat(FormatClass.fromDimensions(1242, 1242)).isEqualTo(FormatClass.SQUARE);
        assertThat(FormatClass.fromDimensions(940, 440)).isEqualTo(FormatClass.LANDSCAPE);
        assertThat(FormatClass.fromDimensions(7559, 945)).isEqualTo(FormatClass.LONG_LANDSCAPE);
        // 기본 규격 13종이 모두 어느 한 분류에 들어간다
        for (FormatPreset p : FormatPreset.values()) {
            assertThat(FormatClass.fromDimensions(p.width(), p.height())).isNotNull();
        }
        assertThat(FormatClass.fromDimensions(1240, 1754)).isEqualTo(FormatClass.PORTRAIT);
        assertThat(FormatClass.fromDimensions(1920, 600)).isEqualTo(FormatClass.LONG_LANDSCAPE);
    }

    @Test
    void 요소분리_떨어진_덩어리는_따로_작은_부속은_큰_덩어리에_붙는다() {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(20, 20, 100, 100);      // 큰 덩어리 1
        g.fillRect(122, 60, 4, 4);         // 1 바로 옆 작은 부속(지팡이 별 같은)
        g.setColor(Color.BLUE);
        g.fillOval(250, 120, 120, 120);    // 큰 덩어리 2
        g.setColor(Color.GREEN);
        g.fillRect(30, 250, 2, 2);         // 먼지 — 버려져야 한다
        g.dispose();

        List<ElementSplitter.Element> parts = new ElementSplitter().split(img);

        assertThat(parts).hasSize(2);
        assertThat(parts).anySatisfy(p -> assertThat(p.bounds()).isEqualTo(new Rectangle(20, 20, 106, 100)));
        assertThat(parts).anySatisfy(p -> assertThat(p.bounds().x).isEqualTo(250));
        // 잘라낸 요소는 원래 픽셀을 그대로 가진다
        ElementSplitter.Element red = parts.stream().filter(p -> p.bounds().x == 20).findFirst().orElseThrow();
        assertThat(red.image().getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    void 합성엔진은_z순서대로_쌓는다() {
        BufferedImage red = solid(10, 10, Color.RED), blue = solid(10, 10, Color.BLUE);
        BufferedImage out = new LayerCompositor().compose(20, 20, Color.BLACK, List.of(
                new LayerCompositor.Layer("top", blue, new Rectangle2D.Double(5, 5, 10, 10), 2),
                new LayerCompositor.Layer("bottom", red, new Rectangle2D.Double(0, 0, 10, 10), 1)));
        assertThat(out.getRGB(7, 7)).isEqualTo(Color.BLUE.getRGB());
        assertThat(out.getRGB(2, 2)).isEqualTo(Color.RED.getRGB());
        assertThat(out.getRGB(18, 2)).isEqualTo(Color.BLACK.getRGB());
    }

    @Test
    void 배치엔진은_블록_안_상대배치를_유지하고_영역_안에_맞춘다() {
        // 원본 750x1000: 제목 두 줄(위), 피사체(가운데), 정보(아래)
        List<LayoutPlanner.SourceElement> els = List.of(
                el("bg", ElementRole.BACKDROP, 0, 0, 750, 1000),
                el("t1", ElementRole.TITLE, 100, 200, 400, 80),
                el("t2", ElementRole.TITLE, 100, 300, 500, 80),
                el("kv", ElementRole.SUBJECT, 200, 500, 350, 350),
                el("info", ElementRole.INFO, 100, 940, 550, 24));
        FormatClassRule rule = TempLayoutPriors.forClass(FormatClass.LANDSCAPE);

        LayoutPlanner.Plan plan = new LayoutPlanner().plan(750, 1000, els, rule, 940, 440);

        Rectangle2D t1 = target(plan, "t1"), t2 = target(plan, "t2");
        // 두 줄의 상대 위치(가로 정렬·세로 간격 비율)가 유지된다
        assertThat(t1.getX()).isEqualTo(t2.getX(), org.assertj.core.data.Offset.offset(0.5));
        assertThat((t2.getY() - t1.getY()) / t1.getHeight()).isEqualTo(100.0 / 80, org.assertj.core.data.Offset.offset(0.01));
        // 블록이 규칙 영역 안에 들어간다
        double[] r = rule.block(LayoutBlock.HEADLINE).region();
        Rectangle2D head = plan.blockRects().get(LayoutBlock.HEADLINE);
        assertThat(head.getX()).isGreaterThanOrEqualTo(r[0] * 940 - 0.5);
        assertThat(head.getMaxX()).isLessThanOrEqualTo(r[2] * 940 + 0.5);
        // 배경은 캔버스를 덮는다
        Rectangle2D bg = target(plan, "bg");
        assertThat(bg.getWidth()).isGreaterThanOrEqualTo(940 - 0.5);
        assertThat(bg.getHeight()).isGreaterThanOrEqualTo(440 - 0.5);
    }

    @Test
    void 정보가_최소가독크기보다_작아지면_생략된다() {
        List<LayoutPlanner.SourceElement> els = List.of(
                el("bg", ElementRole.BACKDROP, 0, 0, 750, 1000),
                el("info", ElementRole.INFO, 0, 900, 750, 10));
        LayoutPlanner.Plan plan = new LayoutPlanner().plan(750, 1000, els,
                TempLayoutPriors.forClass(FormatClass.LONG_LANDSCAPE), 2048, 256);
        assertThat(plan.blockRects()).doesNotContainKey(LayoutBlock.INFO);
        assertThat(plan.dropped()).anyMatch(s -> s.startsWith("info"));
    }

    @Test
    void 학습기는_표본_중앙값으로_규칙을_만들고_표본_없는_분류는_임시값을_쓴다() {
        List<LayoutLearner.LayoutAnnotation> samples = List.of(
                ann(FormatClass.SQUARE, new double[]{0.1, 0.1, 0.9, 0.4}),
                ann(FormatClass.SQUARE, new double[]{0.2, 0.2, 0.8, 0.5}),
                ann(FormatClass.SQUARE, new double[]{0.3, 0.3, 0.7, 0.6}));
        Map<FormatClass, FormatClassRule> rules = new LayoutLearner().learn(samples);

        FormatClassRule sq = rules.get(FormatClass.SQUARE);
        assertThat(sq.source()).isEqualTo("LEARNED");
        assertThat(sq.sampleCount()).isEqualTo(3);
        assertThat(sq.block(LayoutBlock.HEADLINE).region()).containsExactly(0.2, 0.2, 0.8, 0.5);
        assertThat(sq.block(LayoutBlock.INFO).show()).isFalse(); // 표본 어디에도 없음
        assertThat(rules.get(FormatClass.PORTRAIT).source()).isEqualTo("TEMP_PRIOR");
    }

    private static LayoutLearner.LayoutAnnotation ann(FormatClass c, double[] headline) {
        return new LayoutLearner.LayoutAnnotation("t", c, 1000, 1000, Map.of(LayoutBlock.HEADLINE, headline), true);
    }

    private static LayoutPlanner.SourceElement el(String id, ElementRole role, int x, int y, int w, int h) {
        return new LayoutPlanner.SourceElement(id, role, solid(w, h, Color.WHITE), new Rectangle(x, y, w, h), 0);
    }

    private static Rectangle2D target(LayoutPlanner.Plan plan, String id) {
        return plan.placements().stream().filter(p -> p.elementId().equals(id)).findFirst().orElseThrow().target();
    }

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }
}
