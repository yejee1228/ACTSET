package com.actset.conversion.layout;

/**
 * 분리된 요소의 역할. docs/05 레이어 타입에 기존 포스터를 변환할 때 필요한 역할을 더했다.
 * <ul>
 *   <li>COPY: 제목 주변의 홍보 문장(부제·태그라인). 규격에 따라 제목과 달리 생략될 수 있어 TITLE과 나눈다</li>
 *   <li>KEYWORD: 제목도 부제도 아닌 꾸밈 낱말(예: MAGIC · SHADOW · LASER). 장식처럼 다룬다 — 좁은 규격에서 가장 먼저 뺀다</li>
 *   <li>INFO: 관객이 꼭 봐야 하는 공연 정보(날짜·시간·장소·가격·예매)</li>
 *   <li>CREDIT: 작은 글씨 정보(주최·주관·후원·문의·관람연령). 크기에 따라 아주 작게 줄이거나 뺀다</li>
 *   <li>FRAME: 캔버스 가장자리 테두리. 비율이 바뀌면 다시 그려야 해서 MVP 규격 변환에서는 생략한다</li>
 * </ul>
 */
public enum ElementRole {
    BACKDROP(0), SUBJECT(1), DECOR(2), PHOTO(3), TITLE(4), COPY(5), KEYWORD(5), INFO(6), CREDIT(6), MARK(7), FRAME(8),
    NOISE(-1);

    /** 기본 쌓는 순서(docs/05 BACKDROP→SUBJECT→DECOR→PHOTO→TITLE→INFO→MARK). */
    public final int zBase;

    ElementRole(int zBase) {
        this.zBase = zBase;
    }

    public boolean isText() {
        return this == TITLE || this == COPY || this == KEYWORD || this == INFO || this == CREDIT;
    }

    public static ElementRole parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            return NOISE;
        }
    }
}
