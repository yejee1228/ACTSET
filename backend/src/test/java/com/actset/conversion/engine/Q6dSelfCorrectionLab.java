package com.actset.conversion.engine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PoC Q6d — Q6c 생성 레이어를 LLM 재작성(v2) 대신 자체 보정 엔진(LayerRegistrar)으로 맞춘다. 외부 호출·과금 없음.
 *
 * <pre>
 * cd backend && mvn -q test-compile
 * java -cp "target/classes;target/test-classes" com.actset.conversion.engine.Q6dSelfCorrectionLab ..
 * </pre>
 * 인자: 저장소 루트(기본 ..). 결과: poc/out/Q6d/
 */
public class Q6dSelfCorrectionLab {

    static Path root, out;
    static BufferedImage original;
    static int W, H;
    static final StringBuilder report = new StringBuilder();

    static final String[] OBJECTS = {
            "Q6_sandmagicshow_L6_layer1.png",
            "Q6_sandmagicshow_L6_layer3_size_el03_x0_y0.png",
            "Q6_sandmagicshow_L6_layer3_size_el01_x28_y264.png",
            "Q6_sandmagicshow_L6_layer3_size_el02_x382_y342.png",
            "Q6_sandmagicshow_L6_layer3_size_el09_x458_y292.png",
            "Q6_sandmagicshow_L6_layer3_size_el10_x334_y350.png",
            "Q6_sandmagicshow_L6_layer3_size_el11_x431_y492.png",
            "Q6_sandmagicshow_L6_layer3_size_el12_x403_y536.png",
            "Q6_sandmagicshow_L6_layer5_size_el03_x0_y393.png",
            "Q6_sandmagicshow_L6_layer4_size_el02_x149_y330.png",
            "Q6_sandmagicshow_L6_layer5_size_el04_x139_y496.png",
    };
    static final String[] TEXT = {
            "Q6_sandmagicshow_L6_layer3.png",
            "Q6_sandmagicshow_L6_layer4_size_el01_x139_y263.png",
            "Q6_sandmagicshow_L6_layer4_size_el03_x64_y92.png",
            "Q6_sandmagicshow_L6_layer5.png",
    };

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : "..").toAbsolutePath().normalize();
        out = root.resolve("poc/out/Q6d");
        Files.createDirectories(out);
        original = argb(ImageIO.read(root.resolve("poc/input/sandmagicshow.jpg").toFile()));
        W = original.getWidth();
        H = original.getHeight();

        // 원본에서 분해로 떼어 낸 앞쪽 요소(오브젝트·텍스트) — 생성 레이어보다 위에 있다
        BufferedImage objects = canvas(), text = canvas();
        for (String f : OBJECTS) placeL6(objects, f);
        for (String f : TEXT) placeL6(text, f);
        float[] front = LayerRegistrar.coverage(W, H, List.of(objects, text));

        Path q6c = root.resolve("poc/out/Q6c");
        BufferedImage bdV1 = load(q6c, "backdrop_v1.png"), clouds = load(q6c, "clouds.png"),
                curvesV1 = load(q6c, "curves_v1.png"), moon = load(q6c, "moon.png");
        BufferedImage bdV2 = load(q6c, "backdrop.png"), curvesV2 = load(q6c, "curves.png");
        BufferedImage moonV2 = load(q6c, "moon_registered.png");

        line("[Q6d] 자체 보정 엔진(LayerRegistrar) — Q6c v1 생성 레이어를 LLM 재작성 없이 보정");
        line("입력: poc/out/Q6c (backdrop_v1·clouds·curves_v1·moon), 원본 " + W + "x" + H + ", 외부 호출 없음");
        line("");

        long t0 = System.currentTimeMillis();
        // 위쪽 레이어부터 보정한다 — 아래 레이어의 "가려짐"에 위 레이어의 보정 결과를 쓴다
        line("1) moon");
        float[] occMoon = front;
        long t = System.currentTimeMillis();
        LayerRegistrar.Registered reg = LayerRegistrar.register(moon, original, occMoon);
        line("  register  " + reg.placement() + "  (" + (System.currentTimeMillis() - t) + "ms)");
        LayerRegistrar.TrimResult moonT = LayerRegistrar.trim(reg.layer(), original, occMoon);
        line("  trim      제거 " + moonT.removedPx() + "px, 가려진 자리 유지 " + moonT.keptHiddenPx() + "px");
        LayerRegistrar.ToneResult moonC = LayerRegistrar.toneMatchThin(moonT.layer(), original, visibleAlpha(moonT.layer(), occMoon));
        line("  tone      " + tone(moonC));
        BufferedImage moonS = moonC.layer();

        line("2) curves");
        float[] occCurves = LayerRegistrar.coverage(W, H, List.of(objects, text, moonS));
        t = System.currentTimeMillis();
        reg = LayerRegistrar.register(curvesV1, original, occCurves);
        line("  register  " + (reg.placement() == null ? "건너뜀(화면 대부분을 덮는 레이어)" : reg.placement()));
        LayerRegistrar.TrimResult curT = LayerRegistrar.trim(reg.layer(), original, occCurves);
        line("  trim      제거 " + curT.removedPx() + "px, 가려진 자리 유지 " + curT.keptHiddenPx() + "px  (" + (System.currentTimeMillis() - t) + "ms)");
        LayerRegistrar.ToneResult curC = LayerRegistrar.toneMatchThin(curT.layer(), original, visibleAlpha(curT.layer(), occCurves));
        line("  tone      " + tone(curC));
        BufferedImage curvesS = curC.layer();

        line("3) clouds");
        float[] occClouds = LayerRegistrar.coverage(W, H, List.of(objects, text, moonS, curvesS));
        LayerRegistrar.TrimResult cloT = LayerRegistrar.trim(clouds, original, occClouds);
        line("  trim      제거 " + cloT.removedPx() + "px, 가려진 자리 유지 " + cloT.keptHiddenPx() + "px");
        LayerRegistrar.ToneResult cloC = LayerRegistrar.toneMatch(cloT.layer(), original, visibleAlpha(cloT.layer(), occClouds));
        line("  tone      " + tone(cloC));
        BufferedImage cloudsS = cloC.layer();

        line("4) backdrop");
        float[] occBd = LayerRegistrar.coverage(W, H, List.of(objects, text, moonS, curvesS, cloudsS));
        float[] skyVisible = invert(occBd);
        LayerRegistrar.ToneResult bdC = LayerRegistrar.toneMatchThin(bdV1, original, skyVisible);
        line("  tone      " + tone(bdC));
        t = System.currentTimeMillis();
        BufferedImage bdS = LayerRegistrar.blendOriginal(bdC.layer(), original, occBd,
                LayerRegistrar.BLEND_DILATE, LayerRegistrar.BLEND_FEATHER);
        line("  blend     보이는 하늘은 원본 픽셀, 덮였던 자리만 생성 배경 (" + (System.currentTimeMillis() - t) + "ms)");
        line("  보정 합계 " + (System.currentTimeMillis() - t0) + "ms");
        line("");

        // B안: 화면 전체 요소(구름·곡선)는 규격변환에서 배경판과 함께 움직인다(LayoutPlanner FULL_CANVAS) — 따로 둘 이유가 없다.
        // 배경판 하나로 합쳐 보이는 자리는 원본, 오브젝트·텍스트·달에 덮였던 자리만 생성 레이어로 채운다
        line("5) B안 — 배경판 통합(배경+구름+곡선)");
        t = System.currentTimeMillis();
        float[] occPlate = LayerRegistrar.coverage(W, H, List.of(objects, text, moonS));
        BufferedImage generatedPlate = stack(bdC.layer(), cloudsS, curvesS);
        BufferedImage plate = LayerRegistrar.blendOriginal(generatedPlate, original, occPlate,
                LayerRegistrar.BLEND_DILATE, LayerRegistrar.BLEND_FEATHER);
        line("  blend     보이는 자리는 원본, 덮였던 자리만 생성 (" + (System.currentTimeMillis() - t) + "ms)");
        line("");

        BufferedImage v1 = stack(bdV1, clouds, curvesV1, moon, objects, text);
        BufferedImage v2 = stack(bdV2, clouds, curvesV2, moonV2, objects, text);
        BufferedImage self = stack(bdS, cloudsS, curvesS, moonS, objects, text);
        BufferedImage selfB = stack(plate, moonS, objects, text);
        BufferedImage selfBBg = stack(plate, moonS);
        BufferedImage v1Bg = stack(bdV1, clouds, curvesV1, moon), v2Bg = stack(bdV2, clouds, curvesV2, moonV2),
                selfBg = stack(bdS, cloudsS, curvesS, moonS);

        // 점수: 원본과의 평균 색 차이(낮을수록 원본에 가깝다). 배경 영역 = 앞쪽 오브젝트·텍스트가 없는 자리
        float[] bgArea = invert(front);
        line("점수(원본 대비 평균 색 차이, 0~255, 낮을수록 좋음)");
        line(String.format("  %-28s %8s %8s %8s %8s", "", "전체", "배경영역", "하늘판", "좌상단"));
        float[] topLeft = region(0, 0, W / 2, H / 4);
        for (Object[] row : new Object[][]{{"v1 생성 그대로", v1}, {"v2 LLM 재작성+정합(Q6c)", v2}, {"v1 + 자체 보정(Q6d A안)", self}, {"v1 + 배경판 통합(Q6d B안)", selfB}}) {
            BufferedImage img = (BufferedImage) row[1];
            line(String.format("  %-28s %8.1f %8.1f %8.1f %8.1f", row[0], LayerRegistrar.score(img, original, null),
                    LayerRegistrar.score(img, original, bgArea), LayerRegistrar.score(img, original, skyVisible),
                    LayerRegistrar.score(img, original, and(topLeft, bgArea))));
        }
        line("  합격 기준(임시값) 배경영역 ≤ " + LayerRegistrar.PASS_MAE);
        line("  v2는 지시문 재작성 1회 + 이미지 2장 재생성(과금). Q6d는 과금 없음");

        write("recomposed_self.png", self);
        write("background_self.png", selfBg);
        write("moon_self.png", onChecker(moonS));
        write("curves_self.png", onChecker(curvesS));
        write("curves_removed.png", onChecker(removed(curvesV1, curT.layer())));
        write("clouds_self.png", onChecker(cloudsS));
        write("backdrop_self.png", bdS);
        write("recomposed_plate.png", selfB);
        write("plate.png", plate);
        write("sheet_compare.png", sheet(List.of(original, v1, v2, self, selfB),
                List.of("원본", "v1 생성 그대로", "v2 LLM 재작성", "A안 자체 보정", "B안 배경판 통합")));
        // 오브젝트를 걷어 낸 배경 — 규격변환에서 오브젝트가 움직이면 드러나는 자리의 품질
        write("sheet_background.png", sheet(List.of(original, v1Bg, v2Bg, selfBg, selfBBg),
                List.of("원본", "v1 배경", "v2 배경", "A안 배경", "B안 배경판")));
        int zw = W / 2, zh = H / 3;
        write("sheet_topleft.png", sheet(List.of(crop(original, zw, zh), crop(v1, zw, zh), crop(v2, zw, zh), crop(self, zw, zh), crop(selfB, zw, zh)),
                List.of("원본", "v1", "v2", "A안", "B안")));
        write("sheet_layers.png", sheet(List.of(onChecker(curvesV1), onChecker(curvesS), onChecker(moon), onChecker(moonS)),
                List.of("곡선 v1", "곡선 보정", "달 v1", "달 보정")));
        Files.writeString(out.resolve("Q6d_report.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
    }

    // ------------------------------------------------------------------ helpers

    static float[] visibleAlpha(BufferedImage layer, float[] occ) {
        int[] px = layer.getRGB(0, 0, W, H, null, 0, W);
        float[] wt = new float[W * H];
        for (int i = 0; i < wt.length; i++) wt[i] = (px[i] >>> 24) / 255f * (1 - occ[i]);
        return wt;
    }

    static float[] invert(float[] a) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) r[i] = 1 - a[i];
        return r;
    }

    static float[] and(float[] a, float[] b) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * b[i];
        return r;
    }

    static float[] region(int x0, int y0, int x1, int y1) {
        float[] r = new float[W * H];
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) r[y * W + x] = 1;
        return r;
    }

    static BufferedImage removed(BufferedImage before, BufferedImage after) {
        int[] a = before.getRGB(0, 0, W, H, null, 0, W), b = after.getRGB(0, 0, W, H, null, 0, W);
        int[] o = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            int da = (a[i] >>> 24) - (b[i] >>> 24);
            if (da > 0) o[i] = (da << 24) | 0xff2020;
        }
        BufferedImage img = canvas();
        img.setRGB(0, 0, W, H, o, 0, W);
        return img;
    }

    static String tone(LayerRegistrar.ToneResult r) {
        return String.format("gain=[%.2f,%.2f,%.2f] offset=[%.0f,%.0f,%.0f] 표본 %d px",
                r.gain()[0], r.gain()[1], r.gain()[2], r.offset()[0], r.offset()[1], r.offset()[2], r.samples());
    }

    static BufferedImage stack(BufferedImage... layers) {
        BufferedImage c = canvas();
        Graphics2D g = c.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        for (BufferedImage l : layers) g.drawImage(l, 0, 0, null);
        g.dispose();
        return c;
    }

    /** PoC place_from_l6와 같다 — L6 분해는 544x736, 파일명 _x{n}_y{n}이 그 좌표. */
    static void placeL6(BufferedImage c, String f) throws Exception {
        BufferedImage img = ImageIO.read(root.resolve("poc/out").resolve(f).toFile());
        double sx = W / 544.0, sy = H / 736.0;
        int x = 0, y = 0;
        int ix = f.lastIndexOf("_x");
        if (ix > 0 && f.contains("_y")) {
            String tail = f.substring(ix + 2, f.lastIndexOf('.'));
            x = Integer.parseInt(tail.substring(0, tail.indexOf("_y")));
            y = Integer.parseInt(tail.substring(tail.indexOf("_y") + 2));
        }
        Graphics2D g = c.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, (int) Math.round(x * sx), (int) Math.round(y * sy),
                (int) Math.round(img.getWidth() * sx), (int) Math.round(img.getHeight() * sy), null);
        g.dispose();
    }

    static BufferedImage load(Path dir, String name) throws Exception {
        BufferedImage img = argb(ImageIO.read(dir.resolve(name).toFile()));
        if (img.getWidth() == W && img.getHeight() == H) return img;
        BufferedImage r = canvas();
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, W, H, null);
        g.dispose();
        return r;
    }

    static BufferedImage argb(BufferedImage src) {
        BufferedImage r = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = r.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return r;
    }

    static BufferedImage canvas() {
        return new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    }

    static BufferedImage crop(BufferedImage img, int w, int h) {
        return img.getSubimage(0, 0, w, h);
    }

    static BufferedImage onChecker(BufferedImage img) {
        BufferedImage c = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        for (int y = 0; y < img.getHeight(); y += 32) {
            for (int x = 0; x < img.getWidth(); x += 32) {
                g.setColor(((x / 32 + y / 32) % 2 == 0) ? new Color(200, 200, 200) : Color.WHITE);
                g.fillRect(x, y, 32, 32);
            }
        }
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return c;
    }

    static BufferedImage sheet(List<BufferedImage> tiles, List<String> labels) {
        int th = 720, tw = (int) Math.round(tiles.get(0).getWidth() * th / (double) tiles.get(0).getHeight()), cap = 40;
        BufferedImage s = new BufferedImage((tw + 10) * tiles.size(), th + cap, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = s.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, s.getWidth(), s.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Malgun Gothic", Font.BOLD, 22));
        for (int i = 0; i < tiles.size(); i++) {
            g.drawImage(tiles.get(i), i * (tw + 10), cap, tw, th, null);
            g.setColor(Color.DARK_GRAY);
            g.drawString(labels.get(i), i * (tw + 10) + 8, 28);
        }
        g.dispose();
        return s;
    }

    static void write(String name, BufferedImage img) throws Exception {
        ImageIO.write(img, "png", out.resolve(name).toFile());
    }

    static void line(String s) {
        report.append(s).append('\n');
    }

}
