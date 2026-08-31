package com.actset.repository;

import com.actset.domain.PrintOrderDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrintOrderDraftRepository extends JpaRepository<PrintOrderDraft, UUID> {
    List<PrintOrderDraft> findByProjectId(UUID projectId);
}
