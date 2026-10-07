-- 테두리(FRAME) 역할 추가 — 규격변환 5차 실행(sandmagicshow)에서 금색 테두리가 배경 조각으로 판정돼 가로형에 세로줄로 남았다.
ALTER TABLE design_elements DROP CONSTRAINT design_elements_role_check;
ALTER TABLE design_elements ADD CONSTRAINT design_elements_role_check CHECK (role IN (
    'BACKDROP','SUBJECT','DECOR','PHOTO','TITLE','COPY','INFO','MARK','FRAME','NOISE'));
