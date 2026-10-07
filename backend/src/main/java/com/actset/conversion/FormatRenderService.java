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
