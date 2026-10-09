package com.actset.external.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI images/edits. PoC poc/q6c_hybrid.py에서 검증한 모델·파라미터(quality=high, png).
 * 출력 크기는 입력 비율에 가장 가까운 16의 배수로 맞춘다(PoC: 1086×1448 → 1088×1456).
 */
@Component
@ConditionalOnExpression("!'${actset.external.openai.api-key:}'.isEmpty()")
public class OpenAiImageEditAdapter implements ImageEditAdapter {

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAiImageEditAdapter(@Value("${actset.external.openai.api-key}") String apiKey,
                                  @Value("${actset.external.openai.image-model:gpt-image-2.5-sunburst}") String model,
                                  ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public BufferedImage edit(BufferedImage input, String prompt, boolean transparentBackground) throws Exception {
        BufferedImage src = ExternalHttp.fit(input, 1536);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("prompt", prompt);
        fields.put("size", sizeFor(src.getWidth(), src.getHeight()));
        fields.put("quality", "high");
        fields.put("output_format", "png");
        fields.put("background", transparentBackground ? "transparent" : "opaque");
        String boundary = ExternalHttp.newBoundary();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/images/edits"))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(ExternalHttp.multipart(boundary, fields,
                        Map.of("image", new ExternalHttp.FilePart("input.png", "image/png", ExternalHttp.png(src)))))
                .build();
        HttpResponse<String> res = ExternalHttp.send(req);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("images/edits " + res.statusCode() + ": " + res.body());
        }
        JsonNode body = objectMapper.readTree(res.body());
        byte[] png = Base64.getDecoder().decode(body.path("data").path(0).path("b64_json").asText());
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** 긴 변을 1024~1536 사이로, 각 변을 16의 배수로. */
    static String sizeFor(int w, int h) {
        double s = Math.min(1536.0 / Math.max(w, h), 1.0);
        s = Math.max(s, 1024.0 / Math.max(w, h));
        int nw = Math.max(16, (int) Math.round(w * s / 16.0) * 16);
        int nh = Math.max(16, (int) Math.round(h * s / 16.0) * 16);
        return nw + "x" + nh;
    }
}
