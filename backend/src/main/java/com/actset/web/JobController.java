package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Job;
import com.actset.repository.JobRepository;
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

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
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
