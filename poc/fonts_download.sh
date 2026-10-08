#!/usr/bin/env bash
# q8 폰트 대조 PoC의 후보 폰트(Google Fonts 저장소, OFL/Apache)를 poc/fonts/ 에 내려받는다.
# 사용: bash poc/fonts_download.sh   (GitHub API 비인증 한도 시간당 60회 — 폰트 38종이면 충분)
set -e
cd "$(dirname "$0")" && mkdir -p fonts && cd fonts
FAMILIES="blackhansans dohyeon jua gothica1 nanumgothic nanummyeongjo nanumpenscript nanumbrushscript gaegu sunflower gugi dokdo eastseadokdo gamjaflower himelody yeonsung songmyung stylish poorstory cutefont kiranghaerang singleday ibmplexsanskr hahmlet gowundodum gowunbatang notosanskr notoserifkr dongle orbit bagelfatone tinos ptserif librebaskerville ebgaramond playfairdisplay cinzel cormorantgaramond"
for d in $FAMILIES; do
  lic=ofl
  files=$(curl -s "https://api.github.com/repos/google/fonts/contents/ofl/$d" | grep -o '"name": "[^"]*\.ttf"' | sed 's/"name": "//; s/"$//')
  if [ -z "$files" ]; then
    lic=apache
    files=$(curl -s "https://api.github.com/repos/google/fonts/contents/apache/$d" | grep -o '"name": "[^"]*\.ttf"' | sed 's/"name": "//; s/"$//')
  fi
  for f in $files; do
    case "$f" in *Italic*|*italic*) continue;; esac
    case "$d:$f" in gothica1:*Thin*|gothica1:*Light*|gothica1:*Medium*|gothica1:*SemiBold*|ibmplexsanskr:*Thin*|ibmplexsanskr:*Light*|ibmplexsanskr:*Medium*|ibmplexsanskr:*Text*|ibmplexsanskr:*ExtraLight*|sunflower:*Light*|sunflower:*Medium*) continue;; esac
    [ -f "$f" ] || curl -s -o "$f" "https://raw.githubusercontent.com/google/fonts/main/$lic/$d/$(echo "$f" | sed 's/\[/%5B/g; s/\]/%5D/g')"
  done
done
ls | wc -l

# 2026-10-09 추가 — 포스터에 많이 쓰이는 무료 한글 폰트(상업 사용 + 서버 임베딩 허용 확인, poc/FONT-LICENSES.md)
# 원본 보관 저장소 github.com/fonts-archive 에서 TTF만 받는다
mkdir -p kr_free
REPOS="BMHANNA11yrs BMHANNAAir BMHANNAPro BMEULJIRO BMEuljiro10yearslater BMEuljiroOraeorae BMKkubulim Jalnan JalnanGothic GmarketSans Cafe24Ssurround Cafe24SsurroundAir Cafe24Ohsquare Cafe24OhsquareAir Cafe24Dongdong NEXONLv1Gothic NEXONLv2Gothic Maplestory KartriderKor CookieRun Aggro TmonMonsori S-CoreDream NanumSquare NanumSquareNeo NanumSquareRound"
for r in $REPOS; do
  # 파일 목록은 jsDelivr API로(GitHub API 비인증 한도 회피), 파일은 jsDelivr CDN에서. 하위 폴더(subsets)는 뺀다
  for f in $(curl -s "https://data.jsdelivr.com/v1/packages/gh/fonts-archive/$r@main?structure=flat" | grep -o '"name": *"/[^"/]*\.ttf"' | sed 's/"name": *"\///; s/"$//'); do
    [ -f "kr_free/$f" ] || curl -sL -o "kr_free/$f" "https://cdn.jsdelivr.net/gh/fonts-archive/$r@main/$f"
  done
done
ls kr_free | wc -l
