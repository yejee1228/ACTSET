-- 작업 진행 단계(진행 바용). {"step":2,"total":5,"label":"...","from":10,"to":55,"expected_sec":100,"started_at":"..."}
-- 외부 API 대기 중의 실제 진척은 알 수 없어서, 화면은 단계 구간 안에서 expected_sec 기준으로 추정해 채운다.
ALTER TABLE jobs ADD COLUMN progress jsonb;
