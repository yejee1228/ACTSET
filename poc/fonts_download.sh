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
