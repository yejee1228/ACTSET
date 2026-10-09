package com.actset.external.conversion;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 규격변환 파이프라인의 외부 API 호출 공통부(java.net.http).
 *
 * <p>연결 단계 오류(TLS 핸드셰이크 끊김 등 — PoC q6c에서 이 환경에서 간헐적으로 확인)만 재시도한다.
 * 응답을 받은 뒤의 오류는 재시도하지 않는다 — 생성 API 이중 과금 방지.
 */
public final class ExternalHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private ExternalHttp() {
    }

    public static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (java.net.ConnectException | javax.net.ssl.SSLException | java.net.http.HttpConnectTimeoutException e) {
                last = e;
                Thread.sleep(2000L * (i + 1));
            }
        }
        throw last;
    }

    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                HttpResponse<byte[]> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() / 100 != 2) throw new IOException("GET " + url + " → " + res.statusCode());
                return res.body();
            } catch (java.net.ConnectException | javax.net.ssl.SSLException e) {
                last = e;
                Thread.sleep(2000L * (i + 1));
            }
        }
        throw last;
    }

    public static String jpegDataUri(BufferedImage image, int maxSide) throws IOException {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(fit(image, maxSide)));
    }

    public static String pngDataUri(BufferedImage image) throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(image));
    }

    public static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    public static byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, java.awt.Color.BLACK, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", out);
        return out.toByteArray();
    }

    public static BufferedImage fit(BufferedImage image, int maxSide) {
        int w = image.getWidth(), h = image.getHeight();
        double s = Math.min(1.0, maxSide / (double) Math.max(w, h));
        if (s >= 1.0) return image;
        int nw = (int) Math.round(w * s), nh = (int) Math.round(h * s);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image.getScaledInstance(nw, nh, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return out;
    }

    /** multipart/form-data 본문. files 값은 (파일명, content-type, 바이트). */
    public record FilePart(String filename, String contentType, byte[] data) {
    }

    public static HttpRequest.BodyPublisher multipart(String boundary, Map<String, String> fields,
                                                      Map<String, FilePart> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> f : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                        + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<String, FilePart> f : files.entrySet()) {
                FilePart p = f.getValue();
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey()
                        + "\"; filename=\"" + p.filename() + "\"\r\nContent-Type: " + p.contentType() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(p.data());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
    }

    public static String newBoundary() {
        return "----actset" + UUID.randomUUID().toString().replace("-", "");
    }
}
