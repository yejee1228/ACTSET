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
import java.util.ArrayList;
import java.util.List;

/**
 * fal.ai가 서버리스로 호스팅하는 Qwen-Image-Layered(오픈소스 가중치)를 큐 REST API로 호출한다.
 * PoC poc/q6_layered_api.py와 같은 모델·파라미터. FAL_KEY가 있을 때만 활성화(벤더별 독립 mock/real 전환).
 */
@Component
@ConditionalOnExpression("!'${actset.external.fal.api-key:}'.isEmpty()")
public class FalQwenLayeredAdapter implements PosterLayerSplitAdapter {

    private static final String MODEL = "fal-ai/qwen-image-layered";
    private static final Duration MAX_WAIT = Duration.ofMinutes(10);

    private final String apiKey;
    private final ObjectMapper objectMapper;

    public FalQwenLayeredAdapter(@Value("${actset.external.fal.api-key}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BufferedImage> decompose(BufferedImage poster, int numLayers) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("image_url", ExternalHttp.pngDataUri(ExternalHttp.fit(poster, 1600)));
        body.put("num_layers", numLayers);
        body.put("output_format", "png");

        HttpResponse<String> submit = ExternalHttp.send(request("https://queue.fal.run/" + MODEL)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        if (submit.statusCode() / 100 != 2) {
            throw new IllegalStateException("fal submit " + submit.statusCode() + ": " + submit.body());
        }
        JsonNode queued = objectMapper.readTree(submit.body());
        String statusUrl = queued.path("status_url").asText();
        String responseUrl = queued.path("response_url").asText();

        long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();
        while (true) {
            Thread.sleep(3000);
            HttpResponse<String> status = ExternalHttp.send(request(statusUrl).GET().build());
            String s = objectMapper.readTree(status.body()).path("status").asText();
            if ("COMPLETED".equals(s)) break;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("fal 대기 시간 초과(" + MAX_WAIT + "): 마지막 상태 " + status.body());
            }
        }
        HttpResponse<String> result = ExternalHttp.send(request(responseUrl).GET().build());
        if (result.statusCode() / 100 != 2) {
            throw new IllegalStateException("fal result " + result.statusCode() + ": " + result.body());
        }
        List<BufferedImage> layers = new ArrayList<>();
        for (JsonNode img : objectMapper.readTree(result.body()).path("images")) {
            layers.add(ImageIO.read(new ByteArrayInputStream(ExternalHttp.getBytes(img.path("url").asText()))));
        }
        if (layers.isEmpty()) throw new IllegalStateException("fal 결과에 레이어가 없다: " + result.body());
        return layers;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json");
    }
}
