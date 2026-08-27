"""
ACTSET PoC — 하이브리드 2레이어 렌더링 엔진
==========================================
Layer 1 : 배경·키비주얼 (실서비스에서는 이미지 생성 API. PoC에서는 플레이스홀더 생성)
Layer 2 : 공연정보 텍스트 (시스템이 폰트로 렌더링해 합성)

이 파일은 검증용이며, 실제 구현 시 구조만 참고한다.
"""
from dataclasses import dataclass, field
from typing import Optional
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT_REGULAR = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_BLACK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
KR_INDEX = 1  # Noto Sans CJK KR


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = FONT_BLACK if bold else FONT_REGULAR
    return ImageFont.truetype(path, size, index=KR_INDEX)


# ---------------------------------------------------------------------------
# FormatSpec / FormatRule  (Stage 2·5에서 구조만 정의하고 값이 비어 있던 부분)
# ---------------------------------------------------------------------------

@dataclass
class FormatSpec:
    format_id: str
    label: str
    width: int
    height: int
    group: str            # 원본 / 온라인 / 오프라인
    unit: str = "px"
    dpi: int = 72
    is_print_target: bool = False


@dataclass
class FormatRule:
    """규격별 정보 배치 기준."""
    info_area: tuple           # (x0, y0, x1, y1) 비율 0~1 — Layer 2를 얹을 영역
    title_scale: float         # 캔버스 짧은 변 대비 제목 크기 비율
    body_scale: float          # 본문 기본 크기 비율
    min_readable_px: int       # 이보다 작아지면 생략 판단
    align: str = "center"
    max_blocks: Optional[int] = None   # 표시할 최대 정보 블록 수(소규격 제한)
    undetermined_copy: dict = field(default_factory=lambda: {
        "date": "일정 추후 공지",
        "venue": "장소 추후 공지",
    })


# 실제 치수 — 국내 공연 홍보물 통용 규격 기준
FORMATS = {
    "poster": FormatSpec("poster", "포스터(세로)", 1240, 1754, "원본", is_print_target=True),
    "ticket_site": FormatSpec("ticket_site", "예매처 이미지", 652, 423, "온라인"),
    "sns_1x1": FormatSpec("sns_1x1", "SNS 1:1", 1080, 1080, "온라인"),
    "sns_4x5": FormatSpec("sns_4x5", "SNS 4:5", 1080, 1350, "온라인"),
    "story": FormatSpec("story", "SNS 스토리", 1080, 1920, "온라인"),
    "web_thumb": FormatSpec("web_thumb", "홈페이지 썸네일", 940, 440, "온라인"),
    "web_banner": FormatSpec("web_banner", "홈페이지 배너", 1920, 600, "온라인"),
    "did": FormatSpec("did", "DID", 1080, 1920, "온라인"),
    "hall_monitor": FormatSpec("hall_monitor", "공연장 모니터", 1920, 1080, "온라인"),
    "banner_wide": FormatSpec("banner_wide", "현수막(가로)", 2048, 256, "오프라인", is_print_target=True),
    "x_banner": FormatSpec("x_banner", "X배너", 600, 1800, "오프라인", is_print_target=True),
}

RULES = {
    # 세로 포스터 — 하단 38%를 정보 영역으로
    "poster": FormatRule((0.08, 0.60, 0.92, 0.95), 0.085, 0.030, 18),
    "sns_1x1": FormatRule((0.07, 0.58, 0.93, 0.94), 0.075, 0.034, 18, max_blocks=5),
    "sns_4x5": FormatRule((0.07, 0.60, 0.93, 0.94), 0.080, 0.032, 18, max_blocks=6),
    "story": FormatRule((0.08, 0.62, 0.92, 0.92), 0.075, 0.028, 20, max_blocks=6),
    "did": FormatRule((0.08, 0.62, 0.92, 0.92), 0.075, 0.028, 20, max_blocks=6),
    "ticket_site": FormatRule((0.05, 0.52, 0.95, 0.94), 0.115, 0.055, 14, max_blocks=3),
    "web_thumb": FormatRule((0.04, 0.48, 0.96, 0.94), 0.120, 0.058, 14, max_blocks=3),
    "web_banner": FormatRule((0.35, 0.15, 0.97, 0.88), 0.110, 0.045, 16, align="left", max_blocks=4),
    "hall_monitor": FormatRule((0.40, 0.18, 0.96, 0.86), 0.095, 0.040, 18, align="left", max_blocks=5),
    "banner_wide": FormatRule((0.28, 0.10, 0.98, 0.90), 0.240, 0.130, 14, align="left", max_blocks=2),
    "x_banner": FormatRule((0.08, 0.58, 0.92, 0.95), 0.080, 0.030, 18, max_blocks=5),
}


# ---------------------------------------------------------------------------
# 정보 블록 구성 — Stage 5 "정보 포함·생략 우선순위 규칙"의 구현
# ---------------------------------------------------------------------------

# 낮은 우선순위부터 나열 → 공간 부족 시 앞에서부터 버린다
DROP_ORDER = ["inquiry", "ticket", "organizer", "running_time", "age", "price", "cast"]


def build_blocks(info: dict, rule: FormatRule, title_in_layer2: bool):
    """PerformanceInfo → 화면에 얹을 정보 블록 목록."""
    exclude = set(info.get("_exclude", []))          # image_direction_note의 제외 지시
    blocks = []

    def add(kind, text, size="body", weight=False, keep=False):
        if not text or kind in exclude:
            return
        blocks.append({"kind": kind, "text": text, "size": size,
                       "bold": weight, "keep": keep})

    if title_in_layer2:
        add("title", info["main_title"], size="title", weight=True, keep=True)
        add("subtitle", info.get("subtitle"), size="sub")

    # 날짜 — 미정이면 대체 문구
    s = info["sessions"][0]
    date_txt = rule.undetermined_copy["date"] if s.get("is_undetermined") else s["display"]
    add("date", date_txt, size="lead", weight=True, keep=True)

    v = info["venue"]
    venue_txt = rule.undetermined_copy["venue"] if v.get("is_undetermined") else v["name"]
    add("venue", venue_txt, size="lead", keep=True)

    if info.get("running_time"):
        add("running_time", f"러닝타임 {info['running_time']}")
    if info.get("cast"):
        cast_txt = "  ".join(
            f"{c['name']}" + (f"({c['part']})" if c.get("part") else "") for c in info["cast"])
        add("cast", cast_txt)
    if info.get("price_items"):
        add("price", "  ".join(f"{p['label']} {p['price']:,}원" for p in info["price_items"]))
    if info.get("age"):
        add("age", info["age"])
    og = info.get("organizer_group") or {}
    org_bits = []
    if og.get("presenter"):
        org_bits.append("주최 " + "·".join(og["presenter"]))
    if og.get("organizer"):
        org_bits.append("주관 " + "·".join(og["organizer"]))
    if org_bits:
        add("organizer", "   ".join(org_bits))
    if info.get("inquiry", {}).get("phone"):
        add("inquiry", f"문의 {info['inquiry']['phone']}")

    # 필수 안내문구 — 생략 대상에서 제외(keep=True)
    for n in info.get("mandatory_notices", []):
        add("notice", n, size="small", keep=True)

    return blocks


SIZE_KEYS = {"title": 1.0, "sub": 0.42, "lead": 0.34, "body": 0.26, "small": 0.20}

# 블록 아래 여백 — 자기 글자 크기 대비 비율. 정보군 사이만 띄우고 같은 군은 붙인다.
GAP_AFTER = {
    "title": 0.30,        # 제목 다음은 약간 띄움
    "subtitle": 0.55,     # 제목군 → 일정군 사이는 확실히 구분
    "date": 0.08,         # 날짜-장소는 붙임
    "venue": 0.45,        # 일정군 → 상세군 구분
    "running_time": 0.10,
    "cast": 0.10,
    "price": 0.10,
    "age": 0.10,
    "organizer": 0.08,
    "inquiry": 0.35,
    "notice": 0.10,
}
DEFAULT_GAP = 0.12
LINE_RATIO = 1.26        # 한 블록 안 줄간격(글자 크기 대비)


def fit_blocks(blocks, rule, spec, draw):
    """정보 영역 안에 들어가도록 폰트 크기를 줄이고, 그래도 넘치면 우선순위대로 버린다."""
    short = min(spec.width, spec.height)
    x0, y0, x1, y1 = rule.info_area
    area_w = (x1 - x0) * spec.width
    area_h = (y1 - y0) * spec.height

    working = list(blocks)
    dropped = []

    # 소규격 블록 수 제한
    if rule.max_blocks:
        while len([b for b in working if not b["keep"]]) + \
                len([b for b in working if b["keep"]]) > rule.max_blocks:
            victim = next((k for k in DROP_ORDER
                           if any(b["kind"] == k and not b["keep"] for b in working)), None)
            if victim is None:
                break
            working = [b for b in working if b["kind"] != victim]
            dropped.append(victim)

    scale = 1.0
    while True:
        title_px = max(int(short * rule.title_scale * scale), 8)
        laid, total_h = [], 0
        for b in working:
            px = max(int(title_px * SIZE_KEYS[b["size"]]), 8)
            f = font(px, b["bold"])
            lines = wrap(b["text"], f, area_w, draw)
            gap = int(px * GAP_AFTER.get(b["kind"], DEFAULT_GAP))
            h = len(lines) * line_h(px) + gap
            laid.append({**b, "px": px, "font": f, "lines": lines, "h": h, "gap": gap})
            total_h += h

        body_px = int(title_px * SIZE_KEYS["body"])
        if total_h <= area_h:
            return laid, dropped, True
        if body_px > rule.min_readable_px:
            scale *= 0.94
            continue
        # 최소 가독 크기 도달 → 우선순위대로 버린다
        victim = next((k for k in DROP_ORDER
                       if any(b["kind"] == k and not b["keep"] for b in working)), None)
        if victim is None:
            # 더 버릴 게 없다 = keep 항목만 남았는데도 넘침 → 경고 대상
            return laid, dropped, False
        working = [b for b in working if b["kind"] != victim]
        dropped.append(victim)
        scale = 1.0


def wrap(text, f, max_w, draw):
    words, lines, cur = text.split(" "), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if draw.textlength(t, font=f) <= max_w or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def line_h(px):
    """CJK 폰트는 메트릭상 상하 여백이 커서, 글자 크기 기준으로 줄간격을 잡는다."""
    return int(px * LINE_RATIO)


# ---------------------------------------------------------------------------
# Layer 2 합성
# ---------------------------------------------------------------------------

def render_layer2(base: Image.Image, info: dict, spec: FormatSpec,
                  rule: FormatRule, title_in_layer2=True):
    """배경 위에 정보 레이어를 합성하고 object_map을 반환."""
    canvas = base.convert("RGBA").copy()
    overlay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    blocks = build_blocks(info, rule, title_in_layer2)
    laid, dropped, fits = fit_blocks(blocks, rule, spec, draw)

    x0, y0, x1, y1 = rule.info_area
    ax0, ay0 = int(x0 * spec.width), int(y0 * spec.height)
    ax1, ay1 = int(x1 * spec.width), int(y1 * spec.height)

    # 가독성 보조: 정보 영역에 어두운 그라디언트 밴드
    band = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    band_top = max(int(ay0 - spec.height * 0.06), 0)
    for i in range(band_top, spec.height):
        p = (i - band_top) / max(spec.height - band_top, 1)
        bd.line([(0, i), (spec.width, i)], fill=(8, 10, 18, int(200 * min(p * 1.6, 1))))
    canvas = Image.alpha_composite(canvas, band)
    draw = ImageDraw.Draw(overlay)

    total_h = sum(b["h"] for b in laid)
    y = ay0 + max((ay1 - ay0 - total_h) // 2, 0)

    object_map = {}
    for b in laid:
        for line in b["lines"]:
            w = draw.textlength(line, font=b["font"])
            if rule.align == "left":
                x = ax0
            else:
                x = ax0 + ((ax1 - ax0) - w) / 2
            color = (255, 255, 255, 255) if b["kind"] != "notice" else (215, 218, 228, 235)
            # 줄 상자 안에서 세로 중앙에 오도록 보정(CJK 메트릭 여백 제거)
            a, d = b["font"].getmetrics()
            oy = y + (line_h(b["px"]) - (a + d)) // 2
            draw.text((x, oy), line, font=b["font"], fill=color)
            y += line_h(b["px"])
        y += b["gap"]
        # object_map: 시스템이 직접 배치했으므로 좌표를 정확히 알고 있다
        object_map[b["kind"]] = {
            "x": ax0, "y": y - b["h"], "w": ax1 - ax0, "h": b["h"],
            "font_px": b["px"], "bold": b["bold"], "text": b["text"],
        }

    out = Image.alpha_composite(canvas, overlay).convert("RGB")
    return out, object_map, dropped, fits


# ---------------------------------------------------------------------------
# Layer 1 플레이스홀더 (실서비스에서는 이미지 생성 API 결과)
# ---------------------------------------------------------------------------

def placeholder_visual(spec: FormatSpec, palette, seed=0) -> Image.Image:
    """생성 API를 대체하는 배경. 하단부는 정보 영역이므로 단순하게 둔다."""
    w, h = spec.width, spec.height
    img = Image.new("RGB", (w, h), palette[0])
    d = ImageDraw.Draw(img)
    top, bot = palette[1], palette[0]
    for i in range(h):
        p = i / h
        d.line([(0, i), (w, i)],
               fill=tuple(int(top[c] + (bot[c] - top[c]) * min(p * 1.25, 1)) for c in range(3)))
    # 키비주얼 대체 도형 — 상단 2/3 영역
    import math
    cx, cy = w * 0.5, h * 0.33
    r = min(w, h) * 0.30
    for k in range(7):
        rr = r * (1 - k * 0.10)
        off = math.sin(seed + k) * r * 0.12
        d.ellipse([cx - rr + off, cy - rr, cx + rr + off, cy + rr],
                  outline=tuple(min(c + 42 + k * 8, 255) for c in palette[2]),
                  width=max(int(min(w, h) * 0.004), 2))
    glow = img.filter(ImageFilter.GaussianBlur(min(w, h) * 0.012))
    return Image.blend(img, glow, 0.45)


# ---------------------------------------------------------------------------
# 규격 변환 (Recomposition) — 배경 크롭 + Layer 2 재배치
# ---------------------------------------------------------------------------

def recompose_background(src: Image.Image, target: FormatSpec,
                         focus_y: float = 0.33) -> tuple:
    """원본 배경을 대상 규격 비율로 크롭. 주요 피사체(focus_y)를 최대한 살린다.
    반환: (이미지, 배경확장필요여부)"""
    sw, sh = src.size
    tr = target.width / target.height
    sr = sw / sh
    needs_outpaint = False

    if tr > sr:
        # 대상이 더 가로로 넓다 → 가로를 다 쓰고 세로를 잘라낸다
        nh = int(sw / tr)
        cy = int(sh * focus_y)
        top = max(min(cy - nh // 2, sh - nh), 0)
        crop = src.crop((0, top, sw, top + nh))
        # 세로가 심하게 잘리면 원본에 없던 영역을 만들어야 함 = 아웃페인팅 필요
        if nh / sh < 0.45:
            needs_outpaint = True
    else:
        nw = int(sh * tr)
        left = max((sw - nw) // 2, 0)
        crop = src.crop((left, 0, left + nw, sh))
        if nw / sw < 0.45:
            needs_outpaint = True

    return crop.resize((target.width, target.height), Image.LANCZOS), needs_outpaint
