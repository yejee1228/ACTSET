package com.actset.external.imagegen;

import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * 0-1(Ideogram 4.0 API 키)이 아직 없어 실제 호출 대신 결정적 그라디언트 배경을
 * 생성하는 껍데기 어댑터(OVERNIGHT-LOG 참고). 실제 키가 준비되면 이 클래스를
 * ImageGenerationAdapter를 구현하는 IdeogramAdapter로 교체하고 이 클래스는 지운다.
 *
 * 글자 없는 배경만 만든다는 계약(docs/05)은 목업에서도 지킨다 — 텍스트를 그리지 않는다.
 */
@Service
public class MockImageGenerationAdapter implements ImageGenerationAdapter {

    @Override
    public ImageGenerationResult generate(ImageGenerationRequest request) {
        String seed = UUID.randomUUID().toString();
        BufferedImage image = gradientPlaceholder(request.width(), request.height(), seed);
        byte[] bytes = toJpegBytes(image);
        return new ImageGenerationResult(bytes, "mock-ideogram-4.0", seed);
    }

    /** seed로 팔레트를 정하는 그라디언트 + 장식 원 — poc-java/PosterRenderer.placeholderVisual과 같은 구조. */
    private BufferedImage gradientPlaceholder(int w, int h, String seed) {
        Random rnd = new Random(seed.hashCode());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color top = randomColor(rnd, 40, 100);
        Color bottom = new Color(14, 18, 34);
        for (int y = 0; y < h; y++) {
            float p = Math.min(y / (float) h * 1.25f, 1f);
            g.setColor(new Color(
                    (int) (top.getRed() + (bottom.getRed() - top.getRed()) * p),
                    (int) (top.getGreen() + (bottom.getGreen() - top.getGreen()) * p),
                    (int) (top.getBlue() + (bottom.getBlue() - top.getBlue()) * p)));
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

    private Color randomColor(Random rnd, int min, int range) {
        return new Color(min + rnd.nextInt(range), min + rnd.nextInt(range), min + rnd.nextInt(range) + 40);
    }

    private byte[] toJpegBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
