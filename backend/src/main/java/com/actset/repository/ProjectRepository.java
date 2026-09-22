package com.actset.repository;

import com.actset.domain.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** 조건 없이 id만으로 조회하는 메서드는 두지 않는다(docs/09 권한 원칙) — 항상 owner_id를 함께 검사한다. */
    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Project> findByOwnerId(UUID ownerId);

    Page<Project> findByOwnerIdAndStatusOrderByUpdatedAtDesc(UUID ownerId, String status, Pageable pageable);

    Page<Project> findByOwnerIdAndStatusAndMainTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
            UUID ownerId, String status, String q, Pageable pageable);

    /**
     * Gallery(G-1·G-2, Stage 17)는 인증 없이 열람 가능한 공개 콘텐츠라 owner_id로 좁힐 수 없다.
     * 대신 visibility·status 조건으로 "공개·확정 프로젝트만"을 강제해 docs/09 권한 원칙의 취지
     * (id 하나만으로 무제한 조회하지 않는다)를 지킨다.
     */
    Page<Project> findByVisibilityAndStatusOrderByPublishedAtDesc(String visibility, String status, Pageable pageable);

    Page<Project> findByVisibilityAndStatusAndGenreOrderByPublishedAtDesc(
            String visibility, String status, String genre, Pageable pageable);

    Optional<Project> findByIdAndVisibilityAndStatus(UUID id, String visibility, String status);
}
