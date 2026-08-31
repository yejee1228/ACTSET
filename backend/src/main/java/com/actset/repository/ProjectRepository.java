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
}
