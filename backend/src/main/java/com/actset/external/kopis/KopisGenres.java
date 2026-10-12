package com.actset.external.kopis;

import java.util.Map;

/**
 * KOPIS 장르명(genrenm) → 앱 장르 버튼 값(프런트 GENRES: 클래식·무용·연극·뮤지컬·어린이공연·인디밴드·대중음악).
 * 앱에 없는 장르(국악·복합·서커스/마술)는 KOPIS 원문을 그대로 둔다 — "전체"에서만 보인다.
 * 어린이공연은 장르가 아니라 상세 API의 child=Y 표시로 거른다(PerformanceSeed.kidFriendly).
 */
public final class KopisGenres {

    public static final String KIDS = "어린이공연";

    private static final Map<String, String> TO_APP = Map.of(
            "연극", "연극",
            "뮤지컬", "뮤지컬",
            "서양음악(클래식)", "클래식",
            "무용(서양/한국무용)", "무용",
            "대중무용", "무용",
            "무용", "무용",
            "대중음악", "대중음악");

    private KopisGenres() {
    }

    public static String toApp(String kopisGenre) {
        if (kopisGenre == null) return null;
        return TO_APP.getOrDefault(kopisGenre.trim(), kopisGenre.trim());
    }
}
