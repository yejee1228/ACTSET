-- Gallery KOPIS 시드 확장(E7 7-7): 목록·상세 API 항목을 담는다. 공연이 끝난 시드는 Gallery에서 뺀다(end_date).
-- genre는 앱 장르 버튼 값(클래식·무용·연극·뮤지컬·대중음악 등)으로 바꾼 값, kopis_genre는 KOPIS 원문.

ALTER TABLE performance_seeds
    ADD COLUMN end_date       date,
    ADD COLUMN kopis_genre    text,
    ADD COLUMN area           text,
    ADD COLUMN state          text,              -- 공연예정 | 공연중 | 공연완료 (KOPIS 원문)
    ADD COLUMN kid_friendly   boolean NOT NULL DEFAULT false,
    ADD COLUMN detail         jsonb,             -- 출연진·가격·소개 이미지·예매처 등(KopisDetail)
    ADD COLUMN detail_fetched_at timestamptz,
    -- KOPIS 포스터 원본은 장당 수 MB라 목록용 축소본(긴 변 480px)을 저장소에 만들어 둔다
    ADD COLUMN thumbnail_path   text,
    ADD COLUMN thumbnail_source text;            -- 축소본을 만든 원본 포스터 URL(바뀌면 다시 만든다)

CREATE INDEX idx_performance_seeds_active ON performance_seeds (end_date, primary_date);
