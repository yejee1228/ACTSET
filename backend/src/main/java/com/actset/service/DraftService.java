package com.actset.service;

import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ③ 시안 후보 생성 요청(1-11·1-12). 크레딧 차감은 작업 등록과 한 트랜잭션으로
 * 묶는다(CLAUDE.md 규칙 4·docs/06). 단가는 아직 확정되지 않아 임시값을 쓴다
 * (OVERNIGHT-LOG 기록 — Ideogram 실단가·크레딧 단가 확정 후 교체).
 */
@Service
public class DraftService {

    private final ProjectService projectService;
    private final JobService jobService;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;

    @Value("${actset.credit.cost-per-draft-image:10}")
    private int costPerImage;

    public DraftService(ProjectService projectService, JobService jobService, CreditService creditService,
                         ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID requestDrafts(UUID projectId, UUID ownerId, String mode, int count) {
        Project project = projectService.getOwned(projectId, ownerId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("mode", mode);
        payload.put("count", count);

        Job job = jobService.enqueue("draft_generate", project.getId(), payload);
        int cost = costPerImage * count;
        creditService.consume(ownerId, cost, job.getId(), "시안 생성 " + count + "장(" + mode + ")");
        return job.getId();
    }
}
