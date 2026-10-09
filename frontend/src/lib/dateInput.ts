/**
 * 날짜 입력(<input type="date">) 범위. max가 없으면 크롬은 연도 칸에 6자리까지 받아서 "2026"을 쳐도 월로 넘어가지 않는다.
 * 연도를 4자리로 막아 4자리 입력 즉시 월로 넘어가게 한다.
 */
export const DATE_INPUT_MIN = '1900-01-01';
export const DATE_INPUT_MAX = '9999-12-31';
