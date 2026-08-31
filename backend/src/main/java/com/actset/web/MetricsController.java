package com.actset.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 6-6 사용 지표 대시보드(내부용) + 6-8 크레딧 소비 분포 집계. docs/13이 "MVP 성공 기준(완주율·
 * 다운로드율)을 측정하는 수단"이라고 명시해 미룰 수 없는 항목이다.
 */
@RestController
public class MetricsController {

    private final JdbcClient jdbcClient;

    public MetricsController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/api/v1/admin/metrics")
    public Map<String, Object> metrics() {
        long draftProjects = count("SELECT count(*) FROM projects WHERE status = 'draft'");
        long activeProjects = count("SELECT count(*) FROM projects WHERE status = 'active'");
        long totalProjects = draftProjects + activeProjects;
        double completionRate = totalProjects == 0 ? 0 : (double) activeProjects / totalProjects;

        long selectActions = count("SELECT count(*) FROM selection_events WHERE screen = '시안선택' AND action = 'select'");
        long regenerateActions = count("SELECT count(*) FROM selection_events WHERE action IN ('regenerate', 'view_more_direction')");
        long moreLikeActions = count("SELECT count(*) FROM selection_events WHERE action = 'more_like_this'");
        long totalDraftScreenActions = selectActions + regenerateActions + moreLikeActions;
        double selectionRate = totalDraftScreenActions == 0 ? 0 : (double) selectActions / totalDraftScreenActions;

        return Map.of(
                "funnel", Map.of(
                        "draft_projects", draftProjects,
                        "active_projects", activeProjects,
                        "completion_rate", completionRate
                ),
                "draft_selection", Map.of(
                        "select_count", selectActions,
                        "regenerate_count", regenerateActions,
                        "more_like_count", moreLikeActions,
                        "selection_rate", selectionRate
                )
        );
    }

    /** 6-8: 액션별(설명 접두사 기준) 크레딧 소비 분포 — 정식 단가 산정 근거(docs/06). */
    @GetMapping("/api/v1/admin/usage")
    public Map<String, Object> usage() {
        List<Map<String, Object>> byAction = jdbcClient.sql("""
                SELECT
                  CASE
                    WHEN description LIKE '시안 생성%' THEN '시안 생성'
                    WHEN description LIKE '규격 변환%' THEN '규격 변환'
                    WHEN description LIKE '%환불%' THEN '환불'
                    ELSE type
                  END AS action,
                  count(*) AS tx_count,
                  sum(abs(amount)) AS total_amount
                FROM credit_transactions
                WHERE type IN ('consume', 'refund')
                GROUP BY action
                ORDER BY total_amount DESC
                """).query((rs, rowNum) -> Map.<String, Object>of(
                        "action", rs.getString("action"),
                        "tx_count", rs.getLong("tx_count"),
                        "total_amount", rs.getLong("total_amount")
                )).list();

        return Map.of("by_action", byAction);
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}
