package com.actset.conversion.engine;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 생성 레이어 자체 보정 엔진 — 편집 모델이 원본을 참조해 새로 그린 레이어(배경판·구름·곡선·달 등)를 원본에 맞춘다.
 *
 * <p>PoC Q6c v2는 결과를 LLM에 보여 주고 지시문을 고쳐 다시 생성했다(재과금, 결과도 들쭉날쭉). 이 엔진은 그 루프를
 * 측정·보정으로 대신한다 — 과금 없음, 결정적. LLM은 처음 생성 지시문을 쓸 때만 쓴다(이미지 생성은 LLM 경유 원칙).
 *
 * <pre>
 * register  크기·위치 정합: 레이어 내용을 원본에서 찾아 제자리에 다시 놓는다 (편집 모델은 작은 요소를 키워 그린다 — 달 1.6배)
 * trim      없는 모양 잘라내기: 원본에서 보이는 자리인데 원본과 색이 크게 다르면 지운다. 원본에서 다른 요소에 가려진 자리는
 *           "가려졌던 부분을 이어 그린 것"이므로 남긴다 (곡선에 섞여 나온 초승달 꼬리)
 * toneMatch 색 맞춤: 원본에서 이 레이어가 그대로 보이는 픽셀끼리 채널별 평균·표준편차를 맞춘다 (배경판이 원본보다 어두움)
 * score     판정: 보이는 픽셀의 평균 색 차이(0~255). 기준 이하이면 합격
 * </pre>
 *
 * 입력·출력 레이어는 원본과 같은 캔버스 크기의 ARGB다. occluded는 픽셀별 0..1 — 원본에서 이 레이어보다 앞에 있는
 * 다른 요소가 덮은 정도(그 자리의 원본 픽셀은 이 레이어의 정답이 아니다).
 */
public final class LayerRegistrar {

    /** 임시값 — 정합 탐색 크기 배율(편집 모델이 작은 요소를 확대해 그리는 경향, PoC Q6c: 달 0.62배로 맞음). */
    static final double SCALE_MIN = 0.50, SCALE_MAX = 1.20, SCALE_STEP = 0.05;
    /** 임시값 — 요소 중심은 생성 위치에서 캔버스 긴 변의 ±20% 안에 있다고 본다(금빛 상자 등 비슷한 색 오인 방지, PoC Q6c). */
    static final double SEARCH_RADIUS = 0.20;
    /** 임시값 — 전역 탐색 해상도(1/8)와 정밀화 해상도(1/2). */
    static final double COARSE = 0.125, FINE = 0.5;
    /** 임시값 — 정합 바깥 띠 폭(작업 해상도 px)과 "요소 색과 비슷함" 거리. */
    static final int BAND = 2;
    static final double BAND_SIMILAR = 60;
    /** 임시값 — 이 비율보다 큰 레이어(구름·화면 전체 곡선)는 정합하지 않는다. 가장자리에 걸쳐 있고 가려진 부분이 많아 매칭이 흔들린다(PoC Q6c). */
    public static final double MAX_REGISTER_AREA = 0.25;
    /** 임시값 — 색 거리(RGB 유클리드) 이 아래면 원본과 같은 모양(trim 덩어리 고정점·얇은 요소 색 표본). */
    static final int TRIM_LOW = 70;
    /** 임시값 — trim: 생성 레이어는 원래 궤적에서 수~수십 px 어긋난다(PoC Q6c align_check). 긴 변의 이 비율 안에 같은 색이 있으면 같은 모양으로 본다. */
    static final double TRIM_TOLERANCE = 0.012;
    /** 임시값 — trim: 겹쳐 놓았을 때의 차이(알파 × 색 거리) 이 아래는 유지, 위쪽 값 이상이면 지운다. */
    static final double TRIM_SEEN_LOW = 22, TRIM_SEEN_HIGH = 55;
    /** 임시값 — trim: 이 정도 이상 가려진 픽셀은 "가려졌던 부분"으로 본다. */
    static final float OCCLUDED = 0.5f;
    /** 임시값 — toneMatch 채널별 표준편차 배율 한계(과보정 방지). */
    static final double GAIN_MIN = 0.6, GAIN_MAX = 1.6;
    /** 임시값 — blendOriginal: 이 값보다 덮인 픽셀을 "요소 자리"로 본다(빛 번짐의 옅은 가장자리도 포함). */
    static final float BLEND_OCCLUDED = 0.08f;
    /** 임시값 — blendOriginal 기본 넓힘·이음매 폭(긴 변 대비). */
    public static final double BLEND_DILATE = 0.008, BLEND_FEATHER = 0.006;
    /** 임시값 — 얇은 요소 색 맞춤의 채널별 평균 이동 한계. */
    static final double THIN_MAX_SHIFT = 30;
    /** 임시값 — 합격 기준: 보이는 픽셀 평균 색 차이. Q6c 결과로 정한 출발값(학습 전). */
    public static final double PASS_MAE = 28;

    private LayerRegistrar() {
    }

    public record Placement(double scale, int fromX, int fromY, int toX, int toY, double before, double after) {
        public boolean moved() {
            return scale != 1.0 || fromX != toX || fromY != toY;
        }
    }

    public record Registered(BufferedImage layer, Placement placement) {
    }

    // ---------------------------------------------------------------- register

    /**
     * 레이어의 실제 내용(알파 bbox)을 템플릿으로 원본에서 크기·위치를 찾아 다시 놓는다. 1/8 해상도 전역 탐색 → 1/2 해상도 근방 정밀화.
     * 맞춘 쪽이 원래 자리보다 원본과 더 비슷할 때만 옮긴다(오매칭으로 망치지 않게).
     *
     * @return 내용이 없거나 레이어가 너무 크면 placement=null로 입력 그대로
     */
    public static Registered register(BufferedImage layer, BufferedImage original, float[] occluded) {
        int w = original.getWidth(), h = original.getHeight();
        Rectangle bbox = alphaBounds(layer, 16);
        if (bbox == null || (double) bbox.width * bbox.height > MAX_REGISTER_AREA * w * h) {
            return new Registered(layer, null);
        }
        BufferedImage content = layer.getSubimage(bbox.x, bbox.y, bbox.width, bbox.height);
        Level coarseImg = Level.of(original, occluded, COARSE), fineImg = Level.of(original, occluded, FINE);

        double cx = bbox.getCenterX(), cy = bbox.getCenterY(), radius = SEARCH_RADIUS * Math.max(w, h);
        Candidate best = null;
        for (double s = SCALE_MIN; s <= SCALE_MAX + 1e-9; s += SCALE_STEP) {
            Candidate c = search(coarseImg, content, s, cx, cy, radius, null, 0);
            if (c != null && (best == null || c.cost < best.cost)) best = c;
        }
        if (best == null) return new Registered(layer, null);
        Candidate refined = null;
        for (double d = -0.04; d <= 0.04 + 1e-9; d += 0.02) {
            double s = round3(best.scale + d);
            Candidate c = search(fineImg, content, s, cx, cy, radius, best, 6);
            if (c != null && (refined == null || c.cost < refined.cost)) refined = c;
        }
        if (refined == null) refined = best;
        double before = cost(fineImg, content, 1.0, bbox.x, bbox.y);
        if (!(refined.cost < before)) {
            return new Registered(layer, new Placement(1.0, bbox.x, bbox.y, bbox.x, bbox.y, before, before));
        }
        int nw = Math.max(1, (int) Math.round(content.getWidth() * refined.scale));
        int nh = Math.max(1, (int) Math.round(content.getHeight() * refined.scale));
        BufferedImage placed = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = placed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(content, (int) Math.round(refined.x), (int) Math.round(refined.y), nw, nh, null);
        g.dispose();
        return new Registered(placed, new Placement(refined.scale, bbox.x, bbox.y,
                (int) Math.round(refined.x), (int) Math.round(refined.y), before, refined.cost));
    }

    private record Candidate(double scale, double x, double y, double cost) {
    }

    /** 원본(또는 그 축소본)과 가림 가중치. 가려진 픽셀은 정답이 아니므로 비교에서 뺀다. */
    private record Level(float[] r, float[] g, float[] b, float[] visible, int w, int h, double factor) {
        static Level of(BufferedImage original, float[] occluded, double factor) {
            int w = Math.max(1, (int) Math.round(original.getWidth() * factor));
            int h = Math.max(1, (int) Math.round(original.getHeight() * factor));
            BufferedImage small = scaled(original, w, h);
            int[] px = small.getRGB(0, 0, w, h, null, 0, w);
            float[] r = new float[w * h], g = new float[w * h], b = new float[w * h], vis = new float[w * h];
            int ow = original.getWidth(), oh = original.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x, p = px[i];
                    r[i] = (p >> 16) & 0xff;
                    g[i] = (p >> 8) & 0xff;
                    b[i] = p & 0xff;
                    if (occluded == null) {
                        vis[i] = 1;
                    } else {
                        int ox = Math.min(ow - 1, (int) (x / factor)), oy = Math.min(oh - 1, (int) (y / factor));
                        vis[i] = 1 - occluded[oy * ow + ox];
                    }
                }
            }
            return new Level(r, g, b, vis, w, h, factor);
        }
    }

    /** 템플릿(배율 s)을 놓을 수 있는 위치를 훑어 비용 최소 위치. near가 있으면 그 주변 ±win(작업 해상도 px)만. */
    private static Candidate search(Level lv, BufferedImage content, double s, double cx, double cy, double radius,
                                    Candidate near, int win) {
        int tw = (int) Math.round(content.getWidth() * s * lv.factor), th = (int) Math.round(content.getHeight() * s * lv.factor);
        if (tw < 6 || th < 6 || tw > lv.w || th > lv.h) return null;
        Template t = Template.of(content, tw, th);
        int x0, x1, y0, y1;
        if (near != null) {
            int nx = (int) Math.round(near.x * lv.factor), ny = (int) Math.round(near.y * lv.factor);
            x0 = nx - win; x1 = nx + win; y0 = ny - win; y1 = ny + win;
        } else {
            x0 = 0; x1 = lv.w - tw; y0 = 0; y1 = lv.h - th;
        }
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(lv.w - tw, x1); y1 = Math.min(lv.h - th, y1);
        double r = radius * lv.factor, ccx = cx * lv.factor, ccy = cy * lv.factor;
        Candidate best = null;
        for (int y = y0; y <= y1; y++) {
            if (Math.abs(y + th / 2.0 - ccy) > r) continue;
            for (int x = x0; x <= x1; x++) {
                if (Math.abs(x + tw / 2.0 - ccx) > r) continue;
                double c = t.cost(lv, x, y);
                if (best == null || c < best.cost) best = new Candidate(s, x / lv.factor, y / lv.factor, c);
            }
        }
        return best;
    }

    private static double cost(Level lv, BufferedImage content, double s, int x, int y) {
        int tw = (int) Math.round(content.getWidth() * s * lv.factor), th = (int) Math.round(content.getHeight() * s * lv.factor);
        int lx = (int) Math.round(x * lv.factor), ly = (int) Math.round(y * lv.factor);
        if (tw < 1 || th < 1 || lx + tw > lv.w || ly + th > lv.h) return Double.MAX_VALUE;
        return Template.of(content, tw, th).cost(lv, lx, ly);
    }

    /**
     * 알파가 있는 픽셀만 모은 희소 템플릿 — 마스크 가중 SSD를 이 픽셀들에 대해서만 계산한다.
     * 바깥 띠(bx,by): 요소 경계 바로 바깥 픽셀. 그 자리의 원본이 요소 색과 비슷하면 "더 큰 요소의 안쪽에 들어가 있다"는 뜻이라
     * 벌점을 준다 — 단색 요소는 작게 줄인 템플릿이 원본 요소 안 어디에 놓여도 SSD가 0이 되어 크기가 작게 쏠린다(단위 테스트).
     */
    private record Template(int[] dx, int[] dy, float[] r, float[] g, float[] b, float[] a,
                            int[] bx, int[] by, float mr, float mg, float mb) {
        static Template of(BufferedImage content, int tw, int th) {
            BufferedImage t = scaled(content, tw, th);
            int[] px = t.getRGB(0, 0, tw, th, null, 0, tw);
            int n = 0;
            for (int p : px) if ((p >>> 24) > 25) n++;
            int[] dx = new int[n], dy = new int[n];
            float[] r = new float[n], g = new float[n], b = new float[n], a = new float[n];
            int k = 0;
            double sr = 0, sg = 0, sb = 0, sa = 0;
            for (int i = 0; i < px.length; i++) {
                int p = px[i], al = p >>> 24;
                if (al <= 25) continue;
                dx[k] = i % tw; dy[k] = i / tw;
                r[k] = (p >> 16) & 0xff; g[k] = (p >> 8) & 0xff; b[k] = p & 0xff;
                a[k] = al / 255f;
                sr += a[k] * r[k]; sg += a[k] * g[k]; sb += a[k] * b[k]; sa += a[k];
                k++;
            }
            // 바깥 띠: 템플릿을 BAND px 넓힌 사각형에서, 내용 픽셀이 아니면서 BAND 안에 내용 픽셀이 있는 자리
            int pw = tw + 2 * BAND, ph = th + 2 * BAND;
            boolean[] in = new boolean[pw * ph];
            for (int j = 0; j < n; j++) in[(dy[j] + BAND) * pw + dx[j] + BAND] = true;
            java.util.List<int[]> band = new java.util.ArrayList<>();
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    if (in[y * pw + x]) continue;
                    boolean near = false;
                    for (int yy = Math.max(0, y - BAND); yy <= Math.min(ph - 1, y + BAND) && !near; yy++) {
                        for (int xx = Math.max(0, x - BAND); xx <= Math.min(pw - 1, x + BAND); xx++) {
                            if (in[yy * pw + xx]) { near = true; break; }
                        }
                    }
                    if (near) band.add(new int[]{x - BAND, y - BAND});
                }
            }
            int[] bx = new int[band.size()], by = new int[band.size()];
            for (int j = 0; j < bx.length; j++) { bx[j] = band.get(j)[0]; by[j] = band.get(j)[1]; }
            double m = Math.max(sa, 1e-6);
            return new Template(dx, dy, r, g, b, a, bx, by, (float) (sr / m), (float) (sg / m), (float) (sb / m));
        }

        double cost(Level lv, int x, int y) {
            double s = 0, wsum = 0;
            for (int k = 0; k < dx.length; k++) {
                int i = (y + dy[k]) * lv.w + x + dx[k];
                double wt = a[k] * lv.visible[i];
                if (wt <= 0) continue;
                double er = lv.r[i] - r[k], eg = lv.g[i] - g[k], eb = lv.b[i] - b[k];
                s += wt * (er * er + eg * eg + eb * eb);
                wsum += wt;
            }
            if (wsum < 1e-6) return Double.MAX_VALUE;
            double band = 0;
            int nb = 0;
            for (int k = 0; k < bx.length; k++) {
                int xx = x + bx[k], yy = y + by[k];
                if (xx < 0 || yy < 0 || xx >= lv.w || yy >= lv.h) continue;
                int i = yy * lv.w + xx;
                if (lv.visible[i] < 0.5f) continue;
                double er = lv.r[i] - mr, eg = lv.g[i] - mg, eb = lv.b[i] - mb;
                double over = Math.max(0, BAND_SIMILAR - Math.sqrt(er * er + eg * eg + eb * eb));
                band += over * over;
                nb++;
            }
            // 대부분 가려진 자리로 도망가는 것을 막는다 — 보이는 비율이 낮으면 벌점
            return (s / wsum + (nb == 0 ? 0 : band / nb)) / Math.max(0.25, wsum / dx.length);
        }
    }

    // ---------------------------------------------------------------- trim

    public record TrimResult(BufferedImage layer, int removedPx, int keptHiddenPx) {
    }

    /**
     * 원본에서 보이는 자리인데 원본과 색이 크게 다른 픽셀을 지운다(편집 모델이 없는 모양을 그린 것).
     * 가려졌던 자리의 픽셀은 남기되, 보이는 자리에서 원본과 맞는 픽셀과 이어진 덩어리만 남긴다(허공에 뜬 덩어리 제거).
     */
    public static TrimResult trim(BufferedImage layer, BufferedImage original, float[] occluded) {
        int w = original.getWidth(), h = original.getHeight();
        int[] lp = layer.getRGB(0, 0, w, h, null, 0, w), op = original.getRGB(0, 0, w, h, null, 0, w);
        int tol = Math.max(1, (int) Math.round(TRIM_TOLERANCE * Math.max(w, h)));
        float[] keep = new float[w * h];
        boolean[] anchor = new boolean[w * h];
        for (int i = 0; i < lp.length; i++) {
            int a = lp[i] >>> 24;
            keep[i] = 1;
            if (a == 0 || (occluded != null && occluded[i] >= OCCLUDED)) continue;
            double d = nearestDistance(lp[i], op, i % w, i / w, w, h, tol);
            // 겹쳐 놓았을 때 원본과 달라 보이는 정도 = 알파 × 색 거리. 옅은 빛 번짐도 원본에 없으면 지운다
            // (Q6d 2차: 색 거리만 알파로 깎았더니 원본에 없는 옅은 안개가 남았다)
            double seen = a / 255.0 * d;
            keep[i] = (float) (1 - clamp01((seen - TRIM_SEEN_LOW) / (TRIM_SEEN_HIGH - TRIM_SEEN_LOW)));
            anchor[i] = a > 25 && d < TRIM_LOW;
        }
        // 지우는 경계를 부드럽게 — 픽셀 단위로 지우면 빛 번짐에 찢어진 구멍이 남는다(Q6d 2차)
        int r = Math.max(1, tol / 3);
        keep = boxBlur(keep, w, h, r);
        int[] alpha = new int[w * h];
        int removed = 0;
        for (int i = 0; i < lp.length; i++) {
            int a = lp[i] >>> 24;
            if (a == 0) continue;
            float k = (occluded != null && occluded[i] >= OCCLUDED) ? 1 : keep[i];
            alpha[i] = Math.round(a * k);
            if (alpha[i] < a - 8) removed++;
        }
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) mask[i] = alpha[i] > 0;
        int[] labels = ElementSplitter.label8(mask, w, h);
        int max = 0;
        for (int l : labels) max = Math.max(max, l);
        boolean[] anchored = new boolean[max + 1];
        for (int i = 0; i < labels.length; i++) if (anchor[i]) anchored[labels[i]] = true;
        int hidden = 0;
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            if (labels[i] == 0) continue;
            if (!anchored[labels[i]]) {
                removed++;
                continue;
            }
            out[i] = (alpha[i] << 24) | (lp[i] & 0xffffff);
            if (occluded != null && occluded[i] >= OCCLUDED) hidden++;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return new TrimResult(img, removed, hidden);
    }

    // ---------------------------------------------------------------- toneMatch

    public record ToneResult(BufferedImage layer, double[] gain, double[] offset, long samples) {
    }

    /**
     * weight(0..1)가 큰 픽셀끼리 레이어와 원본의 채널별 평균·표준편차를 맞춘다. weight가 null이면
     * "레이어 알파 × 원본에서 안 가려짐"을 쓴다. 레이어 알파는 그대로 둔다.
     */
    public static ToneResult toneMatch(BufferedImage layer, BufferedImage original, float[] weight) {
        return toneMatch(layer, original, weight, false);
    }

    /**
     * 얇은 요소(곡선·달 등)용 색 맞춤 — 원본과 같은 모양으로 확인된 픽셀(색 거리 TRIM_LOW 미만)만 표본으로 쓰고 평균만 맞춘다.
     * 얇은 요소는 수 px만 어긋나도 표본에 하늘색이 섞여 표준편차 맞춤이 색을 망친다(Q6d 1차: 금색 곡선이 녹갈색이 됨).
     */
    public static ToneResult toneMatchThin(BufferedImage layer, BufferedImage original, float[] weight) {
        return toneMatch(layer, original, weight, true);
    }

    private static ToneResult toneMatch(BufferedImage layer, BufferedImage original, float[] weight, boolean thin) {
        int w = original.getWidth(), h = original.getHeight();
        int[] lp = layer.getRGB(0, 0, w, h, null, 0, w), op = original.getRGB(0, 0, w, h, null, 0, w);
        double[] sl = new double[3], sl2 = new double[3], so = new double[3], so2 = new double[3];
        double ws = 0;
        long n = 0;
        for (int i = 0; i < lp.length; i++) {
            double wt = weight != null ? weight[i] : (lp[i] >>> 24) / 255.0;
            // 반투명 픽셀은 원본에서 아래 색과 섞여 있어 비교 대상이 아니다 — 거의 불투명한 픽셀만
            if (wt < 0.5 || (lp[i] >>> 24) < 230) continue;
            if (thin && rgbDistance(lp[i], op[i]) >= TRIM_LOW) continue;
            for (int c = 0; c < 3; c++) {
                double l = (lp[i] >> (16 - 8 * c)) & 0xff, o = (op[i] >> (16 - 8 * c)) & 0xff;
                sl[c] += wt * l; sl2[c] += wt * l * l; so[c] += wt * o; so2[c] += wt * o * o;
            }
            ws += wt;
            n++;
        }
        double[] gain = {1, 1, 1}, offset = {0, 0, 0};
        if (n < 500) return new ToneResult(layer, gain, offset, n); // 비교할 픽셀이 너무 적으면 손대지 않는다
        for (int c = 0; c < 3; c++) {
            double ml = sl[c] / ws, mo = so[c] / ws;
            double sdl = Math.sqrt(Math.max(1e-6, sl2[c] / ws - ml * ml)), sdo = Math.sqrt(Math.max(1e-6, so2[c] / ws - mo * mo));
            if (thin) {
                offset[c] = Math.max(-THIN_MAX_SHIFT, Math.min(THIN_MAX_SHIFT, mo - ml));
                continue;
            }
            gain[c] = Math.max(GAIN_MIN, Math.min(GAIN_MAX, sdo / sdl));
            offset[c] = mo - gain[c] * ml;
        }
        int[] out = new int[lp.length];
        for (int i = 0; i < lp.length; i++) {
            int p = lp[i], v = p & 0xff000000;
            for (int c = 0; c < 3; c++) {
                double x = ((p >> (16 - 8 * c)) & 0xff) * gain[c] + offset[c];
                v |= clampByte(x) << (16 - 8 * c);
            }
            out[i] = v;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return new ToneResult(img, gain, offset, n);
    }

    // ---------------------------------------------------------------- blendOriginal

    /**
     * 배경판 — 원본에서 하늘이 그대로 보이는 자리는 원본 픽셀을 쓰고, 요소에 덮였던 자리만 생성 배경을 쓴다.
     * 생성 배경은 색을 맞춰도 질감(은하수 띠 등)이 원본과 다르다(Q6c v1). 요소 경계에 원본 요소의 번짐이 새지 않게
     * 덮인 영역을 dilate만큼 넓히고, 이음매는 feather 폭으로 섞는다. 두 값은 긴 변 대비 비율.
     */
    public static BufferedImage blendOriginal(BufferedImage generated, BufferedImage original, float[] occluded,
                                              double dilate, double feather) {
        int w = original.getWidth(), h = original.getHeight(), side = Math.max(w, h);
        float[] mask = new float[w * h];
        for (int i = 0; i < mask.length; i++) mask[i] = occluded[i] > BLEND_OCCLUDED ? 1 : 0;
        int d = Math.max(1, (int) Math.round(dilate * side)), f = Math.max(1, (int) Math.round(feather * side));
        mask = boxMax(mask, w, h, d);
        mask = boxBlur(boxBlur(mask, w, h, f), w, h, f);
        int[] gp = generated.getRGB(0, 0, w, h, null, 0, w), op = original.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            float m = mask[i];
            int v = 0xff000000;
            for (int c = 0; c < 3; c++) {
                int sh = 16 - 8 * c;
                v |= clampByte(m * ((gp[i] >> sh) & 0xff) + (1 - m) * ((op[i] >> sh) & 0xff)) << sh;
            }
            out[i] = v;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    /** 분리형 최댓값 필터(정사각 창, 반경 r). */
    private static float[] boxMax(float[] v, int w, int h, int r) {
        float[] tmp = new float[v.length], out = new float[v.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) m = Math.max(m, v[y * w + k]);
                tmp[y * w + x] = m;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float m = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) m = Math.max(m, tmp[k * w + x]);
                out[y * w + x] = m;
            }
        }
        return out;
    }

    /** 분리형 상자 흐림(누적합, 반경 r). */
    private static float[] boxBlur(float[] v, int w, int h, int r) {
        float[] tmp = new float[v.length], out = new float[v.length];
        for (int y = 0; y < h; y++) {
            double acc = 0;
            int n = 0;
            for (int x = -r; x < w; x++) {
                if (x + r < w) { acc += v[y * w + x + r]; n++; }
                if (x - r - 1 >= 0) { acc -= v[y * w + x - r - 1]; n--; }
                if (x >= 0) tmp[y * w + x] = (float) (acc / n);
            }
        }
        for (int x = 0; x < w; x++) {
            double acc = 0;
            int n = 0;
            for (int y = -r; y < h; y++) {
                if (y + r < h) { acc += tmp[(y + r) * w + x]; n++; }
                if (y - r - 1 >= 0) { acc -= tmp[(y - r - 1) * w + x]; n--; }
                if (y >= 0) out[y * w + x] = (float) (acc / n);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- score

    /** weight(0..1) 가중 평균 색 차이(채널 평균 절댓값, 0~255). weight가 null이면 전체. */
    public static double score(BufferedImage composite, BufferedImage original, float[] weight) {
        int w = original.getWidth(), h = original.getHeight();
        int[] a = composite.getRGB(0, 0, w, h, null, 0, w), b = original.getRGB(0, 0, w, h, null, 0, w);
        double s = 0, ws = 0;
        for (int i = 0; i < a.length; i++) {
            double wt = weight == null ? 1 : weight[i];
            if (wt <= 0) continue;
            double d = 0;
            for (int c = 0; c < 3; c++) d += Math.abs(((a[i] >> (8 * c)) & 0xff) - ((b[i] >> (8 * c)) & 0xff));
            s += wt * d / 3;
            ws += wt;
        }
        return ws == 0 ? 0 : s / ws;
    }

    // ---------------------------------------------------------------- 공통

    /** 여러 레이어 알파의 합집합(최댓값, 0..1). */
    public static float[] coverage(int w, int h, Iterable<BufferedImage> layers) {
        float[] cov = new float[w * h];
        for (BufferedImage l : layers) {
            int[] px = l.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < px.length; i++) cov[i] = Math.max(cov[i], (px[i] >>> 24) / 255f);
        }
        return cov;
    }

    public static Rectangle alphaBounds(BufferedImage img, int threshold) {
        int w = img.getWidth(), h = img.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > threshold) {
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** (x,y) 주변 ±tol 안의 원본 픽셀(5×5 표본 격자) 중 색이 가장 가까운 것까지의 거리. */
    private static double nearestDistance(int p, int[] op, int x, int y, int w, int h, int tol) {
        double best = Double.MAX_VALUE;
        for (int j = -2; j <= 2; j++) {
            int yy = y + j * tol / 2;
            if (yy < 0 || yy >= h) continue;
            for (int k = -2; k <= 2; k++) {
                int xx = x + k * tol / 2;
                if (xx < 0 || xx >= w) continue;
                best = Math.min(best, rgbDistance(p, op[yy * w + xx]));
            }
        }
        return best;
    }

    private static double rgbDistance(int a, int b) {
        double d = 0;
        for (int c = 0; c < 3; c++) {
            int x = ((a >> (8 * c)) & 0xff) - ((b >> (8 * c)) & 0xff);
            d += x * x;
        }
        return Math.sqrt(d);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int clampByte(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
