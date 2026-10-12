package com.actset.conversion.layout;

import com.actset.conversion.engine.ElementSplitter;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 제목 다시 흘리기 — 제목 이미지(여러 겹 레이어)를 낱말 덩어리로 잘라 새 줄바꿈으로 다시 쌓는다. 글자를 새로 그리지 않고
 * 원본 픽셀을 옮기기만 한다. 긴 세로는 한 낱말씩 줄을 바꾸고(STACK), 긴 가로는 한 줄로 늘어놓는다(ONE_LINE).
 * (2026-10-12 사용자 지적: 긴 세로·가로는 글씨 크기를 달리해야 한다 — 기준 이미지의 긴 세로는 제목이 4줄)
 *
 * <pre>
 * 1. 제목 요소 알파 합집합 → 연결요소(가로 1px 넓힘)
 * 2. 위아래로 겹쳐 쌓인 조각(자모)·서로 겹치는 조각을 합친다 → 음절
 * 3. 높이가 비슷하고(STACK_HEIGHT_RATIO 이하) 같은 줄에서 가까운(WORD_GAP 이하) 덩어리를 합친다 → 낱말
 *    마술사의 방: 낱말 사이 6px이 '방' 자모 사이 7px보다 좁아 간격만으로는 못 나눈다 — 높이(91 vs 219)로 나눈다
 * 4. 읽는 순서(줄 → 왼쪽부터)로 다시 배치. 부제(COPY)는 위, 꾸밈 낱말(KEYWORD)은 아래, 장식(DECOR)은 제목 상자 안 상대 위치
 * </pre>
 * 임계값은 모두 임시값이다(CLAUDE.md 규칙 5).
 */
public final class HeadlineReflow {

    public enum Mode { ORIGINAL, STACK, ONE_LINE }

    static final int ALPHA = 40;
    /** 임시값 — 낱말로 합칠 가로 간격(작은 쪽 높이 대비). 반짝반짝↔빛나는 0.22, 마술사의 안 0.02. */
    static final double WORD_GAP = 0.15;
    /** 임시값 — 같은 낱말로 볼 높이 비(큰 쪽/작은 쪽). */
    static final double STACK_HEIGHT_RATIO = 1.35;
    /** 임시값 — 다시 쌓을 때 줄 간격·낱말 간격(낱말 높이 평균 대비). */
    static final double LINE_GAP = 0.08, SPACE = 0.25;
    /** 이보다 작은 조각(제목 bbox 면적 대비)은 낱말을 만들지 않고 가까운 낱말에 붙는다. */
    static final double SPECK = 0.001;

    private HeadlineReflow() {
    }

    /** 제목에서 찾은 낱말 사각형(원본 좌표), 읽는 순서. 둘 미만이면 다시 흘릴 수 없다. */
    public static List<Rectangle> words(List<LayoutPlanner.SourceElement> titles) {
        if (titles.isEmpty()) return List.of();
        Rectangle tb = LayoutPlanner.union(titles);
        boolean[] mask = new boolean[tb.width * tb.height];
        for (LayoutPlanner.SourceElement e : titles) {
            int[] px = e.image().getRGB(0, 0, e.image().getWidth(), e.image().getHeight(), null, 0, e.image().getWidth());
            for (int y = 0; y < e.image().getHeight(); y++) {
                for (int x = 0; x < e.image().getWidth(); x++) {
                    if ((px[y * e.image().getWidth() + x] >>> 24) <= ALPHA) continue;
                    int mx = e.bounds().x - tb.x + x, my = e.bounds().y - tb.y + y;
                    // 가로 1px 넓힘 — 획 사이 1px 틈으로 한 글자가 갈라지지 않게
                    for (int d = -1; d <= 1; d++) {
                        int xx = mx + d;
                        if (xx >= 0 && xx < tb.width && my >= 0 && my < tb.height) mask[my * tb.width + xx] = true;
                    }
                }
            }
        }
        int[] labels = ElementSplitter.label8(mask, tb.width, tb.height);
        int n = 0;
        for (int l : labels) n = Math.max(n, l);
        int[][] box = new int[n + 1][];
        for (int i = 0; i < labels.length; i++) {
            int l = labels[i];
            if (l == 0) continue;
            int x = i % tb.width, y = i / tb.width;
            if (box[l] == null) box[l] = new int[]{x, y, x, y};
            else {
                box[l][0] = Math.min(box[l][0], x); box[l][1] = Math.min(box[l][1], y);
                box[l][2] = Math.max(box[l][2], x); box[l][3] = Math.max(box[l][3], y);
            }
        }
        List<Rectangle> blobs = new ArrayList<>(), specks = new ArrayList<>();
        for (int l = 1; l <= n; l++) {
            if (box[l] == null) continue;
            Rectangle r = new Rectangle(box[l][0] + tb.x, box[l][1] + tb.y, box[l][2] - box[l][0] + 1, box[l][3] - box[l][1] + 1);
            if ((double) r.width * r.height < SPECK * tb.width * tb.height) specks.add(r);
            else blobs.add(r);
        }
        mergeUntilStable(blobs, HeadlineReflow::sameSyllable);
        mergeUntilStable(blobs, HeadlineReflow::sameWord);
        // 작은 조각은 가장 가까운 낱말에 흡수
        for (Rectangle s : specks) {
            Rectangle best = null;
            double bd = Double.MAX_VALUE;
            for (Rectangle b : blobs) {
                double d = distance(s, b);
                if (d < bd) { bd = d; best = b; }
            }
            if (best != null) best.add(s);
        }
        return readingOrder(blobs);
    }

    private interface Rule {
        boolean merge(Rectangle a, Rectangle b);
    }

    private static void mergeUntilStable(List<Rectangle> list, Rule rule) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (rule.merge(list.get(i), list.get(j))) {
                        list.get(i).add(list.get(j));
                        list.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
    }

    /** 겹치거나, 가로로 많이 겹친 채 위아래로 붙어 있으면 한 음절(자모 쌓임). */
    private static boolean sameSyllable(Rectangle a, Rectangle b) {
        if (a.intersects(b)) return true;
        int ox = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
        int gapY = Math.max(a.y, b.y) - Math.min(a.y + a.height, b.y + b.height);
        int minH = Math.min(a.height, b.height);
        return ox >= 0.5 * Math.min(a.width, b.width) && gapY <= 0.25 * minH;
    }

    /** 높이가 비슷하고 같은 줄에서 가까우면 한 낱말. */
    private static boolean sameWord(Rectangle a, Rectangle b) {
        int minH = Math.min(a.height, b.height), maxH = Math.max(a.height, b.height);
        if (maxH > STACK_HEIGHT_RATIO * minH) return false;
        int oy = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        if (oy < 0.6 * minH) return false;
        int gapX = Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width);
        return gapX <= WORD_GAP * minH;
    }

    private static double distance(Rectangle a, Rectangle b) {
        double dx = Math.max(0, Math.max(a.x - (b.x + b.width), b.x - (a.x + a.width)));
        double dy = Math.max(0, Math.max(a.y - (b.y + b.height), b.y - (a.y + a.height)));
        return Math.hypot(dx, dy);
    }

    /**
     * 줄(세로 중심이 가까운 것끼리) → 줄 안에서 왼쪽부터. 두 줄에 걸친 큰 낱말('방')은 보통 낱말로 줄을 다 만든 뒤
     * 세로 중심이 가장 가까운 줄에 넣는다 — 먼저 넣으면 아직 없는 아랫줄 대신 윗줄로 간다(1차 시험).
     */
    private static List<Rectangle> readingOrder(List<Rectangle> words) {
        double medianH = words.stream().mapToInt(r -> r.height).sorted().skip(words.size() / 2).findFirst().orElse(1);
        List<Rectangle> byY = new ArrayList<>(words);
        byY.sort(Comparator.comparingDouble(Rectangle::getCenterY));
        List<List<Rectangle>> lines = new ArrayList<>();
        List<Rectangle> big = new ArrayList<>();
        for (Rectangle w : byY) {
            if (w.height > 1.5 * medianH) {
                big.add(w);
                continue;
            }
            List<Rectangle> best = null;
            double bd = Double.MAX_VALUE;
            for (List<Rectangle> line : lines) {
                Rectangle first = line.get(0);
                double d = Math.abs(first.getCenterY() - w.getCenterY());
                if (d < 0.5 * Math.min(first.height, w.height) && d < bd) { bd = d; best = line; }
            }
            if (best == null) lines.add(best = new ArrayList<>());
            best.add(w);
        }
        for (Rectangle w : big) {
            List<Rectangle> best = null;
            double bd = Double.MAX_VALUE;
            for (List<Rectangle> line : lines) {
                double d = Math.abs(line.get(0).getCenterY() - w.getCenterY());
                if (d < bd) { bd = d; best = line; }
            }
            if (best == null) lines.add(best = new ArrayList<>());
            best.add(w);
        }
        lines.sort(Comparator.comparingDouble(l -> l.get(0).getCenterY()));
        List<Rectangle> out = new ArrayList<>();
        for (List<Rectangle> line : lines) {
            line.sort(Comparator.comparingInt(r -> r.x));
            out.addAll(line);
        }
        return out;
    }

    /**
     * 머리글 요소를 새 줄바꿈으로 옮긴 요소 목록(좌표는 새 머리글 안의 상대 위치 — 블록 배치가 어차피 다시 맞춘다).
     * 제목 요소는 낱말마다 잘린 조각(id "원래id#w번호@x,y" — x,y는 원래 요소 안 위치)이 되고, 나머지는 같은 이미지에 새 bounds.
     *
     * @return 다시 흘릴 수 없으면(낱말 둘 미만) null
     */
    public static List<LayoutPlanner.SourceElement> apply(List<LayoutPlanner.SourceElement> headline, Mode mode) {
        if (mode == Mode.ORIGINAL) return headline;
        List<LayoutPlanner.SourceElement> titles = headline.stream().filter(e -> e.role() == ElementRole.TITLE).toList();
        List<Rectangle> words = words(titles);
        if (words.size() < 2) return null;

        // 낱말마다 새 자리(원점 0,0 기준)
        double avgH = words.stream().mapToInt(r -> r.height).average().orElse(1);
        List<Rectangle> placed = new ArrayList<>();
        if (mode == Mode.STACK) {
            int maxW = words.stream().mapToInt(r -> r.width).max().orElse(0);
            int y = 0;
            for (Rectangle w : words) {
                placed.add(new Rectangle((maxW - w.width) / 2, y, w.width, w.height));
                y += w.height + (int) Math.round(LINE_GAP * avgH);
            }
        } else {
            int maxH = words.stream().mapToInt(r -> r.height).max().orElse(0);
            int x = 0;
            for (Rectangle w : words) {
                placed.add(new Rectangle(x, maxH - w.height, w.width, w.height)); // 아랫줄 맞춤
                x += w.width + (int) Math.round(SPACE * avgH);
            }
        }

        List<LayoutPlanner.SourceElement> out = new ArrayList<>();
        // 제목 조각: 각 픽셀을 가장 가까운 낱말에 준다(낱말 사각형 사이 외곽선·그림자가 두 낱말에 겹쳐 찍히지 않게)
        for (LayoutPlanner.SourceElement t : titles) {
            BufferedImage img = t.image();
            int iw = img.getWidth(), ih = img.getHeight();
            int[] px = img.getRGB(0, 0, iw, ih, null, 0, iw);
            int[][] pieces = new int[words.size()][];
            int[][] ext = new int[words.size()][];
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    int p = px[y * iw + x];
                    if ((p >>> 24) == 0) continue;
                    int sx = t.bounds().x + x, sy = t.bounds().y + y, wi = nearest(words, sx, sy);
                    if (pieces[wi] == null) {
                        pieces[wi] = new int[iw * ih];
                        ext[wi] = new int[]{x, y, x, y};
                    }
                    pieces[wi][y * iw + x] = p;
                    ext[wi][0] = Math.min(ext[wi][0], x); ext[wi][1] = Math.min(ext[wi][1], y);
                    ext[wi][2] = Math.max(ext[wi][2], x); ext[wi][3] = Math.max(ext[wi][3], y);
                }
            }
            for (int wi = 0; wi < words.size(); wi++) {
                if (pieces[wi] == null) continue;
                int[] e = ext[wi];
                int cw = e[2] - e[0] + 1, ch = e[3] - e[1] + 1;
                BufferedImage crop = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
                int[] rowBuf = new int[cw];
                for (int y = 0; y < ch; y++) {
                    System.arraycopy(pieces[wi], (y + e[1]) * iw + e[0], rowBuf, 0, cw);
                    crop.setRGB(0, y, cw, 1, rowBuf, 0, cw);
                }
                Rectangle w = words.get(wi), to = placed.get(wi);
                int nx = to.x + (t.bounds().x + e[0] - w.x), ny = to.y + (t.bounds().y + e[1] - w.y);
                // id에 원래 요소 안의 잘라 낸 위치를 남긴다 — 인쇄 해상도에서 고해상도 원래 요소를 같은 자리로 다시 자르려고
                out.add(new LayoutPlanner.SourceElement(pieceId(t.id(), wi, e[0], e[1]), ElementRole.TITLE, crop,
                        new Rectangle(nx, ny, cw, ch), t.sourceZ()));
            }
        }
        Rectangle titleBox = union(placed);

        // 장식은 제목 전체 상자 안의 상대 위치를 따라간다(모자: 오른쪽 위 모서리). 가까운 낱말을 따라가게 했더니
        // 한 낱말씩 쌓을 때 '방' 옆의 모자가 '마술사의' 위로 올라왔다(1차 시험)
        Rectangle src = union(words);
        for (LayoutPlanner.SourceElement e : headline) {
            if (e.role() != ElementRole.DECOR) continue;
            double u = (e.bounds().getCenterX() - src.x) / Math.max(1, src.width);
            double v = (e.bounds().getCenterY() - src.y) / Math.max(1, src.height);
            int cx = (int) Math.round(titleBox.x + u * titleBox.width), cy = (int) Math.round(titleBox.y + v * titleBox.height);
            out.add(moved(e, cx - e.bounds().width / 2, cy - e.bounds().height / 2));
        }
        // 부제는 위, 꾸밈 낱말은 아래 — 각 무리 안의 상대 배치는 그대로, 가운데 맞춤
        int gap = (int) Math.round(2 * LINE_GAP * avgH);
        stackGroup(headline, ElementRole.COPY, titleBox, true, gap, out);
        stackGroup(headline, ElementRole.KEYWORD, titleBox, false, gap, out);
        return out;
    }

    static String pieceId(String parent, int word, int x, int y) {
        return parent + "#w" + word + "@" + x + "," + y;
    }

    /** 다시 흘린 제목 조각 id → [원래 요소 id, 원래 요소 안 x, y]. 조각이 아니면 null. */
    public static String[] parsePieceId(String id) {
        int w = id.indexOf("#w"), at = id.indexOf('@', Math.max(w, 0));
        if (w < 0 || at < 0) return null;
        String[] xy = id.substring(at + 1).split(",");
        return new String[]{id.substring(0, w), xy[0], xy[1]};
    }

    private static void stackGroup(List<LayoutPlanner.SourceElement> headline, ElementRole role, Rectangle titleBox,
                                   boolean above, int gap, List<LayoutPlanner.SourceElement> out) {
        List<LayoutPlanner.SourceElement> group = headline.stream().filter(e -> e.role() == role).toList();
        if (group.isEmpty()) return;
        Rectangle g = LayoutPlanner.union(group);
        int gx = titleBox.x + (titleBox.width - g.width) / 2;
        int gy = above ? titleBox.y - gap - g.height : titleBox.y + titleBox.height + gap;
        for (LayoutPlanner.SourceElement e : group) out.add(moved(e, gx + e.bounds().x - g.x, gy + e.bounds().y - g.y));
    }

    private static LayoutPlanner.SourceElement moved(LayoutPlanner.SourceElement e, int x, int y) {
        return new LayoutPlanner.SourceElement(e.id(), e.role(), e.image(),
                new Rectangle(x, y, e.bounds().width, e.bounds().height), e.sourceZ());
    }

    private static int nearest(List<Rectangle> words, int x, int y) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            Rectangle r = words.get(i);
            double dx = Math.max(0, Math.max(r.x - x, x - (r.x + r.width - 1)));
            double dy = Math.max(0, Math.max(r.y - y, y - (r.y + r.height - 1)));
            double d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static Rectangle union(List<Rectangle> rs) {
        Rectangle r = new Rectangle(rs.get(0));
        for (Rectangle x : rs) r.add(x);
        return r;
    }
}
