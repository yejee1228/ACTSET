package com.actset.conversion.layout;

import com.actset.conversion.engine.LayerCompositor;
import com.actset.conversion.layout.FormatClassRule.Align;
import com.actset.conversion.layout.FormatClassRule.BlockRule;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * [E-A] 배치 엔진 — 분리된 요소와 규격 분류 규칙으로 대상 캔버스 위의 자리를 계산한다.
 *
 * <p>요소를 역할 블록(HEADLINE·KEYVISUAL·INFO·MARK)으로 묶고, 블록 안의 상대 배치는 원본 그대로 둔 채
 * 블록 전체를 규칙 영역 안에 비율 유지(contain)로 맞춘다. 배경판은 캔버스를 cover-fit으로 채운다.
 * 결과는 합성 엔진(LayerCompositor)에 그대로 넘길 레이어 목록이다.
 */
public final class LayoutPlanner {

    /** 블록을 원본보다 몇 배까지 키울지. 분해 레이어 해상도가 원본 이하라 크게 키우면 흐려진다(임시값). */
    static final double MAX_UPSCALE = 3.0;
    /** DECOR를 블록에 붙일 때 블록 bbox를 원본 긴 변 대비 이만큼 넓혀 본다(임시값). */
    static final double DECOR_ATTACH_MARGIN = 0.08;

    public record SourceElement(String id, ElementRole role, BufferedImage image, Rectangle bounds, int sourceZ) {
    }

    public record Placement(String elementId, ElementRole role, LayoutBlock block, Rectangle2D target) {
    }

    public record Plan(int width, int height, List<LayerCompositor.Layer> layers, List<Placement> placements,
                       Map<LayoutBlock, Rectangle2D> blockRects, List<String> dropped, FormatClassRule rule) {
    }

    public Plan plan(int sourceWidth, int sourceHeight, List<SourceElement> elements,
                     FormatClassRule rule, int width, int height) {
        List<LayerCompositor.Layer> layers = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Map<LayoutBlock, List<SourceElement>> members = new EnumMap<>(LayoutBlock.class);

        for (SourceElement e : elements) {
            switch (e.role()) {
                case BACKDROP -> placeBackdrop(e, sourceWidth, sourceHeight, rule, width, height, layers, placements);
                case TITLE -> add(members, LayoutBlock.HEADLINE, e);
                case COPY -> {
                    if (rule.showCopy()) add(members, LayoutBlock.HEADLINE, e);
                    else dropped.add(e.id() + "(COPY — 이 규격은 부제·태그라인 생략)");
                }
                case SUBJECT, PHOTO -> add(members, LayoutBlock.KEYVISUAL, e);
                case INFO -> add(members, LayoutBlock.INFO, e);
                case MARK -> add(members, LayoutBlock.MARK, e);
                case NOISE -> dropped.add(e.id() + "(NOISE)");
                case DECOR -> {
                    // 아래에서 블록이 정해진 뒤 붙인다
                }
            }
        }
        int longSide = Math.max(sourceWidth, sourceHeight);
        for (SourceElement e : elements) {
            if (e.role() == ElementRole.DECOR) add(members, nearestBlock(e, members, longSide), e);
        }

        Map<LayoutBlock, Rectangle2D> blockRects = new EnumMap<>(LayoutBlock.class);
        for (Map.Entry<LayoutBlock, List<SourceElement>> entry : members.entrySet()) {
            LayoutBlock block = entry.getKey();
            BlockRule br = rule.block(block);
            if (!br.show()) {
                entry.getValue().forEach(e -> dropped.add(e.id() + "(" + block + " — 이 규격에서 표시 안 함)"));
                continue;
            }
            Rectangle bbox = union(entry.getValue());
            Rectangle2D region = toCanvas(br.region(), width, height);
            double s = Math.min(Math.min(region.getWidth() / bbox.width, region.getHeight() / bbox.height), MAX_UPSCALE);
            if (block == LayoutBlock.INFO) {
                int smallest = entry.getValue().stream().mapToInt(e -> e.bounds().height).min().orElse(0);
                if (smallest * s < rule.minTextPx()) {
                    String px = String.format("%.1f", smallest * s);
                    entry.getValue().forEach(e -> dropped.add(e.id() + "(INFO — 글자가 " + px
                            + "px로 최소 가독 " + rule.minTextPx() + "px 미만)"));
                    continue;
                }
            }
            double bw = bbox.width * s, bh = bbox.height * s;
            double ox = region.getX() + offset(br.alignX(), region.getWidth() - bw);
            double oy = region.getY() + offset(br.alignY(), region.getHeight() - bh);
            blockRects.put(block, new Rectangle2D.Double(ox, oy, bw, bh));
            for (SourceElement e : entry.getValue()) {
                Rectangle2D t = new Rectangle2D.Double(
                        ox + (e.bounds().x - bbox.x) * s, oy + (e.bounds().y - bbox.y) * s,
                        e.bounds().width * s, e.bounds().height * s);
                layers.add(new LayerCompositor.Layer(e.id(), e.image(), t, z(e)));
                placements.add(new Placement(e.id(), e.role(), block, t));
            }
        }
        return new Plan(width, height, layers, placements, blockRects, dropped, rule);
    }

    /**
     * 배경판은 원본 캔버스 전체를 대상 캔버스에 cover-fit 하는 변환 하나를 공유한다. 화면 전체를 덮는 배경판뿐 아니라
     * 배경 위에 얹히는 부분 레이어(하단 구름띠 등 LLM이 BACKDROP으로 본 요소)도 같은 변환으로 제자리에 따라간다.
     */
    private void placeBackdrop(SourceElement e, int sourceWidth, int sourceHeight, FormatClassRule rule, int width,
                               int height, List<LayerCompositor.Layer> layers, List<Placement> placements) {
        double s = Math.max(width / (double) sourceWidth, height / (double) sourceHeight);
        double ox = (width - sourceWidth * s) / 2, oy = (height - sourceHeight * s) * rule.backdropAnchorY();
        Rectangle2D t = new Rectangle2D.Double(ox + e.bounds().x * s, oy + e.bounds().y * s,
                e.bounds().width * s, e.bounds().height * s);
        layers.add(new LayerCompositor.Layer(e.id(), e.image(), t, z(e), 1f, new Rectangle2D.Double(0, 0, width, height)));
        placements.add(new Placement(e.id(), e.role(), null, t));
    }

    /** DECOR는 가까운 블록(넓힌 bbox 안에 중심이 들어오는 블록, 없으면 거리 최소)에 붙는다. 블록이 없으면 KEYVISUAL. */
    private LayoutBlock nearestBlock(SourceElement decor, Map<LayoutBlock, List<SourceElement>> members, int longSide) {
        double cx = decor.bounds().getCenterX(), cy = decor.bounds().getCenterY();
        LayoutBlock best = LayoutBlock.KEYVISUAL;
        double bestDist = Double.MAX_VALUE;
        for (LayoutBlock block : List.of(LayoutBlock.HEADLINE, LayoutBlock.KEYVISUAL)) {
            List<SourceElement> list = members.get(block);
            if (list == null || list.isEmpty()) continue;
            Rectangle r = union(list);
            int m = (int) (DECOR_ATTACH_MARGIN * longSide);
            Rectangle grown = new Rectangle(r.x - m, r.y - m, r.width + 2 * m, r.height + 2 * m);
            double dx = Math.max(0, Math.max(grown.x - cx, cx - grown.getMaxX()));
            double dy = Math.max(0, Math.max(grown.y - cy, cy - grown.getMaxY()));
            double d = Math.hypot(dx, dy);
            if (d < bestDist) {
                bestDist = d;
                best = block;
            }
        }
        return best;
    }

    /**
     * 쌓는 순서는 요소가 가진 sourceZ를 그대로 따른다. 기존 포스터를 분해한 요소는 원본의 위아래 순서가 정답이라
     * 역할 기본 순서(docs/05 BACKDROP→…→MARK)로 다시 정렬하면 안 된다 — 2차 실행에서 달 글로우(DECOR)가 마술사
     * (SUBJECT) 위로 올라가 실루엣이 뿌옇게 덮였다. 역할 순서가 필요한 생산자(시안 생성 경로)는 sourceZ에 반영해 넘긴다.
     */
    private static int z(SourceElement e) {
        return e.sourceZ();
    }

    private static double offset(Align a, double free) {
        return switch (a) {
            case START -> 0;
            case CENTER -> free / 2;
            case END -> free;
        };
    }

    private static Rectangle2D toCanvas(double[] r, int w, int h) {
        return new Rectangle2D.Double(r[0] * w, r[1] * h, (r[2] - r[0]) * w, (r[3] - r[1]) * h);
    }

    private static Rectangle union(List<SourceElement> list) {
        Rectangle r = new Rectangle(list.get(0).bounds());
        for (SourceElement e : list) r = r.union(e.bounds());
        return r;
    }

    private static void add(Map<LayoutBlock, List<SourceElement>> m, LayoutBlock b, SourceElement e) {
        m.computeIfAbsent(b, k -> new ArrayList<>()).add(e);
    }
}
