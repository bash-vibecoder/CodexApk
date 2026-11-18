#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/main/jniLibs/arm64-v8a
CODEXT_NATIVE_SRC=vendor/termux-app/terminal-emulator/src/main/jni/termux.c
CODEXT_NATIVE_OUT=app/src/main/jniLibs/arm64-v8a/libtermux.so
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    CODEXT_NDK_TOOLS="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
    CODEXT_NDK_CC=$(find "$CODEXT_NDK_TOOLS" -path '*/bin/aarch64-linux-android26-clang' -print -quit)
    "$CODEXT_NDK_CC" -shared -fPIC -Os -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 "$CODEXT_NATIVE_SRC" -o "$CODEXT_NATIVE_OUT"
elif [[ -x /proc/1/root/data/data/com.termux/files/usr/bin/clang-21 ]]; then
    # Read-only use of the host's existing compiler. No Termux config/files are changed.
    CODEXT_ANDROID_PROJECT="${CODEXT_ANDROID_PROJECT:-/data/local/lix/root/p7.codex}"
    chroot /proc/1/root /system/bin/sh -c '
        export PATH=/data/data/com.termux/files/usr/bin:/system/bin
        export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib
        /data/data/com.termux/files/usr/bin/clang --target=aarch64-linux-android26 \
            -shared -fPIC -Os -Wall -Wextra -Werror -Wl,-z,max-page-size=16384 \
            "$1/vendor/termux-app/terminal-emulator/src/main/jni/termux.c" \
            -o "$1/app/src/main/jniLibs/arm64-v8a/libtermux.so"
    ' codext-native "$CODEXT_ANDROID_PROJECT"
else
    echo 'Set ANDROID_NDK_HOME to an Android NDK to compile the terminal JNI library.' >&2
    exit 1
fi
file "$CODEXT_NATIVE_OUT"
