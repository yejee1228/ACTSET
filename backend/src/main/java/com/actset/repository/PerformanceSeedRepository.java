package com.actset.repository;

import com.actset.domain.PerformanceSeed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PerformanceSeedRepository extends JpaRepository<PerformanceSeed, UUID> {

    Optional<PerformanceSeed> findBySourceAndExternalId(String source, String externalId);

    Page<PerformanceSeed> findAllByOrderByPrimaryDateDesc(Pageable pageable);

    Page<PerformanceSeed> findByGenreOrderByPrimaryDateDesc(String genre, Pageable pageable);
}
