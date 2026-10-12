package com.actset.conversion;

import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.conversion.layout.TempLayoutPriors;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.format.FormatClass;
import com.actset.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑧ 인쇄 — 규격변환 결과(generated_assets.category='규격변환')를 인쇄 크기로 다시 합성한다(2026-10-12).
 *
 * <pre>
 * 1. 저장된 요소로 배치를 다시 계산한다 — 배치 엔진은 결정적이라 화면 결과와 같은 배치가 나온다.
 *    인쇄 비율이 결과 비율과 다르면(정사각 결과를 A3로) 인쇄 비율로 배치한다
 * 2. 필요한 배율만큼 고해상도 요소를 만든다(HiResElementService). 확대 포스터는 프로젝트 요소 폴더에 남겨
 *    같은 배율로 다시 인쇄할 때 외부 호출을 하지 않는다
 * 3. 배치를 인쇄 크기로 키워 합성(FormatRenderService.renderScaled)
 * </pre>
 */
@Service
public class ConversionPrintService {

    private static final Logger log = LoggerFactory.getLogger(ConversionPrintService.class);
    public static final String CATEGORY = "규격변환";
    /** 임시값 — 확대 배율 한계(SeedVR 최대 10, 원본 750px 포스터 8배 = 6000px). 넘는 몫은 bicubic. */
    static final int MAX_FACTOR = 8;
    /** 인쇄 비율이 결과 비율과 이만큼(상대) 넘게 다르면 인쇄 비율로 다시 배치한다. */
    static final double RATIO_TOLERANCE = 0.03;

    private final DesignElementStore store;
    private final FormatRenderService renderer;
    private final HiResElementService hiRes;
    private final StorageService storage;

    public ConversionPrintService(DesignElementStore store, FormatRenderService renderer, HiResElementService hiRes,
                                  StorageService storage) {
        this.store = store;
        this.renderer = renderer;
        this.hiRes = hiRes;
        this.storage = storage;
    }

    public record Printed(BufferedImage image, double upscaleFactor, boolean upscaleReused, String variant) {
    }

    public Printed render(Project project, GeneratedAsset asset, int printW, int printH) throws Exception {
        List<LayoutPlanner.SourceElement> elements = store.load(project.getId());
        if (elements.isEmpty()) throw new IllegalStateException("저장된 요소가 없어 인쇄 해상도로 다시 합성할 수 없습니다");
        int sw = elements.stream().filter(e -> e.role() == ElementRole.BACKDROP)
                .mapToInt(e -> e.bounds().x + e.bounds().width).max().orElseThrow();
        int sh = elements.stream().filter(e -> e.role() == ElementRole.BACKDROP)
                .mapToInt(e -> e.bounds().y + e.bounds().height).max().orElseThrow();

        int planW = asset.getWidth(), planH = asset.getHeight();
        double assetRatio = planW / (double) planH, printRatio = printW / (double) printH;
        if (Math.abs(printRatio / assetRatio - 1) > RATIO_TOLERANCE) planH = (int) Math.round(planW / printRatio);
        FormatClass c = FormatClass.fromDimensions(planW, planH);
        // FormatConvertJobHandler와 같은 규칙(임시 사전값 — 규칙 5)이어야 화면과 같은 배치가 나온다
        LayoutPlanner.Plan plan = new LayoutPlanner().plan(sw, sh, elements, TempLayoutPriors.forClass(c), planW, planH);
        double k = printW / (double) planW;

        double maxScale = 0;
        for (LayoutPlanner.Placement p : plan.placements()) {
            BufferedImage img = elements.stream().filter(e -> e.id().equals(p.elementId())).map(LayoutPlanner.SourceElement::image)
                    .findFirst().orElse(null);
            if (img != null) maxScale = Math.max(maxScale, p.target().getHeight() / img.getHeight());
        }
        int factor = (int) Math.max(1, Math.min(MAX_FACTOR, Math.ceil(maxScale * k)));

        Map<String, String> texts = new HashMap<>();
        for (var e : store.loadAll(project.getId())) {
            if (e.meta() != null && e.meta().hasNonNull("text")) texts.put(e.key(), e.meta().get("text").asText());
        }
        String cachePath = DesignElementStore.projectFolder(project.getOwnerId(), project.getId())
                + "/elements/hires_poster_x" + factor + ".png";
        BufferedImage cached = storage.exists(cachePath) ? read(cachePath) : null;
        HiResElementService.HiRes hr = hiRes.build(sourcePoster(project, sw, sh, elements), elements, factor, texts, cached);
        if (cached == null) storage.store(DesignElementStore.png(hr.upscaledPoster()), cachePath, "image/png");

        BufferedImage image = renderer.renderScaled(plan, k, hr.images(), factor);
        log.info("규격변환 인쇄: {}x{} 배치 {} → {}x{} (확대 {}배, 재사용 {})", planW, planH, plan.variant(), printW, printH,
                factor, cached != null);
        return new Printed(image, factor, cached != null, plan.variant());
    }

    /** 업로드 포스터 원본. 없으면(시안 경로 등) 요소를 다시 쌓은 재구성으로 대신한다. */
    private BufferedImage sourcePoster(Project project, int sw, int sh, List<LayoutPlanner.SourceElement> elements) throws Exception {
        String path = project.getDesignAssets() == null ? null : project.getDesignAssets().path("source_poster").asText(null);
        if (path != null && storage.exists(path)) return read(path);
        List<ElementExtractionService.Extracted> ex = elements.stream()
                .map(e -> new ElementExtractionService.Extracted(e.id(), e.role(), "", "", e.image(), e.bounds(), e.sourceZ(), null))
                .toList();
        return ElementExtractionService.reconstruct(sw, sh, ex);
    }

    private BufferedImage read(String path) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(storage.read(path)));
    }
}
