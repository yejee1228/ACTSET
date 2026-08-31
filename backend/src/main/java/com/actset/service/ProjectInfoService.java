package com.actset.service;

import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * ①·②·6-1 공통 — PATCH /projects/{id}/info(docs/11). 부분 갱신을 허용하는 얕은 병합이다.
 * 포스터(auto_sync_text=true)가 이미 있으면 텍스트 재합성 job을 등록한다(4-4에서 핸들러 구현).
 */
@Service
public class ProjectInfoService {

    private final ProjectRepository projectRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final JobService jobService;

    public ProjectInfoService(ProjectRepository projectRepository,
                               GeneratedAssetRepository generatedAssetRepository,
                               JobService jobService) {
        this.projectRepository = projectRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.jobService = jobService;
    }

    public record UpdateResult(Instant updatedAt, UUID posterResyncJobId) {
    }

    @Transactional
    public UpdateResult update(Project project, JsonNode partial) {
        ObjectNode merged = ((ObjectNode) project.getPerformanceInfo()).setAll((ObjectNode) partial);
        project.setPerformanceInfo(merged);
        syncDenormalizedColumns(project, partial);

        Instant now = Instant.now();
        project.setInfoUpdatedAt(now);
        project.setUpdatedAt(now);
        projectRepository.save(project);

        UUID resyncJobId = null;
        Optional<GeneratedAsset> poster = generatedAssetRepository
                .findFirstByProjectIdAndCategoryAndDeletedAtIsNull(project.getId(), "포스터");
        if (poster.isPresent() && poster.get().isAutoSyncText()) {
            resyncJobId = jobService.enqueue("resync", project.getId(), null).getId();
        }
        return new UpdateResult(now, resyncJobId);
    }

    private void syncDenormalizedColumns(Project project, JsonNode partial) {
        if (partial.has("main_title")) {
            project.setMainTitle(partial.path("main_title").asText(""));
        }
        if (partial.has("genre")) {
            project.setGenre(partial.path("genre").asText(null));
        }
        if (partial.has("sessions") && partial.path("sessions").isArray() && !partial.path("sessions").isEmpty()) {
            JsonNode first = partial.path("sessions").get(0);
            boolean undetermined = first.path("is_undetermined").asBoolean(false);
            project.setDateUndetermined(undetermined);
            String dateStr = first.path("date").asText(null);
            project.setPrimaryDate(!undetermined && dateStr != null ? LocalDate.parse(dateStr) : null);
        }
        if (partial.has("venue")) {
            JsonNode venue = partial.path("venue");
            project.setVenueUndetermined(venue.path("is_undetermined").asBoolean(false));
        }
    }
}
