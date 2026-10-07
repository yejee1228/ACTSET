package com.actset.external.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions(비전, JSON 모드) 구현. temperature는 보내지 않는다 — reasoning 계열 모델이 거부한다
 * (GptPromptWritingAdapter와 같은 이유).
 */
@Component
@ConditionalOnExpression("!'${actset.external.openai.api-key:}'.isEmpty()")
public class GptVisionAnalysisAdapter implements VisionAnalysisAdapter {

    private static final String CHECK_SYSTEM = """
            You inspect one layer produced by splitting a performance poster into layers.
            This layer is supposed to be ONLY the BACKGROUND PLATE: something that can be cropped or stretched to
            any aspect ratio without losing meaning — sky, star field, clouds, haze, gradients, textures, generic
            scenery.
            Anything else counts as a residual element: text or letter-like marks, logos, frames/borders,
            people/characters/silhouettes, animals, distinct focal objects (a big moon, planet, castle, ship,
            props), and also HOLES — transparent, black or smeared patches where an element was cut out.
            Transparent pixels are shown as a grey checkerboard.

            If residual elements exist, write ONE English edit instruction for an image EDIT model that receives
            this exact layer as input and must return the clean background plate:
            - keep the same canvas, composition, palette, lighting and rendering style
            - remove every residual element listed and repaint those areas as natural continuation of the
              surrounding background (fill holes too)
            - no text, letters, logos, frames or watermark anywhere
            Give each residual element a bounding box relative to the layer (0..1) that fully contains it
            including its glow.
            Return JSON only:
            {"clean": true|false,
             "residual_elements": [{"name": "<short English name>", "bbox": [x0,y0,x1,y1], "is_hole": false}, ...],
             "edit_instruction": "<instruction or empty when clean>", "reason": "<one sentence, Korean>"}""";

    private static final String CLASSIFY_SYSTEM = """
            You label the elements cut out of a performance poster. Image 1 is the original poster. Image 2 is a
            sheet where each cut-out element is drawn on a checkerboard with its number.
            Roles:
            - BACKDROP: full background plate (sky, texture) — at most the elements that span the whole canvas
            - SUBJECT: key visual figures/objects (performer silhouette, big moon, main illustration objects)
            - DECOR: small decorative objects (stars, sparkles, small hats, butterflies, ornaments) that are not text
            - TITLE: the main performance title lettering (one or more pieces)
            - COPY: tagline / subtitle / short promotional phrases near the title (including small English words)
            - INFO: date, time, venue, organizer, age, price, contact lines
            - MARK: company/organizer logo
            - NOISE: fragments, dust, duplicated partial copies, unusable pieces
            Also list text that is visible in the original poster but is NOT contained (fully readable) in any
            element — so we can typeset it again. Give its bbox relative to the original poster (0..1).
            Return JSON only:
            {"elements": [{"index": 1, "role": "TITLE", "label": "<short Korean description>"}, ...],
             "missing_texts": [{"text": "...", "role": "TITLE|COPY|INFO", "bbox": [x0,y0,x1,y1],
                                "color": "#RRGGBB", "bold": true}]}""";

    private static final String ANNOTATE_SYSTEM = """
            You annotate the layout of a performance promotion image (poster, banner, thumbnail...).
            Find these blocks and give each bounding box relative to the image (0..1, [x0,y0,x1,y1]):
            - HEADLINE: the main title lettering together with taglines/subtitles directly attached to it
            - KEYVISUAL: the main illustration/photo subject group (e.g. performer + moon + animals)
            - INFO: date/time/venue/organizer/age/contact text group
            - MARK: organizer/company logo
            Omit a block that is not present. Also say whether a tagline/subtitle (COPY) separate from the main
            title is present.
            Return JSON only:
            {"blocks": {"HEADLINE": [..], "KEYVISUAL": [..], "INFO": [..], "MARK": [..]},
             "has_copy": true|false, "description": "<one sentence, Korean>"}""";

    private static final String EVALUATE_SYSTEM = """
            You are a senior designer reviewing an automatic poster format conversion.
            Image 1 = our automatic result. Image 2 = a human-made reference for the same format.
            The reference contains date/venue text and hand-made adjustments that the automatic result cannot
            know about — do NOT penalise missing or different information content. Judge design quality only.
            Score 1-5 (5 = as good as reference):
            - composition: overall arrangement and balance for this aspect ratio
            - hierarchy: title prominence and reading order
            - keyvisual: key visual kept intact, not cut or distorted, good size
            - background: background fills the canvas naturally (no seams, holes, stretching, blur)
            - completeness: important elements present (title, key visual, logo) — information text excluded
            - legibility: text readable at this size
            - overall
            Return JSON only, Korean text:
            {"scores": {"composition":n,"hierarchy":n,"keyvisual":n,"background":n,"completeness":n,"legibility":n,"overall":n},
             "matches": ["..."], "differences": ["..."], "defects": ["..."], "summary": "<2 sentences>"}""";

    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public GptVisionAnalysisAdapter(@Value("${actset.external.openai.api-key}") String apiKey,
                                    @Value("${actset.external.openai.model:gpt-4o-mini}") String model,
                                    ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public BackdropCheck checkBackdrop(BufferedImage backdrop) throws Exception {
        JsonNode j = chatJson(CHECK_SYSTEM, "Inspect this layer.", List.of(backdrop));
        List<Residual> residual = new ArrayList<>();
        j.path("residual_elements").forEach(n -> residual.add(
                new Residual(n.path("name").asText(), box(n.path("bbox")), n.path("is_hole").asBoolean(false))));
        return new BackdropCheck(j.path("clean").asBoolean(false), residual,
                j.path("edit_instruction").asText(""), j.path("reason").asText(""));
    }

    @Override
    public Classification classifyElements(BufferedImage poster, BufferedImage sheet, int count, String elementSummary)
            throws Exception {
        JsonNode j = chatJson(CLASSIFY_SYSTEM, "There are " + count + " numbered elements.\n" + elementSummary,
                List.of(poster, sheet));
        List<ElementLabel> labels = new ArrayList<>();
        for (JsonNode n : j.path("elements")) {
            labels.add(new ElementLabel(n.path("index").asInt(), n.path("role").asText("NOISE"), n.path("label").asText("")));
        }
        List<MissingText> missing = new ArrayList<>();
        for (JsonNode n : j.path("missing_texts")) {
            missing.add(new MissingText(n.path("text").asText(), n.path("role").asText("INFO"), box(n.path("bbox")),
                    n.path("color").asText("#FFFFFF"), n.path("bold").asBoolean(false)));
        }
        return new Classification(labels, missing);
    }

    @Override
    public LayoutNotes annotateLayout(BufferedImage image) throws Exception {
        JsonNode j = chatJson(ANNOTATE_SYSTEM, "Image size: " + image.getWidth() + "x" + image.getHeight(), List.of(image));
        Map<String, double[]> blocks = new LinkedHashMap<>();
        j.path("blocks").fields().forEachRemaining(e -> {
            double[] b = box(e.getValue());
            if (b != null) blocks.put(e.getKey().toUpperCase(), b);
        });
        return new LayoutNotes(blocks, j.path("has_copy").asBoolean(false), j.path("description").asText(""));
    }

    @Override
    public Evaluation evaluate(BufferedImage ours, BufferedImage reference, String formatLabel) throws Exception {
        JsonNode j = chatJson(EVALUATE_SYSTEM, "Format: " + formatLabel, List.of(ours, reference));
        Map<String, Integer> scores = new LinkedHashMap<>();
        j.path("scores").fields().forEachRemaining(e -> scores.put(e.getKey(), e.getValue().asInt()));
        return new Evaluation(scores, texts(j.path("matches")), texts(j.path("differences")),
                texts(j.path("defects")), j.path("summary").asText(""));
    }

    private JsonNode chatJson(String system, String text, List<BufferedImage> images) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        for (BufferedImage img : images) {
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", ExternalHttp.jpegDataUri(onChecker(img), 1280));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = ExternalHttp.send(req);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("chat/completions " + res.statusCode() + ": " + res.body());
        }
        String json = objectMapper.readTree(res.body()).path("choices").path(0).path("message").path("content").asText();
        return objectMapper.readTree(json);
    }

    /** 투명 픽셀이 JPEG에서 검정으로 뭉개지지 않게 체커보드 위에 얹어 보낸다. */
    private static BufferedImage onChecker(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        int cell = Math.max(8, Math.max(img.getWidth(), img.getHeight()) / 48);
        for (int y = 0; y < img.getHeight(); y += cell) {
            for (int x = 0; x < img.getWidth(); x += cell) {
                g.setColor(((x / cell + y / cell) % 2 == 0) ? new java.awt.Color(204, 204, 204) : java.awt.Color.WHITE);
                g.fillRect(x, y, cell, cell);
            }
        }
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    private static double[] box(JsonNode n) {
        if (!n.isArray() || n.size() != 4) return null;
        return new double[]{n.get(0).asDouble(), n.get(1).asDouble(), n.get(2).asDouble(), n.get(3).asDouble()};
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
