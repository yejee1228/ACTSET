package com.actset.worker;

import com.actset.config.RequestIdFilter;
import com.actset.domain.Job;
import com.actset.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * jobs 테이블 등록·조회를 담당한다. 워커의 획득·상태갱신은 JobWorker(worker 프로필)가 한다.
 */
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    public JobService(JobRepository jobRepository, ObjectMapper objectMapper, JdbcClient jdbcClient) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public Job enqueue(String kind, UUID projectId, ObjectNode payload, UUID parentJobId) {
        Job job = new Job();
        job.setKind(kind);
        job.setProjectId(projectId);
        ObjectNode finalPayload = payload != null ? payload : objectMapper.createObjectNode();
        // P-8: 요청을 만든 HTTP 호출의 상관관계 ID를 job에 실어 워커 로그까지 이어지게 한다.
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            finalPayload.put("request_id", requestId);
        }
        job.setPayload(finalPayload);
        job.setParentJobId(parentJobId);
        job.setStatus("pending");
        return jobRepository.save(job);
    }

    public Job enqueue(String kind, UUID projectId, ObjectNode payload) {
        return enqueue(kind, projectId, payload, null);
    }

    /**
     * FOR UPDATE SKIP LOCKED로 대기 작업 1건을 점유하며 즉시 running으로 갱신한다(docs/09·10).
     * Spring Data JPA의 @Modifying 쿼리는 엔티티를 반환할 수 없어(int/void만 허용) UPDATE...RETURNING을
     * JdbcClient로 직접 실행하고, 반환된 id로 관리 엔티티를 다시 조회한다.
     */
    @Transactional
    public Optional<Job> claimNext() {
        Optional<UUID> claimedId = jdbcClient.sql("""
                UPDATE jobs SET status = 'running', locked_at = now(), updated_at = now()
                WHERE id = (
                  SELECT id FROM jobs WHERE status = 'pending'
                  ORDER BY created_at
                  FOR UPDATE SKIP LOCKED LIMIT 1
                )
                RETURNING id
                """).query(UUID.class).optional();
        return claimedId.flatMap(jobRepository::findById);
    }

    @Transactional
    public int recoverStale(Instant threshold) {
        return jobRepository.recoverStale(threshold);
    }

    @Transactional
    public void markSucceeded(UUID jobId, ObjectNode result) {
        Job job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus("succeeded");
        job.setResult(result);
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional
    public void markFailed(UUID jobId, String error) {
        Job job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus("failed");
        job.setError(error);
        job.setAttempts((short) (job.getAttempts() + 1));
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }
}
