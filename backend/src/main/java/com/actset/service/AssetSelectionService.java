package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** ⑥에서 규격별 안을 확정한다(3-5). 같은 규격의 다른 후보는 보관으로 바뀐다(docs/11). */
@Service
public class AssetSelectionService {

    private final GeneratedAssetRepository generatedAssetRepository;
    private final ProjectRepository projectRepository;
    private final SelectionEventService selectionEventService;
    private final ObjectMapper objectMapper;

    public AssetSelectionService(GeneratedAssetRepository generatedAssetRepository, ProjectRepository projectRepository,
                                  SelectionEventService selectionEventService, ObjectMapper objectMapper) {
        this.generatedAssetRepository = generatedAssetRepository;
        this.projectRepository = projectRepository;
        this.selectionEventService = selectionEventService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void select(UUID assetId, UUID ownerId) {
        GeneratedAsset asset = generatedAssetRepository.findById(assetId).orElseThrow(ApiException::notFound);
        Project project = projectRepository.findByIdAndOwnerId(asset.getProjectId(), ownerId).orElseThrow(ApiException::notFound);

        List<GeneratedAsset> siblings = generatedAssetRepository
                .findByProjectIdAndCategoryAndDeletedAtIsNull(project.getId(), asset.getCategory())
                .stream()
                .filter(a -> asset.getFormatCode().equals(a.getFormatCode()))
                .toList();

        ArrayNode shownCandidates = objectMapper.createArrayNode();
        for (GeneratedAsset sibling : siblings) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("candidate_id", sibling.getId().toString());
            entry.set("generation_params", sibling.getGenerationParams() != null
                    ? sibling.getGenerationParams() : objectMapper.createObjectNode());
            shownCandidates.add(entry);

            sibling.setStatus(sibling.getId().equals(assetId) ? "선택됨" : "보관");
            generatedAssetRepository.save(sibling);
        }

        selectionEventService.record(project, ownerId, "규격변환", "select", asset.getFormatCode(), shownCandidates, assetId);
    }
}
