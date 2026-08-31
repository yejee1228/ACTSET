-- 1-20 관리자 백오피스: 모든 조작을 감사 로그에 남긴다

CREATE TABLE admin_audit_log (
    id           bigserial PRIMARY KEY,
    actor_id     uuid NOT NULL REFERENCES accounts(id),
    action       text NOT NULL,
    target_type  text NOT NULL,
    target_id    text,
    details      jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_actor ON admin_audit_log (actor_id, created_at DESC);
CREATE INDEX idx_admin_audit_target ON admin_audit_log (target_type, target_id);
