package com.actset.render;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * PerformanceInfo(JSONB) → TextBlockSpec 목록 변환.
 * 순서·크기 비율은 GenreRule.info_priority_order가 학습되기 전까지의 임시 고정 순서다
 * (docs/12 "값은 학습 후 확정" — CLAUDE.md 규칙 5에 따라 임시값임을 명시).
 */
@Component
public class PerformanceInfoTextMapper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy. M. d");

    public String title(JsonNode info) {
        return text(info, "main_title", "");
    }

    public List<TextBlockSpec> infoBlocks(JsonNode info) {
        List<TextBlockSpec> blocks = new ArrayList<>();

        String subtitle = text(info, "subtitle", null);
        if (subtitle != null) {
            blocks.add(new TextBlockSpec("subtitle", subtitle, 0.42f, false, 0.55f));
        }

        blocks.add(new TextBlockSpec("date", dateSummary(info), 0.34f, true, 0.08f));
        blocks.add(new TextBlockSpec("venue", venueSummary(info), 0.34f, false, 0.45f));

        String runningTime = text(info, "running_time", null);
        if (runningTime != null) {
            blocks.add(new TextBlockSpec("running", "러닝타임 " + runningTime, 0.26f, false, 0.10f));
        }

        String cast = castSummary(info);
        if (cast != null) {
            blocks.add(new TextBlockSpec("cast", cast, 0.26f, false, 0.10f));
        }

        String price = priceSummary(info);
        if (price != null) {
            blocks.add(new TextBlockSpec("price", price, 0.26f, false, 0.10f));
        }

        String age = text(info, "age", null);
        if (age != null) {
            blocks.add(new TextBlockSpec("age", age, 0.26f, false, 0.10f));
        }

        String organizer = organizerSummary(info);
        if (organizer != null) {
            blocks.add(new TextBlockSpec("organizer", organizer, 0.26f, false, 0.08f));
        }

        String inquiry = inquirySummary(info);
        if (inquiry != null) {
            blocks.add(new TextBlockSpec("inquiry", inquiry, 0.26f, false, 0.35f));
        }

        // mandatory_notices는 규격별 생략 대상에서 제외되는 필수문구다(docs/05 우선순위 2번) —
        // 항상 목록 마지막에 포함해 다른 정보보다 먼저 잘리지 않게 한다.
        String notice = noticeSummary(info);
        if (notice != null) {
            blocks.add(new TextBlockSpec("notice", notice, 0.20f, false, 0.10f));
        }

        return blocks;
    }

    private String dateSummary(JsonNode info) {
        JsonNode sessions = info.path("sessions");
        if (!sessions.isArray() || sessions.isEmpty()) {
            return "일정 추후 공지";
        }
        JsonNode first = sessions.get(0);
        if (first.path("is_undetermined").asBoolean(false)) {
            return "일정 추후 공지";
        }
        String dateStr = first.path("date").asText(null);
        String timeStr = first.path("time").asText(null);
        if (dateStr == null) {
            return "일정 추후 공지";
        }
        try {
            LocalDate date = LocalDate.parse(dateStr);
            String weekday = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
            String base = date.format(DATE_FMT) + " (" + weekday + ")";
            return timeStr != null ? base + " " + timeStr : base;
        } catch (Exception e) {
            return dateStr;
        }
    }

    private String venueSummary(JsonNode info) {
        JsonNode venue = info.path("venue");
        if (venue.path("is_undetermined").asBoolean(false)) {
            return "장소 추후 공지";
        }
        String name = venue.path("name").asText(null);
        return (name == null || name.isBlank()) ? "장소 추후 공지" : name;
    }

    private String castSummary(JsonNode info) {
        JsonNode cast = info.path("cast");
        if (!cast.isArray() || cast.isEmpty()) {
            return null;
        }
        return StreamSupport.stream(cast.spliterator(), false)
                .map(c -> {
                    String name = c.path("name").asText("");
                    String part = c.path("part").asText(null);
                    return part != null && !part.isBlank() ? name + "(" + part + ")" : name;
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("  "));
    }

    private String priceSummary(JsonNode info) {
        JsonNode items = info.path("price_items");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        return StreamSupport.stream(items.spliterator(), false)
                .map(p -> {
                    String label = p.path("label").asText("");
                    long price = p.path("price").asLong(0);
                    return label + " " + String.format(Locale.KOREA, "%,d원", price);
                })
                .collect(Collectors.joining("  "));
    }

    private String organizerSummary(JsonNode info) {
        JsonNode org = info.path("organizer_group");
        String presenter = joinArray(org.path("presenter"));
        String organizer = joinArray(org.path("organizer"));
        List<String> parts = new ArrayList<>();
        if (presenter != null) parts.add("주최 " + presenter);
        if (organizer != null) parts.add("주관 " + organizer);
        return parts.isEmpty() ? null : String.join("   ", parts);
    }

    private String inquirySummary(JsonNode info) {
        JsonNode inquiry = info.path("inquiry");
        String phone = inquiry.path("전화").asText(inquiry.path("phone").asText(null));
        return phone != null ? "문의 " + phone : null;
    }

    private String noticeSummary(JsonNode info) {
        JsonNode notices = info.path("mandatory_notices");
        if (!notices.isArray() || notices.isEmpty()) {
            return null;
        }
        return StreamSupport.stream(notices.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.joining(" "));
    }

    private String joinArray(JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) return null;
        String joined = StreamSupport.stream(arr.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.joining(", "));
        return joined.isBlank() ? null : joined;
    }

    private String text(JsonNode info, String field, String fallback) {
        String v = info.path(field).asText(null);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
