#!/usr/bin/env bash
# Selective jadx for Spotify 9.1.90 once APK is at /home/box/Downloads/
set -euo pipefail
APK="${1:-/home/box/Downloads/spotify-9.1.90.2270.apk}"
OUT="${2:-/workspace/apk/spotify-9.1.90}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
mkdir -p "$OUT"
if [[ ! -f "$APK" ]]; then
  echo "Missing APK: $APK" >&2
  echo "See /home/box/Downloads/spotify-9.1.90.2270.EXPECTED" >&2
  exit 1
fi
echo "sha256=$(sha256sum "$APK" | awk '{print $1}') size=$(stat -c%s "$APK")"
strings "$APK" | rg -n 'canvaz|Canvas|color-lyrics|scdn\.co|ShareCard|share_card' | head -80 || true
echo "APK=$APK OUT=$OUT (full jadx not run by default)"

# --- selective decompilation (full jadx of this APK is ~68k classes and too slow) -----------------
# 1) find which classesN.dex holds a string:   grep -l -a "<anchor>" sp-x/classes*.dex
# 2) decompile just that dex:                  jadx -j 3 --no-res --no-debug-info -d out/dN sp-x/classesN.dex
# (unzip classes*.dex from the APK into /workspace/apk/sp-x first)
