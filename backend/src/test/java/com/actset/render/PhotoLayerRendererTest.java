package com.actset.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 사각·라운드·원형 마스크 배치 + object_map 기록을 확인한다(1-10b 완료기준). */
class PhotoLayerRendererTest {

    @Test
    void placesThreeMaskShapesAndRecordsObjectMap() throws Exception {
        int w = 1240, h = 1754;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(30, 26, 46));
        g.fillRect(0, 0, w, h);

        BufferedImage photo = ImageIO.read(new File("../poc-java/out/java_poster.jpg"));

        PhotoLayerRenderer renderer = new PhotoLayerRenderer();
        ObjectNode objectMap = new ObjectMapper().createObjectNode();

        UUID f1 = UUID.randomUUID(), f2 = UUID.randomUUID(), f3 = UUID.randomUUID();
        List<PhotoLayerRenderer.PhotoPlacement> placements = List.of(
                new PhotoLayerRenderer.PhotoPlacement("cast_photo_1", f1, photo,
                        new Rectangle(80, 120, 300, 300), PhotoLayerRenderer.Mask.SQUARE),
                new PhotoLayerRenderer.PhotoPlacement("cast_photo_2", f2, photo,
                        new Rectangle(460, 120, 300, 300), PhotoLayerRenderer.Mask.ROUNDED),
                new PhotoLayerRenderer.PhotoPlacement("cast_photo_3", f3, photo,
                        new Rectangle(840, 120, 300, 300), PhotoLayerRenderer.Mask.CIRCLE)
        );

        renderer.render(g, placements, objectMap);
        g.dispose();

        assertThat(objectMap.size()).isEqualTo(3);
        assertThat(objectMap.get("cast_photo_1").get("mask").asText()).isEqualTo("square");
        assertThat(objectMap.get("cast_photo_2").get("mask").asText()).isEqualTo("rounded");
        assertThat(objectMap.get("cast_photo_3").get("mask").asText()).isEqualTo("circle");
        assertThat(objectMap.get("cast_photo_3").get("source_file_id").asText()).isEqualTo(f3.toString());

        // 마스크 밖 픽셀은 배경색 그대로여야 한다(원형 모서리 바깥이 사진으로 덮이지 않았는지 확인)
        int outsideCircleCorner = canvas.getRGB(840, 120);
        assertThat(outsideCircleCorner).isEqualTo(new Color(30, 26, 46).getRGB());

        File outDir = new File("target/render-test-out");
        outDir.mkdirs();
        ImageIO.write(canvas, "jpg", new File(outDir, "photo_layer_masks.jpg"));
    }
}
