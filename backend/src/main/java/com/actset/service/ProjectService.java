package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Project;
import com.actset.repository.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    public ProjectService(ProjectRepository projectRepository, ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
    }

    /** ① 화면 진입 시 draft 프로젝트를 즉시 만든다(docs/02·03). */
    @Transactional
    public Project createDraft(UUID ownerId) {
        Project project = new Project();
        project.setOwnerId(ownerId);
        project.setStatus("draft");
        ObjectNode empty = objectMapper.createObjectNode();
        project.setPerformanceInfo(empty);
        return projectRepository.save(project);
    }

    /**
     * G-2 "참고해서 만들기"(7-6, Stage 5 4-2·17). 참고 대상의 PerformanceInfo는 복사하지
     * 않는다 — 팔레트 등 스타일 힌트만 새 draft의 design_assets에 심어 ③ 시안 생성 시
     * DraftGenerateJobHandler가 읽어간다. 참고 대상은 Gallery에 공개된 프로젝트여야 한다.
     */
    @Transactional
    public Project createFromReference(UUID sourceProjectId, UUID ownerId) {
        Project source = projectRepository.findByIdAndVisibilityAndStatus(sourceProjectId, "public", "active")
                .orElseThrow(ApiException::notFound);

        Project project = new Project();
        project.setOwnerId(ownerId);
        project.setStatus("draft");
        project.setPerformanceInfo(objectMapper.createObjectNode());

        ObjectNode hint = objectMapper.createObjectNode();
        hint.put("source_project_id", source.getId().toString());
        JsonNode sourcePalette = source.getDesignAssets() != null
                ? source.getDesignAssets().path("palette") : objectMapper.createArrayNode();
        hint.set("palette", sourcePalette);
        ObjectNode designAssets = objectMapper.createObjectNode();
        designAssets.set("reference_style_hint", hint);
        project.setDesignAssets(designAssets);

        return projectRepository.save(project);
    }

    /**
     * 소유자 조건 없이 id만으로 조회하지 않는다(docs/09 권한 원칙).
     * 타인 프로젝트는 존재를 숨기기 위해 404로 응답한다.
     */
    public Project getOwned(UUID projectId, UUID ownerId) {
        return projectRepository.findByIdAndOwnerId(projectId, ownerId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Project touchInfoUpdated(Project project) {
        project.setInfoUpdatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
    }

    /**
     * ⑦ 대시보드 공개 전환 토글(7-2, Stage 2·4·17). active 상태에서만 public 전환을 허용한다 —
     * draft는 아직 완성되지 않은 시안이라 Gallery에 노출할 대상이 아니다.
     */
    @Transactional
    public Project setVisibility(UUID projectId, UUID ownerId, String visibility) {
        Project project = getOwned(projectId, ownerId);
        if (!project.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_NOT_ACTIVE",
                    "확정된 프로젝트만 공개로 전환할 수 있습니다.");
        }
        project.setVisibility(visibility);
        project.setPublishedAt("public".equals(visibility) ? Instant.now() : null);
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
    }
}
