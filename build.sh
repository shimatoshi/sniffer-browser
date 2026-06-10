#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

SDK="$HOME/android-sdk"
PLATFORM="$SDK/platforms/android-34/android.jar"
AAPT2="$(command -v aapt2)"
# Termux同梱d8(R8 3.3.20)はネスト匿名クラスでNPE。SDK 35のd8.jar(8.6.2)を使う
D8_JAR="$SDK/build-tools/35.0.0/lib/d8.jar"
ZIPALIGN="$(command -v zipalign)"
APKSIGNER="$(command -v apksigner)"
KEYSTORE="$HOME/.android/debug.keystore"
KEYSTORE_PASS="android"
KEY_ALIAS="androiddebugkey"

APK_NAME="sniffer-browser"
MIN_SDK=23
TARGET_SDK=28

BUILD="build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/apk" "$BUILD/compiled-res"

echo "== aapt2 compile resources =="
$AAPT2 compile --dir src/main/res -o "$BUILD/compiled-res"

echo "== aapt2 link =="
RES_ARGS=""
for f in "$BUILD/compiled-res"/*; do RES_ARGS="$RES_ARGS $f"; done
$AAPT2 link \
  --manifest src/main/AndroidManifest.xml \
  -I "$PLATFORM" \
  --target-sdk-version "$TARGET_SDK" \
  --min-sdk-version "$MIN_SDK" \
  --java "$BUILD/gen" \
  -o "$BUILD/base.apk" \
  $RES_ARGS

echo "== javac =="
find src/main/java "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
# -source/target 8: Java9+のindy文字列連結を避ける（古いd8がNPEで落ちるため）
javac \
  -source 8 -target 8 \
  -d "$BUILD/classes" \
  -classpath "$PLATFORM" \
  -Xlint:-options -nowarn \
  @"$BUILD/sources.txt"

echo "== d8 dex =="
CLASSES=$(find "$BUILD/classes" -name '*.class')
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$BUILD/apk" $CLASSES

echo "== assemble apk =="
cp "$BUILD/base.apk" "$BUILD/apk/$APK_NAME-unsigned.apk"
cd "$BUILD/apk"
zip -j "$APK_NAME-unsigned.apk" classes.dex >/dev/null
cd - > /dev/null

echo "== zipalign =="
$ZIPALIGN -f -p 4 "$BUILD/apk/$APK_NAME-unsigned.apk" "$BUILD/apk/$APK_NAME-aligned.apk"

echo "== sign =="
$APKSIGNER sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
  --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEYSTORE_PASS" \
  --min-sdk-version "$MIN_SDK" \
  --out "$BUILD/$APK_NAME.apk" \
  "$BUILD/apk/$APK_NAME-aligned.apk"

echo
echo "built: $BUILD/$APK_NAME.apk"
ls -la "$BUILD/$APK_NAME.apk"
