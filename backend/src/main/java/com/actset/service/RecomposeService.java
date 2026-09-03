package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.format.FormatPreset;
import com.actset.worker.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ⑤→⑥ 규격 일괄변환 요청(3-1·3-4). 규격별 하위 job으로 나눠 일부가 실패해도 나머지는
 * 살아남게 한다(docs/09·10). POSTER는 "원본 다시 만들기" 경로(3-7)라 여기서는 제외한다
 * — 3-7은 실제 레이어 재분해가 필요해 이번 라운드에서는 보류한다(OVERNIGHT-LOG).
 */
@Service
public class RecomposeService {

    private final ProjectService projectService;
    private final JobService jobService;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final CostEstimateService costEstimateService;

    public RecomposeService(ProjectService projectService, JobService jobService, CreditService creditService,
                             ObjectMapper objectMapper, CostEstimateService costEstimateService) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.costEstimateService = costEstimateService;
    }

    public record ChildJob(UUID jobId, String formatCode) {
    }

    public record RecomposeResult(UUID parentJobId, List<ChildJob> children, List<String> skipped) {
    }

    @Transactional
    public RecomposeResult requestRecompose(UUID projectId, UUID ownerId, List<String> formatCodes, int variantsPerFormat, String mode) {
        Project project = projectService.getOwned(projectId, ownerId);
        if (project.getDesignAssets() == null || !project.getDesignAssets().has("visual_layers")
                || project.getDesignAssets().get("visual_layers").isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_DESIGN_ASSETS",
                    "먼저 시안을 확정해 원본을 만들어야 규격 변환을 할 수 있습니다.");
        }

        List<String> targets = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String code : formatCodes) {
            if ("POSTER".equals(code)) {
                skipped.add(code); // 3-7(포스터 재생성)은 이번 라운드 범위 밖
            } else {
                targets.add(code);
            }
        }
        if (targets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_TARGET_FORMATS", "변환할 규격이 없습니다.");
        }

        // 컨테이너 job: 실제로 처리되지 않고 하위 job들의 진행 상황을 묶어 보여주기 위한 레코드다
        // (jobs.status CHECK 제약상 pending/running/succeeded/failed/canceled 중 하나여야 해서
        // "그룹 등록 자체는 성공"이라는 의미로 succeeded를 쓴다 — claimNext()는 pending만 집으므로
        // 워커가 이 job을 실제 작업으로 오인해 집지 않는다).
        Job parent = jobService.enqueue("recompose", projectId, objectMapper.createObjectNode());
        jobService.markSucceeded(parent.getId(), objectMapper.createObjectNode());

        List<ChildJob> children = new ArrayList<>();
        for (String code : targets) {
            FormatPreset preset = FormatPreset.byCode(code)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_FORMAT", "알 수 없는 규격: " + code));

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("format_code", code);
            payload.put("width", preset.width());
            payload.put("height", preset.height());
            payload.put("variants", variantsPerFormat);

            Job child = jobService.enqueue("recompose", projectId, payload, parent.getId());
            int cost = costEstimateService.recomposeCostPerFormat(mode);
            String label = "regenerate".equals(mode) ? "규격 재생성 " : "규격 변환 ";
            creditService.consume(ownerId, cost, child.getId(), label + code);
            children.add(new ChildJob(child.getId(), code));
        }

        return new RecomposeResult(parent.getId(), children, skipped);
    }
}
