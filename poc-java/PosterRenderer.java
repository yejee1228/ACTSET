import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.*;

/**
 * ACTSET Layer 2 렌더링 — Java2D 이식 검증
 * Python/PIL 버전(poc/engine.py)과 동일한 결과를 낼 수 있는지 확인한다.
 */
public class PosterRenderer {

    static final String FONT_TTC = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc";
    static final String FONT_TTC_BLACK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc";
    static Font baseRegular, baseBold;

    /** TTC 컬렉션에서 한국어 폰트를 골라 로드한다. */
    static void loadFonts() throws Exception {
        baseRegular = pickKorean(Font.createFonts(new File(FONT_TTC)));
        baseBold = pickKorean(Font.createFonts(new File(FONT_TTC_BLACK)));
        System.out.println("regular = " + baseRegular.getFontName(Locale.US));
        System.out.println("bold    = " + baseBold.getFontName(Locale.US));
    }

    static Font pickKorean(Font[] fonts) {
        for (Font f : fonts) {
            if (f.getFontName(Locale.US).contains("KR")) return f;
        }
        return fonts[0];
    }

    // ---- 정보 블록 ---------------------------------------------------------
    record Block(String kind, String text, float sizeRatio, boolean bold, float gapAfter) {}

    static final float TITLE_SCALE = 0.085f;   // 짧은 변 대비 제목 크기 (PoC 임시값)
    static final float LINE_RATIO = 1.26f;

    static List<Block> blocks() {
        return List.of(
            new Block("title",    "겨울 나그네",                        1.00f, true,  0.30f),
            new Block("subtitle", "슈베르트 연가곡 전곡",                0.42f, false, 0.55f),
            new Block("date",     "2026. 3. 14 (토) 19:30",            0.34f, true,  0.08f),
            new Block("venue",    "예술의전당 리사이틀홀",               0.34f, false, 0.45f),
            new Block("running",  "러닝타임 70분 (인터미션 없음)",       0.26f, false, 0.10f),
            new Block("cast",     "김서현(바리톤)  이지우(피아노)",       0.26f, false, 0.10f),
            new Block("price",    "R석 50,000원  S석 30,000원",         0.26f, false, 0.10f),
            new Block("age",      "8세 이상 관람",                      0.26f, false, 0.10f),
            new Block("organizer","주최 반달컴퍼니   주관 ACTSET",       0.26f, false, 0.08f),
            new Block("inquiry",  "문의 02-000-0000",                   0.26f, false, 0.35f),
            new Block("notice",   "본 공연은 한국문화예술위원회의 후원으로 제작되었습니다.", 0.20f, false, 0.10f)
        );
    }

    // ---- Layer 1 플레이스홀더 ---------------------------------------------
    static BufferedImage placeholderVisual(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Color top = new Color(58, 44, 92), bottom = new Color(14, 18, 34);
        for (int y = 0; y < h; y++) {
            float p = Math.min(y / (float) h * 1.25f, 1f);
            g.setColor(new Color(
                (int) (top.getRed()   + (bottom.getRed()   - top.getRed())   * p),
                (int) (top.getGreen() + (bottom.getGreen() - top.getGreen()) * p),
                (int) (top.getBlue()  + (bottom.getBlue()  - top.getBlue())  * p)));
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

    // ---- Layer 2 합성 ------------------------------------------------------
    static BufferedImage renderLayer2(BufferedImage base, int w, int h,
                                      double[] infoArea) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, null);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                           RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                           RenderingHints.VALUE_STROKE_PURE);

        int ax0 = (int) (infoArea[0] * w), ay0 = (int) (infoArea[1] * h);
        int ax1 = (int) (infoArea[2] * w), ay1 = (int) (infoArea[3] * h);

        // 가독성 보조 밴드
        int bandTop = Math.max((int) (ay0 - h * 0.06), 0);
        for (int y = bandTop; y < h; y++) {
            float p = (y - bandTop) / (float) Math.max(h - bandTop, 1);
            g.setColor(new Color(8, 10, 18, (int) (200 * Math.min(p * 1.6f, 1f))));
            g.drawLine(0, y, w, y);
        }

        int shortSide = Math.min(w, h);
        int titlePx = (int) (shortSide * TITLE_SCALE);
        FontRenderContext frc = g.getFontRenderContext();

        // 전체 높이 계산 → 세로 중앙 정렬
        int totalH = 0;
        for (Block b : blocks()) {
            int px = Math.max((int) (titlePx * b.sizeRatio()), 8);
            totalH += (int) (px * LINE_RATIO) + (int) (px * b.gapAfter());
        }
        int y = ay0 + Math.max((ay1 - ay0 - totalH) / 2, 0);

        Map<String, int[]> objectMap = new LinkedHashMap<>();
        for (Block b : blocks()) {
            int px = Math.max((int) (titlePx * b.sizeRatio()), 8);
            Font f = (b.bold() ? baseBold : baseRegular).deriveFont((float) px);
            TextLayout tl = new TextLayout(b.text(), f, frc);
            int lineH = (int) (px * LINE_RATIO);
            float tw = tl.getAdvance();
            float x = ax0 + ((ax1 - ax0) - tw) / 2f;
            // 줄 상자 안 세로 중앙 정렬 (CJK 메트릭 여백 보정)
            float baseline = y + (lineH - (tl.getAscent() + tl.getDescent())) / 2f + tl.getAscent();

            g.setColor(b.kind().equals("notice")
                       ? new Color(215, 218, 228, 235) : Color.WHITE);
            tl.draw(g, x, baseline);

            objectMap.put(b.kind(), new int[]{ax0, y, ax1 - ax0, lineH, px});
            y += lineH + (int) (px * b.gapAfter());
        }
        g.dispose();

        System.out.println("object_map 항목 수 = " + objectMap.size());
        return out;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        loadFonts();

        int W = 1240, H = 1754;
        double[] infoArea = {0.08, 0.60, 0.92, 0.95};

        BufferedImage layer1 = placeholderVisual(W, H);
        long tBase = System.currentTimeMillis();

        BufferedImage poster = renderLayer2(layer1, W, H, infoArea);
        long tRender = System.currentTimeMillis();

        new File("out").mkdirs();
        ImageIO.write(poster, "jpg", new File("out/java_poster.jpg"));

        // 인쇄 해상도 검증 — A3 300dpi
        int PW = 3508, PH = 4961;
        BufferedImage up = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_RGB);
        Graphics2D ug = up.createGraphics();
        ug.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        ug.drawImage(layer1, 0, 0, PW, PH, null);
        ug.dispose();
        BufferedImage print = renderLayer2(up, PW, PH, infoArea);
        ImageIO.write(print.getSubimage((int)(PW*0.10), (int)(PH*0.62),
                                        (int)(PW*0.80), (int)(PH*0.16)),
                      "jpg", new File("out/java_print_crop.jpg"));

        System.out.printf("폰트 로드+배경 %dms / Layer2 합성 %dms / 전체 %dms%n",
                tBase - t0, tRender - tBase, System.currentTimeMillis() - t0);
    }
}
