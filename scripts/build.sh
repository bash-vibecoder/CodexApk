#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CODEXT_ROOT="$PWD"
CODEXT_JAVA="${CODEXT_JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin}"
[[ -x "$CODEXT_JAVA/javac" ]] || CODEXT_JAVA="$(dirname "$(command -v javac)")"
mkdir -p .tools build dist
cp app/src/main/AndroidManifest.xml build/AndroidManifest.xml
if [[ "${CODEXT_DEBUGGABLE:-false}" == "true" ]]; then
    sed -i 's/<application /<application android:debuggable="true" /' build/AndroidManifest.xml
fi
CODEXT_ANDROID_JAR="${CODEXT_ANDROID_JAR:-/root/p2.vid/.local/android-sdk/platforms/android-35/android.jar}"
CODEXT_RESOURCE_JAR="${CODEXT_RESOURCE_JAR:-/root/p1/.tools/android-34.jar}"
CODEXT_R8="${CODEXT_R8:-/root/p1/.tools/r8.jar}"
if [[ ! -f "$CODEXT_ANDROID_JAR" ]]; then
    CODEXT_ANDROID_JAR="$CODEXT_ROOT/.tools/android.jar"
    [[ -f "$CODEXT_ANDROID_JAR" ]] || curl -fsSL --retry 3 https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-35/android.jar -o "$CODEXT_ANDROID_JAR"
fi
if [[ ! -f "$CODEXT_R8" ]]; then
    CODEXT_R8="$CODEXT_ROOT/.tools/r8.jar"
    [[ -f "$CODEXT_R8" ]] || curl -fsSL --retry 3 https://storage.googleapis.com/r8-releases/raw/8.9.35/r8.jar -o "$CODEXT_R8"
fi
if [[ ! -f "$CODEXT_RESOURCE_JAR" ]]; then
    CODEXT_RESOURCE_JAR="$CODEXT_ROOT/.tools/android-34.jar"
    [[ -f "$CODEXT_RESOURCE_JAR" ]] || curl -fsSL --retry 3 https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar -o "$CODEXT_RESOURCE_JAR"
fi
[[ -f .tools/annotation.jar ]] || curl -fsSL --retry 3 https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar -o .tools/annotation.jar
if [[ "${1:-}" == '--runtime' || ! -f app/src/main/assets/runtime.zip ]]; then python3 scripts/prepare_runtime.py; fi
[[ -f app/src/main/jniLibs/arm64-v8a/libtermux.so ]] || bash scripts/build-native.sh
rm -rf build/classes build/gen build/dex build/staging
mkdir -p build/classes build/gen build/dex build/staging/lib/arm64-v8a
aapt package -f -m --auto-add-overlay \
    -M build/AndroidManifest.xml \
    -S app/src/main/res -S vendor/termux-app/terminal-view/src/main/res \
    -A app/src/main/assets -I "$CODEXT_RESOURCE_JAR" \
    --extra-packages com.termux.view -J build/gen -F build/resources.apk
find app/src/main/java vendor/termux-app/terminal-emulator/src/main/java vendor/termux-app/terminal-view/src/main/java build/gen -name '*.java' > build/sources.txt
"$CODEXT_JAVA/javac" -encoding UTF-8 --release 8 \
    -classpath "$CODEXT_ANDROID_JAR:.tools/annotation.jar" -d build/classes @build/sources.txt
"$CODEXT_JAVA/jar" cf build/classes.jar -C build/classes .
"$CODEXT_JAVA/java" -Xmx1536m -cp "$CODEXT_R8" com.android.tools.r8.D8 --min-api 26 --lib "$CODEXT_ANDROID_JAR" --classpath .tools/annotation.jar --output build/dex build/classes.jar
cp build/dex/classes*.dex build/staging/
cp app/src/main/jniLibs/arm64-v8a/libtermux.so build/staging/lib/arm64-v8a/
cp build/resources.apk build/unsigned.apk
python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    for p in Path('build/staging').rglob('*'):
        if p.is_file(): z.write(p, str(p.relative_to('build/staging')))
PY
[[ -f .tools/codext-signing.jks ]] || "$CODEXT_JAVA/keytool" -genkeypair -keystore .tools/codext-signing.jks -alias codext -storepass android -keypass android -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ACodexT Local Build' -noprompt
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks .tools/codext-signing.jks --ks-key-alias codext --ks-pass pass:android --key-pass pass:android --out dist/ACodexT-v1.6.2-arm64.apk build/aligned.apk
apksigner verify --verbose dist/ACodexT-v1.6.2-arm64.apk
aapt dump badging dist/ACodexT-v1.6.2-arm64.apk | head -8
sha256sum dist/ACodexT-v1.6.2-arm64.apk > dist/ACodexT-v1.6.2-arm64.apk.sha256
ls -lh dist/ACodexT-v1.6.2-arm64.apk
