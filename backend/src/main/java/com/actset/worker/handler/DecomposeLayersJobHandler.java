package com.actset.worker.handler;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.external.layerdecomp.DecomposedLayer;
import com.actset.external.layerdecomp.LayerDecompositionAdapter;
import com.actset.external.layerdecomp.LayerDecompositionResult;
import com.actset.repository.ProjectRepository;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.storage.StorageService;
import com.actset.worker.JobHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * jobs.kind = 'decompose_layers' — ④ 확정 시 1회 레이어 분해(1-15).
 * 결과는 DesignAssets.visual_layers에 저장되고 이후 모든 규격 변환이 재사용한다(docs/05).
 */
@Component
public class DecomposeLayersJobHandler implements JobHandler {

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final StorageService storageService;
    private final LayerDecompositionAdapter adapter;
    private final ObjectMapper objectMapper;

    public DecomposeLayersJobHandler(ProjectRepository projectRepository,
                                      GeneratedAssetRepository generatedAssetRepository,
                                      StorageService storageService,
                                      LayerDecompositionAdapter adapter,
                                      ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.storageService = storageService;
        this.adapter = adapter;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "decompose_layers";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        Project project = projectRepository.findById(job.getProjectId()).orElseThrow(ApiException::notFound);
        GeneratedAsset poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(project.getId(), "포스터")
                .orElseThrow(ApiException::notFound);

        BufferedImage base = ImageIO.read(new ByteArrayInputStream(storageService.read(poster.getBaseImageUrl())));
        LayerDecompositionResult decomposition = adapter.decompose(base);

        ArrayNode layersJson = objectMapper.createArrayNode();
        int zOrder = 0;
        for (DecomposedLayer layer : decomposition.layers()) {
            UUID layerId = UUID.randomUUID();
            String path = "projects/" + project.getId() + "/layers/" + layerId + ".png";
            storageService.store(toPngBytes(layer.image()), path, "image/png");

            ObjectNode node = objectMapper.createObjectNode();
            node.put("layer_id", layerId.toString());
            node.put("type", layer.type());
            node.put("image_url", path);
            ArrayNode bbox = objectMapper.createArrayNode();
            bbox.add(layer.x()).add(layer.y()).add(layer.x() + layer.width()).add(layer.y() + layer.height());
            node.set("bbox", bbox);
            node.put("z_order", zOrder++);
            node.put("removable", layer.removable());
            layersJson.add(node);
        }

        ObjectNode designAssets = (ObjectNode) project.getDesignAssets();
        designAssets.set("visual_layers", layersJson);
        designAssets.put("decomposition_succeeded", decomposition.succeeded());
        project.setDesignAssets(designAssets);
        project.setDesignUpdatedAt(Instant.now());
        projectRepository.save(project);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("layer_count", layersJson.size());
        result.put("decomposition_succeeded", decomposition.succeeded());
        return result;
    }

    private byte[] toPngBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
