#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ACODEXT_TEST_JAVA=/usr/lib/jvm/java-17-openjdk-arm64/bin
ACODEXT_TEST_ANDROID=/root/p2.vid/.local/android-sdk/platforms/android-35/android.jar
mkdir -p build/validation/classes build/validation/dex
aapt package -f -M tests/android/AndroidManifest.xml -I /root/p1/.tools/android-34.jar -F build/validation/resources.apk
"$ACODEXT_TEST_JAVA/javac" --release 8 -encoding UTF-8 -classpath "$ACODEXT_TEST_ANDROID:build/classes.jar" -d build/validation/classes tests/android/SmokeInstrumentation.java
"$ACODEXT_TEST_JAVA/jar" cf build/validation/classes.jar -C build/validation/classes .
"$ACODEXT_TEST_JAVA/java" -cp /root/p1/.tools/r8.jar com.android.tools.r8.D8 --min-api 26 --lib "$ACODEXT_TEST_ANDROID" --classpath build/classes.jar --output build/validation/dex build/validation/classes.jar
cp build/validation/resources.apk build/validation/unsigned.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/validation/unsigned.apk','a') as z: z.write('build/validation/dex/classes.dex','classes.dex')
PY
zipalign -f -p 4 build/validation/unsigned.apk build/validation/aligned.apk
apksigner sign --ks .tools/codext-signing.jks --ks-key-alias codext --ks-pass pass:android --key-pass pass:android --out build/validation/validation.apk build/validation/aligned.apk
