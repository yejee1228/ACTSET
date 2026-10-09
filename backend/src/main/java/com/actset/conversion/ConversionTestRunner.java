package com.actset.conversion;

import com.actset.conversion.layout.FormatClassRule;
import com.actset.conversion.layout.LayoutBlock;
import com.actset.conversion.layout.LayoutLearner;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.layout.TempLayoutPriors;
import com.actset.domain.Account;
import com.actset.domain.Project;
import com.actset.external.conversion.VisionAnalysisAdapter;
import com.actset.format.FormatClass;
import com.actset.format.FormatPreset;
import com.actset.repository.AccountRepository;
import com.actset.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 규격변환 파이프라인 실API 테스트 실행기(2026-10-07 지시서 TASK-FORMAT-CONVERSION.md). 운영 코드가 아니다.
 *
 * <pre>
 * java -jar actset.jar --spring.profiles.active=convert-cli \
 *   --input="poc/input/magician's room.jpg" --name=magicians_room --out=poc/out/format-conversion \
 *   [--refs=poc/input/test] [--reuse]
 * </pre>
 * --reuse: DB에 저장된 요소를 그대로 쓰고 분해·LLM을 다시 부르지 않는다(과금 없음).
 * --refs: 기준 이미지 폴더(파일명 = 5분류 한글명). 주면 (B) 기준 이미지 학습 규칙 렌더 + 기준 비교 평가를 한다.
 */
@Component
@Profile("convert-cli")
public class ConversionTestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConversionTestRunner.class);
    private static final String TEST_EMAIL = "convert-test@actset.local";

    private final ElementExtractionService extraction;
    private final DesignElementStore store;
    private final FormatRenderService renderer;
    private final VisionAnalysisAdapter vision;
    private final AccountRepository accounts;
    private final ProjectRepository projects;
    private final ObjectMapper objectMapper;

    public ConversionTestRunner(ElementExtractionService extraction, DesignElementStore store, FormatRenderService renderer,
                                VisionAnalysisAdapter vision, AccountRepository accounts, ProjectRepository projects,
                                ObjectMapper objectMapper) {
        this.extraction = extraction;
        this.store = store;
        this.renderer = renderer;
        this.vision = vision;
        this.accounts = accounts;
        this.projects = projects;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String input = arg(args, "input");
        String name = arg(args, "name");
        Path out = Path.of(arg(args, "out")).resolve(name);
        Files.createDirectories(out.resolve("debug"));
        boolean reuse = args.containsOption("reuse");
        String refs = args.containsOption("refs") ? arg(args, "refs") : null;

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("input", input);
        BufferedImage poster = ImageIO.read(new File(input));
        Account account = testAccount();
        Project project = testProject(account, name);
        summary.put("account_id", account.getId().toString());
        summary.put("project_id", project.getId().toString());
        summary.put("element_folder", DesignElementStore.projectFolder(account.getId(), project.getId()) + "/elements/");

        // ②~⑤ 요소 추출·저장 ---------------------------------------------------
        if (!reuse || store.load(project.getId()).isEmpty()) {
            long t0 = System.currentTimeMillis();
            List<BufferedImage> cached = args.containsOption("reuse-layers") ? cachedLayers(out.resolve("debug")) : null;
            summary.put("decompose_reused", cached != null);
            ElementExtractionService.Result result = extraction.extract(poster, new FileDebugSink(out.resolve("debug")), cached);
            summary.put("extract_ms", System.currentTimeMillis() - t0);
            summary.set("extraction", result.log());
            int saved = store.replaceAll(account.getId(), project.getId(), result).size();
            summary.put("saved_elements", saved);
            write(out.resolve("extraction.json"), result.log());
        } else {
            summary.put("extraction", "reused (DB)");
        }
        List<LayoutPlanner.SourceElement> elements = store.load(project.getId());
        summary.put("layout_elements", elements.size());
        int sw = poster.getWidth(), sh = poster.getHeight();

        // ⑥⑦ (A) 임시 사전값 규칙 -------------------------------------------------
        Map<FormatClass, FormatClassRule> priorRules = new EnumMap<>(FormatClass.class);
        for (FormatClass c : FormatClass.values()) priorRules.put(c, TempLayoutPriors.forClass(c));
        Map<FormatClass, FormatRenderService.Rendered> setA = renderAll(account, project, sw, sh, elements, priorRules,
                out, "A", summary.putObject("render_A_prior"));

        if (refs == null) {
            write(out.resolve("summary.json"), summary);
            return;
        }

        // (B) 기준 이미지 학습 규칙 + 기준 비교 ----------------------------------------
        Map<FormatClass, BufferedImage> refImages = loadRefs(Path.of(refs));
        Map<FormatClass, VisionAnalysisAdapter.LayoutNotes> refNotes = annotateRefs(refImages, Path.of(arg(args, "out")));
        List<LayoutLearner.LayoutAnnotation> samples = new ArrayList<>();
        for (Map.Entry<FormatClass, VisionAnalysisAdapter.LayoutNotes> e : refNotes.entrySet()) {
            Map<LayoutBlock, double[]> blocks = new EnumMap<>(LayoutBlock.class);
            e.getValue().blocks().forEach((k, v) -> {
                try {
                    blocks.put(LayoutBlock.valueOf(k), v);
                } catch (IllegalArgumentException ignored) {
                    // LLM이 정의 밖 블록 이름을 낸 경우 무시
                }
            });
            BufferedImage img = refImages.get(e.getKey());
            samples.add(new LayoutLearner.LayoutAnnotation("poc/input/test/" + e.getKey().label(), e.getKey(),
                    img.getWidth(), img.getHeight(), blocks, e.getValue().hasCopy()));
        }
        Map<FormatClass, FormatClassRule> learned = new LayoutLearner().learn(samples);
        write(out.resolve("rules_learned_from_refs.json"), objectMapper.valueToTree(learned));
        Map<FormatClass, FormatRenderService.Rendered> setB = renderAll(account, project, sw, sh, elements, learned,
                out, "B", summary.putObject("render_B_learned_from_refs"));

        ObjectNode compare = summary.putObject("compare");
        for (FormatClass c : FormatClass.values()) {
            BufferedImage ref = refImages.get(c);
            if (ref == null) continue;
            ObjectNode node = compare.putObject(c.name());
            node.set("iou_A", iou(setA.get(c).plan(), refNotes.get(c)));
            node.set("iou_B", iou(setB.get(c).plan(), refNotes.get(c)));
            VisionAnalysisAdapter.Evaluation ev = vision.evaluate(setA.get(c).image(), ExternalFit.fit(ref, 1600), c.label());
            node.set("llm_eval_A", objectMapper.valueToTree(ev));
            ImageIO.write(sideBySide(c, setA.get(c).image(), setB.get(c).image(), ref), "png",
                    out.resolve("compare_" + c.name() + ".png").toFile());
            log.info("비교 완료: {} {}", c, ev.scores());
        }
        write(out.resolve("summary.json"), summary);
    }

    /** 이전 실행이 남긴 분해 레이어(02_layer{i}_raw.png). 없으면 null → 분해 API 호출. */
    private List<BufferedImage> cachedLayers(Path debugDir) throws Exception {
        List<BufferedImage> layers = new ArrayList<>();
        for (int i = 0; ; i++) {
            File f = debugDir.resolve("02_layer" + i + "_raw.png").toFile();
            if (!f.exists()) break;
            layers.add(ImageIO.read(f));
        }
        return layers.isEmpty() ? null : layers;
    }

    private Map<FormatClass, FormatRenderService.Rendered> renderAll(Account account, Project project, int sw, int sh,
                                                                    List<LayoutPlanner.SourceElement> elements,
                                                                    Map<FormatClass, FormatClassRule> rules, Path out,
                                                                    String tag, ObjectNode logNode) throws Exception {
        Map<FormatClass, FormatRenderService.Rendered> results = new EnumMap<>(FormatClass.class);
        for (FormatClass c : FormatClass.values()) {
            FormatPreset preset = c.representativePreset();
            long t0 = System.currentTimeMillis();
            FormatRenderService.Rendered r = renderer.render(sw, sh, elements, rules.get(c), preset.width(), preset.height());
            long ms = System.currentTimeMillis() - t0;
            var asset = renderer.save(account.getId(), project.getId(), preset.name(), r);
            Path file = out.resolve(tag + "_" + c.name() + "_" + preset.width() + "x" + preset.height() + ".png");
            ImageIO.write(r.image(), "png", file.toFile());
            ObjectNode n = logNode.putObject(c.name());
            n.put("format", preset.name() + " " + preset.width() + "x" + preset.height());
            n.put("rule_source", r.plan().rule().source());
            n.put("render_ms", ms);
            n.put("file", file.getFileName().toString());
            n.put("db_asset_id", asset.getId().toString());
            n.put("storage_path", asset.getImageUrl());
            ArrayNode dropped = n.putArray("dropped");
            r.plan().dropped().forEach(dropped::add);
            ObjectNode blocks = n.putObject("blocks");
            r.plan().blockRects().forEach((b, rect) -> blocks.set(b.name(), rel(rect, preset.width(), preset.height())));
            results.put(c, r);
        }
        return results;
    }

    private Map<FormatClass, VisionAnalysisAdapter.LayoutNotes> annotateRefs(Map<FormatClass, BufferedImage> refImages,
                                                                           Path outRoot) throws Exception {
        Path cache = outRoot.resolve("ref_annotations.json");
        Map<FormatClass, VisionAnalysisAdapter.LayoutNotes> notes = new EnumMap<>(FormatClass.class);
        if (Files.exists(cache)) {
            JsonNode j = objectMapper.readTree(cache.toFile());
            j.fields().forEachRemaining(e -> notes.put(FormatClass.valueOf(e.getKey()),
                    objectMapper.convertValue(e.getValue(), VisionAnalysisAdapter.LayoutNotes.class)));
            return notes;
        }
        for (Map.Entry<FormatClass, BufferedImage> e : refImages.entrySet()) {
            notes.put(e.getKey(), vision.annotateLayout(ExternalFit.fit(e.getValue(), 1600)));
            log.info("기준 이미지 주석: {}", e.getKey());
        }
        write(cache, objectMapper.valueToTree(notes));
        return notes;
    }

    private Map<FormatClass, BufferedImage> loadRefs(Path dir) throws Exception {
        Map<FormatClass, BufferedImage> map = new EnumMap<>(FormatClass.class);
        ImageIO.setUseCache(false);
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String base = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                for (FormatClass c : FormatClass.values()) {
                    if (c.label().equals(base)) map.put(c, ExternalFit.fit(ImageIO.read(p.toFile()), 2400));
                }
            }
        }
        return map;
    }

    /** 블록별 IoU — 우리 배치(블록 사각형) vs 기준 이미지 주석. 한쪽에만 있는 블록은 presence 불일치로 표시. */
    private ObjectNode iou(LayoutPlanner.Plan plan, VisionAnalysisAdapter.LayoutNotes ref) {
        ObjectNode n = objectMapper.createObjectNode();
        for (LayoutBlock b : LayoutBlock.values()) {
            Rectangle2D ours = plan.blockRects().get(b);
            double[] theirs = ref.blocks().get(b.name());
            if (ours == null && theirs == null) continue;
            if (ours == null || theirs == null) {
                n.put(b.name(), ours == null ? "기준에만 있음" : "우리에만 있음");
                continue;
            }
            double[] o = {ours.getX() / plan.width(), ours.getY() / plan.height(), ours.getMaxX() / plan.width(), ours.getMaxY() / plan.height()};
            double ix = Math.max(0, Math.min(o[2], theirs[2]) - Math.max(o[0], theirs[0]));
            double iy = Math.max(0, Math.min(o[3], theirs[3]) - Math.max(o[1], theirs[1]));
            double inter = ix * iy;
            double union = (o[2] - o[0]) * (o[3] - o[1]) + (theirs[2] - theirs[0]) * (theirs[3] - theirs[1]) - inter;
            n.put(b.name(), Math.round(inter / union * 100) / 100.0);
        }
        return n;
    }

    /** 결과 A | 결과 B | 기준 — 같은 높이로 나란히. 긴 가로는 세로로 쌓는다. */
    private BufferedImage sideBySide(FormatClass c, BufferedImage a, BufferedImage b, BufferedImage ref) {
        boolean stack = c == FormatClass.LONG_LANDSCAPE || c == FormatClass.LANDSCAPE;
        int label = 36;
        List<BufferedImage> imgs = List.of(a, b, ref);
        List<String> names = List.of("A 임시 사전값(본 결과)", "B 기준 학습(누설·참고)", "기준(사용자 제작)");
        int unit = stack ? 1600 : 900;
        List<BufferedImage> scaled = new ArrayList<>();
        for (BufferedImage img : imgs) {
            double s = stack ? unit / (double) img.getWidth() : unit / (double) img.getHeight();
            scaled.add(ImageOps.resize(img, (int) (img.getWidth() * s), (int) (img.getHeight() * s)));
        }
        int W = stack ? unit : scaled.stream().mapToInt(BufferedImage::getWidth).sum() + 20 * 2;
        int H = stack ? scaled.stream().mapToInt(i -> i.getHeight() + label).sum() + 20 * 2 : unit + label;
        BufferedImage sheet = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setFont(new Font("Malgun Gothic", Font.BOLD, 22));
        int x = 0, y = 0;
        for (int i = 0; i < 3; i++) {
            BufferedImage s = scaled.get(i);
            g.setColor(Color.BLACK);
            g.drawString(names.get(i), x + 6, y + 26);
            g.drawImage(s, x, y + label, null);
            if (stack) y += s.getHeight() + label + 20;
            else x += s.getWidth() + 20;
        }
        g.dispose();
        return sheet;
    }

    private Account testAccount() {
        return accounts.findByEmail(TEST_EMAIL).orElseGet(() -> {
            Account a = new Account();
            a.setEmail(TEST_EMAIL);
            a.setDisplayName("규격변환 테스트");
            return accounts.save(a);
        });
    }

    private Project testProject(Account account, String name) {
        String title = "[규격변환 테스트] " + name;
        return projects.findAll().stream()
                .filter(p -> p.getOwnerId().equals(account.getId()) && title.equals(p.getMainTitle()))
                .findFirst()
                .orElseGet(() -> {
                    Project p = new Project();
                    p.setOwnerId(account.getId());
                    p.setStatus("active");
                    p.setMainTitle(title);
                    p.setPerformanceInfo(objectMapper.createObjectNode());
                    return projects.save(p);
                });
    }

    private ObjectNode rel(Rectangle2D r, int w, int h) {
        ObjectNode n = objectMapper.createObjectNode();
        n.putArray("rel").add(round(r.getX() / w)).add(round(r.getY() / h)).add(round(r.getMaxX() / w)).add(round(r.getMaxY() / h));
        return n;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private void write(Path p, JsonNode node) throws Exception {
        Files.writeString(p, objectMapper.writeValueAsString(node), StandardCharsets.UTF_8);
    }

    private static String arg(ApplicationArguments args, String key) {
        List<String> v = args.getOptionValues(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("--" + key + " 필요");
        return v.get(0);
    }

    /** 중간 산출물을 out/debug/ 에 PNG로. */
    private record FileDebugSink(Path dir) implements ElementExtractionService.DebugSink {
        @Override
        public void image(String name, BufferedImage image) {
            try {
                ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void text(String name, String content) {
            try {
                Files.writeString(dir.resolve(name + ".txt"), content, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** ExternalHttp.fit을 이 패키지에서 짧게 쓰기 위한 별칭. */
    private static final class ExternalFit {
        static BufferedImage fit(BufferedImage img, int maxSide) {
            return com.actset.external.conversion.ExternalHttp.fit(img, maxSide);
        }
    }
}
