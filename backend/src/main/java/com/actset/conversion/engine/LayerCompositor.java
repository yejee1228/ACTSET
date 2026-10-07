package com.actset.conversion.engine;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.List;

/**
 * [E-C] 레이어 합성 엔진 — 요소 이미지들을 정해진 자리·순서대로 쌓아 한 장으로 만든다(Java2D).
 *
 * <p>쌓는 순서는 호출부가 정한 z 값이 전부다. 역할별 기본 순서(BACKDROP→SUBJECT→DECOR→PHOTO→TITLE→INFO→MARK,
 * docs/05)는 배치 엔진(LayoutPlanner)이 z로 바꿔 넘긴다. 이 엔진은 배치를 판단하지 않는다.
 */
public final class LayerCompositor {

    /**
     * 쌓을 레이어 1장.
     *
     * @param target 캔버스 좌표의 목표 사각형. 이미지를 이 사각형에 그대로 늘려 그리므로 비율 유지는 호출부 책임이다
     * @param clip   null이 아니면 이 사각형 밖은 그리지 않는다(배경 cover-fit 같은 잘라내기용)
     */
    public record Layer(String name, BufferedImage image, Rectangle2D target, int z, float opacity, Rectangle2D clip) {
        public Layer(String name, BufferedImage image, Rectangle2D target, int z) {
            this(name, image, target, z, 1f, null);
        }
    }

    public BufferedImage compose(int width, int height, Color background, List<Layer> layers) {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (background != null) {
                g.setColor(background);
                g.fillRect(0, 0, width, height);
            }
            layers.stream().sorted(Comparator.comparingInt(Layer::z)).forEach(layer -> draw(g, layer));
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private void draw(Graphics2D g, Layer layer) {
        Rectangle2D t = layer.target();
        int tw = (int) Math.round(t.getWidth()), th = (int) Math.round(t.getHeight());
        if (tw <= 0 || th <= 0) return;
        Graphics2D lg = (Graphics2D) g.create();
        try {
            if (layer.clip() != null) lg.clip(layer.clip());
            lg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity()));
            lg.drawImage(resize(layer.image(), tw, th), (int) Math.round(t.getX()), (int) Math.round(t.getY()), null);
        } finally {
            lg.dispose();
        }
    }

    /** 크게 줄일 때 Java2D bicubic 한 번이면 계단이 생겨 area-averaging으로 줄이고, 키울 때는 bicubic. */
    static BufferedImage resize(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            if (w < src.getWidth() / 2 || h < src.getHeight() / 2) {
                g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(src, 0, 0, w, h, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }
}
