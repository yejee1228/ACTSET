package com.actset.conversion.layout;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 제목 다시 흘리기 — 합성 제목(윗줄 낱말 둘, 아랫줄 낱말 + 두 줄에 걸친 큰 글자)으로 낱말 나누기·줄 순서·재배치를 본다. */
class HeadlineReflowTest {

    /**
     * 원본 좌표계 500x260 제목. 윗줄 [A A A A]  [B B B], 아랫줄 [C C C C] 바로 옆에 큰 글자 D(두 줄 높이, 윗줄 오른쪽까지).
     * 글자 사이 2px, 낱말 사이 12px, C와 D 사이 6px(마술사의 방처럼 낱말 사이가 좁다).
     */
    static List<LayoutPlanner.SourceElement> title() {
        BufferedImage img = new BufferedImage(500, 260, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.YELLOW);
        int x = 10;
        for (int i = 0; i < 4; i++, x += 30 + 2) g.fillRect(x, 10, 30, 50);   // 낱말 A: x 10..136
        x += 12 - 2;
        for (int i = 0; i < 3; i++, x += 30 + 2) g.fillRect(x, 10, 30, 50);   // 낱말 B: x 146..238 (D 앞에서 끝남)
        x = 10;
        for (int i = 0; i < 4; i++, x += 60 + 2) g.fillRect(x, 80, 60, 90);   // 낱말 C(아랫줄, 큰 글씨)
        g.fillRect(x - 2 + 6, 5, 130, 250);                                   // 큰 글자 D(두 줄 높이)
        g.dispose();
        return List.of(new LayoutPlanner.SourceElement("t", ElementRole.TITLE, img, new Rectangle(100, 200, 500, 260), 10));
    }

    @Test
    void splits_words_by_gap_and_height_and_orders_lines() {
        List<Rectangle> words = HeadlineReflow.words(title());

        assertThat(words).hasSize(4);
        // 읽는 순서: 윗줄 A, B → 아랫줄 C → 큰 글자 D(아랫줄에 가깝다)
        assertThat(words.get(0).x).isLessThan(words.get(1).x);
        assertThat(words.get(0).y).isEqualTo(words.get(1).y);
        assertThat(words.get(2).height).isEqualTo(90);
        assertThat(words.get(3).height).isEqualTo(250);
    }

    @Test
    void stack_puts_one_word_per_line_and_keeps_pixels() {
        List<LayoutPlanner.SourceElement> head = new ArrayList<>(title());
        List<LayoutPlanner.SourceElement> stacked = HeadlineReflow.apply(head, HeadlineReflow.Mode.STACK);

        assertThat(stacked).hasSize(4);
        Rectangle u = LayoutPlanner.union(stacked);
        assertThat(u.height).isGreaterThan(50 + 50 + 90 + 250); // 네 줄 높이 합 이상(줄 간격 포함)
        assertThat(u.width).isLessThan(300);                   // 가장 넓은 낱말 폭 정도
        long ink = stacked.stream().mapToLong(e -> opaque(e.image())).sum();
        assertThat(ink).isEqualTo(opaque(title().get(0).image())); // 픽셀을 옮기기만 했다(잃거나 겹치지 않음)
    }

    @Test
    void piece_ids_carry_parent_and_offset() {
        List<LayoutPlanner.SourceElement> stacked = HeadlineReflow.apply(title(), HeadlineReflow.Mode.ONE_LINE);
        String[] p = HeadlineReflow.parsePieceId(stacked.get(0).id());
        assertThat(p).isNotNull();
        assertThat(p[0]).isEqualTo("t");
        assertThat(HeadlineReflow.parsePieceId("plain-id")).isNull();
    }

    @Test
    void single_word_title_is_not_reflowed() {
        BufferedImage img = new BufferedImage(200, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.YELLOW);
        g.fillRect(0, 0, 200, 60);
        g.dispose();
        var one = List.of(new LayoutPlanner.SourceElement("t", ElementRole.TITLE, img, new Rectangle(0, 0, 200, 60), 1));
        assertThat(HeadlineReflow.apply(one, HeadlineReflow.Mode.STACK)).isNull();
    }

    static long opaque(BufferedImage img) {
        long n = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) > 0) n++;
        return n;
    }
}
