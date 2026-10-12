package com.actset.conversion;

import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.pdf.PdfPosterReader;
import com.actset.conversion.pdf.PdfTextRoles;
import com.actset.external.conversion.ImageUpscaleAdapter;
import com.actset.render.FontRegistry;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 인쇄 해상도 요소 — 외부 확대는 가짜 어댑터(규칙 9). 사진은 보내지 않는지, 글자는 다시 쓰는지, 배경에 글자가 비치지 않는지. */
class HiResElementServiceTest {

    static final Color SKY = new Color(20, 40, 120), PHOTO = new Color(200, 150, 120);

    /** 받은 이미지를 기억하고 bicubic 대신 "선명한" 색(초록)을 칠해 돌려주는 가짜 확대 — 어디서 가져왔는지 구분하려고. */
    static final class FakeUpscaler implements ImageUpscaleAdapter {
        final AtomicReference<BufferedImage> received = new AtomicReference<>();

        public boolean live() {
            return true;
        }

        public BufferedImage upscale(BufferedImage image, double factor) {
            received.set(image);
            int w = (int) Math.round(image.getWidth() * factor), h = (int) Math.round(image.getHeight() * factor);
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = image.getRGB((int) (x / factor), (int) (y / factor));
                    out.setRGB(x, y, p == SKY.getRGB() ? 0xff00ff00 : p); // 하늘은 초록으로 = "확대 포스터에서 왔다"
                }
            }
            return out;
        }
    }

    static BufferedImage fill(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void photo_area_is_blanked_before_upscale_and_text_is_retyped() throws Exception {
        BufferedImage poster = fill(200, 200, SKY);
        Graphics2D g = poster.createGraphics();
        g.setColor(PHOTO);
        g.fillRect(50, 50, 60, 60);
        g.setColor(Color.WHITE);
        g.fillRect(20, 170, 160, 12);
        g.dispose();
        var backdrop = new LayoutPlanner.SourceElement("bg", ElementRole.BACKDROP, fill(200, 200, SKY), new Rectangle(0, 0, 200, 200), 0);
        var photo = new LayoutPlanner.SourceElement("ph", ElementRole.PHOTO, fill(60, 60, PHOTO), new Rectangle(50, 50, 60, 60), 5);
        var credit = new LayoutPlanner.SourceElement("cr", ElementRole.CREDIT, fill(160, 12, Color.WHITE), new Rectangle(20, 170, 160, 12), 6);
        FakeUpscaler up = new FakeUpscaler();

        FontRegistry fonts = new FontRegistry();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fonts, "load"); // @PostConstruct(패키지 전용)
        HiResElementService.HiRes hr = new HiResElementService(up, fonts)
                .build(poster, List.of(backdrop, photo, credit), 4, Map.of("cr", "주최 반달컴퍼니"), null);

        // 사진 자리는 외부로 보내기 전에 지워졌다(규칙 1)
        assertThat(new Color(up.received.get().getRGB(80, 80))).isNotEqualTo(PHOTO);
        assertThat(hr.method().get("ph")).contains("서버 안");
        assertThat(hr.method().get("cr")).contains("글꼴");
        assertThat(hr.images().get("cr").getWidth()).isEqualTo(640);
        // 배경판: 위에 아무것도 없던 하늘 = 확대 포스터(초록), 크레딧이 덮었던 자리 = 확대 포스터를 쓰지 않음(글자 잔상 방지)
        BufferedImage bg = hr.images().get("bg");
        assertThat(bg.getRGB(20, 20) & 0xffffff).isEqualTo(0x00ff00);
        assertThat(bg.getRGB(400, 704) & 0xffffff).isNotEqualTo(0x00ff00);
    }

    @Test
    void pdf_credit_lines_are_credit_and_dated_lines_are_info() {
        var big = run("마술사의 방", 100, 100);
        var credit = run("주최 인생마술 | 관람연령 24개월 이상 | 문의 010-3013-4643", 20, 950);
        var info = run("2026.11.16(토) 11시 · 예울마루 소극장", 24, 900);
        List<PdfPosterReader.TextRun> all = List.of(big, credit, info);
        assertThat(PdfTextRoles.roleOf(credit, all, 1000)).isEqualTo(ElementRole.CREDIT);
        assertThat(PdfTextRoles.roleOf(info, all, 1000)).isEqualTo(ElementRole.INFO);
        assertThat(PdfTextRoles.roleOf(big, all, 1000)).isEqualTo(ElementRole.TITLE);
    }

    static PdfPosterReader.TextRun run(String text, float size, int y) {
        return new PdfPosterReader.TextRun(0, text, "F" + (int) size, size, 0xffffff, Set.of(), new Rectangle(10, y, 500, (int) size), null);
    }
}
