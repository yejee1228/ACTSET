package com.actset.external.kopis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KOPIS XML 파싱 — 네트워크 없이 응답 형식만 본다(실제 키로 받은 응답과 태그가 다르면 여기와 어댑터를 함께 고친다). */
class RealKopisAdapterTest {

    static final String LIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dbs>
              <db>
                <mt20id>PF250001</mt20id>
                <prfnm>신비한 샌드매직쇼</prfnm>
                <prfpdfrom>2026.10.01</prfpdfrom>
                <prfpdto>2026.12.31</prfpdto>
                <fcltynm>대학로 소극장</fcltynm>
                <poster>http://www.kopis.or.kr/upload/pfmPoster/PF_PF250001.jpg</poster>
                <area>서울특별시</area>
                <genrenm>서커스/마술</genrenm>
                <openrun>N</openrun>
                <prfstate>공연중</prfstate>
              </db>
              <db>
                <mt20id>PF250002</mt20id>
                <prfnm>리어왕</prfnm>
                <prfpdfrom>2026.11.05</prfpdfrom>
                <prfpdto>2026.11.30</prfpdto>
                <fcltynm>국립극장</fcltynm>
                <poster> </poster>
                <genrenm>연극</genrenm>
                <openrun>Y</openrun>
                <prfstate>공연예정</prfstate>
              </db>
            </dbs>
            """;

    static final String DETAIL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dbs>
              <db>
                <mt20id>PF250001</mt20id>
                <prfnm>신비한 샌드매직쇼</prfnm>
                <prfcast>홍길동, 김철수</prfcast>
                <prfcrew> </prfcrew>
                <prfruntime>1시간 20분</prfruntime>
                <prfage>만 4세 이상</prfage>
                <entrpsnmP>반달컴퍼니</entrpsnmP>
                <entrpsnmH>반달컴퍼니</entrpsnmH>
                <pcseguidance>전석 30,000원</pcseguidance>
                <dtguidance>토요일 ~ 일요일(14:00)</dtguidance>
                <child>Y</child>
                <styurls>
                  <styurl>http://www.kopis.or.kr/upload/pfmIntroImage/PF_1.jpg</styurl>
                  <styurl>http://www.kopis.or.kr/upload/pfmIntroImage/PF_2.jpg</styurl>
                </styurls>
                <relates>
                  <relate><relatenm>NOL티켓</relatenm><relateurl>https://tickets.example/PF250001</relateurl></relate>
                  <relate><relatenm>잘못된 링크</relatenm><relateurl>javascript:alert(1)</relateurl></relate>
                </relates>
              </db>
            </dbs>
            """;

    @Test
    void parses_list_rows_with_dates_state_and_https_poster() throws Exception {
        List<KopisPerformance> rows = RealKopisAdapter.parseList(LIST);

        assertThat(rows).hasSize(2);
        KopisPerformance a = rows.get(0);
        assertThat(a.externalId()).isEqualTo("PF250001");
        assertThat(a.startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(a.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(a.posterUrl()).isEqualTo("https://kopis.or.kr/upload/pfmPoster/PF_PF250001.jpg");
        assertThat(a.state()).isEqualTo("공연중");
        assertThat(rows.get(1).posterUrl()).isNull(); // 공백만 있는 태그
        assertThat(rows.get(1).openRun()).isTrue();
    }

    @Test
    void parses_detail_images_and_only_http_ticket_links() throws Exception {
        KopisDetail d = RealKopisAdapter.parseDetail(DETAIL).orElseThrow();

        assertThat(d.cast()).isEqualTo("홍길동, 김철수");
        assertThat(d.crew()).isNull();
        assertThat(d.producer()).isEqualTo("반달컴퍼니");
        assertThat(d.childFriendly()).isTrue();
        assertThat(d.introImages()).containsExactly(
                "https://kopis.or.kr/upload/pfmIntroImage/PF_1.jpg",
                "https://kopis.or.kr/upload/pfmIntroImage/PF_2.jpg");
        assertThat(d.ticketLinks()).extracting(KopisDetail.TicketLink::name).containsExactly("NOL티켓");
    }

    @Test
    void empty_response_gives_nothing() throws Exception {
        assertThat(RealKopisAdapter.parseList("")).isEmpty();
        assertThat(RealKopisAdapter.parseDetail("<dbs></dbs>")).isEmpty();
    }

    @Test
    void rejects_doctype_to_block_external_entities() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><dbs><db><mt20id>&e;</mt20id></db></dbs>";
        assertThatThrownBy(() -> RealKopisAdapter.parseList(xxe)).isInstanceOf(Exception.class);
    }

    @Test
    void maps_kopis_genres_to_app_buttons() {
        assertThat(KopisGenres.toApp("서양음악(클래식)")).isEqualTo("클래식");
        assertThat(KopisGenres.toApp("대중무용")).isEqualTo("무용");
        assertThat(KopisGenres.toApp("한국음악(국악)")).isEqualTo("한국음악(국악)"); // 앱에 없는 장르는 원문
    }
}
