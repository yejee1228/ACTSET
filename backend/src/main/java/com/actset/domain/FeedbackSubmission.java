package com.actset.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 6-5 피드백 수집 채널 — 고객 인터뷰 대상자 접점 확보용(docs/13). */
@Entity
@Table(name = "feedback_submissions")
@Getter
@Setter
@NoArgsConstructor
public class FeedbackSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private String message;

    private String contact;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
