"""ACTSET PoC Q8-2 — 찾은 폰트로 텍스트를 다시 조판한다(q8_font_match.py 결과 이용).

  1. 효과 측정: 원본 제목의 분홍·청록 그림자가 노란 글자 기준으로 몇 px 어긋나 있는지(색상 hue로 마스크 → 이동 탐색)
  2. 선명도: 원본 배치 그대로 3배로 다시 그린 제목 vs 원본 이미지를 3배 확대한 것
  3. 줄바꿈: 긴 세로(X배너 600×1800)에서 제목을 4줄로 다시 짠 결과 vs 이전 결과(이미지 제목 축소) vs 기준 이미지

폰트는 q8 대조 결과의 OFL 폰트만 쓴다(제목 도현체, 영문 Tinos, 작은 한글 Nanum Gothic Bold).
배치 수치(줄 간격·크기 비율)는 원본에서 잰 값이며, 4줄 배치는 이 PoC용 손 배치다 — 학습 산출물이 아니다(규칙 5).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

POC = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(POC, "out", "font-match")
FONTS = os.path.join(POC, "fonts")
POSTER = os.path.join(POC, "input", "magician's room.jpg")
CONV = os.path.join(POC, "out", "format-conversion", "magicians_room")
REF_LONG = os.path.join(POC, "out", "format-conversion", "ref", "long_portrait.png")

TITLE_FONT = os.path.join(FONTS, "DoHyeon-Regular.ttf")
SERIF = os.path.join(FONTS, "Tinos-Regular.ttf")
KO_SMALL = os.path.join(FONTS, "NanumGothic-Bold.ttf")
TITLE_BOX = (105, 190, 640, 392)  # 원본 제목 영역(px)


def hue_masks(img):
    """노랑(글자 본체)·분홍·청록(그림자) 마스크 — PIL HSV(0~255)."""
    hsv = np.asarray(img.convert("HSV"), int)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    yellow = (h >= 25) & (h <= 45) & (s > 90) & (v > 170)
    magenta = (h >= 200) & (h <= 240) & (s > 70) & (v > 120)
    cyan = (h >= 110) & (h <= 140) & (s > 70) & (v > 120)
    return yellow, magenta, cyan


def measure_offset(fill, eff, r=12):
    """그림자 = 글자 본체를 (dx,dy)만큼 옮긴 사본이 본체 뒤에 깔린 것. 보이는 부분 = 옮긴 본체 − 본체.
    그 '보이는 테두리'와 실제 그림자 마스크의 IoU가 최대인 이동을 찾는다.
    (1차: '그림자 픽셀이 옮긴 본체에 덮이는 비율'로 재니 많이 옮길수록 커져 (-5,-3)으로 과대 측정됐다)"""
    best = (0, 0, 0.0)
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx == 0 and dy == 0:
                continue
            vis = np.roll(np.roll(fill, dy, 0), dx, 1) & ~fill
            inter = (vis & eff).sum()
            iou = inter / max(1, (vis | eff).sum())
            if iou > best[2]:
                best = (dx, dy, iou)
    return best


def ink_height_ratio(font_path, text="마술사의"):
    """폰트 크기 대비 실제 글자 높이(잉크) 비율 — 원본 글자 높이로 폰트 크기를 역산할 때 쓴다."""
    f = ImageFont.truetype(font_path, 200)
    b = f.getbbox(text)
    return (b[3] - b[1]) / 200


def draw_layered(canvas, text, font_path, size, x, y, fill, effects, tracking=0.0):
    """그림자(오프셋 사본) → 본체 순으로 그린다. 오프셋은 글자 크기에 비례(원본 측정값 기준)."""
    f = ImageFont.truetype(font_path, size)
    d = ImageDraw.Draw(canvas)
    for color, (odx, ody) in effects:
        _text(d, text, f, x + odx, y + ody, color, tracking * size)
    _text(d, text, f, x, y, fill, tracking * size)


def _text(d, text, f, x, y, color, spacing):
    for ch in text:
        d.text((x, y), ch, font=f, fill=tuple(color))
        x += f.getlength(ch) + spacing


def text_width(text, font_path, size, tracking=0.0):
    f = ImageFont.truetype(font_path, size)
    return sum(f.getlength(ch) for ch in text) + tracking * size * (len(text) - 1)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    poster = Image.open(POSTER).convert("RGB")
    crop = poster.crop(TITLE_BOX)
    yel, mag, cya = hue_masks(crop)
    arr = np.asarray(crop, float)
    fill = np.median(arr[yel], 0).astype(int).tolist()
    effects = []
    from scipy import ndimage
    # 노랑 마스크는 색 조건이 엄격해 가장자리(안티앨리어싱)가 빠진다 → 1px 키워 실제 글자 크기에 맞춘다
    # (안 키우면 본체가 얇아져 오프셋이 4~5px로 과대 측정됐다)
    yel_full = ndimage.binary_dilation(yel, iterations=1)
    for name, m in (("분홍", mag), ("청록", cya)):
        dx, dy, sc = measure_offset(yel_full, m)
        color = np.median(arr[m], 0).astype(int).tolist() if m.any() else [0, 0, 0]
        effects.append((name, color, (dx, dy), sc))
        print(f"{name} 그림자: 색 {color}, 오프셋 ({dx},{dy})px, IoU {sc:.2f}")
    print(f"노랑 본체: {fill}")

    # 원본 글자 높이(마술사의 88px, q8 리포트) → 도현체 폰트 크기
    ratio = ink_height_ratio(TITLE_FONT)
    size2 = round(88 / ratio)
    ref_size = size2
    print(f"도현체 잉크 높이 비율 {ratio:.3f} → '마술사의' 폰트 크기 {size2}px")
    eff = [(c, o) for _, c, o, _ in reversed(effects)]  # 청록을 먼저, 분홍을 그 위(원본 겹침 순서와 무관하게 둘 다 본체 아래)

    # 2. 선명도 — 원본 배치 그대로 3배
    S = 3
    # 배경판 + 별빛 줄기(반투명 오버레이 레이어) — 줄기를 빼면 덮은 자리가 네모난 판처럼 보인다(1차)
    bg_full = Image.open(os.path.join(CONV, "debug", "03_backdrop_local_fill.png")).convert("RGBA")
    streaks = Image.open(os.path.join(CONV, "debug", "02_layer1_raw.png")).convert("RGBA").resize(bg_full.size, Image.LANCZOS)
    bg_full.alpha_composite(streaks)
    bg_full = bg_full.convert("RGB")
    bg = bg_full.crop(TITLE_BOX).resize((crop.width * S, crop.height * S), Image.LANCZOS)
    redraw = bg.copy()
    # 원본에서 잰 줄 위치(제목 영역 기준 px) — 마스크 bbox
    lines = line_boxes(yel)
    texts = [t for t, _ in CONFIRMED_LINES]
    print("줄 bbox(제목 영역 기준):", lines)
    for (x0, y0, x1, y1), t in zip(lines, texts):
        h_px = (y1 - y0) * S
        fpath = TITLE_FONT
        size = round(h_px / ink_height_ratio(fpath, t))
        f = ImageFont.truetype(fpath, size)
        b = f.getbbox(t)
        ox, oy = x0 * S - b[0], y0 * S - b[1]
        # 폭 맞춤 자간
        natural = text_width(t, fpath, size)
        tr = ((x1 - x0) * S - (natural - (b[0]))) / max(1, size * (len(t) - 1)) if len(t) > 1 else 0
        draw_layered(redraw, t, fpath, size, ox, oy, fill,
                     [(c, (o[0] * S * size / (ref_size * S), o[1] * S * size / (ref_size * S))) for c, o in eff], tr)
    up = crop.resize((crop.width * S, crop.height * S), Image.LANCZOS)
    sheet = Image.new("RGB", (up.width * 2 + 30, up.height + 50), (255, 255, 255))
    d = ImageDraw.Draw(sheet)
    lab = ImageFont.truetype(KO_SMALL, 24)
    d.text((10, 10), "원본 이미지 3배 확대", fill=(0, 0, 0), font=lab)
    d.text((up.width + 40, 10), "도현체로 다시 조판(3배 해상도)", fill=(0, 0, 0), font=lab)
    sheet.paste(up, (0, 50))
    sheet.paste(redraw, (up.width + 30, 50))
    sheet.save(os.path.join(OUT, "retypeset_title_3x.png"))
    # 확대 비교용 부분
    zx = (0, 0, 560, 320)
    z = Image.new("RGB", (560 * 2 + 20, 320), (255, 255, 255))
    z.paste(up.crop(zx), (0, 0))
    z.paste(redraw.crop(zx), (580, 0))
    z.save(os.path.join(OUT, "retypeset_title_3x_zoom.png"))

    # 3. 긴 세로 4줄 — 이전 결과의 제목 블록 자리를 배경으로 덮고 다시 조판
    old = Image.open(os.path.join(CONV, "A_LONG_PORTRAIT_600x1800.png")).convert("RGB")
    W, H = old.size
    new = old.copy()
    s = max(W / bg_full.width, H / bg_full.height)
    cov = bg_full.resize((round(bg_full.width * s), round(bg_full.height * s)), Image.LANCZOS)
    ox = (W - cov.width) // 2
    top, bot = 160, 600  # 이전 결과의 제목 블록(HEADLINE rel y 0.12~0.30) + 여유
    new.paste(cov.crop((-ox, top, -ox + W, bot)), (0, top))
    d = ImageDraw.Draw(new)
    # 영문·한글 소제목 줄
    y = 190
    col_x = [W * 0.17, W * 0.5, W * 0.83]
    for (ko, en), cx in zip([("마술", "MAGIC"), ("그림자", "SHADOW"), ("레이저", "LASER")], col_x):
        fk = ImageFont.truetype(KO_SMALL, 18)
        fe = ImageFont.truetype(SERIF, 34)
        d.text((cx - fk.getlength(ko) / 2, y), ko, font=fk, fill=(185, 198, 233))
        d.text((cx - fe.getlength(en) / 2, y + 22), en, font=fe, fill=(238, 250, 255))
    # 제목 4줄 — 원본 크기 비율(47:88:161)을 유지하되 폭 540px 안에서 최대
    rows = [("반짝반짝", 47), ("빛나는", 47), ("마술사의", 88), ("방", 161)]
    maxw = W * 0.9
    k = min(maxw / text_width(t, TITLE_FONT, round(hh / ratio)) for t, hh in rows[:3])
    k = min(k, 1.9)
    y = 270
    for t, hh in rows:
        size = round(hh * k / ratio)
        f = ImageFont.truetype(TITLE_FONT, size)
        b = f.getbbox(t)
        x = (W - (b[2] - b[0])) / 2 - b[0]
        draw_layered(new, t, TITLE_FONT, size, x, y - b[1], fill,
                     [(c, (o[0] * size / ref_size, o[1] * size / ref_size)) for c, o in eff])
        y += (b[3] - b[1]) + int(0.18 * size)
    new.save(os.path.join(OUT, "retypeset_long_portrait.png"))

    ref = Image.open(REF_LONG).convert("RGB").resize((W, H), Image.LANCZOS)
    cmp_ = Image.new("RGB", (W * 3 + 40, H + 50), (255, 255, 255))
    d = ImageDraw.Draw(cmp_)
    for i, (im, t) in enumerate([(old, "이전: 제목 이미지 축소"), (new, "다시 조판: 도현체 4줄"), (ref, "기준(사용자 제작)")]):
        cmp_.paste(im, (i * (W + 20), 50))
        d.text((i * (W + 20) + 6, 12), t, fill=(0, 0, 0), font=lab)
    cmp_.save(os.path.join(OUT, "retypeset_long_portrait_compare.png"))
    print("저장: retypeset_title_3x.png, retypeset_title_3x_zoom.png, retypeset_long_portrait_compare.png")


# 사람이 확인한 줄 위치(원본 px) — q8_font_match.py의 OCR + 위치 교정과 같다
CONFIRMED_LINES = [("반짝반짝 빛나는", (128, 218, 468, 270)), ("마술사의", (128, 285, 466, 380)), ("방", (474, 205, 628, 386))]


def line_boxes(yel):
    """확인된 줄 위치 안에 중심이 있는 노랑 덩어리들의 tight bbox(제목 영역 기준 px)."""
    from scipy import ndimage
    lab, n = ndimage.label(yel)
    objs = ndimage.find_objects(lab)
    out = []
    for _, (x0, y0, x1, y1) in CONFIRMED_LINES:
        x0, x1 = x0 - TITLE_BOX[0], x1 - TITLE_BOX[0]
        y0, y1 = y0 - TITLE_BOX[1], y1 - TITLE_BOX[1]
        bs = [(o[1].start, o[0].start, o[1].stop, o[0].stop) for o in objs
              if x0 <= (o[1].start + o[1].stop) / 2 <= x1 and y0 <= (o[0].start + o[0].stop) / 2 <= y1]
        bs = [b for b in bs if (b[2] - b[0]) * (b[3] - b[1]) > 20]
        out.append((min(b[0] for b in bs), min(b[1] for b in bs), max(b[2] for b in bs), max(b[3] for b in bs)))
    return out


if __name__ == "__main__":
    main()
