-- E7 Discover 최소 구현 (Stage 17): Gallery 공개 전환, 후보함, 구독 스키마 훅.

ALTER TABLE accounts
    ADD COLUMN plan text NOT NULL DEFAULT 'free' CHECK (plan IN ('free', 'subscriber'));

ALTER TABLE projects
    ADD COLUMN visibility text NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'public')),
    ADD COLUMN published_at timestamptz;

CREATE INDEX idx_projects_gallery ON projects (genre, published_at DESC)
    WHERE visibility = 'public' AND status = 'active';

ALTER TABLE generated_assets
    ADD COLUMN is_favorited boolean NOT NULL DEFAULT false;

CREATE INDEX idx_assets_favorited ON generated_assets (project_id)
    WHERE is_favorited = true AND deleted_at IS NULL;
