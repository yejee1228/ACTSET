-- 텍스트 역할 세분화(2026-10-12 사용자 지적): 꾸밈 낱말(KEYWORD, 예: MAGIC·SHADOW·LASER)과
-- 작은 글씨 정보(CREDIT, 주최·주관·문의·관람연령)를 INFO·COPY에서 나눈다 — 규격에 따라 빼거나 아주 작게 줄이기 위해.
ALTER TABLE design_elements DROP CONSTRAINT design_elements_role_check;
ALTER TABLE design_elements ADD CONSTRAINT design_elements_role_check CHECK (role IN (
    'BACKDROP','SUBJECT','DECOR','PHOTO','TITLE','COPY','KEYWORD','INFO','CREDIT','MARK','FRAME','NOISE'));
