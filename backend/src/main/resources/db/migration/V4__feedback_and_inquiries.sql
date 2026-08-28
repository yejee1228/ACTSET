-- E6 베타 운영 준비: 6-5(피드백) · 6-5b(문의)

CREATE TABLE feedback_submissions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   uuid REFERENCES accounts(id) ON DELETE SET NULL,
    message      text NOT NULL,
    contact      text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- 6-5b: 결제·삭제 문의 등 상시 고객 문의 창구 — 6-5(인터뷰 접점)와 별개(docs/13).
CREATE TABLE customer_inquiries (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   uuid REFERENCES accounts(id) ON DELETE SET NULL,
    subject      text NOT NULL,
    message      text NOT NULL,
    contact      text,
    status       text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'answered', 'closed')),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_inquiries_status ON customer_inquiries (status, created_at DESC);
