-- 규격변환 파이프라인(2026-10-07) — 포스터에서 분리·생성한 요소 1개 = 1행.
-- 파일은 accounts/{account_id}/projects/{project_id}/elements/{id}.png 에 둔다(사용자 지시: 고객ID+프로젝트ID 폴더).
-- 요소는 프로젝트 전체가 공유하는 디자인 자산이다(docs/05 "분해는 1회") — 규격 변환은 이 행들을 재배치만 한다.
CREATE TABLE design_elements (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    project_id     uuid NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    role           text NOT NULL CHECK (role IN ('BACKDROP','SUBJECT','DECOR','PHOTO','TITLE','COPY','INFO','MARK','NOISE')),
    -- decomposed: 분해 레이어 / split: 레이어를 다시 쪼갠 요소 / backdrop_residual: 배경에 박혀 있다 떼어낸 요소
    -- regenerated: LLM 편집으로 다시 만든 배경 / rendered: 못 뗀 텍스트를 자체 렌더링
    origin         text NOT NULL CHECK (origin IN ('decomposed','split','backdrop_residual','regenerated','rendered')),
    label          text,
    storage_path   text NOT NULL,
    -- 원본 포스터 좌표계의 bbox(px)
    x              integer NOT NULL,
    y              integer NOT NULL,
    width          integer NOT NULL,
    height         integer NOT NULL,
    source_width   integer NOT NULL,
    source_height  integer NOT NULL,
    z_order        integer NOT NULL,
    meta           jsonb NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_design_elements_project ON design_elements (project_id, z_order);
CREATE INDEX idx_design_elements_account ON design_elements (account_id);
