package com.actset.conversion;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** 규격변환 파이프라인의 픽셀 처리 도우미(자체 엔진, AI 없음). */
public final class ImageOps {

    private ImageOps() {
    }

    public static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    public static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        if (w < src.getWidth() / 2 || h < src.getHeight() / 2) {
            g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        }
        g.dispose();
        return out;
    }

    /** 불투명(alpha ≥ 128) 픽셀 비율. */
    public static double opaqueRatio(BufferedImage img) {
        int[] p = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        long c = 0;
        for (int v : p) if ((v >>> 24) >= 128) c++;
        return c / (double) p.length;
    }

    /** 불투명 픽셀 색의 표준편차 평균(채널). 단색 레이어(Qwen이 내놓는 검정판 등) 판별용. */
    public static double colorStdDev(BufferedImage img) {
        int[] p = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        double[] sum = new double[3], sq = new double[3];
        long n = 0;
        for (int v : p) {
            if ((v >>> 24) < 128) continue;
            n++;
            for (int c = 0; c < 3; c++) {
                int x = (v >> (16 - 8 * c)) & 0xff;
                sum[c] += x;
                sq[c] += x * x;
            }
        }
        if (n == 0) return 0;
        double sd = 0;
        for (int c = 0; c < 3; c++) {
            double m = sum[c] / n;
            sd += Math.sqrt(Math.max(0, sq[c] / n - m * m));
        }
        return sd / 3;
    }

    /**
     * 분해 레이어(아래→위 순서)를 원본 해상도로 되살린다. Qwen-Image-Layered 출력이 원본보다 작아 그대로 키우면 흐려지는
     * 문제 대응. 알파는 업스케일하고, 색은 "그 레이어가 원본에서 그대로 보이는 픽셀" — 자기 알파가 거의 불투명하고
     * 위 레이어들에 가려지지 않은 곳 — 만 원본 픽셀을 쓰고, 나머지(가려진 곳·반투명)는 업스케일한 레이어 색을 쓴다.
     *
     * <p>1차 실행에서는 "레이어 색과 원본 색이 비슷하면 원본"으로 판정했는데, 어두운 제목 그림자가 하늘색과 비슷해
     * 배경판에 제목·달 잔상이 섞였다(FORMAT-CONVERSION-REPORT.md 1차 실행). 그래서 가림 여부로 바꿨다.
     */
    public static List<BufferedImage> restoreResolution(List<BufferedImage> layers, BufferedImage original) {
        int w = original.getWidth(), h = original.getHeight(), n = w * h;
        int[] op = original.getRGB(0, 0, w, h, null, 0, w);
        int[][] up = new int[layers.size()][];
        for (int k = 0; k < layers.size(); k++) up[k] = resize(layers.get(k), w, h).getRGB(0, 0, w, h, null, 0, w);
        float[] clearAbove = new float[n]; // 위 레이어들을 모두 통과하는 비율 Π(1-α)
        java.util.Arrays.fill(clearAbove, 1f);
        BufferedImage[] out = new BufferedImage[layers.size()];
        for (int k = layers.size() - 1; k >= 0; k--) {
            int[] px = new int[n];
            for (int i = 0; i < n; i++) {
                int a = up[k][i] >>> 24;
                if (a == 0) continue;
                boolean visible = a >= RESTORE_OPAQUE && clearAbove[i] >= RESTORE_CLEAR;
                px[i] = (a << 24) | ((visible ? op[i] : up[k][i]) & 0xffffff);
            }
            for (int i = 0; i < n; i++) clearAbove[i] *= 1f - (up[k][i] >>> 24) / 255f;
            out[k] = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out[k].setRGB(0, 0, w, h, px, 0, w);
        }
        return List.of(out);
    }

    /** 임시값 — 이 알파 이상이고 위가 이 비율 이상 뚫려 있으면 "원본에서 그대로 보이는 픽셀". */
    static final int RESTORE_OPAQUE = 217;
    static final float RESTORE_CLEAR = 0.9f;

    /**
     * 넓게 퍼진 반투명 레이어(빛줄기·안개 등)인지. 이런 레이어를 연결요소로 쪼개면 수십 개의 의미 없는 조각이 된다
     * (1차 실행: 별빛 줄기 레이어 → 20여 조각). 알파>0 픽셀이 캔버스의 30% 이상이고 거의 불투명한(≥200) 픽셀이
     * 캔버스의 1% 미만이면 참. 제목 레이어도 글로우 때문에 옅은 픽셀이 넓게 퍼지지만 글자 획은 불투명해서(5~18%)
     * 여기서 걸리지 않는다(2차 실행에서 "진한 픽셀 비율"로 판정했다가 제목 레이어가 걸린 것을 고친 기준 — 임시값).
     */
    public static boolean isDiffuseOverlay(BufferedImage img) {
        int[] p = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        long any = 0, opaque = 0;
        for (int v : p) {
            int a = v >>> 24;
            if (a > 0) any++;
            if (a >= 200) opaque++;
        }
        return any >= 0.3 * p.length && opaque < 0.01 * p.length;
    }

    /** 알파 ≥ threshold 픽셀만 남긴 사본(나머지 투명). */
    public static BufferedImage strongPart(BufferedImage img, int threshold) {
        int w = img.getWidth(), h = img.getHeight();
        int[] p = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < p.length; i++) if ((p[i] >>> 24) < threshold) p[i] = 0;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, p, 0, w);
        return out;
    }

    /** 떼어낸 덩어리 자리를 투명하게 비운 사본(덩어리 bbox 안에서 덩어리 픽셀만). */
    public static BufferedImage subtract(BufferedImage img, java.util.List<com.actset.conversion.engine.ElementSplitter.Element> parts) {
        BufferedImage out = toArgb(img) == img ? copyArgb(img) : toArgb(img);
        for (var e : parts) {
            Rectangle b = e.bounds();
            for (int y = 0; y < b.height; y++) {
                for (int x = 0; x < b.width; x++) {
                    if ((e.image().getRGB(x, y) >>> 24) > 0) out.setRGB(b.x + x, b.y + y, 0);
                }
            }
        }
        return out;
    }

    /** 덩어리 모양(splitImage의 알파>0 자리) 그대로, 원 레이어의 픽셀(옅은 글로우 포함)을 잘라 온다. */
    public static BufferedImage cropOriginal(BufferedImage layer, Rectangle b, BufferedImage splitImage) {
        BufferedImage out = new BufferedImage(b.width, b.height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < b.height; y++) {
            for (int x = 0; x < b.width; x++) {
                if ((splitImage.getRGB(x, y) >>> 24) > 0) out.setRGB(x, y, layer.getRGB(b.x + x, b.y + y));
            }
        }
        return out;
    }

    private static BufferedImage copyArgb(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    /** 알파가 0이 아닌 영역의 bbox. 비어 있으면 null. */
    public static Rectangle alphaBounds(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] p = img.getRGB(0, 0, w, h, null, 0, w);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < p.length; i++) {
            if ((p[i] >>> 24) == 0) continue;
            int x = i % w, y = i / w;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    public static int colorDistance(int a, int b) {
        int dr = ((a >> 16) & 0xff) - ((b >> 16) & 0xff);
        int dg = ((a >> 8) & 0xff) - ((b >> 8) & 0xff);
        int db = (a & 0xff) - (b & 0xff);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * 투명 구멍을 주변 색으로 메운다(알파 가중 피라미드 push-pull). 편집 모델에 보내기 전 구멍을 단순화하고,
     * 배경판이 구멍 난 채로 쓰이지 않게 하는 용도. 결과는 불투명.
     */
    public static BufferedImage fillHoles(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] p = src.getRGB(0, 0, w, h, null, 0, w);
        float[][] level = new float[4][w * h]; // r,g,b 가중합(premultiplied), weight
        for (int i = 0; i < p.length; i++) {
            float a = (p[i] >>> 24) / 255f;
            level[0][i] = ((p[i] >> 16) & 0xff) * a;
            level[1][i] = ((p[i] >> 8) & 0xff) * a;
            level[2][i] = (p[i] & 0xff) * a;
            level[3][i] = a;
        }
        float[][] filled = pushPull(level, w, h);
        // push-pull의 거친 단계가 넓은 구멍을 계단 모양 블록으로 채운다 → 채운 값을 흐려 부드럽게(PDF 시험: 메운 자리가 사각형으로 보였다)
        int blurR = Math.max(2, Math.min(w, h) / 80);
        for (int c = 0; c < 4; c++) filled[c] = boxBlurFloat(filled[c], w, h, blurR);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            float a = (p[i] >>> 24) / 255f;
            float wsum = Math.max(filled[3][i], 1e-6f);
            int r = clamp(level[0][i] + (1 - a) * filled[0][i] / wsum);
            int g = clamp(level[1][i] + (1 - a) * filled[1][i] / wsum);
            int b = clamp(level[2][i] + (1 - a) * filled[2][i] / wsum);
            out[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    private static float[] boxBlurFloat(float[] v, int w, int h, int r) {
        float[] tmp = new float[v.length], out = new float[v.length];
        for (int pass = 0; pass < 2; pass++) { // 두 번 = 삼각형 커널에 가깝다
            float[] src = pass == 0 ? v : out;
            for (int y = 0; y < h; y++) {
                float acc = 0;
                int n = 0;
                for (int x = -r; x < w; x++) {
                    if (x + r < w) { acc += src[y * w + x + r]; n++; }
                    if (x - r - 1 >= 0) { acc -= src[y * w + x - r - 1]; n--; }
                    if (x >= 0) tmp[y * w + x] = acc / n;
                }
            }
            for (int x = 0; x < w; x++) {
                float acc = 0;
                int n = 0;
                for (int y = -r; y < h; y++) {
                    if (y + r < h) { acc += tmp[(y + r) * w + x]; n++; }
                    if (y - r - 1 >= 0) { acc -= tmp[(y - r - 1) * w + x]; n--; }
                    if (y >= 0) out[y * w + x] = acc / n;
                }
            }
        }
        return out;
    }

    private static float[][] pushPull(float[][] lv, int w, int h) {
        if (w <= 1 && h <= 1) return lv;
        int cw = (w + 1) / 2, ch = (h + 1) / 2;
        float[][] coarse = new float[4][cw * ch];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ci = (y / 2) * cw + x / 2;
                for (int c = 0; c < 4; c++) coarse[c][ci] += lv[c][y * w + x];
            }
        }
        float[][] up = pushPull(coarse, cw, ch);
        float[][] out = new float[4][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, ci = (y / 2) * cw + x / 2;
                float wt = lv[3][i];
                if (wt >= 0.999f) {
                    for (int c = 0; c < 4; c++) out[c][i] = lv[c][i];
                } else {
                    float uw = Math.max(up[3][ci], 1e-6f);
                    for (int c = 0; c < 3; c++) out[c][i] = lv[c][i] + (1 - wt) * up[c][ci] / uw;
                    out[3][i] = 1f;
                }
            }
        }
        return out;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** 박스 블러(반경 r, 분리형). 별 같은 고주파 잡음을 비교 전에 눌러준다. */
    public static int[] boxBlur(int[] rgb, int w, int h, int r) {
        int[] tmp = new int[rgb.length], out = new int[rgb.length];
        pass(rgb, tmp, w, h, r, true);
        pass(tmp, out, w, h, r, false);
        return out;
    }

    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int k = 0; k < len; k++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int d = -r; d <= r; d++) {
                    int kk = k + d;
                    if (kk < 0 || kk >= len) continue;
                    int v = horizontal ? src[line * w + kk] : src[kk * w + line];
                    sr += (v >> 16) & 0xff;
                    sg += (v >> 8) & 0xff;
                    sb += v & 0xff;
                    n++;
                }
                int idx = horizontal ? line * w + k : k * w + line;
                dst[idx] = 0xff000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
            }
        }
    }

    /** 요소들을 번호와 함께 체커보드 위에 늘어놓은 시트 — 역할 판정(LLM)·리포트용. */
    public static BufferedImage numberedSheet(List<BufferedImage> images, int cell, int cols) {
        int rows = (images.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, Math.max(1, rows) * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        for (int i = 0; i < images.size(); i++) {
            int cx = (i % cols) * cell, cy = (i / cols) * cell;
            checker(g, new Rectangle(cx + 4, cy + 4, cell - 8, cell - 8), 12);
            BufferedImage img = images.get(i);
            double s = Math.min((cell - 16.0) / img.getWidth(), (cell - 40.0) / img.getHeight());
            int w = Math.max(1, (int) (img.getWidth() * s)), h = Math.max(1, (int) (img.getHeight() * s));
            g.drawImage(resize(img, w, h), cx + (cell - w) / 2, cy + 32 + (cell - 40 - h) / 2, null);
            g.setColor(new Color(220, 30, 30));
            g.setStroke(new BasicStroke(2));
            g.drawRect(cx + 4, cy + 4, cell - 8, cell - 8);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            g.drawString(String.valueOf(i + 1), cx + 10, cy + 28);
        }
        g.dispose();
        return sheet;
    }

    public static BufferedImage onChecker(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        checker(g, new Rectangle(0, 0, img.getWidth(), img.getHeight()), Math.max(8, img.getWidth() / 40));
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void checker(Graphics2D g, Rectangle r, int c) {
        for (int y = r.y; y < r.y + r.height; y += c) {
            for (int x = r.x; x < r.x + r.width; x += c) {
                g.setColor((((x - r.x) / c + (y - r.y) / c) % 2 == 0) ? new Color(205, 205, 205) : Color.WHITE);
                g.fillRect(x, y, Math.min(c, r.x + r.width - x), Math.min(c, r.y + r.height - y));
            }
        }
    }
}
