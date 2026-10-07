-- 규격변환(포스터 업로드형) 작업 종류 추가. docs/10 jobs.kind 목록에도 반영.
ALTER TABLE jobs DROP CONSTRAINT jobs_kind_check;
ALTER TABLE jobs ADD CONSTRAINT jobs_kind_check CHECK (kind IN (
    'draft_generate','decompose_layers','recompose','resync','render_print','zip_download','analyze_poster',
    'format_convert'));
