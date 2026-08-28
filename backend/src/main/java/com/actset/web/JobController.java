package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.repository.JobRepository;
import com.actset.security.CurrentUser;
import com.actset.service.ProjectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** docs/11 "3. 작업 상태". TanStack Query 폴링 대상. */
@RestController
public class JobController {

    private final JobRepository jobRepository;
    private final ProjectService projectService;

    public JobController(JobRepository jobRepository, ProjectService projectService) {
        this.jobRepository = jobRepository;
        this.projectService = projectService;
    }

    /** 6-5c 진행 중 작업 안내 — 재진입 시 처리 중인 job을 알 수 있게 한다(docs/13). */
    @GetMapping("/api/v1/projects/{projectId}/jobs")
    public Map<String, Object> listForProject(@PathVariable UUID projectId) {
        projectService.getOwned(projectId, CurrentUser.id());
        List<Job> jobs = jobRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(j -> "pending".equals(j.getStatus()) || "running".equals(j.getStatus()))
                .toList();
        List<Map<String, Object>> items = jobs.stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId().toString());
            m.put("kind", j.getKind());
            m.put("status", j.getStatus());
            return m;
        }).toList();
        return Map.of("items", items);
    }

    @GetMapping("/api/v1/jobs/{jobId}")
    public Map<String, Object> get(@PathVariable UUID jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(ApiException::notFound);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.getId().toString());
        body.put("kind", job.getKind());
        body.put("status", job.getStatus());
        body.put("error", job.getError());
        body.put("result", job.getResult());

        List<Job> children = jobRepository.findByParentJobId(job.getId());
        if (!children.isEmpty()) {
            long done = children.stream().filter(c -> "succeeded".equals(c.getStatus()) || "failed".equals(c.getStatus())).count();
            body.put("progress", Map.of("done", done, "total", children.size()));
            body.put("children", children.stream().map(c -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("job_id", c.getId().toString());
                cm.put("status", c.getStatus());
                cm.put("error", c.getError());
                cm.put("format_code", c.getPayload() != null ? c.getPayload().path("format_code").asText(null) : null);
                return cm;
            }).toList());
        }
        return body;
    }
}
