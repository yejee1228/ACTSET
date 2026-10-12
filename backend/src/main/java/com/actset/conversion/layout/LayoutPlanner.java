package com.actset.conversion.layout;

import com.actset.conversion.engine.LayerCompositor;
import com.actset.conversion.layout.FormatClassRule.Align;
import com.actset.conversion.layout.FormatClassRule.BlockRule;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [E-A] 배치 엔진 — 분리된 요소와 규격 분류 규칙으로 대상 캔버스 위의 자리를 계산한다.
 *
 * <p>요소를 역할 블록(HEADLINE·KEYVISUAL·INFO·CREDIT·MARK)으로 묶고, 블록 안의 상대 배치는 원본 그대로 둔다.
 * 배치 하나를 정해 두지 않고 <b>후보를 여러 개 만들어 점수(LayoutScorer)로 고른다</b>(2026-10-12, 사용자 지적:
 * 정사각은 더 크게, 긴 규격은 주요 요소를 잘리더라도 크게 넣거나 못 키우면 빼기).
 * <pre>
 * BLOCKS  규칙 영역에 블록을 맞춘다. 키비주얼: 영역 안에 맞춤 | 넘침(잘림 허용, 크게) | 생략
 *         머리글: 전부 | 꾸밈 낱말(KEYWORD) 생략 | 제목만 — 뺄수록 제목이 커진다
 *         작은 글씨(CREDIT): 유지 | 생략,  키비주얼이 제목 영역 뒤까지 올라가기: 예 | 아니오
 * WHOLE   원본 구도를 통째로 확대(맞춤~채움 사이 5단계 × 세로 기준 3개). 캔버스 밖으로 나간 글자 블록만 안쪽으로 민다
 * </pre>
 * 배경판은 어느 후보든 캔버스를 cover-fit으로 채운다. 결과는 합성 엔진(LayerCompositor)에 그대로 넘길 레이어 목록이다.
 */
public final class LayoutPlanner {

    /** 블록을 원본보다 몇 배까지 키울지. 넘게 키우면 흐려진다 — 점수에서도 깎는다(임시값). */
    static final double MAX_UPSCALE = 4.0;
    /** DECOR를 블록에 붙일 때 블록 bbox를 원본 긴 변 대비 이만큼 넓혀 본다(임시값). */
    static final double DECOR_ATTACH_MARGIN = 0.08;
    /** 임시값 — 제목 블록에 붙일 수 있는 장식의 최대 크기(제목 상자 면적 대비). */
    static final double DECOR_HEADLINE_MAX = 0.5;
    /** 임시값 — 원본 캔버스의 이 비율 이상(가로·세로 모두)을 덮는 요소는 배경판처럼 다룬다. */
    static final double FULL_CANVAS = 0.85;
    /** 임시값 — 키비주얼 넘침 후보에서 키비주얼이 캔버스 안에 남아야 하는 최소 비율(가로·세로 각각). */
    static final double BLEED_MIN_VISIBLE = 0.62;
    /** 임시값 — 글자 블록이 캔버스 가장자리에서 떨어져야 하는 여백(짧은 변 대비). */
    static final double SAFE_MARGIN = 0.035;
    /** 임시값 — 키비주얼이 제목 뒤로 올라가는 후보에서 영역을 넓히는 비율(캔버스 대비). */
    static final double KV_OVERLAP_GROW = 0.22;
    /** 임시값 — 키비주얼이 세로로 넘칠 때 잘라 낼 부분 중 위쪽 몫(나머지는 아래). */
    static final double BLEED_TOP_SHARE = 0.15;
    /** 임시값 — 주 피사체 무리에 붙일 때 무리 bbox를 넓히는 비율(원본 긴 변 대비). */
    static final double PRIMARY_JOIN = 0.02;
    /** dropped 기록에서 보조 피사체 생략을 표시하는 말(점수에서 센다). */
    static final String SECONDARY = "보조피사체";
    /** 임시값 — 제목을 한 낱말씩 쌓는 후보에서 머리글 영역을 아래로 늘리는 비율(캔버스 높이 대비). */
    static final double STACK_GROW = 0.12;

    public record SourceElement(String id, ElementRole role, BufferedImage image, Rectangle bounds, int sourceZ) {
    }

    public record Placement(String elementId, ElementRole role, LayoutBlock block, Rectangle2D target) {
    }

    /**
     * @param variant     고른 후보 이름(로그·리포트용)
     * @param score       고른 후보의 점수
     * @param scoreParts  점수 항목별 값
     * @param alternatives 점수 상위 후보 요약(고른 것 포함)
     */
    public record Plan(int width, int height, List<LayerCompositor.Layer> layers, List<Placement> placements,
                       Map<LayoutBlock, Rectangle2D> blockRects, List<String> dropped, FormatClassRule rule,
                       String variant, double score, Map<String, Double> scoreParts, List<String> alternatives) {
    }

    /** 키비주얼: 영역 안에 맞춤 | 넘침 | 생략 | 주 피사체만 맞춤 | 주 피사체만 넘침(보조 피사체는 생략). */
    enum KeyVisual {
        CONTAIN, BLEED, DROP, PRIMARY_CONTAIN, PRIMARY_BLEED;

        boolean primaryOnly() {
            return this == PRIMARY_CONTAIN || this == PRIMARY_BLEED;
        }

        boolean bleed() {
            return this == BLEED || this == PRIMARY_BLEED;
        }
    }

    enum Headline { ALL, NO_KEYWORD, TITLE_ONLY }

    record Variant(KeyVisual kv, Headline headline, boolean keepCredit, boolean kvOverlap, HeadlineReflow.Mode reflow) {
        String name() {
            return "BLOCKS kv=" + kv + " headline=" + headline + (keepCredit ? "" : " -credit") + (kvOverlap ? " overlap" : "")
                    + (reflow == HeadlineReflow.Mode.ORIGINAL ? "" : " title=" + reflow);
        }
    }

    public Plan plan(int sourceWidth, int sourceHeight, List<SourceElement> elements,
                     FormatClassRule rule, int width, int height) {
        Grouping g = group(sourceWidth, sourceHeight, elements, rule);
        // 제목 다시 흘리기: 세로로 긴 캔버스는 낱말마다 줄 바꿈, 가로로 긴 캔버스는 한 줄
        List<HeadlineReflow.Mode> reflows = new ArrayList<>(List.of(HeadlineReflow.Mode.ORIGINAL));
        if (height > width * 1.3) reflows.add(HeadlineReflow.Mode.STACK);
        if (width > height * 1.6) reflows.add(HeadlineReflow.Mode.ONE_LINE);
        List<Plan> candidates = new ArrayList<>();
        List<FormatClassRule> templates = new ArrayList<>(List.of(rule));
        if (rule.formatClass() != null) templates.addAll(TempLayoutPriors.alternatives(rule.formatClass()));
        for (FormatClassRule tpl : templates)
        for (HeadlineReflow.Mode reflow : reflows) {
            if (reflow != HeadlineReflow.Mode.ORIGINAL && !g.reflowable(reflow)) continue;
            for (KeyVisual kv : KeyVisual.values()) {
                if (kv != KeyVisual.CONTAIN && g.members.get(LayoutBlock.KEYVISUAL) == null) continue;
                if (kv.primaryOnly() && !g.hasSecondary()) continue;
                for (Headline h : Headline.values()) {
                    if (h == Headline.NO_KEYWORD && !g.has(ElementRole.KEYWORD)) continue;
                    if (h == Headline.TITLE_ONLY && !g.has(ElementRole.COPY)) continue;
                    for (boolean credit : new boolean[]{true, false}) {
                        if (!credit && g.members.get(LayoutBlock.CREDIT) == null) continue;
                        for (boolean overlap : new boolean[]{false, true}) {
                            if (overlap && kv == KeyVisual.DROP) continue;
                            candidates.add(blocks(g, tpl, width, height, new Variant(kv, h, credit, overlap, reflow)));
                        }
                    }
                }
            }
        }
        for (double t : new double[]{0, 0.25, 0.5, 0.75, 1}) {
            for (double anchor : new double[]{0.5, 0.25, 0.75}) {
                candidates.add(whole(g, rule, width, height, t, anchor));
            }
        }
        LayoutScorer scorer = new LayoutScorer(); // 요소 마스크 캐시는 이 호출 안에서만 쓴다
        for (int i = 0; i < candidates.size(); i++) {
            Plan c = candidates.get(i);
            LayoutScorer.Score s = scorer.score(c, g.sourceScale(), sourceWidth, sourceHeight, g.presentRoles);
            candidates.set(i, withScore(c, s));
        }
        candidates.sort(Comparator.comparingDouble(Plan::score).reversed());
        List<String> alts = new ArrayList<>(candidates.stream().limit(6)
                .map(p -> String.format("%.3f %s", p.score(), p.variant())).toList());
        // 갈래(제목 다시 흘리기 방식·WHOLE)마다 최고 후보도 남긴다 — 왜 그 갈래가 졌는지 리포트에서 보려고
        Map<String, Plan> bestOfFamily = new LinkedHashMap<>();
        for (Plan c : candidates) bestOfFamily.putIfAbsent(family(c.variant()), c);
        bestOfFamily.forEach((f, c) -> alts.add(String.format("[%s 최고] %.3f %s %s", f, c.score(), c.variant(), c.scoreParts())));
        Plan best = candidates.get(0);
        return new Plan(best.width(), best.height(), best.layers(), best.placements(), best.blockRects(), best.dropped(),
                best.rule(), best.variant(), best.score(), best.scoreParts(), alts);
    }

    // ------------------------------------------------------------------ 묶기

    /** 역할 블록으로 묶은 결과. 후보마다 다시 묶지 않는다. */
    static final class Grouping {
        final int sw, sh;
        final List<SourceElement> backdrop = new ArrayList<>();
        final Map<LayoutBlock, List<SourceElement>> members = new EnumMap<>(LayoutBlock.class);
        final List<String> alwaysDropped = new ArrayList<>();
        final java.util.EnumSet<ElementRole> presentRoles = java.util.EnumSet.noneOf(ElementRole.class);

        Grouping(int sw, int sh) {
            this.sw = sw;
            this.sh = sh;
        }

        boolean has(ElementRole r) {
            return presentRoles.contains(r);
        }

        private final Map<HeadlineReflow.Mode, List<SourceElement>> reflowed = new EnumMap<>(HeadlineReflow.Mode.class);

        /** 머리글을 이 방식으로 다시 흘린 요소(캐시 — 후보마다 다시 자르지 않는다). 못 흘리면 null. */
        List<SourceElement> headline(HeadlineReflow.Mode mode) {
            List<SourceElement> h = members.get(LayoutBlock.HEADLINE);
            if (h == null || mode == HeadlineReflow.Mode.ORIGINAL) return h;
            return reflowed.computeIfAbsent(mode, m -> {
                List<SourceElement> r = HeadlineReflow.apply(h, m);
                return r == null ? List.of() : r;
            });
        }

        private List<SourceElement> primary;

        /**
         * 주 피사체 무리 — 알파 면적 × 가운데에 가까운 정도가 가장 큰 SUBJECT/PHOTO 하나와, 그와 (조금 넓혀) 겹치는 키비주얼
         * 요소를 차례로 모은 것. sandmagicshow: 피사체 7개가 화면 전체에 흩어져 키비주얼 블록이 캔버스만 해져 긴 가로에서
         * 아주 작아졌다 — 주 피사체(마술사·상자)만 남기면 크게 넣을 수 있다.
         */
        List<SourceElement> primaryKeyVisual() {
            if (primary != null) return primary;
            List<SourceElement> kv = members.getOrDefault(LayoutBlock.KEYVISUAL, List.of());
            SourceElement seed = null;
            double best = -1;
            for (SourceElement e : kv) {
                if (e.role() != ElementRole.SUBJECT && e.role() != ElementRole.PHOTO) continue;
                double dx = (e.bounds().getCenterX() - sw / 2.0) / sw, dy = (e.bounds().getCenterY() - sh / 2.0) / sh;
                double v = alphaMass(e.image()) * Math.max(0.1, 1 - 1.5 * Math.hypot(dx, dy));
                if (v > best) { best = v; seed = e; }
            }
            List<SourceElement> group = new ArrayList<>();
            if (seed != null) {
                group.add(seed);
                boolean grew = true;
                while (grew) {
                    grew = false;
                    Rectangle u = union(group);
                    int m = (int) (PRIMARY_JOIN * Math.max(sw, sh));
                    Rectangle grown = new Rectangle(u.x - m, u.y - m, u.width + 2 * m, u.height + 2 * m);
                    for (SourceElement e : kv) {
                        if (group.contains(e) || !grown.intersects(e.bounds())) continue;
                        // 커다란 보조 요소(성·고래)가 끼어들면 무리가 다시 캔버스만 해진다 — 무리보다 작고 대부분 그 안에 드는 것만
                        Rectangle inter = grown.intersection(e.bounds());
                        if ((double) inter.width * inter.height < 0.5 * e.bounds().width * e.bounds().height) continue;
                        group.add(e);
                        grew = true;
                    }
                }
            }
            primary = group;
            return primary;
        }

        boolean hasSecondary() {
            List<SourceElement> kv = members.get(LayoutBlock.KEYVISUAL);
            return kv != null && primaryKeyVisual().size() < kv.size();
        }

        boolean reflowable(HeadlineReflow.Mode mode) {
            List<SourceElement> r = headline(mode);
            return r != null && !r.isEmpty();
        }

        /** 원본 해상도 기준(요소 픽셀 = 원본 좌표 1:1). 해상도 개선(업스케일 자산)이 붙으면 이 값이 커진다. */
        double sourceScale() {
            return 1.0;
        }
    }

    private Grouping group(int sw, int sh, List<SourceElement> elements, FormatClassRule rule) {
        Grouping g = new Grouping(sw, sh);
        for (SourceElement e : elements) {
            // 캔버스를 거의 다 덮는 요소(넓게 퍼진 빛·안개 레이어 등)는 역할과 관계없이 배경판 변환을 따른다.
            // 블록에 넣으면 블록이 캔버스만큼 커져 제목·키비주얼이 작게 축소된다(PDF 시험: 장식·로고 전체 레이어 3장)
            if (fullCanvas(e, sw, sh) || e.role() == ElementRole.BACKDROP) {
                g.backdrop.add(e);
                continue;
            }
            switch (e.role()) {
                case TITLE, KEYWORD -> add(g.members, LayoutBlock.HEADLINE, e);
                case COPY -> {
                    if (rule.showCopy()) add(g.members, LayoutBlock.HEADLINE, e);
                    else g.alwaysDropped.add(e.id() + "(COPY — 이 규격은 부제·태그라인 생략)");
                }
                case SUBJECT, PHOTO -> add(g.members, LayoutBlock.KEYVISUAL, e);
                case INFO -> add(g.members, LayoutBlock.INFO, e);
                case CREDIT -> add(g.members, LayoutBlock.CREDIT, e);
                case MARK -> add(g.members, LayoutBlock.MARK, e);
                case NOISE -> g.alwaysDropped.add(e.id() + "(NOISE)");
                case FRAME -> g.alwaysDropped.add(e.id() + "(FRAME — 테두리는 비율에 맞춰 다시 그려야 해서 MVP는 생략)");
                case DECOR, BACKDROP -> {
                    // DECOR는 아래에서 블록이 정해진 뒤 붙인다
                }
            }
            if (e.role() != ElementRole.NOISE && e.role() != ElementRole.FRAME) g.presentRoles.add(e.role());
        }
        int longSide = Math.max(sw, sh);
        for (SourceElement e : elements) {
            if (e.role() == ElementRole.DECOR && !fullCanvas(e, sw, sh)) {
                add(g.members, nearestBlock(e, g.members, longSide), e);
            }
        }
        return g;
    }

    // ------------------------------------------------------------------ BLOCKS 후보

    private Plan blocks(Grouping g, FormatClassRule rule, int width, int height, Variant v) {
        List<LayerCompositor.Layer> layers = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        List<String> dropped = new ArrayList<>(g.alwaysDropped);
        Map<LayoutBlock, Rectangle2D> blockRects = new EnumMap<>(LayoutBlock.class);
        for (SourceElement e : g.backdrop) placeBackdrop(e, g.sw, g.sh, rule, width, height, layers, placements);

        for (Map.Entry<LayoutBlock, List<SourceElement>> entry : g.members.entrySet()) {
            LayoutBlock block = entry.getKey();
            List<SourceElement> list = new ArrayList<>(block == LayoutBlock.HEADLINE ? g.headline(v.reflow()) : entry.getValue());
            if (block == LayoutBlock.HEADLINE) {
                list.removeIf(e -> {
                    boolean drop = (v.headline() != Headline.ALL && e.role() == ElementRole.KEYWORD)
                            || (v.headline() == Headline.TITLE_ONLY && e.role() == ElementRole.COPY);
                    if (drop) dropped.add(e.id() + "(" + e.role() + " — 제목을 키우려고 생략)");
                    return drop;
                });
            }
            if (block == LayoutBlock.KEYVISUAL && v.kv().primaryOnly()) {
                List<SourceElement> keep = g.primaryKeyVisual();
                list.removeIf(e -> {
                    boolean drop = !keep.contains(e);
                    if (drop) dropped.add(e.id() + "(" + SECONDARY + " " + e.role() + " — 주 피사체를 키우려고 생략)");
                    return drop;
                });
            }
            if ((block == LayoutBlock.KEYVISUAL && v.kv() == KeyVisual.DROP) || (block == LayoutBlock.CREDIT && !v.keepCredit())) {
                list.forEach(e -> dropped.add(e.id() + "(" + block + " — 이 후보에서 생략)"));
                continue;
            }
            if (list.isEmpty()) continue;
            BlockRule br = blockRule(rule, block);
            if (!br.show()) {
                list.forEach(e -> dropped.add(e.id() + "(" + block + " — 이 규격에서 표시 안 함)"));
                continue;
            }
            Rectangle bbox = union(list);
            Rectangle2D region = toCanvas(br.region(), width, height);
            if (block == LayoutBlock.KEYVISUAL && v.kvOverlap()) region = growToward(region, rule, width, height);
            if (block == LayoutBlock.HEADLINE && v.reflow() == HeadlineReflow.Mode.STACK) {
                // 한 낱말씩 쌓으면 세로로 길어진다 — 머리글 영역을 아래로 늘려 키비주얼 윗부분과 겹치게 한다(기준 이미지 긴 세로: '방'이 달 위)
                region = new Rectangle2D.Double(region.getX(), region.getY(), region.getWidth(),
                        Math.min(height - region.getY(), region.getHeight() + STACK_GROW * height));
            }
            double s = Math.min(region.getWidth() / bbox.width, region.getHeight() / bbox.height);
            Rectangle2D clip = null;
            if (block == LayoutBlock.KEYVISUAL && v.kv().bleed()) {
                // 영역을 채우도록(cover) 키우되 캔버스 안에 BLEED_MIN_VISIBLE 이상은 남게 — 넘친 부분은 캔버스에서 잘린다
                double cover = Math.max(region.getWidth() / bbox.width, region.getHeight() / bbox.height);
                double limit = Math.min(width / (bbox.width * BLEED_MIN_VISIBLE), height / (bbox.height * BLEED_MIN_VISIBLE));
                s = Math.max(s, Math.min(cover, limit));
                clip = new Rectangle2D.Double(0, 0, width, height);
            }
            s = Math.min(s, MAX_UPSCALE);
            if (block == LayoutBlock.INFO || block == LayoutBlock.CREDIT) {
                // 정보 줄은 읽을 수 있는 크기가 안 나오면 뺀다(docs/12 min_readable_px). 기준은 역할별(LayoutScorer)
                double minPx = block == LayoutBlock.INFO
                        ? Math.max(rule.minTextPx(), LayoutScorer.minTextPx(ElementRole.INFO))
                        : LayoutScorer.minTextPx(ElementRole.CREDIT);
                int smallest = list.stream().mapToInt(e -> e.bounds().height).min().orElse(0);
                if (smallest * s < minPx) {
                    String px = String.format("%.1f", smallest * s);
                    list.forEach(e -> dropped.add(e.id() + "(" + block + " — 글자가 " + px + "px로 최소 " + minPx + "px 미만)"));
                    continue;
                }
            }
            double bw = bbox.width * s, bh = bbox.height * s;
            double ox = region.getX() + offset(br.alignX(), region.getWidth() - bw);
            double oy = region.getY() + offset(br.alignY(), region.getHeight() - bh);
            if (clip != null && bh > height) {
                // 넘침으로 세로가 캔버스를 넘으면 위는 조금만 자른다 — 인물의 머리가 잘리면 안 된다(sandmagicshow 가로: 마술사 머리 잘림)
                oy = -BLEED_TOP_SHARE * (bh - height);
            }
            blockRects.put(block, new Rectangle2D.Double(ox, oy, bw, bh));
            for (SourceElement e : list) {
                Rectangle2D t = new Rectangle2D.Double(
                        ox + (e.bounds().x - bbox.x) * s, oy + (e.bounds().y - bbox.y) * s,
                        e.bounds().width * s, e.bounds().height * s);
                layers.add(new LayerCompositor.Layer(e.id(), e.image(), t, e.sourceZ(), 1f, clip));
                placements.add(new Placement(e.id(), e.role(), block, t));
            }
        }
        liftTextAboveKeyVisual(layers, placements);
        String name = v.name() + (rule.source().startsWith("TEMP_PRIOR_") ? " tpl=" + rule.source().substring("TEMP_PRIOR_".length()) : "");
        return new Plan(width, height, layers, placements, blockRects, dropped, rule, name, 0, Map.of(), List.of());
    }

    /**
     * 블록 배치는 원본에 없던 겹침을 만든다(키비주얼 넘침·제목 뒤로 올리기). 그때 글자가 피사체 뒤로 숨지 않게 글자·로고 블록
     * 전체를 키비주얼보다 위로 올린다 — 블록 안의 위아래 순서는 그대로(같은 값만큼 올림). 정사각 1차: MAGIC·SHADOW가 달 뒤로 숨었다.
     * 원본 구도를 그대로 확대하는 WHOLE 후보는 원본 순서가 정답이라(제목이 인물 뒤로 일부러 들어간 포스터) 하지 않는다.
     */
    private static void liftTextAboveKeyVisual(List<LayerCompositor.Layer> layers, List<Placement> placements) {
        Map<String, LayoutBlock> blockOf = new java.util.HashMap<>();
        for (Placement p : placements) if (p.block() != null) blockOf.put(p.elementId(), p.block());
        int kvTop = Integer.MIN_VALUE, otherLow = Integer.MAX_VALUE;
        for (LayerCompositor.Layer l : layers) {
            LayoutBlock b = blockOf.get(l.name());
            if (b == null) continue;
            if (b == LayoutBlock.KEYVISUAL) kvTop = Math.max(kvTop, l.z());
            else otherLow = Math.min(otherLow, l.z());
        }
        if (kvTop == Integer.MIN_VALUE || otherLow == Integer.MAX_VALUE || otherLow > kvTop) return;
        int delta = kvTop + 1 - otherLow;
        for (int i = 0; i < layers.size(); i++) {
            LayerCompositor.Layer l = layers.get(i);
            LayoutBlock b = blockOf.get(l.name());
            if (b == null || b == LayoutBlock.KEYVISUAL) continue;
            layers.set(i, new LayerCompositor.Layer(l.name(), l.image(), l.target(), l.z() + delta, l.opacity(), l.clip()));
        }
    }

    /** 키비주얼 영역을 머리글 쪽으로 넓힌다 — 세로·정사각은 위로, 가로는 머리글이 있는 옆으로. */
    private static Rectangle2D growToward(Rectangle2D kv, FormatClassRule rule, int width, int height) {
        BlockRule head = rule.block(LayoutBlock.HEADLINE);
        Rectangle2D h = head.show() ? toCanvas(head.region(), width, height) : kv;
        if (width <= height * 1.2) {
            double grow = KV_OVERLAP_GROW * height;
            double y0 = Math.max(0, kv.getY() - grow);
            return new Rectangle2D.Double(kv.getX(), y0, kv.getWidth(), kv.getMaxY() - y0);
        }
        double grow = KV_OVERLAP_GROW * width;
        if (h.getCenterX() < kv.getCenterX()) {
            double x0 = Math.max(0, kv.getX() - grow);
            return new Rectangle2D.Double(x0, kv.getY(), kv.getMaxX() - x0, kv.getHeight());
        }
        return new Rectangle2D.Double(kv.getX(), kv.getY(), Math.min(width, kv.getMaxX() + grow) - kv.getX(), kv.getHeight());
    }

    // ------------------------------------------------------------------ WHOLE 후보

    /**
     * 원본 구도를 통째로 확대한다. t=0이면 원본 전체가 캔버스 안에 들어오는 배율(contain), t=1이면 캔버스를 채우는 배율(cover).
     * 글자·로고 블록이 캔버스 밖(여백 포함)으로 나가면 그 블록만 안쪽으로 밀고, 그래도 크면 줄인다. 키비주얼은 잘려도 둔다.
     */
    private Plan whole(Grouping g, FormatClassRule rule, int width, int height, double t, double anchor) {
        List<LayerCompositor.Layer> layers = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        Map<LayoutBlock, Rectangle2D> blockRects = new EnumMap<>(LayoutBlock.class);
        List<String> dropped = new ArrayList<>(g.alwaysDropped);
        for (SourceElement e : g.backdrop) placeBackdrop(e, g.sw, g.sh, rule, width, height, layers, placements);

        double contain = Math.min(width / (double) g.sw, height / (double) g.sh);
        double cover = Math.max(width / (double) g.sw, height / (double) g.sh);
        double s = Math.min(contain + (cover - contain) * t, MAX_UPSCALE);
        double ox = (width - g.sw * s) / 2, oy = (height - g.sh * s) * anchor;
        double margin = SAFE_MARGIN * Math.min(width, height);
        Rectangle2D canvas = new Rectangle2D.Double(0, 0, width, height);
        for (Map.Entry<LayoutBlock, List<SourceElement>> entry : g.members.entrySet()) {
            LayoutBlock block = entry.getKey();
            List<SourceElement> list = entry.getValue();
            if (list.isEmpty()) continue;
            Rectangle bbox = union(list);
            double bs = s, bx = ox + bbox.x * s, by = oy + bbox.y * s;
            if (block != LayoutBlock.KEYVISUAL) {
                // 글자 블록은 여백 안으로 — 크면 먼저 줄이고, 그다음 민다
                double maxW = width - 2 * margin, maxH = height - 2 * margin;
                double shrink = Math.min(1, Math.min(maxW / (bbox.width * s), maxH / (bbox.height * s)));
                if (shrink < 1) {
                    double cx = bx + bbox.width * s / 2, cy = by + bbox.height * s / 2;
                    bs = s * shrink;
                    bx = cx - bbox.width * bs / 2;
                    by = cy - bbox.height * bs / 2;
                }
                bx = clamp(bx, margin, width - margin - bbox.width * bs);
                by = clamp(by, margin, height - margin - bbox.height * bs);
                if (block == LayoutBlock.INFO || block == LayoutBlock.CREDIT) {
                    double minPx = LayoutScorer.minTextPx(block == LayoutBlock.INFO ? ElementRole.INFO : ElementRole.CREDIT);
                    int smallest = list.stream().mapToInt(e -> e.bounds().height).min().orElse(0);
                    if (smallest * bs < minPx) {
                        String px = String.format("%.1f", smallest * bs);
                        list.forEach(e -> dropped.add(e.id() + "(" + block + " — 글자가 " + px + "px로 최소 " + minPx + "px 미만)"));
                        continue;
                    }
                }
            }
            blockRects.put(block, new Rectangle2D.Double(bx, by, bbox.width * bs, bbox.height * bs));
            for (SourceElement e : list) {
                Rectangle2D r = new Rectangle2D.Double(bx + (e.bounds().x - bbox.x) * bs, by + (e.bounds().y - bbox.y) * bs,
                        e.bounds().width * bs, e.bounds().height * bs);
                layers.add(new LayerCompositor.Layer(e.id(), e.image(), r, e.sourceZ(), 1f,
                        block == LayoutBlock.KEYVISUAL ? canvas : null));
                placements.add(new Placement(e.id(), e.role(), block, r));
            }
        }
        String name = String.format("WHOLE fill=%.2f anchorY=%.2f", t, anchor);
        return new Plan(width, height, layers, placements, blockRects, dropped, rule, name, 0, Map.of(), List.of());
    }

    // ------------------------------------------------------------------ 공통

    private static String family(String variant) {
        if (variant.startsWith("WHOLE")) return "WHOLE";
        int i = variant.indexOf("title=");
        String f = i < 0 ? "BLOCKS" : "BLOCKS " + variant.substring(i, variant.indexOf(' ', i) < 0 ? variant.length() : variant.indexOf(' ', i));
        if (variant.contains("PRIMARY")) f += " 주피사체";
        if (variant.contains("tpl=")) f += " " + variant.substring(variant.indexOf("tpl="));
        return f;
    }

    private static Plan withScore(Plan p, LayoutScorer.Score s) {
        return new Plan(p.width(), p.height(), p.layers(), p.placements(), p.blockRects(), p.dropped(), p.rule(),
                p.variant(), s.total(), new LinkedHashMap<>(s.parts()), List.of());
    }

    /** 규칙에 CREDIT 영역이 없으면(학습 규칙은 아직 CREDIT 주석이 없다) 임시 사전값 영역을 쓴다. */
    private static BlockRule blockRule(FormatClassRule rule, LayoutBlock block) {
        BlockRule br = rule.block(block);
        if (block == LayoutBlock.CREDIT && !br.show() && "LEARNED".equals(rule.source()) && rule.formatClass() != null) {
            return TempLayoutPriors.forClass(rule.formatClass()).block(LayoutBlock.CREDIT);
        }
        return br;
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
        layers.add(new LayerCompositor.Layer(e.id(), e.image(), t, e.sourceZ(), 1f, new Rectangle2D.Double(0, 0, width, height)));
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
            // 큰 장식(제목 상자 면적의 DECOR_HEADLINE_MAX 넘게)은 제목에 붙이지 않는다 — 머리글이 그만큼 커져 좁은 띠에서 제목이
            // 작아진다(sandmagicshow 긴 가로: 제목 옆 금빛 곡선 318x597이 붙어 제목이 띠 높이의 1/3로 줄었다)
            if (block == LayoutBlock.HEADLINE
                    && decor.bounds().width * (double) decor.bounds().height > DECOR_HEADLINE_MAX * r.width * r.height) continue;
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
    private static boolean fullCanvas(SourceElement e, int sourceWidth, int sourceHeight) {
        return e.role() != ElementRole.BACKDROP && e.role() != ElementRole.NOISE && e.role() != ElementRole.FRAME
                && e.bounds().width >= FULL_CANVAS * sourceWidth && e.bounds().height >= FULL_CANVAS * sourceHeight;
    }

    private static double alphaMass(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), step = Math.max(1, Math.max(w, h) / 128);
        double m = 0;
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) m += (img.getRGB(x, y) >>> 24) / 255.0;
        return m * step * step;
    }

    private static double offset(Align a, double free) {
        return switch (a) {
            case START -> 0;
            case CENTER -> free / 2;
            case END -> free;
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return hi < lo ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, v));
    }

    private static Rectangle2D toCanvas(double[] r, int w, int h) {
        return new Rectangle2D.Double(r[0] * w, r[1] * h, (r[2] - r[0]) * w, (r[3] - r[1]) * h);
    }

    static Rectangle union(List<SourceElement> list) {
        Rectangle r = new Rectangle(list.get(0).bounds());
        for (SourceElement e : list) r = r.union(e.bounds());
        return r;
    }

    private static void add(Map<LayoutBlock, List<SourceElement>> m, LayoutBlock b, SourceElement e) {
        m.computeIfAbsent(b, k -> new ArrayList<>()).add(e);
    }
}
