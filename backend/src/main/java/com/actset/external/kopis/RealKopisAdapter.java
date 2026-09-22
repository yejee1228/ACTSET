package com.actset.external.kopis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * KOPIS(공연예술통합전산망) 공연목록 오픈API 연동(docs/13 7-7·docs/14 1-4). **MVP 범위
 * 밖 항목이며 이 클래스의 필드명·엔드포인트는 실제 서비스키로 호출해 응답을 검증한
 * 적이 없다 — 확인필요.** 아래 파라미터·XML 태그명은 공개된 일반적인 KOPIS REST API
 * 규격을 따른 최선의 추정이며, 실제 호출 시 오류가 나면 이 클래스만 고치면 된다
 * (호출부인 PerformanceSeedService·GalleryController는 KopisAdapter 인터페이스만 안다).
 *
 * <p>출처 표기 의무가 있을 가능성이 높다(docs/14 1-4, 15) — Gallery 카드에 KOPIS 제공
 * 항목임을 구분 표시하는 것은 이 클래스가 아니라 GalleryController·프런트가 담당한다
 * (PerformanceSeed.source='kopis'로 이미 구분되어 있다).
 */
@Component
@ConditionalOnExpression("!'${actset.external.kopis.service-key:}'.isEmpty()")
public class RealKopisAdapter implements KopisAdapter {

    private static final String ENDPOINT = "http://www.kopis.or.kr/openApi/restful/pblprfr";
    private static final DateTimeFormatter KOPIS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${actset.external.kopis.service-key}")
    private String serviceKey;

    private final RestTemplate restTemplate;

    public RealKopisAdapter(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public List<KopisPerformance> fetchLatest(int count) throws Exception {
        LocalDate today = LocalDate.now();
        String url = UriComponentsBuilder.fromHttpUrl(ENDPOINT)
                .queryParam("service", serviceKey)
                .queryParam("stdate", today.format(KOPIS_DATE))
                .queryParam("eddate", today.plusDays(90).format(KOPIS_DATE))
                .queryParam("cpage", 1)
                .queryParam("rows", count)
                .toUriString();

        String xml = restTemplate.getForObject(url, String.class);
        return parse(xml, count);
    }

    private List<KopisPerformance> parse(String xml, int count) throws Exception {
        List<KopisPerformance> result = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("db");
        for (int i = 0; i < items.getLength() && result.size() < count; i++) {
            Element el = (Element) items.item(i);
            result.add(new KopisPerformance(
                    text(el, "mt20id"),
                    text(el, "prfnm"),
                    text(el, "genrenm"),
                    parseDate(text(el, "prfpdfrom")),
                    text(el, "fcltynm"),
                    text(el, "poster")
            ));
        }
        return result;
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private LocalDate parseDate(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(yyyyMmDd.replace(".", "-").substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
