package com.actset.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 6-5b 고객 문의 창구 — 6-5(인터뷰 접점)와 별개, 결제·삭제 등 상시 문의(docs/13). */
@Entity
@Table(name = "customer_inquiries")
@Getter
@Setter
@NoArgsConstructor
public class CustomerInquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String message;

    private String contact;

    @Column(nullable = false)
    private String status = "open";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
