package com.actset.service;

import com.actset.storage.StorageService;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Gallery 목록용 포스터 축소본. KOPIS 포스터 원본은 장당 약 3MB(2026-10-11 확인)라 목록 24장이면 70MB가 넘는다 —
 * 동기화 때 긴 변 LONG_EDGE px JPEG로 줄여 저장소에 둔다. 상세 화면은 원본 URL을 그대로 쓴다.
 */
@Service
public class PosterThumbnailer {

    /** 목록 카드(약 200~300px 폭, 고해상도 화면 2배)에 충분한 크기. */
    static final int LONG_EDGE = 480;
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final float JPEG_QUALITY = 0.82f;

    private final StorageService storage;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PosterThumbnailer(StorageService storage) {
        this.storage = storage;
    }

    /** @return 저장 경로. 받지 못했거나 이미지가 아니면 empty */
    public Optional<String> create(String sourceUrl, String key) throws IOException, InterruptedException {
        if (sourceUrl == null || !sourceUrl.startsWith("https://")) return Optional.empty();
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            return Optional.empty();
        }
        byte[] data;
        try (InputStream in = res.body()) {
            data = in.readNBytes(MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) return Optional.empty();
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
        if (src == null) return Optional.empty(); // ImageIO가 못 읽는 형식(CMYK JPEG 등) — 목록은 원본 URL로 대체
        String path = "gallery/kopis/" + key.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg";
        storage.store(toJpeg(shrink(src)), path, "image/jpeg");
        return Optional.of(path);
    }

    static BufferedImage shrink(BufferedImage src) {
        double s = Math.min(1.0, LONG_EDGE / (double) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s)), h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE); // 투명 PNG·GIF 포스터는 흰 바탕
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] toJpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}
