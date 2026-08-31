# 공연정보 Role Schema 설계 (Stage 2)

## 목적

사업계획서 S-2("공연정보를 역할별 데이터로 분리")를 실제 구현 가능한 필드·타입 수준으로 구체화한다. 이 스키마가 확정되어야 Stage 3(화면별로 어떤 필드를 노출할지)과 Stage 5(장르별로 어떤 필드를 강조·생략할지)를 설계할 수 있다.

## 설계 원칙

1. **스키마는 장르 공통 구조**로 둔다. 장르별로 필드 구조 자체를 다르게 만들지 않고, 7개 장르가 공유하는 필드 전체 집합을 정의한다.
2. **장르별 강조·생략·필수 여부는 이 문서가 아니라 Stage 5(AI 엔진 기술기획 내 Genre Rules)**에서 다룬다. 여기서는 "어떤 필드가 존재하는가"만 정의한다.
3. 정보는 **이미지 픽셀이 아닌 별도 데이터 객체**로 저장한다. 공연명을 포함한 **모든 텍스트는 생성 이미지에 녹이지 않고 별도 레이어로 얹는다**(Stage 5의 레이어 스택 구조). 포스터는 정보 수정 시 텍스트가 자동 반영되고, 그 외는 사용자가 "최신 반영"을 눌러야 반영된다.

## 최상위 구조

```
Project (status: draft | active | deleted)
 ├─ PerformanceInfo   (공연정보 — 1:1, 수정 가능한 "현재" 값)
 ├─ DesignAssets       (디자인 자산 — 시안 확정 후 고정되는 톤앤매너)
 ├─ GeneratedAsset[]   (생성 결과물 — 시안후보·포스터·규격변환 등)
 └─ SelectionEvent[]   (선택 행동 로그)
```

프로젝트 하나 = 공연 하나. PerformanceInfo는 언제든 수정 가능한 단일 레코드이고, GeneratedAsset은 규격·용도별로 하나씩 존재하는 **현재 상태**다(정보가 바뀌면 같은 레코드의 이미지를 교체하며, 버전 이력을 쌓지 않는다 — 아래 "정보 변경 반영 방식" 참고).

## Project 필드 정의 및 생성 시점

| 필드명 | 타입 | 설명 |
|---|---|---|
| project_id | string | 프로젝트 ID |
| owner_id | string | 계정 참조 |
| status | enum | draft(시안 확정 전) / active(확정 완료) / deleted(소프트 삭제) |
| created_at | datetime | 생성 시각 |
| confirmed_at | datetime(선택) | draft → active 전환 시각(④ 시안 확정 시점) |
| info_updated_at | datetime | PerformanceInfo 최종 수정 시각 — "정보 변경됨" 판정 기준 |
| design_updated_at | datetime | DesignAssets·레이어 최종 갱신 시각 — "원본 변경됨" 판정 기준 |
| deleted_at | datetime(선택) | 프로젝트 소프트 삭제 시각. 30일 후 정리 배치가 하드 삭제(Stage 10) |

**생성 시점(중요)**: Project 레코드는 **① 공연정보 입력 화면에 진입하는 순간 `status='draft'`로 즉시 생성**한다. 시안 확정(④)은 프로젝트를 "만드는" 단계가 아니라 `draft → active`로 **확정하는** 단계다.

이렇게 설계한 이유는 다음과 같다.

- ③ 시안 선택은 원래 프로젝트 생성보다 먼저 일어나므로, 프로젝트를 ④에서 만들면 SelectionEvent·GeneratedAsset이 소속될 `project_id`가 없다. 핵심 데이터 자산인 선택 로그가 기록될 곳이 사라진다.
- ①·② 입력 내용을 자동저장하려면 저장 대상 레코드가 먼저 존재해야 한다.
- draft 상태부터 project_id가 존재하므로 모든 하위 엔티티가 예외 없이 동일한 구조를 갖는다.

**미확정 draft 정리**: 생성 후 30일간 `active`로 전환되지 않은 draft 프로젝트는 자동 삭제한다(입력하다 이탈한 흔적이 계정에 쌓이지 않도록). 홈 대시보드 목록에는 `active` 프로젝트만 노출한다.

## PerformanceInfo 필드 정의

| Role | 필드명 | 타입 | Phase1 필수 | 설명 |
|---|---|---|---|---|
| Main Title | main_title | string | 필수 | 공연명 |
| Subtitle | subtitle | string | 선택 | 부제 |
| Genre | genre | enum(7종) | 필수 | 클래식·무용·연극·뮤지컬·어린이공연·인디밴드·대중음악 |
| Date+Time | sessions | array | 필수(최소 1개) | 회차별 {날짜, 시간, 회차 라벨, 미정여부} — 아래 하위 스키마 참조 |
| Running Time | running_time | string | 선택 | 러닝타임(예: "90분") |
| Venue | venue | object | 필수 | {장소명, 주소?, 지도링크?, 미정여부} — 아래 하위 스키마 참조 |
| Cast | cast | array | 선택 | 출연진 목록 — 아래 하위 스키마 참조 |
| Price | price_items | array | 선택 | 좌석등급·시점별 가격·조건부 할인을 모두 포괄하는 가격 항목 목록 — 아래 하위 스키마 참조 |
| Age | age | string | 선택 | 관람연령 |
| Organizer/Host | organizer_group | object | 선택 | {주최[], 주관[], 후원[]} — 3개 배열로 분리 |
| Inquiry | inquiry | object | 선택 | {전화?, 이메일?, SNS?} |
| Ticket Information(예매처) | ticket_channels | array | 선택 | {예매처명, URL}[] |
| — | introduction | string | 선택 | 한 줄 소개 |
| — | synopsis | string | 선택 | 시놉시스(긴 소개) |
| — | program | array | 선택 | {순서, 제목, 설명} — 레퍼토리/프로그램 순서 |
| Logo | logo | image | 선택 | 로고 이미지 |
| — | performance_photos | image[] | 선택 | 공연 사진 — **외부 AI API로 전송하지 않고 자체 엔진이 PHOTO 레이어로 합성**(Stage 5) |
| — | reference_images | image[] | 선택 | 참고 이미지 |
| — | brand_colors | string[](hex) | 선택 | 색상 |
| — | mandatory_notices | string[] | 선택 | 포스터·홍보물에 반드시 포함되어야 하는 문구(저작권 표기, 특정 후원사 문구, 법적 고지 등). 규격별 정보 생략 대상에서 제외된다 — Stage 5 우선순위 규칙 참고 |
| — | image_direction_note | string | 선택 | 원하는 이미지 방향에 대한 자유 서술(색감·분위기·구도 등). 필수정보 중 일부를 이 시안에는 넣지 말아달라는 지시도 이 필드에 작성 가능하며, 이 지시는 시스템 기본 규칙보다 우선한다 — Stage 5 우선순위 규칙 참고 |

## 하위 객체 스키마

**sessions (회차)**
```
{ session_id, date?, time?, label(예: "1회차"), note?, is_undetermined?: boolean }[]
```
날짜가 아직 정해지지 않은 공연은 `is_undetermined=true`인 세션 1건만 두고 date/time은 비워둔다. 화면에는 "일정 미정"으로 표시한다.
포스터 등에는 보통 "기간 요약"만 표시하고 상세페이지에서만 회차 전체를 나열하는 경우가 많다. 이 요약 표시 규칙은 스키마가 아니라 Stage 5(Format Rules)에서 정의하고, 스키마 자체는 전체 회차 데이터를 다 담는 구조로 둔다.

**venue (장소)**
```
{ name?, address?, map_url?, is_undetermined?: boolean }
```
장소가 아직 정해지지 않은 공연은 `is_undetermined=true`로 두고 name은 비워둔다. 화면에는 "장소 미정"으로 표시한다.

**미정 상태의 처리 원칙**
- 실제 홍보물에는 빈칸이 아니라 정해진 대체 문구(예: "일정 추후 공지", "장소 추후 공지")를 넣어 렌더링한다. 정확한 문구는 Stage 5에서 확정한다.
- 프로젝트 생성 이후에도 장소·일정은 언제든 변경 가능하며, 미정으로 시작했더라도 이후 확정 값으로 바꿀 수 있다.
- 미정 상태로 남아있는 채로 인쇄 페이지(⑧)에 진입하면 경고를 표시한다(Stage 4 참고). 다른 화면·기능은 미정 상태에서도 계속 이용 가능하다.

**cast (출연진)**
```
{ cast_id, name, part/역할, photo_file_id?(uploaded_files 참조), career?, order }[]
```
`career`(경력)는 선택 필드다. 클래식 장르는 포스터에 출연진 경력을 함께 싣는 경우가 많아 추가했다 — 장르별로 이 필드를 얼마나 강조·기본노출할지는 Stage 5(Genre Rules)에서 다룬다.

**price_items (가격)**
```
{
  item_id,
  category: enum(seat_tier | period_price | discount),
  label(예: "R석" / "조기예매" / "가족 3인 이상 할인" / "복지할인"),
  price,
  condition_note?(자유서술, 예: "9/30까지", "3인 이상 동반 구매 시", "국가유공자·기초생활수급자 대상"),
  valid_from?, valid_until?(period_price에서 사용, 예: 조기예매 마감일),
  order
}[]
```
- `seat_tier`: 좌석 등급별 가격(R석/S석/전석 등)
- `period_price`: 조기예매/일반/현장가처럼 시점에 따라 달라지는 가격
- `discount`: 가족할인·복지할인·단체할인 등 조건부 할인 — 조건은 `condition_note`에 자유서술로 담고, 실제 자격 검증·자동계산은 하지 않는다(본 서비스는 홍보물 표기가 목적이며 실제 발권·결제는 범위 밖)

단일가만 있는 공연은 `seat_tier` 항목 1개만 넣으면 되므로 등급·시점·할인 유무와 무관하게 동일 구조로 대응 가능하다.

**organizer_group**
```
{ presenter(주최)[], organizer(주관)[], sponsor(후원)[] }
```

## DesignAssets 필드 정의

시안이 확정된 이후 프로젝트에 고정되는 "톤앤매너"로, 이후 모든 규격변환·추가제작물이 이 값을 기준으로 일관성을 유지한다.

| 필드명 | 타입 | 설명 |
|---|---|---|
| key_visual_image | image | 확정된 키비주얼(합성 완성본) |
| visual_layers | array | **분해된 비주얼 레이어 스택**. ④ 확정 시 1회 분해해 저장하고 모든 규격 변환이 공유한다 — 아래 하위 스키마 참조 |
| title_mode | enum | font(모드 A, 기본) / ai_typography(모드 B). Stage 5 참고 |
| title_layer_image | image(선택) | 모드 B일 때 생성된 타이틀 RGBA 이미지 |
| palette | string[](hex) | 확정된 색상 팔레트 |
| representative_photo | image | 대표 이미지 |
| title_typography_style | object | 제목 폰트·스타일 기준 |
| selected_variant_id | string | 현재 원본으로 채택된 시안 후보 참조 |
| (갱신 시각) | — | DesignAssets 자체에는 타임스탬프를 두지 않는다. 갱신 시각은 **Project.design_updated_at** 컬럼으로 관리한다(Stage 10) |

**visual_layers (분해된 비주얼 레이어)**
```
{ layer_id,
  type: enum(BACKDROP | SUBJECT | DECOR),
  image_url,          // RGBA PNG
  bbox,               // 원본 캔버스 기준 위치·크기
  z_order,
  removable?: boolean // 소규격에서 생략 가능한지(DECOR만)
}[]
```
`BACKDROP`은 반드시 1개이며 규격 확장 시 아웃페인팅 대상이 된다. `SUBJECT`는 잘라내지 않고 이동·스케일로 처리한다.

`PHOTO`(업로드 사진)는 여기 없다 — 분해 산물이 아니라 uploaded_files를 원본으로 참조하는 레이어이며, 배치 정보는 각 결과물의 object_map에 담긴다(Stage 5).

레이어를 결과물이 아니라 DesignAssets에 둔 이유는 **모든 규격이 같은 레이어를 공유하기 때문**이다. 규격마다 따로 분해하면 비용도 크고 규격 간 일관성이 깨진다(Stage 5).

## 규격 정의 — 테이블이 아니라 상수

기본 제공 규격 13종(원본 1 + 변환 12)은 **코드 상수(enum)**로 둔다. 별도 테이블을 만들지 않는다.

```
FormatPreset(code, label, width, height, group)
  POSTER,
  NOL_TICKET, TICKETLINK, PLAY_TICKET,
  SNS_1X1, SNS_4X5, STORY, WEB_THUMB, WEB_BANNER, DID, HALL_MONITOR,
  BANNER_WIDE, X_BANNER
```

**테이블을 두지 않는 이유**

- 기본 규격은 개수가 고정되어 있고 사용자가 늘리지 않는다. 테이블로 두면 시드 마이그레이션과 조인만 늘어난다
- 커스텀 규격은 고객마다 제각각이라 재사용되지 않는다. 한 번 쓰고 마는 치수를 레코드로 쌓으면 **아무도 참조하지 않는 행만 무한히 늘어난다**
- 상수로 두면 Java에서 타입 안전하게 다루고 오타를 컴파일 시점에 잡는다

**대신 치수는 결과물이 직접 들고 있는다.** 아래 GeneratedAsset의 `format_code`·`width`·`height`를 참조한다. 커스텀 규격이 열려도 스키마 변경 없이 `format_code='CUSTOM'`에 실제 치수만 담으면 된다.

분석에 필요한 것은 규격 레코드가 아니라 **안정적인 코드 문자열**이므로, `format_code`만으로 장르·규격별 집계가 가능하다.

## GeneratedAsset 필드 정의

| 필드명 | 타입 | 설명 |
|---|---|---|
| id | string | 생성물 ID |
| project_id | string | 소속 프로젝트 |
| category | enum | 시안후보 / 포스터(원본, 프로젝트당 1건) / 규격변환 / 추가제작물(Phase3+) |
| format_code | string | 규격 코드. 기본 규격은 상수명(`SNS_1X1` 등), 커스텀은 `CUSTOM` |
| width / height | int | 실제 치수. 커스텀 규격도 스키마 변경 없이 담긴다 |
| variant_index | int | 복수안 중 몇 번째 |
| image_url | string | 결과 이미지(원본 해상도) 위치 |
| preview_image_url | string | UI 표시용 축소본(장변 800px 내외). **원본이 만료되어도 남는다** |
| object_map | object | TITLE·INFO·PHOTO·MARK 레이어 요소별 배치 정보. 구조는 `{ role: {layer, x, y, w, h, font_px?, bold?, effect?, source_file_id?, mask?} }` — role을 키로 하는 맵. `source_file_id`·`mask`는 PHOTO 요소 전용(uploaded_files 참조, 액자 마스크 모양)이며 편집 모드의 수정 대상이 아니다. 시스템이 직접 배치한 값이므로 정확하며, 최소 편집·규격 재배치·인쇄 재렌더링의 기반이 된다(Stage 5 참고) |
| base_image_url | string | 생성된 비주얼 원본 위치. **글자가 전혀 없는 순수 비주얼이어야 한다**. 정보만 바뀔 때는 이 파일을 재사용하고 텍스트 레이어만 다시 합성한다 |
| generation_params | object | 생성에 쓰인 파라미터(프롬프트 방향·팔레트·배치 스타일). SelectionEvent 분석의 핵심 재료(Stage 10) |
| auto_sync_text | boolean | true면 PerformanceInfo 변경 시 텍스트가 자동 반영됨. 포스터(category=포스터)만 true, 나머지는 false |
| status | enum | 제안됨 / 선택됨 / 보관 / 삭제됨(소프트 삭제) |
| created_at | datetime | 생성 시각 |
| info_synced_at | datetime | 이 결과물의 텍스트가 마지막으로 PerformanceInfo와 동기화된 시각 |
| design_synced_at | datetime | 이 결과물이 마지막으로 DesignAssets(원본 디자인)와 동기화된 시각. 포스터 재생성 시 "원본 변경됨" 감지에 사용 |
| deleted_at | datetime(선택) | 사용자가 삭제한 시각. 이 시점부터 30일 후 하드 삭제 대상 |
| file_size | number | 파일 크기(byte). 대용량 여부 판정에 사용 — 임계값은 Stage 6 참고 |
| download_expires_at | datetime(선택) | 대용량 결과물의 다운로드 가능 기한 = **생성일 + 90일**. 이후 원본 파일만 삭제되고 레코드와 미리보기는 남는다 |

### 대용량 파일과 미리보기 분리

모든 결과물은 **원본**과 **미리보기 축소본**을 함께 저장한다.

| 구분 | 용도 | 보관 |
|---|---|---|
| `image_url` (원본) | 다운로드·인쇄 | 대용량이면 생성일 + 90일 |
| `preview_image_url` (축소본) | 대시보드 표시, 결과 확인 | 영구 |

이렇게 나눈 이유는 두 가지다. 첫째, 대용량 원본이 만료되어도 **어떤 이미지였는지는 계속 볼 수 있다.** 프로젝트는 고객의 자산이므로 목록에서 통째로 사라지면 안 된다. 둘째, 대시보드가 항상 축소본을 쓰므로 화면 로딩과 전송 비용이 줄어든다.

만료된 결과물은 대시보드에서 "다운로드 기간이 지났습니다"로 표시하고 다운로드 버튼을 비활성화한다. 다시 필요하면 재생성한다(크레딧 소비).

### 포스터(원본)와 규격변환의 관계

- `category='포스터'`인 GeneratedAsset은 프로젝트당 **정확히 1건**만 존재하는 원본(대표 이미지)이며, ④ 시안 확정 시 생성된다. 유일하게 `auto_sync_text=true`다.
- ⑤ 규격 선택 화면에는 포스터도 함께 노출하되, 다른 규격과 같은 "변환 대상"이 아니라 **원본 다시 만들기**로 구분해 표시한다(Stage 4 참고).
- **중복 방지 규칙**: ⑤에서 포스터를 선택해 ⑥에서 새 안을 확정하면, 새 `category='규격변환'` 레코드를 만들지 않고 **기존 포스터 레코드의 `image_url`·`object_map`을 교체**한다. 동시에 DesignAssets(key_visual_image, palette 등)도 새 안 기준으로 갱신한다. 따라서 포스터는 어떤 경로로도 프로젝트당 1건을 유지하며, 자동반영(`auto_sync_text=true`) 정책이 갈라지지 않는다.
- 이 경로가 곧 **포스터 재생성 경로**다. 시안이 마음에 들지 않을 때 새 프로젝트를 만들지 않고 원본을 다시 만들 수 있다.
- 포스터는 인쇄·다운로드 대상으로도 자유롭게 선택할 수 있다(Stage 3 ⑧ 참고).

### 원본 변경 시 다른 결과물의 처리

포스터를 다시 만들면 DesignAssets가 갱신되므로, 이전 키비주얼을 기준으로 만들어둔 규격변환 결과물은 새 원본과 디자인이 어긋난다. 이를 감지하기 위해 정보 변경과 동일한 방식의 타임스탬프 비교를 사용한다.

- `Project.design_updated_at` > `GeneratedAsset.design_synced_at` → 대시보드에 **"원본 변경됨"** 배지 표시
- `Project.info_updated_at` > `GeneratedAsset.info_synced_at` → **"정보 변경됨"** 배지 표시(기존 규칙)

두 경우 모두 자동으로 갱신하지 않고, 사용자가 "최신 반영" 버튼을 눌러야 다시 렌더링한다. 다만 원본이 바뀐 경우는 텍스트만 다시 얹는 수준이 아니라 Recomposition을 다시 수행해야 하므로 크레딧 소비가 크다는 점을 버튼 클릭 전 안내한다.

### 시안 후보(category='시안후보')의 저장과 정리

- ③에서 생성된 후보 이미지는 draft 프로젝트에 속한 `category='시안후보'`, `status='제안됨'` 레코드로 저장한다. SelectionEvent의 `shown_candidates[].candidate_id`는 이 레코드의 id를 가리킨다.
- ④에서 사용자가 고른 후보는 `category='포스터'`, `auto_sync_text=true`로 전환한다(별도 레코드를 새로 만들지 않음).
- 선택되지 않은 후보는 프로젝트 확정 시점부터 **30일 후 이미지 파일만 삭제**한다. 추천엔진 학습에 필요한 것은 이미지 자체가 아니라 생성 파라미터이며, 이는 SelectionEvent에 남아 있으므로 저장비만 늘리는 원본을 오래 보관하지 않는다.

### 정보 변경 반영 방식

- **포스터(category=포스터, auto_sync_text=true)**: PerformanceInfo가 저장되는 즉시 비주얼 레이어는 그대로 두고 텍스트·PHOTO 레이어만 다시 합성한 뒤(사진 교체·순서 변경도 여기 포함 — 생성 API를 부르지 않는 재합성이다) 같은 레코드의 `image_url`을 갱신한다(새 레코드를 만들지 않음). 이미지 생성 API를 호출하지 않으므로 비용이 들지 않는다. 이전 합성 이미지 파일은 서버에서 삭제한다.
- **그 외 결과물(auto_sync_text=false)**: 자동으로 갱신하지 않는다. `Project.info_updated_at`이 `GeneratedAsset.info_synced_at`보다 최신이면 대시보드에 "정보 변경됨" 배지를 표시하고, 사용자가 "최신 반영" 버튼을 눌러야 갱신한다. 갱신 시에도 같은 레코드의 image_url을 교체하고 이전 이미지 파일은 삭제한다(새 레코드로 쌓이지 않음).
### 결과물 삭제 정책 (2단계 삭제)

사용자가 대시보드에서 결과물을 삭제하면 **소프트 삭제**로 처리한다.

1. `status='삭제됨'`, `deleted_at=삭제 시각`으로 표시하고 목록에서 감춘다. 이미지 파일은 이 시점에는 지우지 않는다.
2. `deleted_at`으로부터 **30일** 경과 후 배치 작업으로 레코드와 이미지 파일을 **하드 삭제**한다.

30일 이내에는 데이터가 남아 있으므로 실수로 지운 결과물을 되살릴 수 있다(복구 UI 제공 여부는 Stage 4 확인 필요 사항 참고). 이는 사업계획서 S-7의 자동 보관·삭제 정책과는 별개로, 사용자가 능동적으로 지우는 기능이다.

한편 "최신 반영"으로 이미지를 교체할 때 버려지는 **구 이미지 파일**은 사용자가 지운 것이 아니라 시스템이 대체한 것이므로 위 30일 정책을 적용하지 않고 즉시 삭제한다.

## SelectionEvent 필드 정의 (사용자 선택 행동 로그 — 추천엔진 고도화의 핵심 데이터)

시안 선택·규격변환 등 복수안 중 하나를 고르는 모든 화면에서 사용자의 행동을 남긴다. 사업계획서의 "데이터가 만드는 장기 진입장벽"(선택→거절→재생성→규격선택 데이터 축적)을 구현하는 핵심 엔티티이므로 MVP부터 반드시 수집한다.

| 필드명 | 타입 | 설명 |
|---|---|---|
| event_id | string | 이벤트 ID |
| project_id | string | 소속 프로젝트 |
| screen | enum | 시안선택(③) / 규격변환(⑥) — 향후 다른 선택 화면으로 확장 가능 |
| shown_candidates | array | 그 시점에 함께 제시된 후보 전체와 각각의 생성 파라미터 {candidate_id, generation_params} — 무엇과 비교해서 골랐는지가 데이터의 핵심 |
| action | enum | select / view_more_direction(다른 방향 보기) / regenerate(재생성) / more_like_this(이 방향으로 더 보기) |
| selected_candidate_id | string(선택) | action=select일 때만 값 존재 |
| created_at | datetime | 발생 시각 |

MVP는 최소한 ③ 시안 선택 화면의 모든 액션을 이 구조로 기록한다. ⑥ 일괄변환 화면의 규격별 선택도 동일 구조를 재사용하는 것을 기본으로 하되, generation_params에 어떤 값을 담을지는 Stage 5(AI엔진기술기획)에서 확정한다.

## LayoutSample 필드 정의 (학습 데이터 단위 — 레이아웃 근거의 원천)

Stage 5의 학습 파이프라인이 포스터 1건을 분석해 만들어내는 레코드다. 초기 수집본 약 100건에서 시작해 고객 데이터를 더해 최대 10,000건까지 축적하며, 이 분포가 GenreRule·FormatRule 값의 근거가 된다.

| 필드명 | 타입 | 설명 |
|---|---|---|
| sample_id | string | 샘플 ID |
| source | enum | collected(수집 포스터) / user_upload(고객 업로드) / user_generated(서비스 생성물) |
| genre | enum | 장르(7종). 판정 불가 시 미분류로 두고 집계에서 제외 |
| aspect_ratio | number | 원본 가로/세로 비율 — 규격이 달라도 비교 가능하도록 |
| elements | array | 역할별 배치 정보 — 아래 하위 스키마 |
| palette | string[](hex) | 추출 색상 |
| margin_ratio | object | 상하좌우 여백 비율 |
| present_roles | string[] | 이 포스터에 실제로 포함된 정보 역할 목록(무엇을 넣고 무엇을 뺐는가) |
| analyzed_at | datetime | 분석 시각 |
| confidence | number | VLM 역할 판정 신뢰도. 낮은 샘플은 집계에서 가중치를 낮춘다 |

**elements (역할별 배치)**
```
{ role(제목/부제/날짜/장소/출연진/가격/주최/로고/피사체...),
  x, y, w, h,          // 0~1 상대좌표 — 규격 무관 비교를 위해 정규화
  area_ratio,          // 전체 대비 차지 면적
  align,               // left / center / right
  font_size_ratio?,    // 짧은 변 대비 글자 크기(텍스트 역할만)
  color? }[]
```

수집 포스터의 저작권 처리는 별도 검토가 필요하다. 원본 이미지를 보관하지 않고 **분석 결과인 LayoutSample만 저장**하는 방식이면 위험을 낮출 수 있으나, 법률 자문으로 확인해야 한다(Stage 15 참고).

## PrintOrderDraft 필드 정의 (⑧ 인쇄 페이지 — MVP는 초안까지만 지원)

MVP는 인쇄 페이지 진입과 입력·예상금액 계산까지 지원하되 실제 결제·주문 접수는 하지 않는다.

| 필드명 | 타입 | 설명 |
|---|---|---|
| draft_id | string | 초안 ID |
| project_id | string | 소속 프로젝트 |
| generated_asset_id | string | 인쇄 대상 결과물 참조 |
| print_spec | object | {규격, 수량, 용지, 옵션[]} |
| shipping_address | object | {수령인, 연락처, 주소, 요청사항} |
| estimated_price | number | 입력값 기준 예상 금액 |
| status | enum | MVP는 `draft_only` 고정값만 사용. 결제 연동 시점(Phase 6)부터 주문 상태값 추가 |

예상금액 계산에 필요한 단가표(규격×수량×용지×옵션)는 인쇄 협력업체와 협의 후 확정해야 하며, MVP는 플레이스홀더 단가로 화면을 시연한다.

## 프로젝트 복제

기존 프로젝트에서 새 프로젝트를 빠르게 시작할 수 있도록 복제 기능을 제공한다(예: 같은 공연의 다음 시즌·재공연).

- **복제되는 것**: PerformanceInfo(값을 그대로 복사), DesignAssets(톤앤매너 재사용)
- **복제되지 않는 것**: GeneratedAsset(과거 생성 이미지) — 새 프로젝트이므로 필요한 규격을 다시 생성한다
- 복제 시 sessions는 `is_undetermined=true`로 초기화되어 사용자가 새 일정을 입력하도록 유도한다(장소는 복사된 값 유지 — 같은 장소에서 재공연하는 경우가 많다고 가정)
- 복제 직후 새 프로젝트는 `active` 상태이며 사용자는 곧바로 **⑦ 프로젝트 대시보드**로 이동한다(③ 시안 선택 단계를 다시 거치지 않음). 포스터 GeneratedAsset은 복사하지 않고 복제된 DesignAssets·PerformanceInfo 기준으로 새로 1건 렌더링한다

## 설계 결정 확정 (Stage 2 검토 결과)

1. **가격**: 좌석등급뿐 아니라 조기예매·현장가 같은 시점별 가격, 가족할인·복지할인 같은 조건부 할인까지 포괄해야 한다는 피드백을 반영해 `price_items` 구조로 재설계함(위 하위 스키마 참조)
2. **주최/주관/후원**: 정보 입력 화면에서도 3개를 각각 따로 받는 것으로 확정
3. **object_map**: 지금 단계에서는 조정하지 않고 유지. Stage 4(화면상세기획)에서 편집 가능 범위가 정해지면 재검토

## 설계 결정 확정 (추가 반영, Stage 4 피드백)

1. **출연진**: `career`(경력) 필드 추가 — 클래식 등 경력 표기가 필요한 장르 대응
2. **정보 변경 반영**: `auto_sync_text` 도입 — 포스터는 자동 반영(같은 레코드 갱신, 이전 파일 삭제), 그 외는 "최신 반영" 버튼으로 수동 일괄 반영(마찬가지로 이전 파일 삭제, 새 레코드 생성 안 함)
3. **결과물 삭제**: 소프트 삭제 후 30일 뒤 하드 삭제하는 2단계 방식으로 확정(`status='삭제됨'` + `deleted_at`)
4. **대용량 파일 다운로드 기한**: 대용량 결과물의 원본은 **생성일 + 90일**까지만 다운로드할 수 있다. 이후 원본 파일만 삭제하고 레코드와 `preview_image_url`은 영구 보관한다(Stage 6)
5. **프로젝트 복제**: 기능 추가 확정(위 섹션 참고)

## 설계 결정 확정 (정합성 검토 반영)

1. **Project 생성 시점**: ① 진입 시 `status='draft'`로 즉시 생성하고 ④에서 `active`로 확정. 미확정 draft는 30일 후 자동 삭제
2. **시안 후보 저장**: `category='시안후보'` 레코드로 draft 프로젝트에 저장. 선택된 후보는 포스터로 전환, 미선택 후보는 확정 30일 후 이미지 파일만 삭제(생성 파라미터는 SelectionEvent에 보존)
3. **포스터 중복 제거**: 포스터는 ⑤에 "원본 다시 만들기"로 노출하되, 확정 시 새 레코드를 만들지 않고 기존 포스터 레코드를 교체하여 프로젝트당 1건을 유지한다. 이 경로가 곧 포스터 재생성 수단이 된다
4. **필수문구 우선**: `mandatory_notices`는 규격별 정보 생략 대상에서 제외
5. **사용자 지시 우선**: `image_direction_note`의 지시가 미정 대체문구 등 시스템 기본 규칙보다 우선

## 다음 단계

Stage 3: 사용자 플로우/정보구조(IA) — 이미 확정된 유저 플로우 8단계를 이 스키마 기준으로 화면 단위까지 구체화한다.
