-- Stage 10 DB 스키마 초기 적재
-- 순서: accounts -> projects -> generated_assets -> uploaded_files -> jobs
--       -> credit_transactions -> selection_events -> print_order_drafts -> layout_samples
-- (jobs가 credit_transactions보다 먼저 와야 함 — credit_transactions.job_id가 jobs를 참조)

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. accounts ----------------------------------------------------------
CREATE TABLE accounts (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email                 text UNIQUE NOT NULL,
    password_hash         text,
    display_name          text,
    role                  text NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')),
    status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active','withdrawn')),
    email_verified_at     timestamptz,
    deleted_at            timestamptz,
    last_login_at         timestamptz,
    terms_agreed_at       timestamptz,
    privacy_agreed_at     timestamptz,
    terms_version         text,
    privacy_version       text,
    marketing_agreed_at   timestamptz,
    credit_balance        integer NOT NULL DEFAULT 0 CHECK (credit_balance >= 0),
    created_at            timestamptz NOT NULL DEFAULT now()
);

-- 2. projects ------------------------------------------------------------
CREATE TABLE projects (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            uuid NOT NULL REFERENCES accounts(id),
    status              text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active','deleted')),
    deleted_at          timestamptz,
    main_title          text NOT NULL DEFAULT '',
    genre               text CHECK (genre IS NULL OR genre IN ('클래식','무용','연극','뮤지컬','어린이공연','인디밴드','대중음악')),
    primary_date        date,
    date_undetermined   boolean NOT NULL DEFAULT false,
    venue_undetermined  boolean NOT NULL DEFAULT false,
    performance_info    jsonb NOT NULL DEFAULT '{}',
    design_assets       jsonb,
    design_updated_at   timestamptz,
    info_updated_at     timestamptz,
    confirmed_at        timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_owner_active ON projects (owner_id, updated_at DESC)
  WHERE status = 'active';
CREATE INDEX idx_projects_draft_cleanup ON projects (created_at)
  WHERE status = 'draft';
CREATE INDEX idx_projects_title_trgm ON projects USING gin (main_title gin_trgm_ops);
CREATE INDEX idx_projects_owner ON projects (owner_id);

-- 4. generated_assets ------------------------------------------------------
CREATE TABLE generated_assets (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          uuid NOT NULL REFERENCES projects(id),
    category            text NOT NULL CHECK (category IN ('시안후보','포스터','규격변환','추가제작물')),
    format_code         text NOT NULL,
    width               integer NOT NULL,
    height              integer NOT NULL,
    variant_index       smallint,
    base_image_url      text,
    image_url           text,
    preview_image_url   text,
    object_map          jsonb,
    generation_params   jsonb,
    auto_sync_text      boolean NOT NULL DEFAULT false,
    status              text NOT NULL CHECK (status IN ('제안됨','선택됨','보관','삭제됨')),
    info_synced_at      timestamptz,
    design_synced_at    timestamptz,
    deleted_at          timestamptz,
    file_size           bigint,
    download_expires_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_project_poster ON generated_assets (project_id)
  WHERE category = '포스터' AND deleted_at IS NULL;
CREATE INDEX idx_assets_project ON generated_assets (project_id, category, created_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_assets_purge ON generated_assets (deleted_at)
  WHERE deleted_at IS NOT NULL;

-- 9. uploaded_files --------------------------------------------------------
CREATE TABLE uploaded_files (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid NOT NULL REFERENCES projects(id),
    kind          text NOT NULL CHECK (kind IN ('performance_photo','cast_photo','logo','reference_image')),
    storage_path  text NOT NULL,
    mime_type     text,
    file_size     bigint,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_uploaded_files_project ON uploaded_files (project_id);

-- 7. jobs --------------------------------------------------------------
CREATE TABLE jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      uuid REFERENCES projects(id),
    kind            text NOT NULL CHECK (kind IN ('draft_generate','decompose_layers','recompose','resync','render_print','zip_download','analyze_poster')),
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','succeeded','failed','canceled')),
    payload         jsonb,
    result          jsonb,
    error           text,
    attempts        smallint NOT NULL DEFAULT 0,
    parent_job_id   uuid REFERENCES jobs(id),
    locked_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_queue ON jobs (created_at) WHERE status = 'pending';
CREATE INDEX idx_jobs_project ON jobs (project_id);
CREATE INDEX idx_jobs_parent ON jobs (parent_job_id);

-- 6-2. credit_transactions -------------------------------------------------
CREATE TABLE credit_transactions (
    id             bigserial PRIMARY KEY,
    account_id     uuid NOT NULL REFERENCES accounts(id),
    type           text NOT NULL CHECK (type IN ('signup_grant','purchase','consume','refund','admin_grant')),
    amount         integer NOT NULL CHECK (amount <> 0),
    balance_after  integer NOT NULL,
    job_id         uuid REFERENCES jobs(id),
    actor_id       uuid REFERENCES accounts(id),
    description    text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_account ON credit_transactions (account_id, created_at DESC);
CREATE UNIQUE INDEX uq_credit_consume_job ON credit_transactions (job_id)
  WHERE type = 'consume';

-- 5. selection_events -------------------------------------------------
CREATE TABLE selection_events (
    id                      bigserial PRIMARY KEY,
    project_id              uuid REFERENCES projects(id) ON DELETE SET NULL,
    owner_id                uuid REFERENCES accounts(id) ON DELETE SET NULL,
    screen                  text NOT NULL CHECK (screen IN ('시안선택','규격변환')),
    action                  text NOT NULL CHECK (action IN ('select','view_more_direction','regenerate','more_like_this')),
    format_code             text,
    genre                   text,
    shown_candidates        jsonb NOT NULL,
    selected_candidate_id   uuid,
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sel_analysis ON selection_events (genre, screen, action, created_at);
CREATE INDEX idx_sel_format ON selection_events (format_code, action) WHERE format_code IS NOT NULL;
CREATE INDEX idx_sel_project ON selection_events (project_id, created_at);

-- 8. print_order_drafts ----------------------------------------------------
CREATE TABLE print_order_drafts (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id            uuid NOT NULL REFERENCES projects(id),
    generated_asset_id    uuid REFERENCES generated_assets(id),
    print_spec            jsonb,
    shipping_address      jsonb,
    estimated_price       integer,
    status                text NOT NULL DEFAULT 'draft_only',
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_print_drafts_project ON print_order_drafts (project_id);

-- 6. layout_samples ------------------------------------------------------
CREATE TABLE layout_samples (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source         text NOT NULL CHECK (source IN ('collected','user_upload','user_generated')),
    genre          text,
    aspect_ratio   numeric(6,4) NOT NULL,
    elements       jsonb NOT NULL,
    palette        text[],
    margin_ratio   jsonb,
    present_roles  text[],
    confidence     numeric(3,2),
    source_ref     text,
    analyzed_at    timestamptz
);

CREATE INDEX idx_layout_genre ON layout_samples (genre, confidence DESC) WHERE genre IS NOT NULL;
CREATE INDEX idx_layout_roles ON layout_samples USING gin (present_roles);
