package com.actset.render;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.io.InputStream;

/**
 * 서버 사이드 텍스트 레이어 렌더링용 폰트 로더(docs/09 "한글 폰트").
 * 무료·상업 이용 가능(SIL OFL)한 Pretendard를 기본 서체로 번들한다 — 0-2(폰트 확정)는
 * 아직 열려 있는 E0 티켓이지만, 렌더링 엔진 이식(1-10)이 막히지 않도록 임시로 확정해
 * 사용한다. 최종 서체는 0-2 완료 후 이 클래스만 교체하면 된다.
 */
@Component
public class FontRegistry {

    private static final String REGULAR = "/fonts/Pretendard-Regular.otf";
    private static final String BOLD = "/fonts/Pretendard-Bold.otf";
    private static final String BLACK = "/fonts/Pretendard-Black.otf";

    private Font regularBase;
    private Font boldBase;
    private Font blackBase;

    @PostConstruct
    void load() {
        regularBase = loadFont(REGULAR);
        boldBase = loadFont(BOLD);
        blackBase = loadFont(BLACK);
    }

    private Font loadFont(String classpathResource) {
        try (InputStream in = getClass().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("폰트 리소스를 찾을 수 없습니다: " + classpathResource);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            throw new IllegalStateException("폰트 로드 실패: " + classpathResource, e);
        }
    }

    public Font regular(float sizePx) {
        return regularBase.deriveFont(sizePx);
    }

    public Font bold(float sizePx) {
        return boldBase.deriveFont(sizePx);
    }

    /** TITLE 레이어처럼 가장 굵은 강조가 필요한 곳에 쓴다. */
    public Font black(float sizePx) {
        return blackBase.deriveFont(sizePx);
    }
}
