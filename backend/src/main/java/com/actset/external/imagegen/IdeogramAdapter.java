package com.actset.external.imagegen;

import com.actset.render.ImageFit;
import com.actset.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

/**
 * Ideogram 3.0 API(https://developer.ideogram.ai) 연동. {@code IDEOGRAM_API_KEY}를
 * 채우면(빈 문자열이 아니게 되면) 이 빈이 MockImageGenerationAdapter를 자동으로 대체한다
 * — 다른 벤더(GPT·KOPIS) 키 유무와는 무관하다. 호출부(DraftGenerateJobHandler)·
 * ImageGenerationRequest 타입은 그대로다.
 *
 * <p>Ideogram의 {@code resolution}은 고정된 조합만 허용하므로(임의 크기 거부) 가장
 * 가까운 비율을 골라 요청하고, 돌아온 이미지를 {@link ImageFit#coverFit}으로 우리가
 * 요청한 정확한 width×height에 맞춰 잘라낸다 — 이 계약은 Mock 어댑터와 동일해야
 * 이후 레이어 합성 좌표가 어긋나지 않는다.
 *
 * <p>업로드 사진(cast_photo·performance_photo·logo)은 이 클래스에 절대 들어오지
 * 않는다(CLAUDE.md 규칙 1) — {@code styleReferenceImageUrls}는 reference_image만
 * 담는 타입 수준 계약이며, Ideogram의 {@code style_reference_images}로 그대로 전달된다.
 */
@Component
@ConditionalOnExpression("!'${actset.external.ideogram.api-key:}'.isEmpty()")
public class IdeogramAdapter implements ImageGenerationAdapter {

    private static final String ENDPOINT = "https://api.ideogram.ai/v1/ideogram-v3/generate";

    // developer.ideogram.ai가 나열한 허용 resolution 전체 — 임의 크기는 400으로 거부된다.
    private static final int[][] ALLOWED_RESOLUTIONS = {
            {512, 1536}, {576, 1408}, {576, 1472}, {576, 1536}, {640, 1344}, {640, 1408}, {640, 1472}, {640, 1536},
            {704, 1152}, {704, 1216}, {704, 1280}, {704, 1344}, {704, 1408}, {704, 1472}, {736, 1312}, {768, 1088},
            {768, 1216}, {768, 1280}, {768, 1344}, {800, 1280}, {832, 960}, {832, 1024}, {832, 1088}, {832, 1152},
            {832, 1216}, {832, 1248}, {864, 1152}, {896, 960}, {896, 1024}, {896, 1088}, {896, 1120}, {896, 1152},
            {960, 832}, {960, 896}, {960, 1024}, {960, 1088}, {1024, 832}, {1024, 896}, {1024, 960}, {1024, 1024},
            {1088, 768}, {1088, 832}, {1088, 896}, {1088, 960}, {1120, 896}, {1152, 704}, {1152, 832}, {1152, 864},
            {1152, 896}, {1216, 704}, {1216, 768}, {1216, 832}, {1248, 832}, {1280, 704}, {1280, 768}, {1280, 800},
            {1312, 736}, {1344, 640}, {1344, 704}, {1344, 768}, {1408, 576}, {1408, 640}, {1408, 704}, {1472, 576},
            {1472, 640}, {1472, 704}, {1536, 512}, {1536, 576}, {1536, 640},
    };

    @Value("${actset.external.ideogram.api-key}")
    private String apiKey;

    /** TURBO(운용전략상 시안 후보) / DEFAULT / QUALITY(운용전략상 확정본 재생성, docs/14). */
    @Value("${actset.external.ideogram.rendering-speed:DEFAULT}")
    private String renderingSpeed;

    private final RestTemplate restTemplate;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public IdeogramAdapter(RestTemplateBuilder builder, StorageService storageService, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ImageGenerationResult generate(ImageGenerationRequest request) throws Exception {
        int[] resolution = closestResolution(request.width(), request.height());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("prompt", request.prompt());
        body.add("resolution", resolution[0] + "x" + resolution[1]);
        body.add("rendering_speed", renderingSpeed);
        body.add("num_images", "1");
        if (request.negativePrompt() != null && !request.negativePrompt().isBlank()) {
            body.add("negative_prompt", request.negativePrompt());
        }
        if (request.styleType() != null && !request.styleType().isBlank()) {
            body.add("style_type", request.styleType());
        }
        if (request.seed() != null && !request.seed().isBlank()) {
            body.add("seed", request.seed());
        }
        for (String path : request.styleReferenceImageUrls()) {
            byte[] refBytes = storageService.read(path);
            String filename = path.substring(path.lastIndexOf('/') + 1);
            body.add("style_reference_images", new ByteArrayResource(refBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Api-Key", apiKey);

        String response = restTemplate.postForObject(ENDPOINT, new HttpEntity<>(body, headers), String.class);
        JsonNode first = objectMapper.readTree(response).path("data").get(0);
        if (first == null || first.path("url").isMissingNode()) {
            throw new IllegalStateException("Ideogram 응답에 이미지 URL이 없습니다: " + response);
        }
        String imageUrl = first.path("url").asText();
        String seed = first.path("seed").asText();

        byte[] rawImage = restTemplate.getForObject(imageUrl, byte[].class);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rawImage));
        BufferedImage fitted = ImageFit.coverFit(decoded, request.width(), request.height());

        return new ImageGenerationResult(toJpegBytes(fitted), "ideogram-v3-" + renderingSpeed.toLowerCase(), seed);
    }

    private int[] closestResolution(int targetW, int targetH) {
        double targetRatio = targetW / (double) targetH;
        int[] best = ALLOWED_RESOLUTIONS[0];
        double bestDiff = Double.MAX_VALUE;
        for (int[] r : ALLOWED_RESOLUTIONS) {
            double diff = Math.abs(r[0] / (double) r[1] - targetRatio);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        return best;
    }

    private byte[] toJpegBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
