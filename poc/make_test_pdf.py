"""시험용 "캔바식" 포스터 PDF — magician's room을 그림 1장 + 실제 폰트 텍스트로 다시 만든다.

캔바 "PDF 인쇄용" 내보내기와 같은 구조: 그림은 이미지, 글자는 폰트를 넣은 텍스트(서브셋), 오프셋 그림자는 같은 문구를
다른 색으로 한 번 더 그린 사본, 곡선 문구는 글자마다 회전한 텍스트. 진짜 캔바 PDF로 검증하기 전 판독기·파이프라인 시험용이다.
위치·크기·색은 원본 포스터에서 잰 값(q8 PoC)이고, 폰트는 상업·임베딩 허용 폰트만 쓴다(poc/FONT-LICENSES.md).

실행: python poc/make_test_pdf.py  → poc/input/magicians_room_test.pdf
"""
import math
import os

from PIL import Image
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

POC = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(POC)
F = os.path.join(POC, "fonts")
OUT = os.path.join(POC, "input", "magicians_room_test.pdf")
W, H = 750, 1000          # 원본 px
PT = 0.75                 # px → pt (562.5×750pt)

FONTS = {
    "DoHyeon": os.path.join(F, "DoHyeon-Regular.ttf"),
    "HannaPro": os.path.join(F, "kr_free", "BMHANNAPro.ttf"),
    "NanumGothicBold": os.path.join(F, "NanumGothic-Bold.ttf"),
    "Tinos": os.path.join(F, "Tinos-Regular.ttf"),
    "NanumSquareB": os.path.join(F, "kr_free", "NanumSquareB.ttf"),
}


def visual():
    """글자 없는 그림 = 분석이 저장한 그림 요소(배경·별빛·구름·로고·달과 마술사·장식)를 원래 자리에 합친 것."""
    canvas_ = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    rows = [l.strip().split("|") for l in open(os.path.join(POC, "out", "font-match", "visual_elements.txt"), encoding="utf-8")]
    for role, x, y, w, h, path, z, *_ in sorted(rows, key=lambda r: int(r[6])):
        el = Image.open(os.path.join(ROOT, "storage", path)).convert("RGBA").resize((int(w), int(h)))
        canvas_.alpha_composite(el, (int(x), int(y)))
    return canvas_.convert("RGB")


def draw(c, font, size_px, rgb, x_px, baseline_px, text):
    c.setFont(font, size_px * PT)
    c.setFillColorRGB(*[v / 255 for v in rgb])
    c.drawString(x_px * PT, (H - baseline_px) * PT, text)


def main():
    for name, path in FONTS.items():
        pdfmetrics.registerFont(TTFont(name, path))
    img = visual()
    img.save(os.path.join(POC, "out", "font-match", "test_pdf_visual.png"))
    c = canvas.Canvas(OUT, pagesize=(W * PT, H * PT))
    c.drawImage(ImageReader(img), 0, 0, W * PT, H * PT)

    yellow, pink, cyan = (254, 222, 94), (232, 64, 170), (80, 220, 200)
    # 제목 3줄 — 그림자 사본(청록 +4,+3 / 분홍 -3,-1) 먼저, 본체 나중(원본에서 잰 오프셋)
    for text, size, x, base in [("반짝반짝 빛나는", 62, 128, 267), ("마술사의", 116, 126, 379), ("방", 212, 476, 375)]:
        draw(c, "DoHyeon", size, cyan, x + 4, base + 3, text)
        draw(c, "DoHyeon", size, pink, x - 3, base - 1, text)
        draw(c, "DoHyeon", size, yellow, x, base, text)
    # 작은 한글 + 영문
    for ko, en, cx in [("마술", "MAGIC", 222), ("그림자", "SHADOW", 375), ("레이저", "LASER", 545)]:
        c.setFont("NanumGothicBold", 14 * PT)
        w = pdfmetrics.stringWidth(ko, "NanumGothicBold", 14 * PT) / PT
        draw(c, "NanumGothicBold", 14, (185, 198, 233), cx - w / 2, 424, ko)
        w = pdfmetrics.stringWidth(en, "Tinos", 29 * PT) / PT
        draw(c, "Tinos", 29, (238, 250, 255), cx - w / 2, 452, en)
    # 정보 줄
    info = "주최 인생마술 | 관람연령 24개월 이상 | 문의 010-3013-4643"
    w = pdfmetrics.stringWidth(info, "NanumSquareB", 20 * PT) / PT
    draw(c, "NanumSquareB", 20, (235, 252, 255), (W - w) / 2, 967, info)
    # 곡선 카피 — 캔바처럼 글자마다 회전(원 중심 (375, 760), 반지름 620)
    copy = "빛과 그림자, LED가 함께 하는 마술쇼"
    size = 30
    cx, cy, r = 375, 760, 620
    total = sum(pdfmetrics.stringWidth(ch, "HannaPro", size) for ch in copy)
    ang = -total / r / 2
    for ch in copy:
        cw = pdfmetrics.stringWidth(ch, "HannaPro", size)
        a = ang + cw / r / 2
        px, py = cx + r * math.sin(a), cy - r * math.cos(a)
        c.saveState()
        c.translate(px * PT, (H - py) * PT)
        c.rotate(-math.degrees(a))
        c.setFont("HannaPro", size * PT)
        c.setFillColorRGB(1, 1, 1)
        c.drawString(-cw / 2 * PT, 0, ch)
        c.restoreState()
        ang += cw / r
    c.showPage()
    c.save()
    print("저장:", OUT)


if __name__ == "__main__":
    main()
