package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Project;
import com.actset.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
}
