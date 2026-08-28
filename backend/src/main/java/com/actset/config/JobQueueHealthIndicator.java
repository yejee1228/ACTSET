package com.actset.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * P-7 모니터링 — jobs 큐 적체를 헬스체크에 반영한다. pending 건수가 기준치를
 * 넘으면 DOWN으로 표시해 외부 헬스체크·경보 시스템이 감지할 수 있게 한다.
 */
@Component
public class JobQueueHealthIndicator implements HealthIndicator {

    private static final int BACKLOG_THRESHOLD = 200;

    private final JdbcClient jdbcClient;

    public JobQueueHealthIndicator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Health health() {
        try {
            Long pending = jdbcClient.sql("SELECT count(*) FROM jobs WHERE status = 'pending'")
                    .query(Long.class).single();
            Long failed = jdbcClient.sql("SELECT count(*) FROM jobs WHERE status = 'failed' AND created_at > now() - interval '1 hour'")
                    .query(Long.class).single();

            Health.Builder builder = pending < BACKLOG_THRESHOLD ? Health.up() : Health.down();
            return builder.withDetail("pending", pending).withDetail("failedLastHour", failed).build();
        } catch (Exception e) {
            return Health.unknown().withDetail("error", e.getMessage()).build();
        }
    }
}
