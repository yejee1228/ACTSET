package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.AdminAuditLog;
import com.actset.domain.Job;
import com.actset.repository.AdminAuditLogRepository;
import com.actset.repository.CreditTransactionRepository;
import com.actset.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 백오피스(1-20). 베타는 "관리자가 크레딧을 지급한다"를 전제로 하므로
 * 미루지 않는다(docs/13). 모든 조작은 admin_audit_log에 남는다.
 */
@Service
public class AdminService {

    private final CreditService creditService;
    private final JobRepository jobRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AdminService(CreditService creditService, JobRepository jobRepository,
                         AdminAuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.creditService = creditService;
        this.jobRepository = jobRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void grantCredits(UUID adminId, UUID targetAccountId, int amount, String reason) {
        creditService.grant(targetAccountId, amount, "admin_grant", reason, adminId);
        audit(adminId, "grant_credits", "account", targetAccountId.toString(),
                objectMapper.createObjectNode().put("amount", amount).put("reason", reason));
    }

    @Transactional
    public void retryJob(UUID adminId, UUID jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(ApiException::notFound);
        if (!"failed".equals(job.getStatus())) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "NOT_FAILED", "실패한 작업만 재시도할 수 있습니다.");
        }
        job.setStatus("pending");
        job.setError(null);
        jobRepository.save(job);
        audit(adminId, "retry_job", "job", jobId.toString(), objectMapper.createObjectNode());
    }

    public List<Job> failedOrStuckJobs() {
        return jobRepository.findAll().stream()
                .filter(j -> "failed".equals(j.getStatus()) || "pending".equals(j.getStatus()))
                .toList();
    }

    private void audit(UUID actorId, String action, String targetType, String targetId, JsonNode details) {
        AdminAuditLog log = new AdminAuditLog();
        log.setActorId(actorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details);
        auditLogRepository.save(log);
    }
}
