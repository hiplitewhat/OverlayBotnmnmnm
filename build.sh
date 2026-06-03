#!/bin/bash
# ──────────────────────────────────────────────────────────
#  OverlayBot — AniList Edition — Build Script
#  Command-line only, no Android Studio, no javac
#  Pipeline: aapt2 → ECJ → d8 → zipalign → apksigner
# ──────────────────────────────────────────────────────────
set -e

PRJ="/home/z/my-project/overlay-bot"
OUT="$PRJ/out"
OBJ="$PRJ/obj"
GEN="$PRJ/gen"

# Build tools
AAPT2="$PRJ/tools/sdk/build-tools/34.0.0/aapt2"
D8="$PRJ/tools/sdk/build-tools/34.0.0/d8"
ZIPALIGN="$PRJ/tools/sdk/build-tools/34.0.0/zipalign"
APKSIGNER="$PRJ/tools/sdk/build-tools/34.0.0/apksigner"
ECJ="$PRJ/tools/ecj.jar"
ANDROID_JAR="$PRJ/tools/sdk/platforms/android-34/android.jar"
KEYSTORE="$OUT/debug.keystore"

# Clean
rm -rf "$OBJ" "$GEN"
mkdir -p "$OBJ" "$GEN/com/overlaybot/anime"

echo "═══ STEP 1: aapt2 compile resources ═══"
RES_COMPILED="$OBJ/resources.zip"
"$AAPT2" compile --dir "$PRJ/res" -o "$RES_COMPILED"
echo "  ✓ Resources compiled"

echo "═══ STEP 2: aapt2 link ═══"
# No --proto-format — standard binary XML for broad compatibility
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$PRJ/AndroidManifest.xml" \
    --java "$GEN" \
    --custom-package com.overlaybot.anime \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 6 \
    --version-name "2.1-anilist-login" \
    -o "$OBJ/resources.apk" \
    "$RES_COMPILED"
echo "  ✓ Resources linked + R.java generated"

echo "═══ STEP 3: ECJ compile Java sources ═══"
java -jar "$ECJ" \
    -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ" \
    -sourcepath "$PRJ/src:$GEN" \
    "$PRJ/src/AnimeFetcher.java" \
    "$PRJ/src/WatchlistManager.java" \
    "$PRJ/src/AuthManager.java" \
    "$PRJ/src/AniListLoginActivity.java" \
    "$PRJ/src/OverlayService.java" \
    "$PRJ/src/MainActivity.java" \
    "$GEN/com/overlaybot/anime/R.java"
echo "  ✓ Java compiled to .class"

echo "═══ STEP 4: d8 dex ═══"
CLASS_FILES=$(find "$OBJ" -name "*.class" | sort)
"$D8" \
    --lib "$ANDROID_JAR" \
    --min-api 26 \
    --output "$OBJ" \
    $CLASS_FILES
echo "  ✓ DEX compiled"

echo "═══ STEP 5: Package APK ═══"
UNSIGNED="$OUT/OverlayBot-unsigned.apk"
cd "$OBJ"
cp resources.apk "$UNSIGNED"
zip -j "$UNSIGNED" classes.dex
cd "$PRJ"
echo "  ✓ APK packaged"

echo "═══ STEP 6: Zipalign ═══"
ALIGNED="$OUT/OverlayBot-aligned.apk"
"$ZIPALIGN" -f 4 "$UNSIGNED" "$ALIGNED"
echo "  ✓ Zipaligned"

echo "═══ STEP 7: Generate debug keystore (if needed) ═══"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Debug,O=OverlayBot,C=US"
    echo "  ✓ Debug keystore created"
else
    echo "  ✓ Debug keystore exists"
fi

echo "═══ STEP 8: Sign APK (v1 + v2 + v3) ═══"
SIGNED="$OUT/OverlayBot.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --min-sdk-version 26 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$SIGNED" \
    "$ALIGNED"
echo "  ✓ APK signed (v1+v2+v3)"

# Copy to download dir
cp "$SIGNED" "/home/z/my-project/download/OverlayBot.apk"

# Verify
echo ""
echo "═══ VERIFY ═══"
"$APKSIGNER" verify -v "$SIGNED" 2>&1 || true

echo ""
echo "═══════════════════════════════════════════"
echo "  BUILD SUCCESS!"
echo "  Output: $SIGNED"
echo "  Size: $(du -h "$SIGNED" | cut -f1)"
echo "  Also: /home/z/my-project/download/OverlayBot.apk"
echo "═══════════════════════════════════════════"
