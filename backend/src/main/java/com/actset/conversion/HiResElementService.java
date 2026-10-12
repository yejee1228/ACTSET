package com.actset.conversion;

import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.external.conversion.ImageUpscaleAdapter;
import com.actset.external.conversion.MockImageUpscaleAdapter;
import com.actset.render.FontRegistry;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 인쇄 해상도 요소 — 분리된 요소(원본 해상도)를 factor배 고해상도로 만든다(docs/12 인쇄: "비주얼 레이어는 업스케일,
 * 텍스트는 다시 렌더링"). 2026-10-12 사용자 지적: 긴 이미지는 인쇄할 수 있는데 원본 해상도(750x1000 등)로는 안 된다.
 *
 * <pre>
 * 1. 원본 포스터에서 PHOTO 영역을 지운다(규칙 1 — 업로드 사진은 외부로 보내지 않는다, 사용자 결정 "혼합")
 * 2. 포스터 전체를 한 번만 초해상도 확대(ImageUpscaleAdapter, SeedVR2 4배 ≈ $0.025)
 * 3. 요소마다: 요소가 원본 픽셀을 그대로 보여 주는 자리(위에 다른 요소가 없고 색이 원본과 같음)는 확대된 포스터에서 가져오고, 가려졌다 메운 자리(배경판 구멍 등)는
 *    요소를 그냥 키운 것(bicubic)을 쓴다. 알파는 요소 알파를 키운 것 — 확대 모델은 알파를 모른다
 * 4. PHOTO는 서버 안에서만 키운다(bicubic + 선명화)
 * 5. 일반 글꼴 정보 글자(INFO·CREDIT)에 문구(meta.text)가 있으면 글꼴로 다시 쓴다 — 확대보다 선명하다
 * </pre>
 */
@Service
public class HiResElementService {

    /** 임시값 — "원본 픽셀 그대로"로 볼 색 거리(RGB). 이보다 다르면 메운 자리로 보고 확대 포스터를 쓰지 않는다. */
    static final int SAME_AS_ORIGINAL = 30;
    /** 임시값 — 메운 자리 색을 원본 해상도에서 흐리는 반경(px). 분해 해상도 블록(약 1.4px)을 지울 만큼. */
    static final int FILL_BLUR = 2;
    /** 임시값 — 비주얼 요소 알파를 흐리는 반경(배율 대비). 8배면 5px. */
    static final double ALPHA_SOFTEN = 0.6;
    /** 임시값 — 두 출처를 섞는 가중치를 원본 해상도에서 흐리는 반경(px). */
    static final int WEIGHT_BLUR = 2;
    /** 이 알파를 넘으면 "그 자리를 덮었다"고 본다. */
    static final int COVER_ALPHA = 20;

    private final ImageUpscaleAdapter upscaler;
    private final FontRegistry fonts;

    public HiResElementService(ImageUpscaleAdapter upscaler, FontRegistry fonts) {
        this.upscaler = upscaler;
        this.fonts = fonts;
    }

    public record HiRes(double factor, BufferedImage upscaledPoster, Map<String, BufferedImage> images,
                        Map<String, String> method) {
    }

    /**
     * @param texts         요소 id → 문구(INFO·CREDIT만 다시 쓴다). 없으면 빈 맵
     * @param cachedPoster  이미 확대한 포스터(재실행 시 과금 방지). null이면 확대 호출
     */
    public HiRes build(BufferedImage poster, List<LayoutPlanner.SourceElement> elements, double factor,
                       Map<String, String> texts, BufferedImage cachedPoster) throws Exception {
        BufferedImage original = ImageOps.toArgb(poster);
        int w = original.getWidth(), h = original.getHeight();
        int W = (int) Math.round(w * factor), H = (int) Math.round(h * factor);
        BufferedImage up = cachedPoster;
        if (up == null) up = upscaler.upscale(withoutPhotos(original, elements), factor);
        if (up.getWidth() != W || up.getHeight() != H) up = ImageOps.resize(up, W, H);
        int[] upPx = up.getRGB(0, 0, W, H, null, 0, W);
        int[] origPx = original.getRGB(0, 0, w, h, null, 0, w);

        // 원본 픽셀마다 덮은 요소 중 가장 높은 z — 위에 다른 요소가 있던 자리의 원본 픽셀은 그 요소의 것이다.
        // 배경판이 그 자리를 확대 포스터에서 가져오면 글자 그림자가 배경에 비친다(1차 인쇄 시험: 크레딧 뒤에 큰 글자 잔상)
        int[] topZ = new int[w * h];
        java.util.Arrays.fill(topZ, Integer.MIN_VALUE);
        for (LayoutPlanner.SourceElement e : elements) {
            BufferedImage img = e.image();
            int ew = img.getWidth(), eh = img.getHeight();
            int[] ep = img.getRGB(0, 0, ew, eh, null, 0, ew);
            for (int y = 0; y < eh; y++) {
                for (int x = 0; x < ew; x++) {
                    int ox = e.bounds().x + x, oy = e.bounds().y + y;
                    if (ox < 0 || oy < 0 || ox >= w || oy >= h || (ep[y * ew + x] >>> 24) <= COVER_ALPHA) continue;
                    topZ[oy * w + ox] = Math.max(topZ[oy * w + ox], e.sourceZ());
                }
            }
        }
        Map<String, BufferedImage> images = new HashMap<>();
        Map<String, String> method = new HashMap<>();
        for (LayoutPlanner.SourceElement e : elements) {
            String text = texts.get(e.id());
            if ((e.role() == ElementRole.INFO || e.role() == ElementRole.CREDIT) && text != null && !text.isBlank()) {
                images.put(e.id(), retype(e, text, factor));
                method.put(e.id(), "글꼴로 다시 씀");
            } else if (e.role() == ElementRole.PHOTO) {
                images.put(e.id(), sharpen(MockImageUpscaleAdapter.bicubic(e.image(), factor)));
                method.put(e.id(), "서버 안 확대(사진)");
            } else {
                images.put(e.id(), fromUpscaled(e, factor, origPx, topZ, w, h, upPx, W, H));
                method.put(e.id(), upscaler.live() ? "초해상도 포스터에서" : "bicubic(목업)");
            }
        }
        return new HiRes(factor, up, images, method);
    }

    /** PHOTO 요소가 덮은 자리를 주변 평균색으로 지운 포스터 — 외부 확대에 보낸다. */
    static BufferedImage withoutPhotos(BufferedImage original, List<LayoutPlanner.SourceElement> elements) {
        int w = original.getWidth(), h = original.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(original, 0, 0, null);
        for (LayoutPlanner.SourceElement e : elements) {
            if (e.role() != ElementRole.PHOTO) continue;
            g.setColor(new Color(128, 128, 128));
            g.fillRect(e.bounds().x, e.bounds().y, e.bounds().width, e.bounds().height);
        }
        g.dispose();
        return out;
    }

    /**
     * 요소의 고해상도 판. 알파와 "메운 자리" 색은 요소를 키운 것, 원본이 그대로 보이던 자리의 색은 확대 포스터.
     * 두 출처의 경계가 보이지 않게 원본 해상도에서 가중치를 만든 뒤 부드럽게 키워 섞는다.
     */
    private static BufferedImage fromUpscaled(LayoutPlanner.SourceElement e, double f, int[] origPx, int[] topZ, int w, int h,
                                              int[] upPx, int W, int H) {
        BufferedImage img = e.image();
        int ew = img.getWidth(), eh = img.getHeight();
        int[] ep = img.getRGB(0, 0, ew, eh, null, 0, ew);
        BufferedImage weight = new BufferedImage(ew, eh, BufferedImage.TYPE_INT_ARGB);
        int[] wp = new int[ew * eh];
        for (int y = 0; y < eh; y++) {
            for (int x = 0; x < ew; x++) {
                int ox = e.bounds().x + x, oy = e.bounds().y + y, p = ep[y * ew + x];
                boolean same = ox >= 0 && oy >= 0 && ox < w && oy < h && (p >>> 24) >= 250
                        && topZ[oy * w + ox] <= e.sourceZ()
                        && ImageOps.colorDistance(p, origPx[oy * w + ox]) < SAME_AS_ORIGINAL;
                wp[y * ew + x] = same ? 0xffffffff : 0xff000000;
            }
        }
        // 원본 해상도에서 경계를 흐린다 — 0/1 그대로 키우면 8배에서 계단·조각이 보인다(인쇄 시험 2차: 배경 구름 가장자리)
        wp = ImageOps.boxBlur(wp, ew, eh, WEIGHT_BLUR);
        for (int i = 0; i < wp.length; i++) {
            int v = wp[i] & 0xff;
            // 흐린 뒤 반쯤 덮인 외딴 조각(한두 픽셀)은 0으로 — 확대 포스터 픽셀이 점처럼 박히지 않게
            v = v < 96 ? 0 : Math.min(255, (int) Math.round((v - 96) * 255.0 / 159));
            wp[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        weight.setRGB(0, 0, ew, eh, wp, 0, ew);
        // 원본이 그대로 보이지 않던 자리(메운 자리)의 색은 분해 해상도(약 544px)에서 온 것이라 블록져 있다 —
        // 원본 해상도에서 그 자리만 흐린 뒤 키운다(인쇄 시험 4차: 아래 구름띠의 계단 조각). 알파는 그대로
        int[] soft = ImageOps.boxBlur(ep, ew, eh, FILL_BLUR), src = ep.clone();
        for (int i = 0; i < src.length; i++) {
            double k = (wp[i] & 0xff) / 255.0;
            if (k >= 1) continue;
            int v = src[i] & 0xff000000;
            for (int c = 0; c < 3; c++) {
                int sh = 16 - 8 * c;
                v |= (int) Math.round(k * ((src[i] >> sh) & 0xff) + (1 - k) * ((soft[i] >> sh) & 0xff)) << sh;
            }
            src[i] = v;
        }
        BufferedImage srcImg = new BufferedImage(ew, eh, BufferedImage.TYPE_INT_ARGB);
        srcImg.setRGB(0, 0, ew, eh, src, 0, ew);
        int tw, th;
        BufferedImage base = MockImageUpscaleAdapter.bicubic(srcImg, f), wUp = MockImageUpscaleAdapter.bicubic(weight, f);
        tw = base.getWidth();
        th = base.getHeight();
        int[] bp = base.getRGB(0, 0, tw, th, null, 0, tw), ww = wUp.getRGB(0, 0, tw, th, null, 0, tw);
        int ox0 = (int) Math.round(e.bounds().x * f), oy0 = (int) Math.round(e.bounds().y * f);
        int[] out = new int[tw * th];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int i = y * tw + x, b = bp[i], a = b >>> 24;
                if (a == 0) continue;
                double k = (ww[i] & 0xff) / 255.0;
                int ux = ox0 + x, uy = oy0 + y;
                if (k <= 0 || ux < 0 || uy < 0 || ux >= W || uy >= H) {
                    out[i] = b;
                    continue;
                }
                int u = upPx[uy * W + ux], v = a << 24;
                for (int c = 0; c < 3; c++) {
                    int sh = 16 - 8 * c;
                    v |= (int) Math.round(k * ((u >> sh) & 0xff) + (1 - k) * ((b >> sh) & 0xff)) << sh;
                }
                out[i] = v;
            }
        }
        // 알파 가장자리를 배율에 비례해 부드럽게 — 분해 레이어는 약 544px에서 원본 해상도로 되살린 것이라 알파가 계단져 있고,
        // 8배로 키우면 블록 조각으로 보인다(인쇄 시험 3차: 아래 구름띠). 인쇄 크기에서 약 1mm라 실루엣이 흐려 보이지 않는다
        softenAlpha(out, tw, th, (int) Math.round(ALPHA_SOFTEN * f));
        BufferedImage result = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, tw, th, out, 0, tw);
        return result;
    }

    /** 알파 채널만 상자 흐림(반경 r, 분리형 누적합). 색은 그대로. */
    static void softenAlpha(int[] px, int w, int h, int r) {
        if (r < 1) return;
        float[] a = new float[px.length], tmp = new float[px.length];
        for (int i = 0; i < px.length; i++) a[i] = px[i] >>> 24;
        for (int y = 0; y < h; y++) {
            double acc = 0;
            int n = 0;
            for (int x = -r; x < w; x++) {
                if (x + r < w) { acc += a[y * w + x + r]; n++; }
                if (x - r - 1 >= 0) { acc -= a[y * w + x - r - 1]; n--; }
                if (x >= 0) tmp[y * w + x] = (float) (acc / n);
            }
        }
        for (int x = 0; x < w; x++) {
            double acc = 0;
            int n = 0;
            for (int y = -r; y < h; y++) {
                if (y + r < h) { acc += tmp[(y + r) * w + x]; n++; }
                if (y - r - 1 >= 0) { acc -= tmp[(y - r - 1) * w + x]; n--; }
                if (y >= 0) {
                    int i = y * w + x;
                    px[i] = (Math.round((float) (acc / n)) << 24) | (px[i] & 0xffffff);
                }
            }
        }
    }

    /**
     * 정보 글자를 글꼴로 다시 쓴다(고해상도 bbox에 맞춤). 색은 요소의 불투명 픽셀 중앙값, 굵기는 잉크 밀도로 짐작.
     * 원래 서체와 다를 수 있다 — 작은 정보 글자는 선명함이 서체 일치보다 중요하다(사용자: 주최·주관은 아주 작게 줄여도 된다).
     */
    BufferedImage retype(LayoutPlanner.SourceElement e, String text, double f) {
        int tw = (int) Math.round(e.bounds().width * f), th = (int) Math.round(e.bounds().height * f);
        String[] lines = text.split("\\R");
        Color color = dominant(e.image());
        boolean bold = inkDensity(e.image()) > 0.32;
        BufferedImage out = new BufferedImage(Math.max(1, tw), Math.max(1, th), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        float size = th / (float) lines.length * 0.62f;
        Font font;
        FontMetrics fm;
        while (true) {
            font = bold ? fonts.bold(size) : fonts.regular(size);
            fm = g.getFontMetrics(font);
            int widest = 0;
            for (String l : lines) widest = Math.max(widest, fm.stringWidth(l));
            if (widest <= tw * 0.98 || size < 4) break;
            size *= 0.97f;
        }
        g.setFont(font);
        g.setColor(color);
        double lineH = th / (double) lines.length;
        for (int i = 0; i < lines.length; i++) {
            int x = (tw - fm.stringWidth(lines[i])) / 2;
            int y = (int) Math.round(i * lineH + (lineH + fm.getAscent() - fm.getDescent()) / 2);
            g.drawString(lines[i], x, y);
        }
        g.dispose();
        return out;
    }

    private static Color dominant(BufferedImage img) {
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        int[][] hist = new int[3][256];
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 200) continue;
            hist[0][(p >> 16) & 0xff]++;
            hist[1][(p >> 8) & 0xff]++;
            hist[2][p & 0xff]++;
            n++;
        }
        if (n == 0) return Color.WHITE;
        int[] med = new int[3];
        for (int c = 0; c < 3; c++) {
            int acc = 0, v = 0;
            while (v < 255 && acc + hist[c][v] < n / 2) acc += hist[c][v++];
            med[c] = v;
        }
        return new Color(med[0], med[1], med[2]);
    }

    private static double inkDensity(BufferedImage img) {
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        double s = 0;
        for (int p : px) s += (p >>> 24) / 255.0;
        return s / px.length;
    }

    /** 3x3 언샤프 마스크(약하게) — 사진은 외부 초해상도 대신 이것만. */
    private static BufferedImage sharpen(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] p = src.getRGB(0, 0, w, h, null, 0, w), blur = ImageOps.boxBlur(p, w, h, 1), out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            int v = p[i] & 0xff000000;
            for (int c = 0; c < 3; c++) {
                int sh = 16 - 8 * c, a = (p[i] >> sh) & 0xff, b = (blur[i] >> sh) & 0xff;
                v |= Math.max(0, Math.min(255, a + (int) Math.round(0.6 * (a - b)))) << sh;
            }
            out[i] = v;
        }
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        r.setRGB(0, 0, w, h, out, 0, w);
        return r;
    }
}
