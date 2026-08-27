"""ACTSET PoC 실행 — Q3(재합성) / Q4(규격변환) / Q5(인쇄해상도) 검증"""
import json, os
from PIL import Image
from engine import (FORMATS, RULES, FormatSpec, placeholder_visual,
                    render_layer2, recompose_background)

OUT = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(OUT, exist_ok=True)

# --- 테스트 케이스 A: 클래식 (정보량 많음) --------------------------------
CLASSIC = {
    "genre": "클래식",
    "main_title": "겨울 나그네",
    "subtitle": "슈베르트 연가곡 전곡",
    "sessions": [{"display": "2026. 3. 14 (토) 19:30", "is_undetermined": False}],
    "venue": {"name": "예술의전당 리사이틀홀", "is_undetermined": False},
    "running_time": "70분 (인터미션 없음)",
    "cast": [{"name": "김서현", "part": "바리톤"}, {"name": "이지우", "part": "피아노"}],
    "price_items": [{"label": "R석", "price": 50000}, {"label": "S석", "price": 30000}],
    "age": "8세 이상 관람",
    "organizer_group": {"presenter": ["반달컴퍼니"], "organizer": ["ACTSET"]},
    "inquiry": {"phone": "02-000-0000"},
    "mandatory_notices": ["본 공연은 한국문화예술위원회의 후원으로 제작되었습니다."],
}

# --- 테스트 케이스 B: 어린이공연 (정보량 적음, 일정·장소 미정) --------------
KIDS = {
    "genre": "어린이공연",
    "main_title": "구름빵 대모험",
    "subtitle": None,
    "sessions": [{"display": None, "is_undetermined": True}],
    "venue": {"name": None, "is_undetermined": True},
    "cast": [{"name": "박하늘", "part": "홍비"}],
    "price_items": [{"label": "전석", "price": 20000}],
    "age": "36개월 이상",
    "organizer_group": {"presenter": ["반달컴퍼니"]},
    "mandatory_notices": [],
}

PALETTES = {
    "클래식": [(14, 18, 34), (58, 44, 92), (150, 140, 210)],
    "어린이공연": [(24, 16, 40), (196, 92, 64), (255, 206, 120)],
}

report = []


def save(img, name):
    p = os.path.join(OUT, name)
    img.save(p, quality=92)
    return p


# ===========================================================================
# 1. 원본 포스터 생성 (Layer 1 + Layer 2)
# ===========================================================================
posters = {}
for tag, info in (("classic", CLASSIC), ("kids", KIDS)):
    spec = FORMATS["poster"]
    bg = placeholder_visual(spec, PALETTES[info["genre"]], seed=1 if tag == "classic" else 4)
    bg.save(os.path.join(OUT, f"{tag}_layer1.png"))
    img, omap, dropped, fits = render_layer2(bg, info, spec, RULES["poster"])
    save(img, f"{tag}_01_poster.jpg")
    posters[tag] = (bg, info, omap)
    report.append(f"[포스터] {tag}: 생략={dropped or '없음'} 영역맞음={fits} 객체수={len(omap)}")

# ===========================================================================
# Q3. 정보 수정 → Layer 1 재사용, Layer 2만 재렌더링
# ===========================================================================
import copy, time
bg, info, _ = posters["classic"]
changed = copy.deepcopy(info)
changed["sessions"][0]["display"] = "2026. 4. 25 (토) 17:00"     # 날짜 변경
changed["venue"]["name"] = "롯데콘서트홀"                          # 장소 변경

t0 = time.time()
img2, omap2, _, _ = render_layer2(bg, changed, FORMATS["poster"], RULES["poster"])
elapsed = time.time() - t0
save(img2, "classic_02_poster_정보수정후.jpg")
report.append(f"[Q3] 재합성 소요 {elapsed*1000:.0f}ms / 이미지생성 API 호출 0회 "
              f"/ Layer1 파일 재사용 여부: {'예' if bg is posters['classic'][0] else '아니오'}")

# 비교 이미지(원본 | 수정본)
a = Image.open(os.path.join(OUT, "classic_01_poster.jpg"))
b = Image.open(os.path.join(OUT, "classic_02_poster_정보수정후.jpg"))
cmp = Image.new("RGB", (a.width * 2 + 40, a.height), (245, 245, 248))
cmp.paste(a, (0, 0)); cmp.paste(b, (a.width + 40, 0))
cmp.resize((cmp.width // 2, cmp.height // 2), Image.LANCZOS).save(
    os.path.join(OUT, "Q3_정보수정_비교.jpg"), quality=90)

# ===========================================================================
# Q4. 규격 변환
# ===========================================================================
targets = ["sns_1x1", "story", "web_banner", "banner_wide", "ticket_site", "x_banner"]
bg, info, _ = posters["classic"]
thumbs = []
for fid in targets:
    spec, rule = FORMATS[fid], RULES[fid]
    nbg, needs_out = recompose_background(bg, spec)
    img, omap, dropped, fits = render_layer2(nbg, info, spec, rule)
    save(img, f"classic_03_{fid}.jpg")
    thumbs.append((spec.label, img))
    report.append(f"[Q4] {spec.label:<14} {spec.width}x{spec.height} "
                  f"생략={','.join(dropped) if dropped else '없음':<28} "
                  f"영역맞음={str(fits):<5} 배경확장필요={'예' if needs_out else '아니오'}")

# 규격 변환 결과 한 장으로
CELL = 420
sheet_w = CELL * 3 + 80
rows = (len(thumbs) + 2) // 3
sheet = Image.new("RGB", (sheet_w, rows * (CELL + 60) + 20), (247, 247, 250))
from PIL import ImageDraw
from engine import font as ft
sd = ImageDraw.Draw(sheet)
for i, (label, im) in enumerate(thumbs):
    r, c = divmod(i, 3)
    im2 = im.copy(); im2.thumbnail((CELL - 30, CELL - 30), Image.LANCZOS)
    x = c * CELL + 25 + (CELL - 30 - im2.width) // 2
    y = r * (CELL + 60) + 20
    sheet.paste(im2, (x, y))
    sd.text((c * CELL + 25, y + CELL - 20), label, font=ft(20, True), fill=(30, 32, 44))
sheet.save(os.path.join(OUT, "Q4_규격변환_결과.jpg"), quality=90)

# ===========================================================================
# Q5. 인쇄 해상도 (A3 300dpi)
# ===========================================================================
A3 = FormatSpec("poster_a3_300", "A3 포스터 300dpi", 3508, 4961, "원본", dpi=300,
                is_print_target=True)
src_bg = posters["classic"][0]
scale_needed = A3.width / src_bg.width
up = src_bg.resize((A3.width, A3.height), Image.LANCZOS)   # 실서비스는 AI 업스케일러
img300, _, dropped, fits = render_layer2(up, CLASSIC, A3, RULES["poster"])
img300.save(os.path.join(OUT, "Q5_A3_300dpi.jpg"), quality=88)
# 텍스트 선명도 확인용 100% 크롭
crop = img300.crop((int(A3.width*0.10), int(A3.height*0.62),
                    int(A3.width*0.90), int(A3.height*0.78)))
crop.save(os.path.join(OUT, "Q5_텍스트_100퍼센트확대.jpg"), quality=92)
report.append(f"[Q5] A3 300dpi = {A3.width}x{A3.height}px / "
              f"배경 업스케일 배율 {scale_needed:.1f}배 필요 / "
              f"Layer2는 300dpi로 재렌더링(벡터급 선명도)")

print("\n".join(report))
with open(os.path.join(OUT, "report.txt"), "w") as f:
    f.write("\n".join(report))
