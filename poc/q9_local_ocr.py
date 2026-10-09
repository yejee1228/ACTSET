"""ACTSET PoC Q9 — 로컬 OCR(PaddleOCR)로 문구·줄 위치를 읽으면 LLM·사람 교정 없이 폰트 대조가 되는가.

q8에서는 LLM(GPT 비전)이 문구·위치를 읽었고, 오독("마술사의 꿈"·"밤")을 사람이 고쳤다. 이를 로컬 OCR로 바꾼다.
  1. PaddleOCR(한국어 모델, 우리 서버에서 실행 — 포스터를 외부로 보내지 않는다)로 줄 단위 문구·위치
  2. 정답(사람이 확인한 문구·위치, q8 교정 반영)과 비교: 문구 일치율(글자 오류율 CER), 줄 위치 겹침(IoU)
  3. OCR 결과를 그대로 q8 폰트 대조 입력으로 저장(사람 교정 없음) → python poc/q8_font_match.py --ocr-json ...

실행: python poc/q9_local_ocr.py
"""
import json
import os
import sys
import time

POC = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(POC, "out", "font-match")
POSTER = os.path.join(POC, "input", "magician's room.jpg")
sys.path.insert(0, POC)


def ground_truth(W, H):
    """q8 OCR(LLM) 결과에 사람 교정(문구·위치)을 반영한 것 = 정답."""
    import q8_font_match as q8
    data = json.load(open(os.path.join(OUT, "magicians_room_ocr.json"), encoding="utf-8"))
    fixes = q8.CORRECTIONS["magicians_room"]
    boxes = q8.BBOX_CORRECTIONS["magicians_room"]
    gt = []
    for ln in data["lines"]:
        t = fixes.get(ln["text"], ln["text"])
        b = boxes.get(t)
        bbox = [b[0] / W, b[1] / H, b[2] / W, b[3] / H] if b else ln["bbox"]
        gt.append({"text": t, "bbox": bbox, "role": ln["role"], "curved": ln.get("curved", False)})
    return gt


def cer(a, b):
    """글자 오류율(공백 무시) = 편집 거리 / 정답 길이."""
    a, b = a.replace(" ", ""), b.replace(" ", "")
    d = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        prev, d[0] = d[0], i
        for j, cb in enumerate(b, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (ca != cb))
    return d[len(b)] / max(1, len(b))


def iou(p, q):
    ix = max(0, min(p[2], q[2]) - max(p[0], q[0]))
    iy = max(0, min(p[3], q[3]) - max(p[1], q[1]))
    inter = ix * iy
    u = (p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - inter
    return inter / u if u > 0 else 0


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    import argparse
    from PIL import Image
    from paddleocr import PaddleOCR
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=POSTER)
    ap.add_argument("--tag", default="magicians_room")
    ap.add_argument("--no-gt", action="store_true", help="정답 비교 없이 OCR 결과만 저장")
    args = ap.parse_args()
    poster_path = args.image
    W, H = Image.open(poster_path).size

    t0 = time.time()
    ocr = PaddleOCR(lang="korean", use_doc_orientation_classify=False, use_doc_unwarping=False,
                    use_textline_orientation=False)
    load_s = time.time() - t0
    t0 = time.time()
    res = ocr.predict(poster_path)[0]
    run_s = time.time() - t0
    lines = []
    for text, score, box in zip(res["rec_texts"], res["rec_scores"], res["rec_boxes"]):
        x0, y0, x1, y1 = [float(v) for v in box]
        lines.append({"text": text, "score": round(float(score), 3), "bbox": [x0 / W, y0 / H, x1 / W, y1 / H]})
    print(f"PaddleOCR: 모델 로드 {load_s:.1f}s, 추론 {run_s:.1f}s, {len(lines)}줄")

    if args.no_gt:
        gt = []
    else:
        gt = ground_truth(W, H)
    rows, hit, cers = [], 0, []
    for g in gt:
        best = max(lines, key=lambda l: iou(l["bbox"], g["bbox"]), default=None)
        bi = iou(best["bbox"], g["bbox"]) if best else 0
        # 같은 줄이 OCR에서 여러 조각으로 나뉠 수 있다 — 정답 bbox 안에 중심이 있는 조각을 왼→오 순으로 이어 붙여 비교
        parts = sorted([l for l in lines if g["bbox"][0] <= (l["bbox"][0] + l["bbox"][2]) / 2 <= g["bbox"][2]
                        and g["bbox"][1] <= (l["bbox"][1] + l["bbox"][3]) / 2 <= g["bbox"][3]],
                       key=lambda l: l["bbox"][0])
        joined = " ".join(p["text"] for p in parts)
        c = cer(joined, g["text"]) if parts else 1.0
        found = bi >= 0.5
        hit += found
        cers.append(min(c, 1.0))
        rows.append({"gt": g["text"], "role": g["role"], "curved": g["curved"], "ocr": joined, "cer": round(c, 3),
                     "iou": round(bi, 2), "pieces": len(parts)})
    for r in rows if gt else []:
        print(f"  {r['role']:<5} IoU {r['iou']:.2f} CER {r['cer']:.2f} 조각{r['pieces']} | 정답 {r['gt']!r} | OCR {r['ocr']!r}")
    summary = {"engine": "PaddleOCR 3.7 korean", "load_s": round(load_s, 1), "infer_s": round(run_s, 1),
               "ocr_lines": len(lines), "gt_lines": len(gt), "detected_iou50": hit,
               "mean_cer": round(sum(cers) / len(cers), 3) if cers else None, "rows": rows, "raw": lines}
    json.dump(summary, open(os.path.join(OUT, f"{args.tag}_paddleocr.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    if gt:
        print(f"줄 찾기(IoU≥0.5) {hit}/{len(gt)}, 평균 CER {summary['mean_cer']:.3f}")

    # q8 입력(사람 교정 없음): 같은 높이끼리 size_group, 역할·곡선·디자인 판정은 없다(OCR은 모른다)
    hs = sorted(set(round((l["bbox"][3] - l["bbox"][1]) * H / 4) for l in lines))
    q8_lines = []
    for l in lines:
        g = round((l["bbox"][3] - l["bbox"][1]) * H / 4)
        q8_lines.append({"text": l["text"], "bbox": l["bbox"], "role": "TEXT", "curved": False, "designed": False,
                         "size_group": hs.index(g) + 1})
    json.dump({"lines": q8_lines, "_ms": int(run_s * 1000), "_source": "paddleocr"},
              open(os.path.join(OUT, f"{args.tag}_ocr_paddle_q8.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
