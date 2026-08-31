package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.external.moderation.ContentModerationAdapter;
import com.actset.external.moderation.ModerationResult;
import com.actset.worker.JobService;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ContentModerationAdapter contentModerationAdapter;
    private final CostCapService costCapService;
    private final CostEstimateService costEstimateService;

    public DraftService(ProjectService projectService, JobService jobService, CreditService creditService,
                         ObjectMapper objectMapper, ContentModerationAdapter contentModerationAdapter,
                         CostCapService costCapService, CostEstimateService costEstimateService) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.contentModerationAdapter = contentModerationAdapter;
        this.costCapService = costCapService;
        this.costEstimateService = costEstimateService;
    }

    @Transactional
    public UUID requestDrafts(UUID projectId, UUID ownerId, String mode, int count) {
        Project project = projectService.getOwned(projectId, ownerId);

        // 1-23: 차단 시 크레딧을 차감하지 않는다 — consume()보다 먼저 검사한다.
        String note = project.getPerformanceInfo().path("image_direction_note").asText(null);
        ModerationResult moderation = contentModerationAdapter.checkText(note);
        if (!moderation.allowed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONTENT_BLOCKED", moderation.reason());
        }

        // 1-25: 비용 상한도 크레딧 차감 전에 확인한다 — 차단된 요청에는 비용을 물리지 않는다.
        costCapService.checkBeforeGeneration(ownerId, count);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("mode", mode);
        payload.put("count", count);

        Job job = jobService.enqueue("draft_generate", project.getId(), payload);
        int cost = costEstimateService.draftCost(count);
        creditService.consume(ownerId, cost, job.getId(), "시안 생성 " + count + "장(" + mode + ")");
        return job.getId();
    }
}
