package com.actset.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_order_drafts")
@Getter
@Setter
@NoArgsConstructor
public class PrintOrderDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "generated_asset_id")
    private UUID generatedAssetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "print_spec")
    private JsonNode printSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address")
    private JsonNode shippingAddress;

    @Column(name = "estimated_price")
    private Integer estimatedPrice;

    @Column(nullable = false)
    private String status = "draft_only";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
