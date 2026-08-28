package com.actset.web;

import com.actset.domain.FunnelEvent;
import com.actset.repository.FunnelEventRepository;
import com.actset.security.AccountPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 6-7 퍼널 이벤트 — 방문(비로그인)부터 시작해야 하므로 인증 없이도 받는다
 * (SecurityConfig에서 permitAll 처리). 202로 즉시 응답하고 실패해도 사용자 동작을
 * 막지 않는다(docs/11 선택 로그와 동일한 원칙 — CLAUDE.md 규칙 6과 결이 같다).
 */
@RestController
public class FunnelEventController {

    private final FunnelEventRepository funnelEventRepository;

    public FunnelEventController(FunnelEventRepository funnelEventRepository) {
        this.funnelEventRepository = funnelEventRepository;
    }

    public record FunnelEventRequest(String session_id, String step, String utm_source, String utm_medium, String utm_campaign) {
    }

    @PostMapping("/api/v1/funnel-events")
    public ResponseEntity<Void> record(@RequestBody FunnelEventRequest req) {
        try {
            FunnelEvent event = new FunnelEvent();
            event.setSessionId(req.session_id());
            event.setAccountId(currentAccountIdOrNull());
            event.setStep(req.step());
            event.setUtmSource(req.utm_source());
            event.setUtmMedium(req.utm_medium());
            event.setUtmCampaign(req.utm_campaign());
            funnelEventRepository.save(event);
        } catch (Exception ignored) {
            // 적재 실패가 사용자 동작을 막아서는 안 된다 — 조용히 넘어간다.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private UUID currentAccountIdOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal ap) {
            return ap.getAccountId();
        }
        return null;
    }
}
