package com.actset.conversion.layout;

import com.actset.conversion.engine.LayerCompositor;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 배치 후보 점수 — 후보를 거친 격자(긴 변 GRID px)에 요소 알파로 찍어 보고 잰다. 높을수록 좋다.
 *
 * <pre>
 * + 제목 크기      제목 글자가 덮은 면적 / 캔버스 (TITLE_TARGET에서 포화)
 * + 키비주얼 크기  캔버스 안에 보이는 키비주얼 알파 면적 (KV_TARGET에서 포화)
 * + 채움          요소가 덮은 면적 (FILL_TARGET에서 포화)
 * − 가독성        글자가 역할별 최소 px보다 작음
 * − 선명도        원본 해상도 대비 SHARP_LIMIT배 넘게 키움
 * − 키비주얼 잘림  캔버스 밖으로 나간 키비주얼 비율
 * − 글자 잘림      글자·로고가 캔버스 밖 — 사실상 탈락
 * − 글자 겹침      서로 다른 글자 블록끼리 겹침
 * − 글자 아래 피사체 글자가 인물·오브젝트 위에 얹힘(머리글은 약하게, 작은 정보 글자는 세게)
 * − 글자 숨음      글자가 그보다 위에 쌓인 피사체에 가려짐 — 크게 깎는다
 * − 생략          원본에 있던 역할을 뺌(키비주얼·정보는 크게, 꾸밈 낱말은 작게)
 * − 여백·균형      글자가 가장자리에 붙음, 무게중심이 한쪽으로 쏠림
 * </pre>
 * <b>모든 가중치·목표값은 임시값이다(CLAUDE.md 규칙 5)</b> — magician's room·sandmagicshow 5규격을 보고 손으로 정했다.
 * 사용자 선택(SelectionEvent)이 쌓이면 이 가중치를 학습으로 바꾼다.
 */
public final class LayoutScorer {

    static final int GRID = 160;
    static final double TITLE_TARGET = 0.11, KV_TARGET = 0.30, FILL_TARGET = 0.55;
    static final double W_TITLE = 1.0, W_KV = 0.8, W_FILL = 0.35;
    static final double SHARP_LIMIT = 2.2;

    public record Score(double total, Map<String, Double> parts) {
    }

    /** 역할별 최소 글자 높이(요소 bbox 높이, px) — 임시값. 화면용 기준이며 인쇄는 해상도 단계에서 따로 본다. */
    public static double minTextPx(ElementRole role) {
        return switch (role) {
            case TITLE -> 22;
            case COPY -> 13;
            case KEYWORD -> 11;
            case INFO -> 12;
            case CREDIT -> 10;
            default -> 0;
        };
    }

    /** 원본에 있던 역할을 뺐을 때 깎는 점수 — 임시값. */
    static double dropPenalty(ElementRole role) {
        return switch (role) {
            case TITLE -> 2.0;
            case SUBJECT, PHOTO -> 0.6;
            case INFO -> 0.35;
            case COPY -> 0.2;
            case MARK -> 0.06;
            case CREDIT -> 0.06;
            case KEYWORD -> 0.06;
            default -> 0;
        };
    }

    private final Map<BufferedImage, float[]> maskCache = new IdentityHashMap<>();
    private final Map<BufferedImage, int[]> maskSize = new IdentityHashMap<>();

    public Score score(LayoutPlanner.Plan p, double sourceScale, int sourceWidth, int sourceHeight, Set<ElementRole> present) {
        int W = p.width(), H = p.height();
        double k = GRID / (double) Math.max(W, H);
        int gw = Math.max(1, (int) Math.round(W * k)), gh = Math.max(1, (int) Math.round(H * k));
        Map<String, LayerCompositor.Layer> layerById = new LinkedHashMap<>();
        for (LayerCompositor.Layer l : p.layers()) layerById.put(l.name(), l);

        float[] subject = new float[gw * gh], content = new float[gw * gh], titleInk = new float[gw * gh];
        // 칸마다 그 칸을 덮은 키비주얼 요소 중 가장 높은 z — 글자가 피사체 뒤로 숨는지 본다
        int[] kvTopZ = new int[gw * gh];
        java.util.Arrays.fill(kvTopZ, Integer.MIN_VALUE);
        Map<LayoutBlock, float[]> text = new EnumMap<>(LayoutBlock.class);
        double kvIn = 0, kvAll = 0, readability = 0, sharp = 0, textCrop = 0, margin = 0, titleMaxH = -1;
        double safe = LayoutPlanner.SAFE_MARGIN * Math.min(W, H) * 0.5;
        java.util.EnumSet<ElementRole> placed = java.util.EnumSet.noneOf(ElementRole.class);

        for (LayoutPlanner.Placement pl : p.placements()) {
            LayerCompositor.Layer layer = layerById.get(pl.elementId());
            if (layer == null || pl.block() == null) continue; // 배경판
            placed.add(pl.role());
            Rectangle2D t = pl.target();
            boolean isText = pl.block() != LayoutBlock.KEYVISUAL;
            double[] inOut = stamp(layer.image(), t, k, gw, gh, pl.block() == LayoutBlock.KEYVISUAL && pl.role() != ElementRole.DECOR
                    ? subject : null, content, null, pl.block() == LayoutBlock.KEYVISUAL ? kvTopZ : null, layer.z());
            // 글자는 획이 가늘어 알파로는 겹침이 잘 안 잡힌다(Q 정사각: MAGIC·SHADOW가 달 뒤로 숨음) — 글자 칸은 bbox로 본다
            if (isText) fillRect(t, k, gw, gh, text.computeIfAbsent(pl.block(), b -> new float[gw * gh]));
            if (pl.role() == ElementRole.TITLE) stamp(layer.image(), t, k, gw, gh, null, titleInk, null, null, 0);
            if (pl.block() == LayoutBlock.KEYVISUAL) {
                kvIn += inOut[0];
                kvAll += inOut[0] + inOut[1];
            }
            double scale = t.getHeight() / Math.max(1, layer.image().getHeight()) / sourceScale;
            if (pl.role().isText()) {
                // 못 읽는 글자는 빼는 것보다 항상 나쁘다. 제목은 여러 겹·조각(다시 흘린 낱말 조각, 외곽선 부스러기)이라
                // 조각마다 보지 않고 가장 큰 제목 요소 하나로 본다(아래)
                if (pl.role() == ElementRole.TITLE) titleMaxH = Math.max(titleMaxH, t.getHeight());
                else if (t.getHeight() < minTextPx(pl.role())) readability += Math.max(0.4, dropPenalty(pl.role()) + 0.2);
                if (scale > SHARP_LIMIT) sharp += Math.min(0.5, (scale - SHARP_LIMIT) * 0.12);
            } else if (pl.block() == LayoutBlock.KEYVISUAL && pl.role() != ElementRole.DECOR && scale > SHARP_LIMIT) {
                sharp += Math.min(0.3, (scale - SHARP_LIMIT) * 0.06);
            }
            if (isText) {
                if (t.getX() < -1 || t.getY() < -1 || t.getMaxX() > W + 1 || t.getMaxY() > H + 1) textCrop += 3;
                else if (t.getX() < safe || t.getY() < safe || t.getMaxX() > W - safe || t.getMaxY() > H - safe) margin += 0.05;
            }
        }

        if (titleMaxH >= 0 && titleMaxH < minTextPx(ElementRole.TITLE)) readability += dropPenalty(ElementRole.TITLE) + 0.2;
        Map<String, Double> parts = new LinkedHashMap<>();
        // 제목 크기는 글자가 실제로 덮은 면적으로 잰다 — 머리글 bbox로 재면 한 줄로 늘어놓은 제목(큰 '방' + 작은 낱말)이
        // 빈 곳까지 면적으로 쳐서 이겼다(가로형 1차)
        double ink = 0;
        for (float v : titleInk) ink += v;
        parts.put("title", W_TITLE * sat(ink / (gw * (double) gh) / TITLE_TARGET));
        parts.put("keyvisual", W_KV * sat(kvIn / (gw * (double) gh) / KV_TARGET));
        parts.put("fill", W_FILL * sat(coverage(content) / FILL_TARGET));
        parts.put("readability", -readability);
        parts.put("sharpness", -sharp);
        double visible = kvAll == 0 ? 1 : kvIn / kvAll;
        parts.put("kv_crop", -((1 - visible) * 0.5 + (visible < 0.5 ? 1 : 0)));
        parts.put("text_crop", -textCrop);
        parts.put("text_overlap", -2 * textOverlap(text));
        double onSubject = 0;
        for (LayoutPlanner.Placement pl : p.placements()) {
            if (pl.block() == null || !pl.role().isText()) continue;
            // 작은 글자는 피사체 위에서 대비가 무너진다(정사각: 연회색 MAGIC·SHADOW가 밝은 달 위) — 제목은 약하게
            double w = switch (pl.role()) {
                case TITLE -> 0.2;
                case COPY, KEYWORD -> 0.6;
                case INFO, CREDIT -> 0.7;
                default -> 0.3;
            };
            onSubject += w * subjectFraction(pl.target(), k, gw, gh, subject);
        }
        parts.put("text_on_subject", -onSubject);
        double hidden = 0;
        for (LayoutPlanner.Placement pl : p.placements()) {
            LayerCompositor.Layer layer = layerById.get(pl.elementId());
            if (layer == null || pl.block() == null || !pl.role().isText()) continue;
            double f = hiddenFraction(pl.target(), layer.z(), k, gw, gh, kvTopZ, subject);
            hidden += 1.5 * f + (f > 0.2 ? 1.0 : 0); // 한 글자 요소라도 가려지면 크게 깎는다
        }
        parts.put("text_hidden", -hidden);
        double drop = 0;
        for (ElementRole r : present) if (!placed.contains(r)) drop += dropPenalty(r);
        // 주 피사체만 남긴 후보 — 뺀 보조 피사체마다 조금씩(원본의 이야기 요소를 잃는다)
        drop += Math.min(0.12, 0.03 * p.dropped().stream().filter(d -> d.contains(LayoutPlanner.SECONDARY)).count());
        parts.put("dropped", -drop);
        parts.put("margin", -margin);
        parts.put("balance", -balance(content, gw, gh));
        double total = parts.values().stream().mapToDouble(Double::doubleValue).sum();
        parts.replaceAll((key, v) -> Math.round(v * 1000) / 1000.0);
        return new Score(Math.round(total * 1000) / 1000.0, parts);
    }

    /**
     * 요소 알파를 격자에 찍는다(목표 사각형 전체를 훑어 캔버스 밖 칸은 수만 센다).
     * @return [캔버스 안 알파 합, 캔버스 밖 알파 합] (격자 칸 단위)
     */
    private double[] stamp(BufferedImage img, Rectangle2D t, double k, int gw, int gh, float[] subject, float[] content,
                           float[] text, int[] topZ, int z) {
        float[] m = mask(img);
        int[] ms = maskSize.get(img);
        int mw = ms[0], mh = ms[1];
        int x0 = (int) Math.floor(t.getX() * k), y0 = (int) Math.floor(t.getY() * k);
        int x1 = (int) Math.ceil(t.getMaxX() * k), y1 = (int) Math.ceil(t.getMaxY() * k);
        double in = 0, out = 0;
        for (int gy = y0; gy < y1; gy++) {
            double v = ((gy + 0.5) / k - t.getY()) / t.getHeight();
            if (v < 0 || v >= 1) continue;
            int my = Math.min(mh - 1, (int) (v * mh));
            for (int gx = x0; gx < x1; gx++) {
                double u = ((gx + 0.5) / k - t.getX()) / t.getWidth();
                if (u < 0 || u >= 1) continue;
                float a = m[my * mw + Math.min(mw - 1, (int) (u * mw))];
                if (a <= 0) continue;
                if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) {
                    out += a;
                    continue;
                }
                in += a;
                int i = gy * gw + gx;
                content[i] = Math.max(content[i], a);
                if (subject != null) subject[i] = Math.max(subject[i], a);
                if (text != null) text[i] = Math.max(text[i], a);
                if (topZ != null && a > 0.3f) topZ[i] = Math.max(topZ[i], z);
            }
        }
        return new double[]{in, out};
    }

    /** 사각형을 격자에 채운다(글자 칸). */
    private static void fillRect(Rectangle2D t, double k, int gw, int gh, float[] m) {
        int x0 = Math.max(0, (int) Math.floor(t.getX() * k)), y0 = Math.max(0, (int) Math.floor(t.getY() * k));
        int x1 = Math.min(gw, (int) Math.ceil(t.getMaxX() * k)), y1 = Math.min(gh, (int) Math.ceil(t.getMaxY() * k));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                m[y * gw + x] = 1;
            }
        }
    }

    /** 글자 요소 bbox 칸 중 그 글자보다 위(z)에 있는 피사체에 덮인 비율 — 글자가 숨으면 못 읽는다. */
    private static double hiddenFraction(Rectangle2D t, int z, double k, int gw, int gh, int[] kvTopZ, float[] subject) {
        int x0 = Math.max(0, (int) Math.floor(t.getX() * k)), y0 = Math.max(0, (int) Math.floor(t.getY() * k));
        int x1 = Math.min(gw, (int) Math.ceil(t.getMaxX() * k)), y1 = Math.min(gh, (int) Math.ceil(t.getMaxY() * k));
        int n = 0, hidden = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                n++;
                int i = y * gw + x;
                if (kvTopZ[i] > z && subject[i] > 0.3f) hidden++;
            }
        }
        return n == 0 ? 0 : hidden / (double) n;
    }

    /** 요소 알파를 긴 변 48칸으로 줄인 평균 마스크(캐시). */
    private float[] mask(BufferedImage img) {
        float[] cached = maskCache.get(img);
        if (cached != null) return cached;
        int w = img.getWidth(), h = img.getHeight();
        double f = Math.min(1, 48.0 / Math.max(w, h));
        int mw = Math.max(1, (int) Math.round(w * f)), mh = Math.max(1, (int) Math.round(h * f));
        float[] m = new float[mw * mh];
        int[] cnt = new int[mw * mh];
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            int my = Math.min(mh - 1, (int) (y * f));
            for (int x = 0; x < w; x++) {
                int i = my * mw + Math.min(mw - 1, (int) (x * f));
                m[i] += (px[y * w + x] >>> 24) / 255f;
                cnt[i]++;
            }
        }
        for (int i = 0; i < m.length; i++) m[i] = cnt[i] == 0 ? 0 : m[i] / cnt[i];
        maskCache.put(img, m);
        maskSize.put(img, new int[]{mw, mh});
        return m;
    }

    /** 서로 다른 글자 블록이 함께 덮은 칸 / 작은 쪽 블록 칸 수 — 블록 쌍 중 최댓값. */
    private static double textOverlap(Map<LayoutBlock, float[]> text) {
        double worst = 0;
        LayoutBlock[] blocks = text.keySet().toArray(new LayoutBlock[0]);
        for (int a = 0; a < blocks.length; a++) {
            for (int b = a + 1; b < blocks.length; b++) {
                float[] ma = text.get(blocks[a]), mb = text.get(blocks[b]);
                int both = 0, na = 0, nb = 0;
                for (int i = 0; i < ma.length; i++) {
                    boolean ia = ma[i] > 0.3f, ib = mb[i] > 0.3f;
                    if (ia) na++;
                    if (ib) nb++;
                    if (ia && ib) both++;
                }
                if (na > 0 && nb > 0) worst = Math.max(worst, both / (double) Math.min(na, nb));
            }
        }
        return worst;
    }

    /** 글자 bbox 칸 중 피사체 위에 얹힌 비율. */
    private static double subjectFraction(Rectangle2D t, double k, int gw, int gh, float[] subject) {
        int x0 = Math.max(0, (int) Math.floor(t.getX() * k)), y0 = Math.max(0, (int) Math.floor(t.getY() * k));
        int x1 = Math.min(gw, (int) Math.ceil(t.getMaxX() * k)), y1 = Math.min(gh, (int) Math.ceil(t.getMaxY() * k));
        int n = 0, on = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                n++;
                if (subject[y * gw + x] > 0.3f) on++;
            }
        }
        return n == 0 ? 0 : on / (double) n;
    }

    private static double coverage(float[] m) {
        int n = 0;
        for (float v : m) if (v > 0.15f) n++;
        return n / (double) m.length;
    }

    /** 내용 무게중심이 가운데에서 벗어난 정도. 가로 0.3, 세로 0.15 가중. */
    private static double balance(float[] m, int gw, int gh) {
        double sx = 0, sy = 0, s = 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                float v = m[y * gw + x];
                sx += v * (x + 0.5);
                sy += v * (y + 0.5);
                s += v;
            }
        }
        if (s == 0) return 0;
        return 0.3 * Math.abs(sx / s / gw - 0.5) * 2 + 0.15 * Math.abs(sy / s / gh - 0.5) * 2;
    }

    private static double area(Rectangle2D r) {
        return r.isEmpty() ? 0 : r.getWidth() * r.getHeight();
    }

    /** 0..1 포화, 작은 쪽을 더 쳐 준다(지수 0.7). */
    private static double sat(double x) {
        return Math.pow(Math.max(0, Math.min(1, x)), 0.7);
    }
}
