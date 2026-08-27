package com.actset.repository;

import com.actset.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /** FOR UPDATE SKIP LOCKED로 대기 작업 1건을 점유한다(docs/10). */
    @Query(value = """
            SELECT * FROM jobs WHERE id = (
              SELECT id FROM jobs WHERE status = 'pending'
              ORDER BY created_at
              FOR UPDATE SKIP LOCKED LIMIT 1
            )
            """, nativeQuery = true)
    Optional<Job> claimNextPending();

    List<Job> findByParentJobId(UUID parentJobId);

    @Modifying
    @Query("UPDATE Job j SET j.status = 'pending', j.lockedAt = null WHERE j.status = 'running' AND j.lockedAt < :threshold")
    int recoverStale(@Param("threshold") Instant threshold);

    List<Job> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
