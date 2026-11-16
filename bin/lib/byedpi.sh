#!/usr/bin/env bash
# Guise: cross-compile ByeDPI (library/byedpi, MIT, https://github.com/hufrea/byedpi)
# as a standalone executable for every app ABI and drop it into
# app/src/main/jniLibs/<abi>/libbyedpi.so so it is extracted to nativeLibraryDir
# (useLegacyPackaging = true) and can be exec'd like other bundled binaries.
#
# Source list / flags follow byedpi's Makefile and romanvht/ByeByeDPI's
# app/src/main/cpp/CMakeLists.txt (GPL-3.0), minus the JNI glue: Guise runs
# byedpi as a separate process instead of in-process via JNI.
set -euo pipefail

if [[ "$OSTYPE" =~ ^darwin ]]; then PROJECT=${PROJECT:-$PWD}; HOST_TAG=darwin-x86_64
else PROJECT=${PROJECT:-$(realpath .)}; HOST_TAG=linux-x86_64; fi

SRC="$PROJECT/library/byedpi"
OUT="$PROJECT/app/src/main/jniLibs"
API=21

if [ ! -f "$SRC/main.c" ]; then
  echo "byedpi sources missing; run: git submodule update --init library/byedpi" >&2
  exit 1
fi

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
    NDK="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
  fi
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "Android NDK not found; set ANDROID_NDK_HOME" >&2
  exit 1
fi
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

SOURCES=(packets.c main.c conev.c proxy.c desync.c mpool.c extend.c)

build() {
  local abi=$1 triple=$2
  mkdir -p "$OUT/$abi"
  (cd "$SRC" && "$TC/clang" --target="$triple$API" \
    -D_DEFAULT_SOURCE -I. -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter \
    -fPIE -pie -ffunction-sections -fdata-sections \
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -s \
    -o "$OUT/$abi/libbyedpi.so" "${SOURCES[@]}")
  echo "built $OUT/$abi/libbyedpi.so"
}

build armeabi-v7a armv7a-linux-androideabi
build arm64-v8a aarch64-linux-android
build x86 i686-linux-android
build x86_64 x86_64-linux-android
