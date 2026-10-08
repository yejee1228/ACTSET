"""q9 자동 채점 — 시험용 PDF(폰트를 아는 포스터)의 렌더 이미지에서 OCR + 폰트 대조가 정답 폰트를 맞혔는가."""
import json, os, sys
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "font-match")
# poc/make_test_pdf.py가 쓴 폰트(정답). 문구 일부로 판정한다
TRUTH = [("방", "DoHyeon-Regular"), ("반짝", "DoHyeon-Regular"), ("나는", "DoHyeon-Regular"), ("마술사의", "DoHyeon-Regular"),
         ("주최", "NanumSquareB"), ("관람", "NanumSquareB"), ("24개월", "NanumSquareB"), ("이상", "NanumSquareB"), ("문의", "NanumSquareB"),
         ("마술", "NanumGothic-Bold"), ("그림자", "NanumGothic-Bold"), ("레이저", "NanumGothic-Bold"),
         ("MAGIC", "Tinos-Regular"), ("SHADOW", "Tinos-Regular"), ("LASER", "Tinos-Regular"),
         ("LED", "BMHANNAPro"), ("함께", "BMHANNAPro"), ("하는", "BMHANNAPro"), ("그림자,", "BMHANNAPro"), ("마술쇼", "BMHANNAPro"), ("빛과", "BMHANNAPro")]
def truth(text):
    for key, font in TRUTH:  # 앞에서부터 — "그림자,"(곡선)가 "그림자"(작은 한글)보다 먼저 걸리지 않게 정확 일치 우선
        if text == key:
            return font
    for key, font in TRUTH:
        if key in text:
            return font
    return None
def main(tag):
    sys.stdout.reconfigure(encoding="utf-8")
    r = json.load(open(os.path.join(OUT, f"{tag}_report.json"), encoding="utf-8"))
    n = top1 = top3 = 0
    for l in r["lines"]:
        if not l.get("top"):
            continue
        t = truth(l["text"])
        if t is None:
            continue
        names = [x["font"] for x in l["top"]]
        n += 1
        top1 += names[0] == t
        top3 += t in names[:3]
        print(f"{'✓' if names[0] == t else ('△' if t in names[:3] else '✗')} {l['text']!r:<26} 정답 {t:<18} 1위 {names[0]} {l['top'][0]['score']:.2f}")
    print(f"top-1 {top1}/{n} ({top1 / n:.0%}), top-3 {top3}/{n} ({top3 / n:.0%})")
if __name__ == "__main__":
    main(sys.argv[1])
