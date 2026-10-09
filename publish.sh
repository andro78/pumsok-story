#!/usr/bin/env bash
# Pumsok Story release & publish script
# Usage: ./publish.sh "<Release Notes>" [patch|minor|major]
set -euo pipefail

cd "$(dirname "$0")"

NOTES="${1:-}"
BUMP="${2:-}"
SITE_DIR="/srv/nas/d32.org/site"
OUT_DIR="$SITE_DIR/pumsok"

if [ -n "$BUMP" ]; then
    OLD_NAME=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
    OLD_CODE=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)
    IFS=. read -r MA MI PA <<< "$OLD_NAME"
    case "$BUMP" in
        patch) PA=$((PA + 1)) ;;
        minor) MI=$((MI + 1)); PA=0 ;;
        major) MA=$((MA + 1)); MI=0; PA=0 ;;
        *) echo "Usage: ./publish.sh [notes] [patch|minor|major]" >&2; exit 1 ;;
    esac
    NEW_NAME="$MA.$MI.$PA"
    NEW_CODE=$((OLD_CODE + 1))
    echo "Bumping version: $OLD_NAME ($OLD_CODE) -> $NEW_NAME ($NEW_CODE)"
    sed -i "s/versionCode = $OLD_CODE/versionCode = $NEW_CODE/" app/build.gradle.kts
    sed -i "s/versionName = \"$OLD_NAME\"/versionName = \"$NEW_NAME\"/" app/build.gradle.kts
fi

CODE=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)
NAME=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
[ -n "$CODE" ] && [ -n "$NAME" ] || { echo "versionCode/versionName not found in app/build.gradle.kts" >&2; exit 1; }

echo "== Building Pumsok Story v$NAME (code $CODE) =="
./gradlew assembleRelease bundleRelease testDebugUnitTest

mkdir -p "$OUT_DIR"
APK_OUT="pumsok-v$NAME.apk"
AAB_OUT="pumsok-v$NAME.aab"

cp app/build/outputs/apk/release/app-release.apk "$OUT_DIR/$APK_OUT"
cp app/build/outputs/bundle/release/app-release.aab "$OUT_DIR/$AAB_OUT"
ln -sf "$APK_OUT" "$OUT_DIR/pumsok.apk"
ln -sf "$AAB_OUT" "$OUT_DIR/pumsok.aab"

APK_SHA=$(sha256sum "$OUT_DIR/$APK_OUT" | awk '{print $1}')
AAB_SHA=$(sha256sum "$OUT_DIR/$AAB_OUT" | awk '{print $1}')
APK_SIZE=$(stat -c "%s" "$OUT_DIR/$APK_OUT")
AAB_SIZE=$(stat -c "%s" "$OUT_DIR/$AAB_OUT")

if [ -z "$NOTES" ]; then
    NOTES="품속동화 v$NAME 릴리스"
fi

python3 - <<PY
import json
data = {
  "name": "품속동화 (Pumsok Story)",
  "package": "com.pumsok.story",
  "versionName": "$NAME",
  "versionCode": int("$CODE"),
  "releaseDate": "$(date +%Y-%m-%d)",
  "minSdk": 26,
  "targetSdk": 35,
  "url": "https://d32.org/pumsok/$APK_OUT",
  "sha256": "$APK_SHA",
  "apk": {
    "file": "$APK_OUT",
    "sizeBytes": int("$APK_SIZE"),
    "sha256": "$APK_SHA"
  },
  "aab": {
    "file": "$AAB_OUT",
    "sizeBytes": int("$AAB_SIZE"),
    "sha256": "$AAB_SHA"
  },
  "sampleStories": [
    {
      "title": "해와 달이 된 오누이",
      "author": "한국 전래동화",
      "file": "sun_and_moon.epub",
      "paragraphs": 22
    },
    {
      "title": "토끼와 거북이",
      "author": "이솝 우화",
      "file": "rabbit_and_turtle.epub",
      "paragraphs": 19
    }
  ],
  "notes": """$NOTES"""
}
with open("$OUT_DIR/version.json", "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print("Updated $OUT_DIR/version.json")
PY

sed -i -E "s/LATEST RELEASE · v[0-9.]+/LATEST RELEASE · v$NAME/g" "$OUT_DIR/index.html"
sed -i -E "s/pumsok-v[0-9.]+\\.apk/$APK_OUT/g" "$OUT_DIR/index.html"
sed -i -E "s/pumsok-v[0-9.]+\\.aab/$AAB_OUT/g" "$OUT_DIR/index.html"
sed -i -E "s/v[0-9.]+ · [0-9.]+ MB/v$NAME · 6.4 MB/g" "$OUT_DIR/index.html"
sed -i -E "/APK \\(직접 설치용\\)/ s/[0-9a-f]{64}/$APK_SHA/" "$OUT_DIR/index.html"
sed -i -E "/AAB \\(Google Play용\\)/ s/[0-9a-f]{64}/$AAB_SHA/" "$OUT_DIR/index.html"

sed -i -E "s#/pumsok/\">앱 페이지 열기 <small>v[0-9.]+#/pumsok/\">앱 페이지 열기 <small>v$NAME#g" "$SITE_DIR/index.html"

echo "== Successfully published Pumsok Story v$NAME ($CODE) =="
echo "APK: https://d32.org/pumsok/$APK_OUT (SHA: $APK_SHA)"
echo "AAB: https://d32.org/pumsok/$AAB_OUT (SHA: $AAB_SHA)"
