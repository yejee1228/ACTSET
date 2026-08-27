package com.actset.render;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

/**
 * PHOTO 레이어 — 업로드 사진(출연진·공연 사진) 액자형 배치(1-10b).
 *
 * CLAUDE.md 규칙 1 / docs/05: 업로드 사진은 어떤 외부 AI API에도 전송하지 않는다.
 * 이 클래스는 StorageService에서 읽은 로컬 바이트를 Java2D로만 다루며, 이 서비스가
 * 참조하는 어댑터 타입에는 사진 바이트를 실어 나르는 필드가 없다(입력은 순수
 * BufferedImage와 배치 좌표뿐 — HTTP 클라이언트를 아예 쓰지 않는다).
 */
@Service
public class PhotoLayerRenderer {

    public enum Mask { SQUARE, ROUNDED, CIRCLE }

    public record PhotoPlacement(
            String role,
            UUID sourceFileId,
            BufferedImage image,
            Rectangle bbox,
            Mask mask
    ) {
    }

    /** PHOTO 레이어를 캔버스에 합성한다. 렌더링 순서상 TITLE·INFO보다 먼저 그려야 한다(docs/05). */
    public void render(Graphics2D g, List<PhotoPlacement> placements, ObjectNode objectMap) {
        Graphics2D pg = (Graphics2D) g.create();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (PhotoPlacement p : placements) {
            drawOne(pg, p);

            ObjectNode entry = objectMap.putObject(p.role());
            entry.put("layer", "PHOTO");
            entry.put("x", p.bbox().x);
            entry.put("y", p.bbox().y);
            entry.put("w", p.bbox().width);
            entry.put("h", p.bbox().height);
            entry.put("source_file_id", p.sourceFileId().toString());
            entry.put("mask", p.mask().name().toLowerCase());
        }
        pg.dispose();
    }

    private void drawOne(Graphics2D g, PhotoPlacement p) {
        Rectangle b = p.bbox();
        Shape clip = switch (p.mask()) {
            case CIRCLE -> new Ellipse2D.Float(b.x, b.y, b.width, b.height);
            case ROUNDED -> {
                float arc = Math.min(b.width, b.height) * 0.12f;
                yield new RoundRectangle2D.Float(b.x, b.y, b.width, b.height, arc, arc);
            }
            case SQUARE -> new Rectangle(b.x, b.y, b.width, b.height);
        };

        Graphics2D clipped = (Graphics2D) g.create();
        clipped.clip(clip);
        BufferedImage cropped = centerCropToFill(p.image(), b.width, b.height);
        clipped.drawImage(cropped, b.x, b.y, b.width, b.height, null);
        clipped.dispose();
    }

    /**
     * 비율을 유지한 채 대상 영역을 가득 채우도록 중앙 기준으로 자른다(object_map의
     * PHOTO 요소는 비율 유지가 원칙 — docs/05).
     */
    private BufferedImage centerCropToFill(BufferedImage src, int targetW, int targetH) {
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
        return src.getSubimage(x, y, cropW, cropH);
    }
}
