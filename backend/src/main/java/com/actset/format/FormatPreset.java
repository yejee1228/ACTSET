package com.actset.format;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 기본 규격 상수 13종 (docs/12). 테이블이 아니라 코드 상수로 둔다(docs/02·10).
 * 커스텀 규격은 이 enum에 없고 결과물이 format_code='CUSTOM'과 실제 width·height를
 * 직접 들고 있는다.
 */
public enum FormatPreset {

    POSTER("포스터(세로)", 1240, 1754, FormatGroup.원본),
    NOL_TICKET("NOL티켓 대표이미지", 750, 1000, FormatGroup.예매처),
    TICKETLINK("티켓링크 대표이미지", 720, 960, FormatGroup.예매처),
    PLAY_TICKET("플레이티켓 대표이미지", 400, 600, FormatGroup.예매처),
    SNS_1X1("SNS 1:1", 1080, 1080, FormatGroup.온라인),
    SNS_4X5("SNS 4:5", 1080, 1350, FormatGroup.온라인),
    STORY("SNS 스토리", 1080, 1920, FormatGroup.온라인),
    WEB_THUMB("홈페이지 썸네일", 940, 440, FormatGroup.온라인),
    WEB_BANNER("홈페이지 배너", 1920, 600, FormatGroup.온라인),
    DID("DID", 1080, 1920, FormatGroup.온라인),
    HALL_MONITOR("공연장 모니터", 1920, 1080, FormatGroup.온라인),
    BANNER_WIDE("현수막(가로)", 2048, 256, FormatGroup.오프라인),
    X_BANNER("X배너", 600, 1800, FormatGroup.오프라인);

    public static final String CUSTOM_CODE = "CUSTOM";

    private final String label;
    private final int width;
    private final int height;
    private final FormatGroup group;

    FormatPreset(String label, int width, int height, FormatGroup group) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.group = group;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public FormatGroup group() {
        return group;
    }

    public RatioBucket ratioBucket() {
        return RatioBucket.fromDimensions(width, height);
    }

    public static Optional<FormatPreset> byCode(String code) {
        return Arrays.stream(values()).filter(f -> f.name().equals(code)).findFirst();
    }

    public static List<FormatPreset> all() {
        return Arrays.asList(values());
    }
}
