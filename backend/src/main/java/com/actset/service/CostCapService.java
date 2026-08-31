package com.actset.service;

import com.actset.common.ApiException;
import com.actset.config.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 비용 상한(1-25): 계정별 시간당 생성 횟수 + 전체 일일 외부 API 예산.
 * 1-22의 RateLimiter를 재사용하되 창(윈도우)과 목적이 다르다 — 1-22는 일반적인
 * 어뷰징(분당 요청수) 방지, 이것은 실제 원가(외부 이미지 생성 API 호출)에 대한
 * 사업적 상한이다. 초과 시 자동 차단하고 로그로 경고를 남긴다(1-25 완료기준
 * "자동 차단·알림" — 실시간 알림 채널 연동은 P-7에서 다룬다).
 */
@Service
public class CostCapService {

    private static final Logger log = LoggerFactory.getLogger(CostCapService.class);
    private static final int HOUR_SECONDS = 3600;
    private static final int DAY_SECONDS = 86400;
    private static final String DAILY_BUDGET_KEY = "daily-global-api-budget";

    private final RateLimiter rateLimiter;

    @Value("${actset.cost.hourly-generation-limit:10}")
    private int hourlyGenerationLimit;

    @Value("${actset.cost.daily-api-budget:500}")
    private int dailyApiBudget;

    public CostCapService(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /** count장(외부 API 호출 count회)을 만들기 전에 계정별·전체 상한을 함께 확인한다. */
    public void checkBeforeGeneration(UUID accountId, int count) {
        if (!rateLimiter.tryAcquireN("hourly-gen:" + accountId, count, hourlyGenerationLimit, HOUR_SECONDS)) {
            log.warn("계정 {} 시간당 생성 상한 초과 (limit={})", accountId, hourlyGenerationLimit);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "HOURLY_LIMIT_EXCEEDED",
                    "시간당 생성 횟수 상한을 초과했습니다. 잠시 후 다시 시도해주세요.");
        }
        if (!rateLimiter.tryAcquireN(DAILY_BUDGET_KEY, count, dailyApiBudget, DAY_SECONDS)) {
            log.warn("전체 일일 외부 API 예산 초과 (budget={}) — 계정 {}의 요청을 차단", dailyApiBudget, accountId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DAILY_BUDGET_EXCEEDED",
                    "오늘의 생성 한도에 도달했습니다. 내일 다시 시도해주세요.");
        }
    }
}
