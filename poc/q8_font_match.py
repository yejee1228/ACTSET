"""ACTSET PoC Q8 — 포스터 텍스트의 폰트를 찾아 다시 조판할 수 있는가(규격변환 텍스트 문제).

규격변환 테스트(FORMAT-CONVERSION-REPORT.md)에서 기준 이미지와의 차이가 대부분 텍스트였다
(긴 세로에서 제목 줄바꿈 불가, 키운 규격에서 작은 글씨 흐림). 이미지로 떼어낸 텍스트 대신
"같은(비슷한) 폰트로 다시 렌더링"하면 해결되는지 본다.

  1. LLM(OPENAI_MODEL, 비전)이 포스터의 텍스트를 줄 단위로 읽는다 — 문구·bbox·곡선 여부·디자인 글자 여부
     (운영에서는 이 문구를 사용자가 화면에서 확인한다)
  2. 줄마다 원본에서 잘라 배경색과 글자색으로 글자 마스크를 만든다(자체 엔진)
  3. 후보 폰트(OFL 무료 한글 31종 + 영문 세리프 7종 + 식별 전용 Windows 폰트)로 같은 문구를
     굵기·자간을 바꿔 가며 렌더링해 마스크 겹침(Dice)으로 순위를 매긴다 — "무슨 폰트냐"를 LLM에 묻지 않는다
  4. 색·그림자 겹침(마술사의 방 제목의 분홍·청록 오프셋)을 원본에서 재고, 1위 폰트로 다시 조판한다

입력은 테스트 포스터(사용자 허가) — 업로드 사진·로고 파일은 보내지 않는다(규칙 1).
임계값·가중치는 이 PoC용 임시값이다(규칙 5).

실행:
  python poc/q8_font_match.py              # OCR(캐시) → 대조 → 시트·리포트
  python poc/q8_font_match.py --force-ocr  # OCR 다시
"""
import argparse
import base64
import glob
import io
import json
import os
import sys
import time

import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage

POC = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(POC)
OUT = os.path.join(POC, "out", "font-match")
FONT_DIR = os.path.join(POC, "fonts")  # bash poc/fonts_download.sh 로 내려받는다
os.makedirs(OUT, exist_ok=True)

# 식별에만 쓰는 로컬 폰트 — 서버 배포·재배포 라이선스가 없을 수 있다. 1위로 나오면 "비슷한 OFL 폰트"로 대체해야 한다
ID_ONLY = {
    "C:/Windows/Fonts/malgun.ttf": "맑은 고딕(식별용)",
    "C:/Windows/Fonts/malgunbd.ttf": "맑은 고딕 Bold(식별용)",
    "C:/Windows/Fonts/times.ttf": "Times New Roman(식별용)",
    "C:/Windows/Fonts/timesbd.ttf": "Times New Roman Bold(식별용)",
}
PRETENDARD = glob.glob(os.path.join(ROOT, "backend", "src", "main", "resources", "fonts", "Pretendard-*.otf"))
VARIABLE_WEIGHTS = [400, 700, 900]
TRACKING = [-0.06, -0.02, 0.02, 0.06]   # 임시값 — em 대비 자간 후보
MATCH_H = 64                             # 비교 해상도(줄 높이 px)
# 사람이 확인한 문구 교정(LLM 오독) — 이미지 slug별
CORRECTIONS = {"magicians_room": {"마술사의 꿈": "마술사의", "밤": "방"}}
# 사람이 확인한 위치 교정(px, 원본 기준) — LLM이 큰 글자 "방"을 둘째 줄에 합쳐 읽어 위치도 어긋났다
BBOX_CORRECTIONS = {"magicians_room": {"마술사의": [128, 285, 466, 380], "방": [474, 205, 628, 386]}}


def load_dotenv(path):
    if not os.path.exists(path):
        return
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            os.environ.setdefault(k.strip(), v.strip())


load_dotenv(os.path.join(ROOT, ".env"))

OCR_SYSTEM = """You read the text on a performance poster for re-typesetting.
Return every visible text LINE (a run of text on one baseline). For each line give:
- text: exact characters as printed (keep punctuation, spaces, case)
- bbox: [x0,y0,x1,y1] relative to the image (0..1), tight around the glyphs of this line only
- role: TITLE | COPY | INFO | LOGO
- curved: true if the baseline is an arc/curve
- designed: true if the letters are hand-drawn / illustrated lettering that no ordinary font could reproduce
  (ornaments, 3D gold, brush art). Plain fonts with color, outline or offset shadow are NOT designed.
- size_group: lines printed in the same font size and style share the same small integer
If one word in a line is printed much bigger (different size), split it into its own line entry.
Return JSON only: {"lines": [{"text":"...","bbox":[...],"role":"...","curved":false,"designed":false,"size_group":1}]}"""


def b64(img, max_side=1400):
    img = img.convert("RGB")
    img.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode()


def ocr(poster, cache, force):
    if os.path.exists(cache) and not force:
        return json.load(open(cache, encoding="utf-8"))
    t0 = time.time()
    res = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}"},
        json={"model": os.environ.get("OPENAI_MODEL", "gpt-5.5"), "response_format": {"type": "json_object"},
              "messages": [{"role": "system", "content": OCR_SYSTEM},
                           {"role": "user", "content": [
                               {"type": "text", "text": f"Image size {poster.width}x{poster.height}."},
                               {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64(poster)}"}}]}]},
        timeout=300)
    res.raise_for_status()
    data = json.loads(res.json()["choices"][0]["message"]["content"])
    data["_ms"] = int((time.time() - t0) * 1000)
    json.dump(data, open(cache, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return data


# --------------------------------------------------------------------------- 글자 마스크

def glyph_mask(crop, core=None):
    """잘라낸 줄 이미지 → (글자 마스크, 글자색, 배경색, 그 밖의 색들).
    색을 k-means(4)로 나누고, 테두리에 거의 닿지 않는 군집 중 가장 큰 것을 글자색으로 본다 — 배경(하늘 그라데이션·별
    포함)은 테두리를 두르고, 글자는 가운데에 떠 있다. 1차 시도(테두리 중앙값에서 먼 색 = 글자)는 흰 글자에서 하늘
    그라데이션이 글자로 뽑혀 마스크가 뒤집혔다."""
    if crop.mode == "RGBA" and np.asarray(crop)[..., 3].max() > 0:
        # 요소 캔버스 모드: 알파 = "텍스트 요소가 있는 자리"(위치만), RGB = 원본 포스터 픽셀.
        # 배경에서 떼어낸 요소는 반투명해 알파로는 글자 모양이 안 나온다(6차 시도) — 모양은 원본 색에서 뽑는다
        rgba = np.asarray(crop, float)
        a = rgba[..., :3]
        h, w, _ = a.shape
        region = rgba[..., 3] > 0
        outside = ~ndimage.binary_dilation(region, iterations=3)
        bg = np.median(a[outside], 0) if outside.any() else np.median(a, 0)
        pix = a[region]
        if len(pix) < 20:
            return None
        # 글자 여부 = 배경과의 색 거리에 Otsu 임계값(크기·색과 무관하게 안정적 — 색 군집 방식은 9~12차 시도에서
        # 작은 흰 글자의 안티앨리어싱 단계가 다른 군집으로 갈려 획이 끊겼다)
        d = np.sqrt(((a - bg) ** 2).sum(-1))
        T = otsu(d[region])
        fg = region & (d > T)
        if fg.sum() < 20:
            return None
        # 글자 본체 색 = 배경에서 가장 먼 쪽 절반의 중앙값. 효과색(그림자·외곽선) = 본체와 확실히 다른(거리>110) 색 군집
        strong = fg & (d >= np.percentile(d[fg], 50))
        fill = np.median(a[strong], 0)
        pix = a[fg]
        rng = np.random.default_rng(0)
        k = min(4, len(pix))
        cen = pix[rng.choice(len(pix), k, replace=False)]
        for _ in range(12):
            lab = np.argmin(((pix[:, None] - cen[None]) ** 2).sum(-1), 1)
            cen = np.array([pix[lab == j].mean(0) if (lab == j).any() else cen[j] for j in range(k)])
        fb = np.sqrt(((fill - bg) ** 2).sum())
        effects = [cen[j] for j in range(k) if np.sqrt(((cen[j] - fill) ** 2).sum()) > 110
                   and np.sqrt(((cen[j] - bg) ** 2).sum()) > 0.6 * fb  # 배경 잔여(하늘색)는 효과가 아니다
                   and (lab == j).sum() > 0.08 * len(pix)]  # 임시값
        if effects:
            # 효과색이 있는 줄(제목의 분홍·청록 오프셋): 본체 색에 더 가까운 픽셀만 — 그림자로 글자끼리 이어지지 않게
            df = np.sqrt(((a - fill) ** 2).sum(-1))
            de = np.min([np.sqrt(((a - e) ** 2).sum(-1)) for e in effects], 0)
            mask = fg & (df < de)
        else:
            # 효과색이 없는 줄(작은 흰 글자 등): 영역 안 2갈래(배경 쪽/글자 쪽) — 가는 획·안티앨리어싱을 가장 잘 살렸다(7차)
            pix2 = a[region]
            c0 = pix2[np.argmin(((pix2 - bg) ** 2).sum(-1))]
            c1 = pix2[np.argmax(((pix2 - bg) ** 2).sum(-1))]
            for _ in range(10):
                l2 = ((pix2 - c1) ** 2).sum(-1) < ((pix2 - c0) ** 2).sum(-1)
                if l2.all() or (~l2).all():
                    break
                c0, c1 = pix2[~l2].mean(0), pix2[l2].mean(0)
            fill = c1
            mask = region & (((a - c1) ** 2).sum(-1) < ((a - c0) ** 2).sum(-1)) & (d > 50)
        effects = [e.tolist() for e in effects]
        DEBUG_EFFECTS.append(len(effects))
        return _grow(mask, core, h, w), fill.tolist(), bg.tolist(), [(e, 0) for e in effects]
    a = np.asarray(crop.convert("RGB"), float)
    h, w, _ = a.shape
    px = a.reshape(-1, 3)
    rng = np.random.default_rng(0)
    centers = px[rng.choice(len(px), 4, replace=False)]
    for _ in range(12):
        lab = np.argmin(((px[:, None] - centers[None]) ** 2).sum(-1), 1)
        centers = np.array([px[lab == k].mean(0) if (lab == k).any() else centers[k] for k in range(4)])
    lab = lab.reshape(h, w)
    border = np.zeros((h, w), bool)
    bw = max(2, int(0.06 * min(h, w)))
    border[:bw], border[-bw:], border[:, :bw], border[:, -bw:] = True, True, True, True
    stats = []
    for k in range(4):
        area = (lab == k).sum()
        stats.append((k, area, (border & (lab == k)).sum() / max(1, border.sum())))
    bg_k = max(stats, key=lambda t: t[2])[0]
    cand = [t for t in stats if t[0] != bg_k and t[1] > 0.01 * h * w]
    if not cand:
        return None
    # 테두리 점유율이 낮을수록, 면적이 클수록 글자일 가능성이 높다
    fill_k = min(cand, key=lambda t: t[2] - 0.5 * t[1] / (h * w))[0]
    mask = lab == fill_k
    mask = _grow(mask, core, h, w)
    others = [centers[t[0]].tolist() for t in stats if t[0] not in (bg_k, fill_k) and t[1] > 0.02 * h * w]
    return mask, centers[fill_k].tolist(), centers[bg_k].tolist(), [(o, 0) for o in others]


DEBUG_EFFECTS = []


def otsu(values):
    """1차원 값의 Otsu 임계값."""
    hist, edges = np.histogram(values, bins=64)
    mids = (edges[:-1] + edges[1:]) / 2
    w0 = np.cumsum(hist)
    w1 = w0[-1] - w0
    m0 = np.cumsum(hist * mids) / np.maximum(w0, 1)
    m1 = (np.sum(hist * mids) - np.cumsum(hist * mids)) / np.maximum(w1, 1)
    return float(mids[np.argmax(w0 * w1 * (m0 - m1) ** 2)])


def _grow(mask, core, h, w):
    """줄 영역 정하기. 확인된 줄 위치(core = 줄 bbox 안쪽) 안에 중심이 있는 덩어리를 이 줄로 보고, 옆으로는 위·아래 끝이
    거의 같은 덩어리만 글자 높이의 절반 간격까지 잇는다(bbox가 줄 끝 글자를 놓친 경우 — "주최").
    (12차까지의 '연쇄 잇기'는 "의"의 작은 획 조각을 다리 삼아 큰 글자 "방"과 윗줄까지 붙였다)"""
    labels, n = ndimage.label(mask)
    if not n:
        return mask
    objs = ndimage.find_objects(labels)
    areas = ndimage.sum(mask, labels, range(1, n + 1))
    boxes = [(o[1].start, o[0].start, o[1].stop, o[0].stop) for o in objs]
    cx0, cy0, cx1, cy1 = core if core else (0, int(h * 0.3), w, int(h * 0.7) + 1)
    # core는 bbox 안쪽(가로 80%·세로 60%) — 중심 판정은 bbox 전체로 되돌려 본다
    bx0, bx1 = cx0 - (cx1 - cx0) / 8, cx1 + (cx1 - cx0) / 8
    by0, by1 = cy0 - (cy1 - cy0) / 3, cy1 + (cy1 - cy0) / 3
    keep = set(i for i, b in enumerate(boxes)
               if bx0 <= (b[0] + b[2]) / 2 <= bx1 and by0 <= (b[1] + b[3]) / 2 <= by1)
    if not keep:
        return np.zeros_like(mask)
    big = [i for i in keep if areas[i] >= 0.04 * max(areas[j] for j in keep)]
    ky0 = np.median([boxes[i][1] for i in big])
    ky1 = np.median([boxes[i][3] for i in big])
    gh = ky1 - ky0
    changed = True
    while changed:
        changed = False
        kx0 = min(boxes[i][0] for i in keep)
        kx1 = max(boxes[i][2] for i in keep)
        for i, b in enumerate(boxes):
            if i in keep or areas[i] < 0.04 * max(areas[j] for j in keep):
                continue
            same_band = abs(b[1] - ky0) <= 0.25 * gh and abs(b[3] - ky1) <= 0.25 * gh
            near = (b[0] - kx1) <= 0.5 * gh and (kx0 - b[2]) <= 0.5 * gh
            if same_band and near:
                keep.add(i)
                changed = True
    sel = np.zeros(n + 1, bool)
    sel[[i + 1 for i in keep]] = True
    return sel[labels]


def tight(mask):
    ys, xs = np.where(mask)
    if len(xs) == 0:
        return None
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def normalize(mask):
    """tight crop → 높이 MATCH_H로 맞춘 float 마스크(가로는 비율 유지)."""
    b = tight(mask)
    if b is None:
        return None
    m = mask[b[1]:b[3], b[0]:b[2]]
    h, w = m.shape
    nw = max(4, int(round(w * MATCH_H / h)))
    im = Image.fromarray((m * 255).astype("uint8")).resize((nw, MATCH_H), Image.BILINEAR)
    return np.asarray(im.filter(ImageFilter.GaussianBlur(0.8)), float) / 255


# --------------------------------------------------------------------------- 텍스트 요소 캔버스

def element_canvas(size, listing, storage_root):
    """분석 단계가 저장한 텍스트 요소(TITLE·COPY·INFO·MARK, 투명 PNG)를 원래 자리에 모은 '글자 전용' 캔버스.
    원본 포스터에서 색으로 글자를 찾으면 별·그라데이션·이웃 줄이 섞인다(1~5차 시도) — 요소 알파를 쓰면 배경 잡음이 없다."""
    W, H = size
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for line in open(listing, encoding="utf-8"):
        p = line.strip().split("|")
        if len(p) < 6:
            continue
        role, x, y, w, h, path = p[0], int(p[1]), int(p[2]), int(p[3]), int(p[4]), p[5]
        el = Image.open(os.path.join(storage_root, path)).convert("RGBA")
        if el.size != (w, h):
            el = el.resize((w, h), Image.LANCZOS)
        canvas.alpha_composite(el, (x, y))
    return canvas


# --------------------------------------------------------------------------- 후보 폰트

def font_candidates():
    cands = []
    # kr_free/: 상업 사용 + 서버 임베딩 허용이 확인된 무료 한글 폰트(poc/FONT-LICENSES.md)
    for path in sorted(glob.glob(os.path.join(FONT_DIR, "kr_free", "*.ttf"))):
        cands.append({"path": path, "name": os.path.splitext(os.path.basename(path))[0], "wght": None,
                      "license": "무료(임베딩 허용)"})
    for path in sorted(glob.glob(os.path.join(FONT_DIR, "*.ttf"))) + PRETENDARD:
        name = os.path.splitext(os.path.basename(path))[0]
        if "[" in name:  # 가변 폰트 → 굵기별 인스턴스
            for wgt in VARIABLE_WEIGHTS:
                cands.append({"path": path, "name": f"{name.split('[')[0]} w{wgt}", "wght": wgt, "license": "OFL"})
        else:
            lic = "OFL" if path.startswith(FONT_DIR) else ("OFL(Pretendard)" if "Pretendard" in name else "?")
            cands.append({"path": path, "name": name, "wght": None, "license": lic})
    for path, label in ID_ONLY.items():
        if os.path.exists(path):
            cands.append({"path": path, "name": label, "wght": None, "license": "식별 전용"})
    return cands


_font_cache = {}


def get_font(c, size):
    key = (c["path"], c["wght"], size)
    if key not in _font_cache:
        f = ImageFont.truetype(c["path"], size)
        if c["wght"] is not None:
            try:
                f.set_variation_by_axes([c["wght"]])
            except Exception:
                pass
        _font_cache[key] = f
    return _font_cache[key]


def supports(c, text):
    """폰트에 문구의 글자가 다 있는지(없으면 두부 □ 박스로 그려져 점수가 왜곡된다)."""
    f = get_font(c, 40)
    try:
        notdef = f.getmask("\uffff").getbbox()
    except Exception:
        notdef = None
    for ch in set(text.replace(" ", "")):
        m = f.getmask(ch)
        if m.getbbox() is None or (notdef is not None and m.getbbox() == notdef and ch not in "\uffff"):
            return False
    return True


def render_line(c, text, size, tracking):
    """문구 한 줄을 검정 마스크로 렌더링(자간 = tracking × size)."""
    f = get_font(c, size)
    widths = [f.getlength(ch) for ch in text]
    total = int(sum(widths) + tracking * size * max(0, len(text) - 1) + size)
    img = Image.new("L", (total + 4, int(size * 1.6)), 0)
    d = ImageDraw.Draw(img)
    x = 2.0
    for ch, wch in zip(text, widths):
        d.text((x, size * 0.2), ch, font=f, fill=255)
        x += wch + tracking * size
    return np.asarray(img, float) / 255 > 0.5


def score(target, cand):
    """후보를 원본과 같은 폭·높이로 맞춘 뒤 Dice(모양) × 비율 일치도.
    1차 시도(왼쪽 정렬 겹치기)는 긴 줄에서 폭이 조금만 달라도 뒤쪽 글자가 전부 어긋나 점수가 무너졌다."""
    th, tw = target.shape
    ch, cw = cand.shape
    c = np.asarray(Image.fromarray((cand * 255).astype("uint8")).resize((tw, th), Image.BILINEAR), float) / 255
    dice = 2 * (target * c).sum() / (target.sum() + c.sum() + 1e-6)
    ratio = abs(np.log((cw / ch) / (tw / th)))
    return dice * float(np.exp(-2.0 * ratio))  # 임시값 — 비율 10% 차이 ≈ 0.83배


def native_target(mask):
    """원본 해상도의 글자 마스크(tight) — 살짝 흐려 안티앨리어싱과 비슷하게."""
    b = tight(mask)
    m = mask[b[1]:b[3], b[0]:b[2]].astype("uint8") * 255
    return np.asarray(Image.fromarray(m).filter(ImageFilter.GaussianBlur(BLUR)), float) / 255


BLUR = 0.6                     # 임시값 — 안티앨리어싱 정도(더 키우면 굵은 폰트가 유리해진다 — 9차 시도)
X_SCALES = [0.92, 0.96, 1.0, 1.04, 1.08]  # 임시값 — 원본 글자 일부가 잘렸거나 장평이 다른 경우 대비


def native_candidate(c, text, tracking, shape, x_scale=1.0):
    """후보 폰트를 크게(120px) 그린 뒤 원본 글자 크기로 줄인 커버리지 — 작은 글자(20px)에서도 획 두께가 공정하게 비교된다.
    (7차 시도: 20px 글자를 64px로 키워 비교하니 계단 때문에 가는 세리프가 굵은 고딕보다 낮게 나왔다)"""
    m = render_line(c, text, 120, tracking)
    b = tight(m)
    if b is None:
        return None, 1.0
    m = m[b[1]:b[3], b[0]:b[2]]
    ar = (m.shape[1] / m.shape[0])
    th, tw = shape
    cw = max(1, int(round(tw * x_scale)))
    im = Image.fromarray((m * 255).astype("uint8")).resize((cw, th), Image.LANCZOS).filter(ImageFilter.GaussianBlur(BLUR))
    out = np.zeros((th, tw))
    out[:, :min(tw, cw)] = (np.asarray(im, float) / 255)[:, :min(tw, cw)]  # 왼쪽 맞춤(줄 시작은 대개 정확하다)
    return out, ar * (tw / cw)


def match_line(target_mask, text, cands):
    t = native_target(target_mask)
    tar = t.shape[1] / t.shape[0]
    results = []
    for c in cands:
        if not supports(c, text):
            continue
        best = None
        for tr in TRACKING:
            for xs in X_SCALES:
                cm, car = native_candidate(c, text, tr, t.shape, xs)
                if cm is None:
                    continue
                # 1px 이동 탐색 — 가는 획은 1px만 어긋나도 겹침이 크게 준다
                dice = 0.0
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        sh = np.roll(np.roll(cm, dy, 0), dx, 1)
                        dice = max(dice, 2 * (t * sh).sum() / (t.sum() + sh.sum() + 1e-6))
                # 비율 감점(장평 차이) × 잉크 양 감점(획 굵기 차이)
                ink = min(t.sum(), cm.sum()) / max(t.sum(), cm.sum(), 1e-6)
                s_ = dice * float(np.exp(-2.0 * abs(np.log(car / tar)))) * (0.5 + 0.5 * ink)
                if best is None or s_ > best[0]:
                    best = (s_, tr)
        if best:
            results.append({"font": c["name"], "path": c["path"], "wght": c["wght"], "license": c["license"],
                            "score": round(best[0], 4), "tracking": best[1]})
    results.sort(key=lambda r: -r["score"])
    return results


def match_segments(mask, segs, cands):
    """'|'로 나뉜 정보 줄: 마스크를 큰 가로 간격으로 나눠 구간마다 대조하고 폰트별 평균. 구분선은 요소 분리에서 자주
    빠진다(정보 줄 마스크에 '|'가 없어 문구 전체와 어긋났다 — 8차 시도). 구간 수가 안 맞으면 구분선을 뺀 문구로 통째 대조."""
    b = tight(mask)
    m = mask[b[1]:b[3], b[0]:b[2]]
    h = m.shape[0]
    col = m.any(0)
    parts, start, gap = [], None, 0
    for x, on in enumerate(col):
        if on:
            if start is None:
                start = x
            gap = 0
            end = x
        elif start is not None:
            gap += 1
            if gap > 1.0 * h:  # 임시값 — 글자 사이 공백보다 넓은 간격 = 구간 경계
                parts.append((start, end + 1))
                start, gap = None, 0
    if start is not None:
        parts.append((start, end + 1))
    # 아주 좁은 조각(구분선 '|' 잔여)은 버린다
    parts = [p for p in parts if p[1] - p[0] > 0.6 * h]
    if len(parts) != len(segs):
        return match_line(mask, " ".join(segs), cands)
    per_font = {}
    for (x0, x1), text in zip(parts, segs):
        sub = np.zeros_like(mask)
        sub[b[1]:b[3], b[0] + x0:b[0] + x1] = m[:, x0:x1]
        for r in match_line(sub, text, cands):
            per_font.setdefault(r["font"], []).append(r)
    out = []
    for name, rs in per_font.items():
        if len(rs) == len(segs):
            r = dict(rs[0])
            r["score"] = round(float(np.mean([x["score"] for x in rs])), 4)
            out.append(r)
    out.sort(key=lambda r: -r["score"])
    return out


# --------------------------------------------------------------------------- 시트·재조판

def to_rgba(mask, color):
    h, w = mask.shape
    arr = np.zeros((h, w, 4), "uint8")
    arr[..., :3] = np.array(color, "uint8")
    arr[..., 3] = (mask * 255).astype("uint8")
    return Image.fromarray(arr)


def render_colored(r, text, height_px, color):
    c = {"path": r["path"], "wght": r["wght"]}
    m = render_line(c, text, 200, r["tracking"])
    b = tight(m)
    m = m[b[1]:b[3], b[0]:b[2]]
    w = int(m.shape[1] * height_px / m.shape[0])
    im = Image.fromarray((m * 255).astype("uint8")).resize((max(1, w), height_px), Image.LANCZOS)
    return to_rgba(np.asarray(im, float) / 255, color)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=os.path.join(POC, "input", "magician's room.jpg"))
    ap.add_argument("--force-ocr", action="store_true")
    ap.add_argument("--elements", help="분석 단계 텍스트 요소 목록(role|x|y|w|h|storage_path|label) — 주면 요소 캔버스에서 글자를 찾는다")
    args = ap.parse_args()

    poster = Image.open(args.image).convert("RGB")
    W, H = poster.size
    slug = os.path.splitext(os.path.basename(args.image))[0].replace("'", "").replace(" ", "_")
    data = ocr(poster, os.path.join(OUT, f"{slug}_ocr.json"), args.force_ocr)
    # 운영에서는 사용자가 화면에서 문구를 확인·수정한다. PoC는 확인 결과를 여기 적는다(LLM 오독 기록용)
    corrections = CORRECTIONS.get(slug, {})
    for ln in data["lines"]:
        if ln["text"] in corrections:
            ln["ocr_text"], ln["text"] = ln["text"], corrections[ln["text"]]
        fix = BBOX_CORRECTIONS.get(slug, {}).get(ln["text"])
        if fix:
            ln["ocr_bbox"] = ln["bbox"]
            ln["bbox"] = [fix[0] / poster.width, fix[1] / poster.height, fix[2] / poster.width, fix[3] / poster.height]
    cands = font_candidates()
    print(f"OCR {len(data['lines'])}줄 ({data.get('_ms')}ms), 후보 폰트 {len(cands)}개(굵기 인스턴스 포함)")

    source = poster
    if args.elements:
        canvas = element_canvas(poster.size, args.elements, os.path.join(ROOT, "storage"))
        canvas.save(os.path.join(OUT, f"{slug}_text_canvas.png"))
        region = ndimage.binary_dilation(np.asarray(canvas)[..., 3] > 10, iterations=2)
        source = poster.convert("RGBA")
        source.putalpha(Image.fromarray((region * 255).astype("uint8")))
    report = {"image": args.image, "source": "text_elements" if args.elements else "poster",
              "ocr_ms": data.get("_ms"), "candidates": len(cands), "lines": []}
    rows = []
    box_of = {}
    for i, ln in enumerate(data["lines"]):
        entry = {k: ln.get(k) for k in ("text", "ocr_text", "role", "curved", "designed", "size_group", "bbox", "ocr_bbox")}
        if ln.get("role") == "LOGO" or ln.get("curved") or ln.get("designed"):
            entry["skipped"] = ("로고(이미지로 유지)" if ln.get("role") == "LOGO"
                                else "곡선 배치" if ln.get("curved") else "디자인 글자")
            report["lines"].append(entry)
            print(f"[{i}] 건너뜀({entry['skipped']}): {ln['text']}")
            continue
        x0, y0, x1, y1 = ln["bbox"]
        lh = (y1 - y0) * H
        px, py = max(1.5 * lh, 0.15 * (x1 - x0) * W), 0.6 * lh  # 임시값 — LLM bbox 오차를 감안한 여유
        box = (max(0, int(x0 * W - px)), max(0, int(y0 * H - py)), min(W, int(x1 * W + px)), min(H, int(y1 * H + py)))
        crop = source.crop(box)
        # core: LLM bbox의 가운데 60%(세로)·80%(가로) — 여기에 걸치는 덩어리만 이 줄의 글자로 본다
        core = (int(x0 * W - box[0] + 0.1 * (x1 - x0) * W), int(y0 * H - box[1] + 0.2 * lh),
                int(x1 * W - box[0] - 0.1 * (x1 - x0) * W), int(y1 * H - box[1] - 0.2 * lh))
        gm = glyph_mask(crop, core)
        if gm is None or tight(gm[0]) is None:
            entry["skipped"] = "글자 마스크 실패"
            report["lines"].append(entry)
            continue
        mask, fill, bg, others = gm
        t0 = time.time()
        segs = [x.strip() for x in ln["text"].split("|")]
        if len(segs) > 1:
            ranked = match_segments(mask, segs, cands)
            entry["segments"] = segs
        else:
            ranked = match_line(mask, ln["text"], cands)
        entry.update({"fill": [round(v) for v in fill], "background": [round(v) for v in bg],
                      "other_colors": [[round(v) for v in m] for m, _ in others],
                      "glyph_height_px": int(tight(mask)[3] - tight(mask)[1]),
                      "match_ms": int((time.time() - t0) * 1000), "top": ranked[:5]})
        report["lines"].append(entry)
        rows.append((ln, crop, mask, fill, ranked))
        box_of[id(crop)] = box
        print(f"[{i}] {ln['text']!r} ({ln.get('role')}) → " +
              ", ".join(f"{r['font']} {r['score']:.3f}" for r in ranked[:3]))

    # 대조 시트: 줄마다 한 블록 — 원본 · 글자 마스크 · 1~3위 렌더(원본 글자색). 타일은 폭 560px 이내로
    font = ImageFont.truetype(PRETENDARD[0] if PRETENDARD else "C:/Windows/Fonts/malgun.ttf", 16)
    MAXW, cell_h, lab_h = 560, 64, 22
    blocks = []
    for ln, crop, mask, fill, ranked in rows:
        b = tight(mask)
        orig = poster.crop((box_of[id(crop)][0] + b[0], box_of[id(crop)][1] + b[1],
                            box_of[id(crop)][0] + b[2], box_of[id(crop)][1] + b[3])).convert("RGBA")
        mk = Image.fromarray((mask[b[1]:b[3], b[0]:b[2]] * 255).astype("uint8"))
        tiles = [(f"원본: {ln['text']}", orig), ("글자 마스크", Image.merge("RGBA", [mk.point(lambda v: 255 - v)] * 3 + [Image.new("L", mk.size, 255)]))]
        for k, r in enumerate(ranked[:3]):
            tiles.append((f"{k + 1}위 {r['font']} · {r['score']:.2f} · {r['license']}",
                          render_colored(r, ln["text"], 120, [int(v) for v in fill])))
        fitted = []
        for lab, t in tiles:
            s_ = min(cell_h / t.height, MAXW / t.width)
            fitted.append((lab, t.resize((max(1, int(t.width * s_)), max(1, int(t.height * s_))), Image.LANCZOS)))
        blocks.append(fitted)
    sheet = Image.new("RGB", (MAXW * 5 + 6 * 12, len(blocks) * (cell_h + lab_h + 18) + 10), (34, 38, 56))
    d = ImageDraw.Draw(sheet)
    for bi, fitted in enumerate(blocks):
        y = 10 + bi * (cell_h + lab_h + 18)
        for ci, (lab, t) in enumerate(fitted):
            x = 12 + ci * (MAXW + 12)
            d.text((x, y), lab[:60], fill=(225, 225, 225), font=font)
            sheet.paste(t, (x, y + lab_h), t)
    sheet_path = os.path.join(OUT, f"{slug}_match_sheet.png")
    sheet.save(sheet_path)
    # 같은 크기·스타일 줄(size_group)은 한 폰트로 — 그룹 안 줄들의 점수 평균이 가장 높은 폰트(같은 역할 안에서)
    groups = {}
    for (ln, crop, mask, fill, ranked) in rows:
        groups.setdefault((ln.get("role"), ln.get("size_group")), []).append((ln, ranked))
    report["groups"] = []
    for (role, g), members in groups.items():
        totals = {}
        for ln, ranked in members:
            for r in ranked:
                totals.setdefault(r["font"], []).append(r["score"])
        cands_ok = {f: np.mean(v) for f, v in totals.items() if len(v) == len(members)}
        if not cands_ok:
            continue
        best = sorted(cands_ok.items(), key=lambda kv: -kv[1])[:3]
        report["groups"].append({"role": role, "size_group": g, "lines": [m[0]["text"] for m in members],
                                 "top": [{"font": f, "mean_score": round(float(v), 4)} for f, v in best]})
        print(f"그룹 {role}/{g} {[m[0]['text'] for m in members]} → " + ", ".join(f"{f} {v:.3f}" for f, v in best))
    json.dump(report, open(os.path.join(OUT, f"{slug}_report.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"시트: {sheet_path}")


if __name__ == "__main__":
    main()
