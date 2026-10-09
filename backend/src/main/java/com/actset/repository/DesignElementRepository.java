package com.actset.repository;

import com.actset.domain.DesignElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DesignElementRepository extends JpaRepository<DesignElement, UUID> {
    List<DesignElement> findByProjectIdOrderByLayerOrderAsc(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
