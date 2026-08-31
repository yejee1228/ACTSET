package com.actset.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 6-7 퍼널 이벤트 — 방문→가입→①~⑥→다운로드 단계별. SelectionEvent와는 별개 구조(docs/13). */
@Entity
@Table(name = "funnel_events")
@Getter
@Setter
@NoArgsConstructor
public class FunnelEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private String step;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
