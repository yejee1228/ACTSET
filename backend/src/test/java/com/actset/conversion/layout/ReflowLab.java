package com.actset.conversion.layout;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** 제목 다시 흘리기 확인용(외부 호출 없음). 인자: 요소 목록 파일(role|x|y|w|h|z|path) 저장소 루트 출력폴더 */
public class ReflowLab {
    public static void main(String[] a) throws Exception {
        List<LayoutPlanner.SourceElement> head = new ArrayList<>();
        int i = 0;
        for (String line : Files.readAllLines(Path.of(a[0]))) {
            String[] f = line.trim().split(Pattern.quote("|"));
            if (f.length < 7) continue;
            ElementRole role = ElementRole.parse(f[0]);
            if (role != ElementRole.TITLE && role != ElementRole.COPY && role != ElementRole.KEYWORD
                    && !(role == ElementRole.DECOR && Integer.parseInt(f[4]) < 200)) continue;
            head.add(new LayoutPlanner.SourceElement("e" + (i++), role, ImageIO.read(Path.of(a[1], f[6]).toFile()),
                    new Rectangle(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])),
                    Integer.parseInt(f[5])));
        }
        List<Rectangle> words = HeadlineReflow.words(head.stream().filter(e -> e.role() == ElementRole.TITLE).toList());
        System.out.println("words " + words);
        for (HeadlineReflow.Mode m : HeadlineReflow.Mode.values()) {
            List<LayoutPlanner.SourceElement> r = HeadlineReflow.apply(head, m);
            if (r == null) {
                System.out.println(m + " null");
                continue;
            }
            Rectangle u = LayoutPlanner.union(r);
            BufferedImage img = new BufferedImage(u.width + 20, u.height + 20, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(20, 30, 90));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            r.stream().sorted(Comparator.comparingInt(LayoutPlanner.SourceElement::sourceZ))
                    .forEach(e -> g.drawImage(e.image(), e.bounds().x - u.x + 10, e.bounds().y - u.y + 10, null));
            g.dispose();
            ImageIO.write(img, "png", Path.of(a[2], "reflow_" + m + ".png").toFile());
            System.out.println(m + " " + r.size() + " elements, box " + u.width + "x" + u.height);
        }
    }
}
