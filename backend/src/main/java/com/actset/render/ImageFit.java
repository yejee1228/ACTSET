package com.actset.render;

import java.awt.*;
import java.awt.image.BufferedImage;

/** 이미지를 대상 치수에 꽉 채우도록 비율 유지 크롭+리사이즈(CSS object-fit: cover와 동일). */
public final class ImageFit {

    private ImageFit() {
    }

    public static BufferedImage coverFit(BufferedImage src, int targetW, int targetH) {
        double srcRatio = src.getWidth() / (double) src.getHeight();
        double targetRatio = targetW / (double) targetH;

        int cropW, cropH;
        if (srcRatio > targetRatio) {
            cropH = src.getHeight();
            cropW = (int) Math.round(cropH * targetRatio);
        } else {
            cropW = src.getWidth();
            cropH = (int) Math.round(cropW / targetRatio);
        }
        int x = Math.max((src.getWidth() - cropW) / 2, 0);
        int y = Math.max((src.getHeight() - cropH) / 2, 0);
        cropW = Math.min(cropW, src.getWidth() - x);
        cropH = Math.min(cropH, src.getHeight() - y);
        BufferedImage cropped = src.getSubimage(x, y, cropW, cropH);

        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(cropped, 0, 0, targetW, targetH, null);
        g.dispose();
        return out;
    }
}
