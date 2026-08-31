# API 명세 (Stage 11)

> REST + JSON. Spring Boot가 API를 제공하고 React SPA가 호출한다.

## 공통 규약

- 기본 경로 `/api/v1`
- 인증은 Spring Security 세션. 미인증 요청은 `401`
- 타인의 프로젝트 접근은 존재를 숨기기 위해 `403`이 아니라 **`404`**로 응답한다
- 시각은 ISO 8601(UTC), 금액은 정수(원)
- 오류 형식
  ```json
  { "error": { "code": "INFO_INCOMPLETE", "message": "필수 4항목이 채워지지 않았습니다.",
               "details": { "missing": ["venue"] } } }
  ```

**생성 계열은 모두 비동기다.** 요청은 `202 Accepted`와 `job_id`를 돌려주고, 결과는 작업 조회로 받는다.

---

## 0. 인증·계정

### `POST /auth/signup`
가입. **약관 동의 이력을 함께 저장한다**(Stage 15). 필수 동의가 빠지면 `400`.

```json
// 요청
{ "email": "...", "password": "...",
  "agreements": { "terms": true, "privacy": true, "marketing": false },
  "terms_version": "2026-09-01" }
// 201
{ "id": "uuid", "email": "...", "credit_balance": 300 }
```

가입 시 초기 크레딧을 지급하고 `credit_transactions`에 `type='signup_grant'`로 기록한다(Stage 6).

### `POST /auth/login` · `POST /auth/logout`
세션 발급·파기. 로그인 실패는 계정 존재 여부를 노출하지 않도록 동일한 `401`로 응답한다.

### `GET /auth/me`
현재 로그인 계정. 미인증이면 `401`.

```json
{ "id": "uuid", "email": "...", "role": "user", "credit_balance": 1200 }
```

### `POST /auth/password-reset/request` · `POST /auth/password-reset/confirm`
재설정 메일 발송 → 토큰으로 확정. **토큰은 1회용·만료형**이며, 요청 엔드포인트는 계정 존재 여부와 무관하게 항상 `202`를 반환한다(계정 존재 확인 수단이 되지 않게 한다).

### `PATCH /account` · `POST /account/password`
프로필 수정, 비밀번호 변경(현재 비밀번호 확인 필요).

### `DELETE /account`
회원 탈퇴. `status='withdrawn'`으로 표시하고 유예기간 후 정리 배치가 개인 데이터를 삭제한다(Stage 10). `selection_events`는 `owner_id=null`로 익명화되어 남는다.

---

## 0-2. 관리자 (`role='admin'` 전용)

베타 운영이 관리자 크레딧 지급을 전제하므로(Stage 6) **MVP 범위에 포함된다.** 모든 엔드포인트는 관리자 role을 검사하고 **조작 내역을 감사 로그에 남긴다.**

| 엔드포인트 | 용도 |
|---|---|
| `GET /admin/accounts` | 계정 목록·검색, 잔액·가입일·상태 |
| `POST /admin/accounts/{id}/credits` | **크레딧 지급·회수** — `{ "amount": 1000, "reason": "베타 지급" }`. `credit_transactions`에 `type='admin_grant'`, `actor_id`와 함께 기록 |
| `GET /admin/jobs` | 실패·적체 작업 조회 |
| `POST /admin/jobs/{id}/retry` | 작업 재시도 |
| `GET /admin/projects/{id}` | 지원 문의 대응용 프로젝트 조회(읽기 전용) |
| `GET /admin/usage` | 크레딧 소비 분포·외부 API 누적 비용 |

---

## 1. 프로젝트

### `POST /projects`
① 화면 진입 시 draft 프로젝트를 만든다. 본문 없이 호출 가능하다.

```json
// 201
{ "id": "uuid", "status": "draft", "created_at": "..." }
```

### `GET /projects`
홈 대시보드 목록. `status='active'`만 반환한다.

`?q=검색어&sort=updated_at&cursor=...&limit=20`

```json
{ "items": [ { "id": "uuid", "main_title": "겨울 나그네", "genre": "클래식",
               "primary_date": "2026-03-14", "date_undetermined": false,
               "thumbnail_url": "...", "updated_at": "..." } ],
  "next_cursor": null }
```

### `GET /projects/{id}`
프로젝트 상세. `performance_info`, `design_assets`, 결과물 요약, 배지 상태를 함께 준다.

```json
{ "id": "uuid", "status": "active",
  "performance_info": { "main_title": "...", "sessions": [...], "venue": {...},
                        "cast": [...], "price_items": [...] },
  "design_assets": { "palette": ["#..."], "selected_variant_id": "uuid" },
  "flags": { "date_undetermined": false, "venue_undetermined": false,
             "stale_info_count": 3, "stale_design_count": 0 } }
```

`flags.stale_info_count` / `stale_design_count`는 "정보 변경됨"·"원본 변경됨" 배지와 "최신 반영" 버튼 노출을 결정한다. 프런트가 결과물을 전부 훑어 계산하지 않도록 서버가 세어 준다.

### `PATCH /projects/{id}/info`
①·②·6-1에서 공연정보를 저장한다. 부분 갱신을 허용한다.

```json
// 요청
{ "main_title": "겨울 나그네",
  "sessions": [ { "date": "2026-04-25", "time": "17:00", "is_undetermined": false } ],
  "venue": { "name": "롯데콘서트홀", "is_undetermined": false } }

// 200
{ "updated_at": "...",
  "poster_resync": { "job_id": "uuid" },
  "stale_info_count": 5 }
```

포스터는 `auto_sync_text=true`이므로 저장 즉시 텍스트 레이어 재합성 작업이 자동으로 등록된다. 응답의 `poster_resync.job_id`로 진행을 확인할 수 있다. 그 외 결과물은 자동 갱신하지 않고 `stale_info_count`만 올린다.

**신규 입력(①·②)의 자동저장도 이 엔드포인트를 쓴다.** 다만 draft 상태에서는 포스터가 없으므로 `poster_resync`가 비어 있다. 6-1(정보 수정)에서만 명시적 저장 버튼으로 호출한다(Stage 4 공통 원칙).

### `POST /projects/{id}/confirm`
④ 시안 확정. draft → active 전환.

```json
// 요청
{ "selected_candidate_id": "uuid" }

// 200
{ "status": "active", "poster_asset_id": "uuid", "confirmed_at": "..." }
```

선택된 시안후보를 `category='포스터'`로 승격하고 DesignAssets를 확정한다. 필수 4항목이 비어 있으면 `422 INFO_INCOMPLETE`.

### `POST /projects/{id}/duplicate`
프로젝트 복제. 공연정보·톤앤매너만 복사하고 결과물은 복사하지 않는다.

```json
// 201
{ "id": "새 uuid", "status": "active", "poster_job_id": "uuid" }
```

복제본은 일정이 미정으로 초기화되며, 포스터는 새 정보 기준으로 다시 렌더링하는 작업이 등록된다(Stage 2).

### `DELETE /projects/{id}`
소프트 삭제. `status='deleted'`로 바꾸고 30일 후 배치가 실제 삭제한다(Stage 10). 대시보드 목록에서 즉시 사라진다.

---

## 1-2. 파일 업로드

### `POST /projects/{id}/files`
②에서 공연사진·출연진 사진·로고·참고 이미지를 올린다. multipart/form-data.

```
file: (binary)
kind: performance_photo | cast_photo | logo | reference_image
```

```json
// 201
{ "id": "uuid", "kind": "cast_photo", "url": "서명URL",
  "width": 1200, "height": 1600, "bytes": 482000 }
```

- 업로드 시점에 검증을 수행한다(1-21): MIME·매직바이트·크기·확장자 화이트리스트, **재인코딩으로 EXIF(GPS 포함) 제거**
- `kind`에 따라 처리 경로가 갈린다 — **cast_photo·performance_photo·logo는 외부 AI API로 전송되지 않고** 자체 엔진이 레이어로 합성한다. reference_image만 VLM 스타일 분석에 전송된다(Stage 5·15). 화면의 안내 문구도 이 구분을 따른다(Stage 4)
- 검증 실패는 `422`(사유 포함), 용량 초과는 `413`

### `DELETE /files/{id}`
업로드 파일 삭제. 결과물의 object_map이 참조 중이면 `409`와 참조 목록을 반환한다 — 화면에서 "사용 중인 사진" 안내 후 교체를 유도한다.

---

## 2. 시안·홍보물 생성

### `POST /projects/{id}/drafts`
③ 시안 후보 생성. 재생성·다른 방향 보기도 같은 엔드포인트를 쓴다.

```json
// 요청
{ "mode": "initial",          // initial | regenerate | new_direction | more_like
  "count": 3,
  "reference_candidate_id": "uuid"   // more_like일 때만
}

// 202
{ "job_id": "uuid" }
```

### `POST /projects/{id}/recompose`
⑤→⑥ 규격 일괄변환. 포스터를 포함하면 원본 재생성으로 처리된다.

```json
// 요청
{ "format_codes": ["SNS_1X1", "STORY", "BANNER_WIDE"], "variants_per_format": 3 }

// 202
{ "job_id": "uuid",
  "children": [ { "job_id": "uuid", "format_code": "SNS_1X1" }, ... ] }
```

규격별 하위 작업으로 나뉘므로 일부가 실패해도 나머지는 살아남는다. `format_codes`에 `POSTER`가 포함되면 새 레코드를 만들지 않고 기존 포스터를 교체하며, DesignAssets가 갱신되어 다른 결과물이 `stale_design` 상태가 된다.

### `POST /projects/{id}/resync`
⑦ "최신 반영". 정보·원본이 어긋난 결과물을 다시 렌더링한다.

```json
// 요청
{ "scope": "all", "asset_ids": [] }   // scope: all | selected

// 202
{ "job_id": "uuid",
  "estimated": { "text_only": 4, "recompose": 2 } }
```

`text_only`는 텍스트 레이어만 다시 그리는 건이고 `recompose`는 원본이 바뀌어 재구성이 필요한 건이다. 크레딧 소비가 다르므로 화면에서 확인 모달로 안내한다(Stage 4·6).

---

## 3. 작업 상태

### `GET /jobs/{job_id}`

```json
{ "id": "uuid", "kind": "recompose", "status": "running",
  "progress": { "done": 2, "total": 3 },
  "children": [ { "job_id": "...", "format_code": "SNS_1X1", "status": "succeeded" },
                { "job_id": "...", "format_code": "BANNER_WIDE", "status": "failed",
                  "error": "배경 확장 실패" } ],
  "result": { "asset_ids": ["uuid", "uuid"] } }
```

폴링 간격은 2~3초를 권장한다. 실패한 하위 작업은 `POST /jobs/{job_id}/retry`로 개별 재시도한다.

---

## 4. 결과물

### `GET /projects/{id}/assets`
`?category=규격변환&include_deleted=false`

```json
{ "items": [ { "id": "uuid", "category": "규격변환", "format_code": "SNS_1X1",
               "label": "SNS 1:1", "width": 1080, "height": 1080,
               "preview_image_url": "서명URL",
               "image_url": "서명URL",
               "downloadable": true,
               "download_expires_at": "2026-06-12T00:00:00Z",
               "status": "선택됨",
               "stale": { "info": true, "design": false } } ] }
```

- `preview_image_url`은 화면 표시용 축소본이며 **항상 존재한다**
- `image_url`은 원본이며, 대용량 파일이 90일을 넘기면 `null`이 되고 `downloadable=false`가 된다. 이 경우 화면은 미리보기를 그대로 보여주되 다운로드 버튼을 비활성화한다
- 두 URL 모두 만료되는 서명 URL이므로 프런트에서 캐시하지 않는다

### `POST /assets/{id}/select`
⑥에서 규격별 안을 확정한다. 같은 규격의 다른 후보는 `보관`으로 바뀐다.

### `PATCH /assets/{id}/objects`
⑥ 편집 모드. object_map의 위치·크기만 수정한다.

```json
{ "object_map": { "title":      { "layer": "TITLE", "x": 120, "y": 880, "w": 900, "h": 140 },
                  "date":       { "layer": "INFO",  "x": 120, "y": 1050, "w": 900, "h": 60 },
                  "cast_photo_1": { "layer": "PHOTO", "x": 780, "y": 200, "w": 240, "h": 240,
                                    "source_file_id": "uuid", "mask": "circle" } } }
```

텍스트 내용 변경이나 요소 추가는 허용하지 않는다(Stage 4). **PHOTO 요소의 `source_file_id`·`mask`도 편집 대상이 아니다** — 위치·크기(x·y·w·h)만 수정할 수 있고, 사진 교체는 6-1 정보 수정 화면에서 한다. 서버에서 허용 필드를 검증하고, 저장 후 해당 결과물만 다시 합성한다.

### `DELETE /assets/{id}`
소프트 삭제. 30일 후 하드 삭제된다. `category='포스터'`는 삭제할 수 없다(`409`).

### `POST /projects/{id}/assets/download`
선택·일괄 다운로드.

```json
// 요청 { "asset_ids": ["uuid", "uuid"] }
// 202  { "job_id": "uuid" }   → 완료 시 result.zip_url
```

파일이 여러 개면 압축에 시간이 걸리므로 비동기로 처리한다.

---

## 5. 선택 로그

### `POST /projects/{id}/selection-events`

```json
{ "screen": "시안선택", "action": "regenerate",
  "shown_candidates": [ { "candidate_id": "uuid", "generation_params": {...} } ],
  "selected_candidate_id": null }
```

`202`로 즉시 응답하고 기록은 비동기로 처리한다. **로그 적재 실패가 사용자 동작을 막아서는 안 된다.**

다만 이 데이터가 핵심 자산이므로 유실을 방치하지 않는다. 프런트에서 전송 실패 시 재시도하고, 서버는 적재 실패를 별도 로그로 남긴다.

---

## 6. 규격·인쇄

### `GET /formats`
⑤ 화면 목록. DB 조회가 아니라 **서버 상수를 그대로 직렬화**해 내려준다.

```json
{ "items": [ { "code": "POSTER", "label": "포스터(세로)", "width": 1240, "height": 1754,
               "group": "원본", "ratio_bucket": "TALL" },
             { "code": "NOL_TICKET", "label": "NOL티켓 대표이미지", "width": 750, "height": 1000,
               "group": "예매처", "ratio_bucket": "TALL" } ] }
```

커스텀 규격이 열리면 이 목록에 없는 치수를 `recompose` 요청에 직접 담는다(`{"format_code":"CUSTOM","width":1200,"height":400}`). 규칙은 `ratio_bucket`으로 찾아 적용한다(Stage 12).

### `POST /projects/{id}/print-renders`
⑧에서 지정한 인쇄 크기(mm)·dpi로 레이어 스택을 재합성한다(5-3). 비주얼 레이어는 업스케일, 텍스트는 재렌더링, **PHOTO는 업로드 원본을 직접 재배치**한다(외부 업스케일러에 보내지 않는다 — Stage 5).

```json
// 요청
{ "asset_id": "uuid", "width_mm": 297, "height_mm": 420, "dpi": 300 }

// 202
{ "job_id": "uuid" }
```

해상도가 부족하면 작업을 막지 않고 응답에 경고를 포함한다(5-2). 인쇄용 PDF(CMYK·재단여백) 변환은 Phase 6이며, MVP는 고해상도 이미지까지 생성한다.

### `POST /projects/{id}/print-drafts` · `PATCH /print-drafts/{id}`
인쇄 초안 저장과 예상금액 갱신.

```json
// 응답
{ "id": "uuid", "estimated_price": 84000,
  "warnings": [ { "code": "LOW_RESOLUTION",
                  "message": "원본 해상도가 낮아 인쇄 시 화질이 떨어질 수 있습니다." },
                { "code": "SCHEDULE_UNDETERMINED",
                  "message": "일정이 아직 미정입니다." } ] }
```

주문 접수 엔드포인트는 MVP에 두지 않는다. 화면의 주문 버튼이 비활성이므로 서버에도 경로를 만들지 않는다.

---

## 6-2. 크레딧

### `GET /credits`
```json
{ "balance": 1200,
  "recent": [ { "type": "consume", "amount": -60, "balance_after": 1200,
                "description": "규격 일괄변환 3종", "created_at": "..." } ] }
```

### `GET /credits/estimate`
생성 전에 소비량을 미리 알려준다. 화면의 확인 모달이 이 값을 쓴다.

`?kind=recompose&format_codes=SNS_1X1,STORY&variants=3`

```json
{ "estimated_cost": 60, "balance": 1200, "sufficient": true }
```

### `POST /credits/purchase`
충전. 결제 연동은 Phase 6이므로 **MVP에는 이 엔드포인트를 만들지 않는다.** 베타 기간의 크레딧 공급은 `POST /admin/accounts/{id}/credits`(위 0-2)가 담당한다.

**잔액 부족 처리**: 생성 요청 시 잔액이 모자라면 작업을 등록하지 않고 `402 INSUFFICIENT_CREDITS`를 반환한다.

```json
{ "error": { "code": "INSUFFICIENT_CREDITS",
             "message": "크레딧이 부족합니다.",
             "details": { "required": 60, "balance": 20 } } }
```

크레딧은 **작업 등록 시점에 차감하고, 작업이 실패하면 환불**한다. 생성 전에 차감하지 않으면 동시 요청으로 잔액이 음수가 될 수 있고, 성공 후에 차감하면 무료로 생성할 여지가 생긴다.

---

## 7. 워커 내부 처리

워커는 같은 Spring Boot 애플리케이션의 `worker` 프로필로 동작하므로 **내부 HTTP API가 필요 없다.** 서비스 계층을 직접 호출한다.

| 처리 | 구현 |
|---|---|
| 작업 획득 | `JobRepository`에서 `FOR UPDATE SKIP LOCKED` 네이티브 쿼리 |
| 진행률·상태 갱신 | 같은 트랜잭션 안에서 엔티티 갱신 |
| 결과물 등록 | `GeneratedAssetService` 직접 호출 |
| 학습 결과 적재 | `LayoutSampleService` 직접 호출 |

초판(웹·워커 분리)에서 필요했던 내부 API 계층이 단일 애플리케이션 구조에서는 사라진다. 도메인 모델과 렌더링 코드를 그대로 공유하므로 직렬화와 중복 정의도 없다.

**주의**: 워커는 사용자 세션 없이 동작한다. 작업을 등록하는 시점(웹 요청 처리 중)에 권한 검증을 마쳐야 하며, 워커가 처리 중에 권한을 다시 확인하지 않는다.

---

## 정리 — 화면과 엔드포인트 대응

| 화면 | 호출 |
|---|---|
| 0-A 소개 페이지 | (인증 불필요) |
| 가입·로그인 | `POST /auth/signup` · `POST /auth/login` · `POST /auth/password-reset/*` |
| 계정 설정 | `GET /auth/me` · `PATCH /account` · `POST /account/password` · `DELETE /account` |
| 0-B 홈 대시보드 | `GET /projects` · `POST /projects/{id}/duplicate` · `DELETE /projects/{id}` |
| 관리자 백오피스 | `GET /admin/*` · `POST /admin/accounts/{id}/credits` |
| ① 정보 입력 | `POST /projects` → `PATCH /projects/{id}/info` |
| ② 추가정보 | `PATCH /projects/{id}/info` (자동저장) · `POST /projects/{id}/files` |
| ③ 시안 선택 | `POST /projects/{id}/drafts` → `GET /jobs/{id}` → `POST .../selection-events` |
| ④ 확정 | `POST /projects/{id}/confirm` |
| ⑤ 규격 선택 | `GET /formats` |
| ⑥ 일괄변환 | `POST /projects/{id}/recompose` → `GET /jobs/{id}` → `POST /assets/{id}/select` |
| ⑦ 대시보드 | `GET /projects/{id}` · `GET /projects/{id}/assets` · `POST .../resync` |
| 6-1 정보 수정 | `PATCH /projects/{id}/info` (명시적 저장) |
| ⑧ 인쇄 | `POST /projects/{id}/print-renders` → `GET /jobs/{id}` · `POST /projects/{id}/print-drafts` |

## 다음 단계

Stage 12: 규격 프리셋 — 시드 데이터와 룰 스키마를 정의한다.
