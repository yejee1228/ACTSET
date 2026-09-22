package com.actset.external.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * OpenAI Chat Completions(GPT)로 Ideogram에 보낼 긍정 프롬프트를 다듬는다(docs/14 GPT의
 * 두 번째 용도 — 첫 번째는 소개문/시놉시스 보조지만 그건 아직 미착수). 사용자가 ②에
 * 적은 자유서술은 짧고 모호한 경우가 많아, GPT가 장르·팔레트 맥락을 더해 이미지 생성
 * 모델이 더 잘 알아듣는 구체적인 문장으로 바꿔준다.
 *
 * <p>구조화 필드(cast·venue·price 등)는 {@link PromptWritingRequest} 타입 자체에 없어
 * GPT에도 전달되지 않는다(CLAUDE.md 규칙 1과 동일한 "타입으로 차단"). 실패하면 예외를
 * 던질 뿐 여기서 재시도하지 않는다 — 폴백은 호출부(DraftPromptBuilder)의 책임이다.
 */
@Component
@ConditionalOnExpression("!'${actset.external.openai.api-key:}'.isEmpty()")
public class GptPromptWritingAdapter implements PromptWritingAdapter {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            You write short, vivid image-generation prompts for a live performance poster's
            background visual (no people's faces, no readable text/typography/watermark —
            those are composed separately). Given a genre, a free-form direction note, and
            an optional color palette, output ONLY the finished prompt text itself — no
            preamble, no quotes, no markdown, no explanation. Write in the same language as
            the direction note when one is given; otherwise Korean. Keep it to 1-3 sentences.
            """;

    @Value("${actset.external.openai.api-key}")
    private String apiKey;

    @Value("${actset.external.openai.model:gpt-4o-mini}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GptPromptWritingAdapter(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String write(PromptWritingRequest request) throws Exception {
        // temperature를 일부러 안 보낸다 — reasoning 계열 최신 모델(예: gpt-5.6-luna)은
        // 기본값(1) 외의 커스텀 temperature를 거부한다(400 unsupported_value로 실제 확인함).
        // 생략하면 모델별 기본값을 쓰므로 구모델·신모델 양쪽에서 다 동작한다.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", buildUserMessage(request));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String response = restTemplate.postForObject(ENDPOINT, new HttpEntity<>(body.toString(), headers), String.class);
        JsonNode content = objectMapper.readTree(response).path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("GPT 응답에 프롬프트 내용이 없습니다: " + response);
        }
        return content.asText().trim();
    }

    private String buildUserMessage(PromptWritingRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.genre() != null && !request.genre().isBlank()) {
            sb.append("장르: ").append(request.genre()).append("\n");
        }
        if (request.imageDirectionNote() != null && !request.imageDirectionNote().isBlank()) {
            sb.append("원하는 이미지 방향: ").append(request.imageDirectionNote()).append("\n");
        }
        if (!request.referencePalette().isEmpty()) {
            sb.append("참고 색감: ").append(String.join(", ", request.referencePalette())).append("\n");
        }
        if ("regenerate".equals(request.mode())) {
            sb.append("이전과는 다른 새로운 해석으로 써주세요.\n");
        }
        if (sb.isEmpty()) {
            sb.append("공연 포스터 배경에 어울리는 분위기 있는 키비주얼을 자유롭게 제안해주세요.");
        }
        return sb.toString();
    }
}
