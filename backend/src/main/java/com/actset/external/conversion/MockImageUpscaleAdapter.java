package com.actset.external.conversion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** FAL 키가 없을 때(테스트 포함 — 규칙 9): bicubic 확대. 서버 안 확대(PHOTO용)와 같은 결과다. */
@Component
@ConditionalOnExpression("'${actset.external.fal.api-key:}'.isEmpty()")
public class MockImageUpscaleAdapter implements ImageUpscaleAdapter {

    @Override
    public boolean live() {
        return false;
    }

    @Override
    public BufferedImage upscale(BufferedImage image, double factor) {
        return bicubic(image, factor);
    }

    public static BufferedImage bicubic(BufferedImage image, double factor) {
        int w = Math.max(1, (int) Math.round(image.getWidth() * factor)), h = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
