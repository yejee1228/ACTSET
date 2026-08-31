package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ④ 시안 확정(1-14). draft→active 전환 + 선택된 후보를 포스터로 승격한다.
 * 확정 직후 레이어 분해(1-15) job을 등록한다(docs/05 "④ 확정 시 1회 분해").
 */
@Service
public class ConfirmService {

    private final ProjectService projectService;
    private final com.actset.repository.ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;

    public ConfirmService(ProjectService projectService, com.actset.repository.ProjectRepository projectRepository,
                           GeneratedAssetRepository generatedAssetRepository, JobService jobService,
                           ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public record ConfirmResult(UUID posterAssetId, Instant confirmedAt) {
    }

    @Transactional
    public ConfirmResult confirm(UUID projectId, UUID ownerId, UUID selectedCandidateId) {
        Project project = projectService.getOwned(projectId, ownerId);
        validateRequiredFields(project);

        GeneratedAsset candidate = generatedAssetRepository.findById(selectedCandidateId)
                .filter(a -> a.getProjectId().equals(project.getId()) && "시안후보".equals(a.getCategory()))
                .orElseThrow(ApiException::notFound);

        candidate.setCategory("포스터");
        candidate.setAutoSyncText(true);
        candidate.setStatus("선택됨");
        generatedAssetRepository.save(candidate);

        ObjectNode designAssets = objectMapper.createObjectNode();
        designAssets.put("key_visual_image", candidate.getBaseImageUrl());
        designAssets.set("palette", objectMapper.createArrayNode());
        designAssets.put("title_mode", "font");
        designAssets.set("visual_layers", objectMapper.createArrayNode()); // 1-15가 채운다
        designAssets.put("selected_variant_id", candidate.getId().toString());

        Instant now = Instant.now();
        project.setDesignAssets(designAssets);
        project.setDesignUpdatedAt(now);
        project.setStatus("active");
        project.setConfirmedAt(now);
        project.setUpdatedAt(now);
        projectRepository.save(project);

        jobService.enqueue("decompose_layers", project.getId(), null);

        return new ConfirmResult(candidate.getId(), now);
    }

    private void validateRequiredFields(Project project) {
        List<String> missing = new ArrayList<>();
        if (project.getMainTitle() == null || project.getMainTitle().isBlank()) missing.add("main_title");
        if (project.getGenre() == null) missing.add("genre");
        if (!project.isDateUndetermined() && project.getPrimaryDate() == null) missing.add("date");

        JsonNode venue = project.getPerformanceInfo().path("venue");
        boolean venueFilled = project.isVenueUndetermined() || !venue.path("name").asText("").isBlank();
        if (!venueFilled) missing.add("venue");

        if (!missing.isEmpty()) {
            throw ApiException.infoIncomplete(missing);
        }
    }
}
