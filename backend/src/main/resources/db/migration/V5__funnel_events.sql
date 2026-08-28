-- E6 베타 운영 준비: 6-7 방문→가입→①~⑥→다운로드 단계별 퍼널.
-- SelectionEvent(선택 행동)와는 별개 구조(docs/13).
CREATE TABLE funnel_events (
    id           bigserial PRIMARY KEY,
    session_id   text NOT NULL,
    account_id   uuid REFERENCES accounts(id) ON DELETE SET NULL,
    step         text NOT NULL,
    utm_source   text,
    utm_medium   text,
    utm_campaign text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_funnel_step ON funnel_events (step, created_at);
CREATE INDEX idx_funnel_session ON funnel_events (session_id, created_at);
