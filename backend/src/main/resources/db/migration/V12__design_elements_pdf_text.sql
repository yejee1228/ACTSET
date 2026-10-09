-- 포스터 PDF에서 그대로 읽은 텍스트 줄(문구·폰트·크기·색) — 추정 없이 만든 텍스트 요소
ALTER TABLE design_elements DROP CONSTRAINT design_elements_origin_check;
ALTER TABLE design_elements ADD CONSTRAINT design_elements_origin_check CHECK (origin IN (
    'decomposed','split','backdrop_residual','regenerated','rendered','pdf_text'));
