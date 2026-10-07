package com.actset.conversion;

import com.actset.conversion.engine.ElementSplitter;
import com.actset.conversion.layout.ElementRole;
import com.actset.external.conversion.ImageEditAdapter;
import com.actset.external.conversion.PosterLayerSplitAdapter;
import com.actset.external.conversion.VisionAnalysisAdapter;
import com.actset.external.conversion.VisionAnalysisAdapter.BackdropCheck;
import com.actset.external.conversion.VisionAnalysisAdapter.Classification;
import com.actset.external.conversion.VisionAnalysisAdapter.ElementLabel;
import com.actset.external.conversion.VisionAnalysisAdapter.MissingText;
import com.actset.external.conversion.VisionAnalysisAdapter.Residual;
import com.actset.render.FontRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 규격변환 ②~④ — 포스터 한 장을 역할이 붙은 요소들로 만든다(분해는 프로젝트당 1회 — docs/05).
 *
 * <pre>
 * ② 분해      Qwen-Image-Layered → RGBA 레이어 N장 → 원본 해상도로 복원
 * ④ 재분리    [E-B] 배경 아닌 레이어마다 알파 연결요소로 요소 파일 분리
 * ③ 배경 검증  배경 레이어를 LLM이 판정 → 다른 요소가 남았으면 LLM이 쓴 지시문으로 배경만 재생성
 *            → 원래 배경과 재생성 배경의 차이로 "배경에 박혀 있던 요소"를 원본 픽셀째 떼어 [E-B]로 분리
 *   역할 판정 LLM이 번호표 시트를 보고 요소별 역할 판정
 *   텍스트 보정 원본에 있는데 요소로 못 뗀 텍스트는 LLM이 읽은 문구를 자체 렌더링(Java2D)
 * </pre>
 */
@Service
public class ElementExtractionService {

    /** 임시값 — PoC Q6에서 4장보다 6장이 피사체를 더 잘 떼어냈다(poc/out/Q6_*_report.txt). */
    public static final int NUM_LAYERS = 6;
    /** 임시값 — 불투명 90% 이상이면서 색 편차가 이보다 작으면 단색판(Qwen이 내놓는 검정판)으로 버린다. */
    static final double UNIFORM_STDDEV = 6.0;
    /** 임시값 — 배경 차이 추출: 이 거리 아래는 같은 배경, 위쪽 값 이상이면 확실한 잔여 요소(그 사이는 반투명). */
    static final int RESIDUAL_DIFF_LOW = 28, RESIDUAL_DIFF_HIGH = 72;
    /** 임시값 — 배경 잔여물 bbox 합이 캔버스의 이 비율 미만이면 재생성하지 않고 자체 엔진으로 메운다(4차 실행: 0.04% 얼룩에 재생성 1회). */
    static final double SMALL_RESIDUAL_AREA = 0.02;
    /** 역할 판정 시트에 올리는 최대 요소 수. 나머지(작은 조각)는 NOISE로 둔다. */
    static final int MAX_CLASSIFY = 48;

    public record Extracted(String key, ElementRole role, String origin, String label, BufferedImage image,
                            Rectangle bounds, int z, ObjectNode meta) {
    }

    public record Result(int width, int height, List<Extracted> elements, ObjectNode log) {
    }

    /** 단계별 중간 산출물을 리포트용으로 받아 적는 곳. 운영에서는 NOOP. */
    public interface DebugSink {
        DebugSink NOOP = new DebugSink() {
        };

        default void image(String name, BufferedImage image) {
        }

        default void text(String name, String content) {
        }
    }

    private final PosterLayerSplitAdapter layerSplit;
    private final VisionAnalysisAdapter vision;
    private final ImageEditAdapter imageEdit;
    private final FontRegistry fonts;
    private final ObjectMapper objectMapper;
    private final ElementSplitter splitter = new ElementSplitter();

    public ElementExtractionService(PosterLayerSplitAdapter layerSplit, VisionAnalysisAdapter vision,
                                    ImageEditAdapter imageEdit, FontRegistry fonts, ObjectMapper objectMapper) {
        this.layerSplit = layerSplit;
        this.vision = vision;
        this.imageEdit = imageEdit;
        this.fonts = fonts;
        this.objectMapper = objectMapper;
    }

    public Result extract(BufferedImage poster, DebugSink debug) throws Exception {
        return extract(poster, debug, null);
    }

    /** @param preDecomposed null이 아니면 분해 API를 부르지 않고 이 레이어를 쓴다(테스트 재실행 시 과금 방지). */
    public Result extract(BufferedImage poster, DebugSink debug, List<BufferedImage> preDecomposed) throws Exception {
        BufferedImage original = ImageOps.toArgb(poster);
        int w = original.getWidth(), h = original.getHeight();
        ObjectNode log = objectMapper.createObjectNode();
        List<Extracted> elements = new ArrayList<>();

        // ② 분해 ------------------------------------------------------------
        long t0 = System.currentTimeMillis();
        List<BufferedImage> raw = preDecomposed != null ? preDecomposed : layerSplit.decompose(original, NUM_LAYERS);
        log.put("decompose_reused", preDecomposed != null);
        log.put("decompose_ms", System.currentTimeMillis() - t0);
        log.put("decompose_layers", raw.size());
        log.put("decompose_resolution", raw.get(0).getWidth() + "x" + raw.get(0).getHeight());
        ArrayNode layerLog = log.putArray("layers");
        List<BufferedImage> restored = ImageOps.restoreResolution(raw, original);
        int backdropIdx = -1;
        double bestOpaque = 0;
        for (int i = 0; i < raw.size(); i++) {
            debug.image("02_layer" + i + "_raw", raw.get(i));
            BufferedImage r = restored.get(i);
            double opaque = ImageOps.opaqueRatio(r), sd = ImageOps.colorStdDev(r);
            boolean uniform = opaque > 0.9 && sd < UNIFORM_STDDEV;
            layerLog.addObject().put("index", i).put("opaque", round(opaque)).put("color_stddev", round(sd))
                    .put("uniform_discarded", uniform);
            if (!uniform && opaque >= 0.5 && opaque > bestOpaque) {
                bestOpaque = opaque;
                backdropIdx = i;
            }
        }
        debug.image("02_layers_sheet", ImageOps.numberedSheet(raw, 360, Math.min(raw.size(), 6)));
        log.put("backdrop_layer", backdropIdx);

        // ④ 재분리(배경 아닌 레이어) ------------------------------------------------
        List<Extracted> candidates = new ArrayList<>();
        for (int i = 0; i < restored.size(); i++) {
            if (i == backdropIdx || layerLog.get(i).path("uniform_discarded").asBoolean()) continue;
            if (ImageOps.isDiffuseOverlay(restored.get(i))) {
                // 넓게 퍼진 반투명 레이어(빛줄기 등)는 쪼개지 않고 한 장으로 둔다
                Rectangle b = ImageOps.alphaBounds(restored.get(i));
                ((ObjectNode) layerLog.get(i)).put("diffuse_overlay", true);
                candidates.add(new Extracted("L" + i + "_overlay", ElementRole.NOISE, "decomposed", null,
                        restored.get(i).getSubimage(b.x, b.y, b.width, b.height), b, (i + 1) * 100, meta("layer", i)));
                continue;
            }
            List<ElementSplitter.Element> parts = splitter.split(restored.get(i));
            ((ObjectNode) layerLog.get(i)).put("split_elements", parts.size());
            for (int k = 0; k < parts.size(); k++) {
                ElementSplitter.Element p = parts.get(k);
                candidates.add(new Extracted("L" + i + "_e" + (k + 1), ElementRole.NOISE, parts.size() == 1 ? "decomposed" : "split",
                        null, p.image(), p.bounds(), (i + 1) * 100 + k, meta("layer", i)));
            }
        }

        // ③ 배경 검증 → 필요 시 재생성 ---------------------------------------------
        BufferedImage backdropRaw = backdropIdx >= 0 ? restored.get(backdropIdx) : original;
        if (backdropIdx < 0) log.put("backdrop_note", "배경 레이어 후보 없음 — 원본 전체를 배경 후보로 검증");
        debug.image("03_backdrop_raw", ImageOps.onChecker(backdropRaw));
        double holes = 1 - ImageOps.opaqueRatio(backdropRaw);
        BufferedImage filled = ImageOps.fillHoles(backdropRaw);
        debug.image("03_backdrop_filled", filled);

        t0 = System.currentTimeMillis();
        BackdropCheck check = vision.checkBackdrop(backdropRaw);
        ObjectNode checkLog = log.putObject("backdrop_check");
        checkLog.put("ms", System.currentTimeMillis() - t0);
        checkLog.put("clean", check.clean());
        checkLog.put("holes_ratio", round(holes));
        checkLog.put("reason", check.reason());
        checkLog.put("edit_instruction", check.editInstruction());
        ArrayNode resLog = checkLog.putArray("residual_elements");
        check.residualElements().forEach(r -> resLog.addObject().put("name", r.name()).put("is_hole", r.isHole())
                .set("bbox", objectMapper.valueToTree(r.bbox())));

        BufferedImage backdrop;
        String backdropOrigin;
        double residualArea = check.residualElements().stream().filter(r -> r.bbox() != null)
                .mapToDouble(r -> Math.max(0, r.bbox()[2] - r.bbox()[0]) * Math.max(0, r.bbox()[3] - r.bbox()[1])).sum();
        boolean anyHole = check.residualElements().stream().anyMatch(Residual::isHole) || holes >= 0.01;
        checkLog.put("residual_area_ratio", round(residualArea));
        if (check.clean() || check.editInstruction().isBlank()) {
            backdrop = filled;
            backdropOrigin = "decomposed";
        } else if (!anyHole && residualArea < SMALL_RESIDUAL_AREA) {
            // 작은 얼룩만 남았으면 이미지 생성 대신 자체 엔진으로 그 자리만 주변 색으로 메운다(과금·시간 절약)
            backdrop = ImageOps.fillHoles(punch(backdropRaw, check.residualElements()));
            backdropOrigin = "decomposed";
            checkLog.put("local_fill", true);
            debug.image("03_backdrop_local_fill", backdrop);
        } else {
            t0 = System.currentTimeMillis();
            BufferedImage regenerated = imageEdit.edit(filled, check.editInstruction(), false);
            checkLog.put("regenerate_ms", System.currentTimeMillis() - t0);
            checkLog.put("regenerated_size", regenerated.getWidth() + "x" + regenerated.getHeight());
            backdrop = ImageOps.resize(regenerated, w, h);
            backdropOrigin = "regenerated";
            debug.image("03_backdrop_regenerated", backdrop);

            // 다른 레이어가 이미 가진 픽셀(제목·달 등)은 잔여 요소에서 뺀다 — 같은 요소가 두 번 쌓이는 잔상 방지
            float[] covered = new float[w * h];
            for (int i = 0; i < restored.size(); i++) {
                if (i == backdropIdx) continue;
                int[] px = restored.get(i).getRGB(0, 0, w, h, null, 0, w);
                for (int p = 0; p < px.length; p++) covered[p] = Math.max(covered[p], (px[p] >>> 24) / 255f);
            }
            BufferedImage residual = residualLayer(backdropRaw, filled, backdrop, covered, check.residualElements(), checkLog);
            debug.image("03_backdrop_residual", ImageOps.onChecker(residual));
            List<ElementSplitter.Element> parts = splitter.split(residual);
            for (int k = 0; k < parts.size(); k++) {
                ElementSplitter.Element p = parts.get(k);
                candidates.add(new Extracted("B_e" + (k + 1), ElementRole.NOISE, "backdrop_residual", null,
                        p.image(), p.bounds(), 50 + k, meta("layer", backdropIdx)));
            }
            checkLog.put("residual_split_elements", parts.size());
        }
        elements.add(new Extracted("BACKDROP", ElementRole.BACKDROP, backdropOrigin, "배경판", backdrop,
                new Rectangle(0, 0, w, h), 0, meta("layer", backdropIdx)));

        // 역할 판정 ----------------------------------------------------------------
        candidates.sort(Comparator.comparingInt((Extracted e) -> e.bounds().width * e.bounds().height).reversed());
        List<Extracted> toClassify = candidates.subList(0, Math.min(MAX_CLASSIFY, candidates.size()));
        List<BufferedImage> tiles = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < toClassify.size(); i++) {
            Extracted e = toClassify.get(i);
            tiles.add(e.image());
            summary.append(String.format("#%d bbox(rel)=[%.2f,%.2f,%.2f,%.2f] source=%s%n", i + 1,
                    e.bounds().x / (double) w, e.bounds().y / (double) h, e.bounds().getMaxX() / w, e.bounds().getMaxY() / h,
                    e.origin()));
        }
        BufferedImage sheet = ImageOps.numberedSheet(tiles, 220, 6);
        debug.image("04_classify_sheet", sheet);
        t0 = System.currentTimeMillis();
        Classification cls = vision.classifyElements(original, sheet, toClassify.size(), summary.toString());
        log.put("classify_ms", System.currentTimeMillis() - t0);
        ArrayNode labelLog = log.putArray("elements");
        for (int i = 0; i < candidates.size(); i++) {
            Extracted e = candidates.get(i);
            ElementRole role = ElementRole.NOISE;
            String label = i < MAX_CLASSIFY ? "판정 누락" : "작은 조각(판정 제외)";
            for (ElementLabel l : cls.labels()) {
                if (l.index() == i + 1) {
                    role = ElementRole.parse(l.role());
                    label = l.label();
                }
            }
            Extracted labeled = new Extracted(e.key(), role, e.origin(), label, e.image(), e.bounds(), e.z(), e.meta());
            elements.add(labeled);
            labelLog.addObject().put("no", i + 1).put("key", e.key()).put("role", role.name()).put("label", label)
                    .put("origin", e.origin()).put("bbox", e.bounds().x + "," + e.bounds().y + "," + e.bounds().width + "x" + e.bounds().height);
        }

        // 텍스트 보정 — 못 뗀 텍스트는 자체 렌더링 --------------------------------------
        ArrayNode textLog = log.putArray("rendered_texts");
        int k = 0;
        for (MissingText t : cls.missingTexts()) {
            if (t.bbox() == null || t.text().isBlank()) continue;
            Extracted rendered = renderText(t, w, h, 900 + k, "T" + (++k));
            if (rendered == null) continue;
            elements.add(rendered);
            textLog.addObject().put("key", rendered.key()).put("text", t.text()).put("role", rendered.role().name())
                    .put("color", t.colorHex());
        }

        debug.image("05_reconstruction", reconstruct(w, h, elements));
        log.put("reconstruction_mae", round(meanAbsError(original, reconstruct(w, h, elements))));
        return new Result(w, h, elements, log);
    }

    /**
     * 원래 배경(분해 레이어)과 재생성 배경의 차이 = 배경에 박혀 있던 요소. LLM이 짚은 영역(bbox) 안에서만 본다.
     * 재생성 배경은 전체 색감이 조금 달라지므로, bbox 바깥 테두리 띠의 평균 차이를 빼서 색감 차이를 상쇄한다.
     */
    private BufferedImage residualLayer(BufferedImage backdropRaw, BufferedImage filled, BufferedImage regenerated,
                                        float[] coveredByOtherLayers, List<Residual> residuals, ObjectNode log) {
        int w = filled.getWidth(), h = filled.getHeight();
        int[] a = ImageOps.boxBlur(filled.getRGB(0, 0, w, h, null, 0, w), w, h, 2);
        int[] b = ImageOps.boxBlur(regenerated.getRGB(0, 0, w, h, null, 0, w), w, h, 2);
        int[] rawPx = backdropRaw.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[w * h];
        ArrayNode used = log.putArray("residual_regions");
        for (Residual r : residuals) {
            if (r.isHole() || r.bbox() == null) continue;
            int pad = (int) (0.03 * Math.max(w, h));
            int x0 = clampInt((int) (r.bbox()[0] * w) - pad, 0, w), y0 = clampInt((int) (r.bbox()[1] * h) - pad, 0, h);
            int x1 = clampInt((int) (r.bbox()[2] * w) + pad, 0, w), y1 = clampInt((int) (r.bbox()[3] * h) + pad, 0, h);
            if (x1 - x0 < 4 || y1 - y0 < 4) continue;
            // 테두리 띠(바깥 pad 폭)의 평균 차이 = 색감 오프셋
            int ring = Math.max(4, pad);
            long[] sum = new long[3];
            long n = 0;
            for (int y = Math.max(0, y0 - ring); y < Math.min(h, y1 + ring); y++) {
                for (int x = Math.max(0, x0 - ring); x < Math.min(w, x1 + ring); x++) {
                    if (x >= x0 && x < x1 && y >= y0 && y < y1) continue;
                    int i = y * w + x;
                    for (int c = 0; c < 3; c++) sum[c] += ((b[i] >> (16 - 8 * c)) & 0xff) - ((a[i] >> (16 - 8 * c)) & 0xff);
                    n++;
                }
            }
            int[] off = new int[3];
            for (int c = 0; c < 3; c++) off[c] = n == 0 ? 0 : (int) (sum[c] / n);
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int i = y * w + x;
                    if ((rawPx[i] >>> 24) < 200) continue; // 구멍(이미 다른 레이어로 떨어져 나간 자리)
                    double d2 = 0;
                    for (int c = 0; c < 3; c++) {
                        int dc = (((b[i] >> (16 - 8 * c)) & 0xff) - off[c]) - ((a[i] >> (16 - 8 * c)) & 0xff);
                        d2 += dc * dc;
                    }
                    double d = Math.sqrt(d2);
                    int alpha = (int) Math.round(255 * (1 - coveredByOtherLayers[i]) * Math.max(0, Math.min(1,
                            (d - RESIDUAL_DIFF_LOW) / (double) (RESIDUAL_DIFF_HIGH - RESIDUAL_DIFF_LOW))));
                    if (alpha > (out[i] >>> 24)) out[i] = (alpha << 24) | (rawPx[i] & 0xffffff);
                }
            }
            used.addObject().put("name", r.name()).put("rect", x0 + "," + y0 + "-" + x1 + "," + y1)
                    .put("tone_offset", off[0] + "," + off[1] + "," + off[2]);
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    /** 잔여물 bbox(약간 넓혀서)를 투명하게 뚫는다 — fillHoles가 주변 색으로 메운다. */
    private static BufferedImage punch(BufferedImage src, List<Residual> residuals) {
        BufferedImage out = ImageOps.toArgb(src);
        out = out == src ? copyOf(src) : out;
        int w = out.getWidth(), h = out.getHeight(), pad = Math.max(2, (int) (0.01 * Math.max(w, h)));
        for (Residual r : residuals) {
            if (r.bbox() == null) continue;
            int x0 = clampInt((int) (r.bbox()[0] * w) - pad, 0, w), y0 = clampInt((int) (r.bbox()[1] * h) - pad, 0, h);
            int x1 = clampInt((int) (r.bbox()[2] * w) + pad, 0, w), y1 = clampInt((int) (r.bbox()[3] * h) + pad, 0, h);
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) out.setRGB(x, y, 0);
        }
        return out;
    }

    private static BufferedImage copyOf(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    /** LLM이 읽어 준 텍스트 한 줄을 bbox 높이에 맞춰 자체 렌더링(Pretendard). 실제 서체와 다를 수 있다. */
    private Extracted renderText(MissingText t, int w, int h, int z, String key) {
        int bx = (int) (t.bbox()[0] * w), by = (int) (t.bbox()[1] * h);
        int bw = (int) ((t.bbox()[2] - t.bbox()[0]) * w), bh = (int) ((t.bbox()[3] - t.bbox()[1]) * h);
        if (bw < 4 || bh < 4) return null;
        ElementRole role = ElementRole.parse(t.role());
        if (!role.isText()) role = ElementRole.INFO;
        float size = bh * 0.85f;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        Font font;
        FontMetrics fm;
        do {
            font = t.bold() ? fonts.bold(size) : fonts.regular(size);
            fm = pg.getFontMetrics(font);
            size -= 1f;
        } while (fm.stringWidth(t.text()) > bw * 1.05 && size > 6);
        pg.dispose();
        int tw = fm.stringWidth(t.text()) + 4, th = fm.getAscent() + fm.getDescent() + 4;
        BufferedImage img = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(parseColor(t.colorHex()));
        g.drawString(t.text(), 2, 2 + fm.getAscent());
        g.dispose();
        Rectangle bounds = new Rectangle(bx + (bw - tw) / 2, by + (bh - th) / 2, tw, th);
        ObjectNode meta = meta("text", 0);
        meta.put("text", t.text());
        return new Extracted(key, role, "rendered", "렌더링: " + t.text(), img, bounds, z, meta);
    }

    /** 요소를 원래 자리에 다시 쌓은 재구성 — 분리가 정보 손실 없이 됐는지 원본과 비교한다. */
    public static BufferedImage reconstruct(int w, int h, List<Extracted> elements) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        elements.stream().filter(e -> e.role() != ElementRole.NOISE)
                .sorted(Comparator.comparingInt(Extracted::z))
                .forEach(e -> g.drawImage(e.image(), e.bounds().x, e.bounds().y, e.bounds().width, e.bounds().height, null));
        g.dispose();
        return canvas;
    }

    static double meanAbsError(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight();
        int[] pa = a.getRGB(0, 0, w, h, null, 0, w), pb = b.getRGB(0, 0, w, h, null, 0, w);
        long s = 0;
        for (int i = 0; i < pa.length; i++) {
            for (int c = 0; c < 3; c++) s += Math.abs(((pa[i] >> (8 * c)) & 0xff) - ((pb[i] >> (8 * c)) & 0xff));
        }
        return s / (3.0 * pa.length);
    }

    private ObjectNode meta(String key, int value) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put(key, value);
        return n;
    }

    private static Color parseColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
