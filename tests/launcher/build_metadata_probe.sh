#!/bin/sh
# Build the self-contained test APK with standard tools, without Gradle or network dependencies.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SDK=${ANDROID_HOME:-$HOME/android-sdk}
TOOLS=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}
KEYSTORE=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
SRC="$ROOT/tests/launcher/metadata-probe"
OUT="$ROOT/build/metadata-probe"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT" "$TMP/classes" "$TMP/dex" "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000
fi
javac --release 8 -cp "$ANDROID_JAR" -d "$TMP/classes" "$SRC/Probe.java"
jar cf "$TMP/classes.jar" -C "$TMP/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$TMP/dex" "$TMP/classes.jar"
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$SRC/AndroidManifest.xml" -o "$TMP/unsigned.apk"
(cd "$TMP/dex" && zip -q "$TMP/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$OUT/ZettaBridge-Metadata-Probe.apk" "$TMP/aligned.apk"
"$TOOLS/apksigner" verify "$OUT/ZettaBridge-Metadata-Probe.apk"
printf '%s\n' "$OUT/ZettaBridge-Metadata-Probe.apk"
