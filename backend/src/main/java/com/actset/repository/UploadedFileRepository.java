package com.actset.repository;

import com.actset.domain.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {
    List<UploadedFile> findByProjectId(UUID projectId);
}
