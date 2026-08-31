import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;

/**
 * L2(타이틀 레이어) 효과 검증
 * 텍스트를 이미지에 녹이지 않고 위에 얹어도 디자인적으로 성립하는지 확인한다.
 */
public class TitleLayerDemo {

    static Font regular, black;

    static Font pickKorean(Font[] fonts) {
        for (Font f : fonts) if (f.getFontName(Locale.US).contains("KR")) return f;
        return fonts[0];
    }

    /** L1 — 배경(생성 API 대체) */
    static BufferedImage visual(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color top = new Color(72, 52, 108), bot = new Color(16, 14, 30);
        for (int y = 0; y < h; y++) {
            float p = Math.min(y / (float) h * 1.2f, 1f);
            g.setColor(new Color(
                (int)(top.getRed()+(bot.getRed()-top.getRed())*p),
                (int)(top.getGreen()+(bot.getGreen()-top.getGreen())*p),
                (int)(top.getBlue()+(bot.getBlue()-top.getBlue())*p)));
            g.drawLine(0, y, w, y);
        }
        // 밝은 피사체 — 타이틀이 겹쳐도 읽히는지 보기 위한 요소
        double cx = w*0.5, cy = h*0.46, r = Math.min(w,h)*0.34;
        g.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Double(cx, cy), (float) r,
                new float[]{0f, 1f},
                new Color[]{new Color(240, 220, 255, 190), new Color(240, 220, 255, 0)}));
        g.fill(new Ellipse2D.Double(cx-r, cy-r, r*2, r*2));
        g.dispose();
        return img;
    }

    /** L2 — 타이틀 렌더링. style: 0 기본 / 1 외곽선+그림자 / 2 그라디언트+글로우 */
    static void drawTitle(Graphics2D g, String text, int px, int cx, int baseY, int style) {
        FontRenderContext frc = g.getFontRenderContext();
        Font f = black.deriveFont((float) px);
        TextLayout tl = new TextLayout(text, f, frc);
        float w = tl.getAdvance();
        float x = cx - w / 2f;
        Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, baseY));

        switch (style) {
            case 0 -> {
                g.setColor(Color.WHITE);
                g.fill(outline);
            }
            case 1 -> {
                // 그림자
                g.setColor(new Color(0, 0, 0, 150));
                g.fill(AffineTransform.getTranslateInstance(px*0.035, px*0.045)
                        .createTransformedShape(outline));
                // 외곽선
                g.setStroke(new BasicStroke(px * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(18, 12, 34));
                g.draw(outline);
                g.setColor(Color.WHITE);
                g.fill(outline);
            }
            case 2 -> {
                // 글로우
                for (int i = 6; i >= 1; i--) {
                    g.setStroke(new BasicStroke(px * 0.02f * i, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(new Color(150, 110, 255, 16));
                    g.draw(outline);
                }
                // 그라디언트 채움
                g.setPaint(new GradientPaint(x, baseY - px, new Color(255, 255, 255),
                                             x, baseY, new Color(198, 176, 255)));
                g.fill(outline);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        regular = pickKorean(Font.createFonts(new File(PosterRenderer.FONT_TTC)));
        black = pickKorean(Font.createFonts(new File(PosterRenderer.FONT_TTC_BLACK)));

        int W = 720, H = 900;
        String[] labels = {"기본", "외곽선 + 그림자", "그라디언트 + 글로우"};
        BufferedImage sheet = new BufferedImage(W*3 + 80, H + 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setColor(new Color(246,246,250));
        sg.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (int s = 0; s < 3; s++) {
            BufferedImage img = visual(W, H);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // 타이틀을 피사체 위에 겹쳐 배치 — 가장 불리한 조건
            drawTitle(g, "겨울 나그네", (int)(W*0.155), W/2, (int)(H*0.50), s);

            // L3 정보 (동일 조건)
            Font info = regular.deriveFont(W*0.038f);
            g.setFont(info);
            g.setColor(new Color(255,255,255,236));
            String[] lines = {"2026. 3. 14 (토) 19:30", "예술의전당 리사이틀홀"};
            int y = (int)(H*0.60);
            for (String ln : lines) {
                TextLayout t = new TextLayout(ln, info, g.getFontRenderContext());
                g.setColor(new Color(0,0,0,110));
                t.draw(g, W/2 - t.getAdvance()/2 + 2, y + 2);
                g.setColor(new Color(255,255,255,238));
                t.draw(g, W/2 - t.getAdvance()/2, y);
                y += W*0.055;
            }
            g.dispose();

            int px = s*(W+20) + 20;
            sheet.getGraphics().drawImage(img, px, 20, null);
            sg.setFont(black.deriveFont(24f));
            sg.setColor(new Color(28,30,42));
            sg.drawString(labels[s], px, H + 62);
        }
        sg.dispose();
        new File("out").mkdirs();
        ImageIO.write(sheet, "jpg", new File("out/L2_타이틀효과_비교.jpg"));
        System.out.println("done");
    }
}
