package com.actset.external.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * fal.ai SeedVR2 초해상도(fal-ai/seedvr/upscale/image, 배율 1~10, 메가픽셀당 $0.001 — 2026-10-12 확인).
 * 포스터 한 장 4배(1086x1448 → 약 25MP)가 약 $0.025. 큐 REST API는 FalQwenLayeredAdapter와 같다.
 */
@Component
@ConditionalOnExpression("!'${actset.external.fal.api-key:}'.isEmpty()")
public class FalSeedVrUpscaleAdapter implements ImageUpscaleAdapter {

    private static final String MODEL = "fal-ai/seedvr/upscale/image";
    private static final Duration MAX_WAIT = Duration.ofMinutes(10);

    private final String apiKey;
    private final ObjectMapper objectMapper;
    /** SeedVR noise_scale(0~1, 모델 기본 0.1). 높을수록 없는 질감을 더 만든다 — 0.1은 달 표면에 실핏줄 무늬, 0은 덜함(2026-10-12 시험). 그래서 0을 쓴다. */
    private final double noiseScale;

    public FalSeedVrUpscaleAdapter(@Value("${actset.external.fal.api-key}") String apiKey, ObjectMapper objectMapper,
                                   @Value("${actset.external.fal.upscale-noise-scale:0}") double noiseScale) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.noiseScale = noiseScale;
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public BufferedImage upscale(BufferedImage image, double factor) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("image_url", ExternalHttp.pngDataUri(opaque(image)));
        body.put("upscale_mode", "factor");
        body.put("upscale_factor", Math.max(1, Math.min(10, factor)));
        body.put("output_format", "png");
        body.put("noise_scale", noiseScale);

        HttpResponse<String> submit = ExternalHttp.send(request("https://queue.fal.run/" + MODEL)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        if (submit.statusCode() / 100 != 2) {
            throw new IllegalStateException("fal upscale submit " + submit.statusCode() + ": " + submit.body());
        }
        JsonNode queued = objectMapper.readTree(submit.body());
        String statusUrl = queued.path("status_url").asText(), responseUrl = queued.path("response_url").asText();
        long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();
        while (true) {
            Thread.sleep(2000);
            HttpResponse<String> status = ExternalHttp.send(request(statusUrl).GET().build());
            String s = objectMapper.readTree(status.body()).path("status").asText();
            if ("COMPLETED".equals(s)) break;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("fal upscale 대기 시간 초과(" + MAX_WAIT + "): " + status.body());
            }
        }
        HttpResponse<String> result = ExternalHttp.send(request(responseUrl).GET().build());
        if (result.statusCode() / 100 != 2) {
            throw new IllegalStateException("fal upscale result " + result.statusCode() + ": " + result.body());
        }
        String url = objectMapper.readTree(result.body()).path("image").path("url").asText();
        if (url.isBlank()) throw new IllegalStateException("fal upscale 결과에 이미지가 없다: " + result.body());
        return ImageIO.read(new ByteArrayInputStream(ExternalHttp.getBytes(url)));
    }

    /** 알파는 확대 대상이 아니다 — 불투명으로 만들어 보낸다(투명 부분은 검정). */
    private static BufferedImage opaque(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json");
    }
}
