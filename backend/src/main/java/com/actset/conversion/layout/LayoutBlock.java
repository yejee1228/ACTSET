package com.actset.conversion.layout;

/**
 * 배치 단위. 요소를 하나씩 따로 배치하지 않고 역할 덩어리로 묶어, 덩어리 안의 배치는 원본 그대로 두고
 * 덩어리 전체를 규격별 영역에 맞춘다(제목 줄 간격·키비주얼 안의 인물-달 관계가 깨지지 않게).
 */
public enum LayoutBlock {
    /** TITLE(+COPY·KEYWORD, 규칙·후보가 허용할 때) + 그 근처 DECOR */
    HEADLINE,
    /** SUBJECT + 그 근처 DECOR + PHOTO */
    KEYVISUAL,
    /** INFO(날짜·시간·장소·가격) — 공간이 부족하면 생략 */
    INFO,
    /** CREDIT(주최·주관·문의·관람연령) — 작은 글씨 줄. 읽을 수 있는 크기가 안 되면 생략 */
    CREDIT,
    /** 로고 */
    MARK
}
