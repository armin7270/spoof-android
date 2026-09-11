#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build-helper.sh -- compile the optional wrong_seq root helper and install it
# on a connected device.
#
# The helper is NOT packaged in the APK: it is a standalone binary a rooted
# device installs to /data/local/tmp. The app looks for it there (and in its
# own nativeLibraryDir) at connect time.
#
# Usage:
#   ./tools/build-helper.sh            (build + push to the connected device)
#   ./tools/build-helper.sh build-only (build without pushing)
#
# Requires ANDROID_NDK_HOME or ANDROID_NDK_ROOT to point at an NDK.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/../app/src/main/cpp/spoof_helper.c"
OUT="$SCRIPT_DIR/build"

[ -f "$SRC" ] || { echo "[!] cannot find spoof_helper.c at $SRC"; exit 1; }

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
    echo "[!] set ANDROID_NDK_HOME to your Android NDK, e.g."
    echo "    export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/26.3.11579264"
    exit 1
fi

CLANG_BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n1 || true)"
[ -n "$CLANG_BIN" ] || { echo "[!] no clang toolchain under $NDK/toolchains/llvm/prebuilt"; exit 1; }

mkdir -p "$OUT"

# aarch64 for modern phones, armv7 for the handful of older rooted devices.
TARGETS=(aarch64-linux-android24 armv7a-linux-androideabi24)
NAMES=(spoofhelper-arm64 spoofhelper-arm)

BUILT=()
for i in "${!TARGETS[@]}"; do
    t="${TARGETS[$i]}"; exe="${NAMES[$i]}"
    echo "[*] building $t -> $OUT/$exe"
    "$CLANG_BIN/$t-clang" "$SRC" -o "$OUT/$exe" -pthread -O2 -s
    BUILT+=("$OUT/$exe")
done

echo "[+] built: ${BUILT[*]}"

if [ "${1:-}" = "build-only" ]; then
    echo "[*] build-only requested, skipping install"
    exit 0
fi

for f in "${BUILT[@]}"; do
    echo "[*] installing $(basename "$f")"
    adb push "$f" /data/local/tmp/spoofhelper
    adb shell chmod 755 /data/local/tmp/spoofhelper
done

echo "[+] done. Enable 'Root mode' in the app and pick the wrong_seq desync."
