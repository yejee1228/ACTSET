package com.actset.worker;

import com.actset.config.RequestIdFilter;
import com.actset.domain.Job;
import com.actset.service.CreditService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * worker 프로필에서만 동작한다(docs/09). @Scheduled + FOR UPDATE SKIP LOCKED로 jobs를 폴링한다.
 * 내부 HTTP API 없이 서비스 계층을 직접 호출한다(docs/11 Stage 7 — 단일 애플리케이션 구조).
 */
@Component
@Profile("worker")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobService jobService;
    private final CreditService creditService;
    private final Map<String, JobHandler> handlers;

    @Value("${actset.worker.stale-running-minutes:30}")
    private int staleRunningMinutes;

    public JobWorker(JobService jobService, CreditService creditService, List<JobHandler> handlerList) {
        this.jobService = jobService;
        this.creditService = creditService;
        this.handlers = handlerList.stream().collect(Collectors.toMap(JobHandler::kind, h -> h));
    }

    @Scheduled(fixedDelayString = "${actset.worker.poll-interval-ms:2000}")
    public void pollOnce() {
        recoverStaleJobs();
        Optional<Job> claimed = jobService.claimNext();
        claimed.ifPresent(this::process);
    }

    private void process(Job job) {
        // P-8: 등록 시점의 요청 추적 ID를 이어받아 워커 로그도 같은 ID로 상관관계를 유지한다.
        String requestId = job.getPayload() != null && job.getPayload().has("request_id")
                ? job.getPayload().get("request_id").asText() : job.getId().toString();
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        try {
            JobHandler handler = handlers.get(job.getKind());
            if (handler == null) {
                log.warn("등록된 핸들러가 없는 job.kind={} (id={}) — 실패 처리", job.getKind(), job.getId());
                jobService.markFailed(job.getId(), "핸들러 미등록: " + job.getKind());
                refund(job);
                return;
            }
            try {
                ObjectNode result = handler.handle(job);
                jobService.markSucceeded(job.getId(), result);
                log.info("job {} ({}) 완료", job.getId(), job.getKind());
            } catch (Exception e) {
                log.error("job {} ({}) 실패: {}", job.getId(), job.getKind(), e.getMessage(), e);
                jobService.markFailed(job.getId(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                refund(job);
            }
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    /** 실패한 작업에 크레딧 차감 이력이 있으면 환불한다(CLAUDE.md 규칙 4, 1-24). */
    private void refund(Job job) {
        try {
            creditService.refundByJob(job.getId(), job.getKind() + " 실패 환불");
        } catch (Exception e) {
            log.error("job {} 환불 처리 실패 — 수동 확인 필요", job.getId(), e);
        }
    }

    /** locked_at이 오래된 running 작업은 죽은 워커의 것으로 보고 pending으로 되돌린다(docs/10). */
    private void recoverStaleJobs() {
        Instant threshold = Instant.now().minus(staleRunningMinutes, ChronoUnit.MINUTES);
        int recovered = jobService.recoverStale(threshold);
        if (recovered > 0) {
            log.warn("죽은 작업 {}건을 pending으로 복구했습니다(locked_at < {})", recovered, threshold);
        }
    }
}
