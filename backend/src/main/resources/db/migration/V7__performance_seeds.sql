-- E7 7-7 KOPIS 연동 (Stage 17·docs/10 8-2 예고): owner_id 없는 시드 데이터.
-- projects와 구조가 유사하지만 소유자가 없는 공개 콘텐츠라 별도 테이블로 둔다.

CREATE TABLE performance_seeds (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source       text NOT NULL DEFAULT 'kopis',
    external_id  text NOT NULL,              -- KOPIS mt20id 등 벤더 측 공연 ID
    main_title   text NOT NULL,
    genre        text,
    primary_date date,
    venue_name   text,
    poster_url   text,
    fetched_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

CREATE INDEX idx_performance_seeds_gallery ON performance_seeds (genre, primary_date DESC);
