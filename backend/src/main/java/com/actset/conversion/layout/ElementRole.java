package com.actset.conversion.layout;

/**
 * 분리된 요소의 역할. docs/05 레이어 타입에 기존 포스터를 변환할 때 필요한 COPY·NOISE를 더했다.
 * COPY는 제목 주변의 홍보 문구(부제·태그라인)다 — 규격에 따라 제목과 달리 생략될 수 있어 TITLE과 나눈다.
 * FRAME은 캔버스 가장자리를 두르는 테두리다 — 비율이 바뀌면 다시 그려야 해서 MVP 규격 변환에서는 생략한다.
 */
public enum ElementRole {
    BACKDROP(0), SUBJECT(1), DECOR(2), PHOTO(3), TITLE(4), COPY(5), INFO(6), MARK(7), FRAME(8), NOISE(-1);

    /** 기본 쌓는 순서(docs/05 BACKDROP→SUBJECT→DECOR→PHOTO→TITLE→INFO→MARK). */
    public final int zBase;

    ElementRole(int zBase) {
        this.zBase = zBase;
    }

    public boolean isText() {
        return this == TITLE || this == COPY || this == INFO;
    }

    public static ElementRole parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            return NOISE;
        }
    }
}
