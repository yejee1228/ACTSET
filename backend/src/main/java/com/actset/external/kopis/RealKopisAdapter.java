package com.actset.external.kopis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * KOPIS(공연예술통합전산망) 오픈API 연동(docs/13 7-7·docs/14 1-4).
 *
 * <pre>
 * 목록 GET /openApi/restful/pblprfr?service=키&amp;stdate=yyyyMMdd&amp;eddate=yyyyMMdd&amp;cpage=1&amp;rows=100
 * 상세 GET /openApi/restful/pblprfr/{mt20id}?service=키
 * </pre>
 * 응답은 XML(dbs/db). 태그명은 공개 문서·예제 기준이며 2026-10-11 현재 실제 키로 검증 전 — 확인필요.
 * 서비스키는 쿼리 파라미터라 URL을 로그에 남기지 않는다(P-2).
 */
@Component
@ConditionalOnExpression("!'${actset.external.kopis.service-key:}'.isEmpty()")
public class RealKopisAdapter implements KopisAdapter {

    private static final String BASE = "https://www.kopis.or.kr/openApi/restful/pblprfr";
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
    public boolean live() {
        return true;
    }

    @Override
    public List<KopisPerformance> fetchPage(LocalDate from, LocalDate to, int page, int rows) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(BASE)
                .queryParam("service", serviceKey)
                .queryParam("stdate", from.format(KOPIS_DATE))
                .queryParam("eddate", to.format(KOPIS_DATE))
                .queryParam("cpage", page)
                .queryParam("rows", rows)
                .toUriString();
        return parseList(get(url));
    }

    @Override
    public Optional<KopisDetail> fetchDetail(String externalId) throws Exception {
        String url = UriComponentsBuilder.fromHttpUrl(BASE).pathSegment(externalId)
                .queryParam("service", serviceKey)
                .toUriString();
        return parseDetail(get(url));
    }

    /** 차단 응답은 KopisBlockedException으로 바꾼다. 예외 메시지에 URL(서비스키 포함)이 들어가지 않게 응답 코드만 남긴다. */
    private String get(String url) {
        try {
            return restTemplate.getForObject(url, String.class);
        } catch (HttpStatusCodeException e) {
            if (e.getResponseBodyAsString().contains("Request Blocked")) throw new KopisBlockedException(null);
            throw new IllegalStateException("KOPIS 응답 " + e.getStatusCode().value());
        }
    }

    // ------------------------------------------------------------------ 파싱(테스트에서 직접 부른다)

    static List<KopisPerformance> parseList(String xml) throws Exception {
        List<KopisPerformance> result = new ArrayList<>();
        Document doc = parse(xml);
        if (doc == null) return result;
        NodeList items = doc.getElementsByTagName("db");
        for (int i = 0; i < items.getLength(); i++) {
            Element el = (Element) items.item(i);
            String id = text(el, "mt20id"), title = text(el, "prfnm");
            if (id == null || title == null) continue;
            result.add(new KopisPerformance(id, title, text(el, "genrenm"),
                    parseDate(text(el, "prfpdfrom")), parseDate(text(el, "prfpdto")), text(el, "fcltynm"),
                    https(text(el, "poster")), text(el, "area"), text(el, "prfstate"), "Y".equalsIgnoreCase(text(el, "openrun"))));
        }
        return result;
    }

    static Optional<KopisDetail> parseDetail(String xml) throws Exception {
        Document doc = parse(xml);
        if (doc == null) return Optional.empty();
        NodeList items = doc.getElementsByTagName("db");
        if (items.getLength() == 0) return Optional.empty();
        Element el = (Element) items.item(0);
        List<String> images = new ArrayList<>();
        NodeList sty = el.getElementsByTagName("styurl");
        for (int i = 0; i < sty.getLength(); i++) {
            String u = https(blankToNull(sty.item(i).getTextContent()));
            if (u != null) images.add(u);
        }
        List<KopisDetail.TicketLink> links = new ArrayList<>();
        NodeList relates = el.getElementsByTagName("relate");
        for (int i = 0; i < relates.getLength(); i++) {
            Element r = (Element) relates.item(i);
            String u = text(r, "relateurl");
            if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                links.add(new KopisDetail.TicketLink(text(r, "relatenm"), u));
            }
        }
        String producer = text(el, "entrpsnmP");
        if (producer == null) producer = text(el, "entrpsnm");
        return Optional.of(new KopisDetail(text(el, "mt20id"), text(el, "prfcast"), text(el, "prfcrew"),
                text(el, "prfruntime"), text(el, "prfage"), producer, text(el, "entrpsnmH"), text(el, "entrpsnmS"),
                text(el, "pcseguidance"), text(el, "dtguidance"), text(el, "sty"), "Y".equalsIgnoreCase(text(el, "child")),
                images, links));
    }

    /** 외부 XML이라 DTD·외부 엔티티를 끈다(XXE 방지). 빈 응답은 null. */
    private static Document parse(String xml) throws Exception {
        if (xml == null || xml.isBlank()) return null;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** 바로 아래 자식 태그의 글자(공백만 있으면 null). 하위 요소의 같은 이름 태그와 섞이지 않게 직계만 본다. */
    private static String text(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return blankToNull(e.getTextContent());
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** KOPIS 날짜 "2026.10.11" → LocalDate. */
    static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10).replace('.', '-'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 포스터·소개 이미지는 http://www.kopis.or.kr/로 온다 — 배포 사이트가 https이면 섞인 콘텐츠로 막히므로 https로 바꾼다.
     * https://www.kopis.or.kr/는 https://kopis.or.kr/로 301 되돌림이 걸려 있어(2026-10-11 확인) 바로 그 주소를 쓴다.
     */
    static String https(String url) {
        if (url == null) return null;
        for (String prefix : new String[]{"http://www.kopis.or.kr/", "https://www.kopis.or.kr/", "http://kopis.or.kr/"}) {
            if (url.startsWith(prefix)) return "https://kopis.or.kr/" + url.substring(prefix.length());
        }
        return url;
    }
}
