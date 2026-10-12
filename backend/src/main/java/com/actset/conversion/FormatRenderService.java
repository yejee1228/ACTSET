package com.actset.conversion;

import com.actset.conversion.engine.LayerCompositor;
import com.actset.conversion.layout.FormatClassRule;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.domain.GeneratedAsset;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 규격변환 ⑥ 배치[E-A] + ⑦ 합성[E-C]. 저장된 요소를 재배치만 한다(분해를 다시 하지 않는다 — CLAUDE.md 규칙 3). */
@Service
public class FormatRenderService {

    private final LayoutPlanner planner = new LayoutPlanner();
    private final LayerCompositor compositor = new LayerCompositor();
    private final StorageService storage;
    private final GeneratedAssetRepository assetRepository;
    private final ObjectMapper objectMapper;

    public FormatRenderService(StorageService storage, GeneratedAssetRepository assetRepository, ObjectMapper objectMapper) {
        this.storage = storage;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
    }

    public record Rendered(BufferedImage image, LayoutPlanner.Plan plan) {
    }

    public Rendered render(int sourceWidth, int sourceHeight, List<LayoutPlanner.SourceElement> elements,
                           FormatClassRule rule, int width, int height) {
        LayoutPlanner.Plan plan = planner.plan(sourceWidth, sourceHeight, elements, rule, width, height);
        BufferedImage image = compositor.compose(width, height, Color.BLACK, plan.layers());
        return new Rendered(image, plan);
    }

    /**
     * 화면용으로 고른 배치를 k배 크기(인쇄 해상도)로 다시 합성한다 — 배치는 그대로(사용자가 확인한 결과와 같아야 한다),
     * 요소 이미지만 고해상도 판으로 바꾼다. 다시 흘린 제목 조각("원래id#w번호@x,y")은 고해상도 원래 요소에서 같은 자리를
     * 잘라 쓰고, 이웃 낱말이 섞이지 않게 저해상도 조각의 알파를 키워 곱한다.
     *
     * @param hiresFactor hires 이미지들의 배율(원본 대비)
     */
    public BufferedImage renderScaled(LayoutPlanner.Plan plan, double k, Map<String, BufferedImage> hires, double hiresFactor) {
        int w = (int) Math.round(plan.width() * k), h = (int) Math.round(plan.height() * k);
        List<LayerCompositor.Layer> layers = new java.util.ArrayList<>();
        for (LayerCompositor.Layer l : plan.layers()) {
            Rectangle2D t = l.target();
            Rectangle2D target = new Rectangle2D.Double(t.getX() * k, t.getY() * k, t.getWidth() * k, t.getHeight() * k);
            Rectangle2D clip = l.clip() == null ? null
                    : new Rectangle2D.Double(l.clip().getX() * k, l.clip().getY() * k, l.clip().getWidth() * k, l.clip().getHeight() * k);
            BufferedImage img = hires.get(l.name());
            if (img == null) img = hiresPiece(l, hires, hiresFactor);
            if (img == null) img = l.image();
            layers.add(new LayerCompositor.Layer(l.name(), img, target, l.z(), l.opacity(), clip));
        }
        return compositor.compose(w, h, Color.BLACK, layers);
    }

    private static BufferedImage hiresPiece(LayerCompositor.Layer l, Map<String, BufferedImage> hires, double f) {
        String[] piece = com.actset.conversion.layout.HeadlineReflow.parsePieceId(l.name());
        if (piece == null) return null;
        BufferedImage parent = hires.get(piece[0]);
        if (parent == null) return null;
        int x = (int) Math.round(Integer.parseInt(piece[1]) * f), y = (int) Math.round(Integer.parseInt(piece[2]) * f);
        BufferedImage low = l.image();
        int cw = Math.min(parent.getWidth() - x, (int) Math.round(low.getWidth() * f));
        int ch = Math.min(parent.getHeight() - y, (int) Math.round(low.getHeight() * f));
        if (cw <= 0 || ch <= 0) return null;
        BufferedImage mask = ImageOps.resize(low, cw, ch);
        int[] pp = parent.getRGB(x, y, cw, ch, null, 0, cw), mp = mask.getRGB(0, 0, cw, ch, null, 0, cw);
        for (int i = 0; i < pp.length; i++) {
            int a = Math.min(pp[i] >>> 24, mp[i] >>> 24);
            pp[i] = (a << 24) | (pp[i] & 0xffffff);
        }
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, cw, ch, pp, 0, cw);
        return out;
    }

    /** 결과물을 고객·프로젝트 폴더에 저장하고 generated_assets(category='규격변환')에 기록한다. */
    @Transactional
    public GeneratedAsset save(UUID accountId, UUID projectId, String formatCode, Rendered rendered) {
        UUID id = UUID.randomUUID();
        String path = DesignElementStore.projectFolder(accountId, projectId) + "/formats/" + formatCode + "_" + id + ".png";
        byte[] png = DesignElementStore.png(rendered.image());
        storage.store(png, path, "image/png");

        GeneratedAsset asset = new GeneratedAsset();
        asset.setId(id);
        asset.setProjectId(projectId);
        asset.setCategory("규격변환");
        asset.setFormatCode(formatCode);
        asset.setWidth(rendered.image().getWidth());
        asset.setHeight(rendered.image().getHeight());
        asset.setVariantIndex((short) 0);
        asset.setImageUrl(path);
        asset.setPreviewImageUrl(path);
        asset.setObjectMap(objectMap(rendered.plan()));
        ObjectNode params = objectMapper.createObjectNode();
        params.put("format_class", rendered.plan().rule().formatClass().name());
        params.put("rule_source", rendered.plan().rule().source());
        params.put("rule_sample_count", rendered.plan().rule().sampleCount());
        ArrayNode dropped = params.putArray("dropped");
        rendered.plan().dropped().forEach(dropped::add);
        asset.setGenerationParams(params);
        asset.setAutoSyncText(false);
        asset.setStatus("제안됨");
        asset.setFileSize((long) png.length);
        return assetRepository.save(asset);
    }

    /** object_map: 요소 id → 캔버스 위 bbox(docs/02). */
    public ObjectNode objectMap(LayoutPlanner.Plan plan) {
        ObjectNode map = objectMapper.createObjectNode();
        for (LayoutPlanner.Placement p : plan.placements()) {
            Rectangle2D t = p.target();
            ObjectNode n = map.putObject(p.elementId());
            n.put("role", p.role().name());
            n.put("block", p.block() == null ? null : p.block().name());
            n.putArray("bbox").add(Math.round(t.getX())).add(Math.round(t.getY()))
                    .add(Math.round(t.getMaxX())).add(Math.round(t.getMaxY()));
        }
        return map;
    }
}
