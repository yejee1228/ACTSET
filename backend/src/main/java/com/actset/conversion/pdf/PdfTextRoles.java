package com.actset.conversion.pdf;

import com.actset.conversion.layout.ElementRole;

import java.util.List;
import java.util.regex.Pattern;

/**
 * PDF 텍스트 줄의 역할(TITLE·COPY·INFO)을 글자 크기·폰트·위치·문구 모양으로 정한다 — LLM 없이.
 *
 * <p>임시 규칙(CLAUDE.md 규칙 5 — 학습 산출물 아님):
 * <ul>
 *   <li>가장 큰 글자 크기의 60% 이상이거나, 가장 큰 글자와 같은 폰트이면서 25% 이상 → TITLE(제목은 큰 한 글자 "방"과 작은 윗줄이 섞이기도 한다) (그림자 사본도 같은 크기라 같이 TITLE)</li>
 *   <li>날짜·시간·전화·가격·구분선(|) 같은 정보 문구이거나, 최대 크기의 35% 이하이면서 페이지 아래 25% → INFO</li>
 *   <li>나머지 → COPY</li>
 * </ul>
 */
public final class PdfTextRoles {

    private static final Pattern INFO_LIKE = Pattern.compile(
            "(\\d{1,4}[./-]\\d{1,2}([./-]\\d{1,2})?)|(\\d{2,4}-\\d{3,4}-\\d{4})|(\\d{1,2}\\s*(시|:\\d{2}))|(\\d[\\d,]*\\s*원)|\\||(문의|주최|주관|후원|관람|예매|장소|일시)");

    private PdfTextRoles() {
    }

    public static ElementRole roleOf(PdfPosterReader.TextRun run, List<PdfPosterReader.TextRun> all, int pageHeight) {
        float max = 0;
        String maxFont = null;
        for (PdfPosterReader.TextRun r : all) {
            if (r.sizePx() > max) {
                max = r.sizePx();
                maxFont = r.fontName();
            }
        }
        float s = run.sizePx();
        if (s >= 0.6f * max || (run.fontName().equals(maxFont) && s >= 0.25f * max)) return ElementRole.TITLE;
        boolean bottom = run.bounds().getCenterY() > 0.75 * pageHeight;
        if (INFO_LIKE.matcher(run.text()).find() || (s <= 0.35f * max && bottom)) return ElementRole.INFO;
        return ElementRole.COPY;
    }
}
