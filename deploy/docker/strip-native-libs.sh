#!/bin/sh
#
# Removes foreign-platform native libraries from the ONNX Runtime jar inside a
# Spring Boot fat jar.
#
# WHY THIS EXISTS
#
# com.microsoft.onnxruntime ships one jar containing natives for five platforms,
# roughly 590 MB uncompressed:
#
#   win-x64        351 MB   (includes the CUDA and TensorRT execution providers)
#   osx-x64        103 MB
#   osx-aarch64     99 MB
#   linux-x64       19.7 MB <- the only set a linux/amd64 container can load
#   linux-aarch64   16.8 MB
#
# A Linux container needs 19.7 MB of that. The rest costs image size, registry
# storage, pull time on every node scale-up, and vulnerability-scanner noise, for
# libraries the JVM on this platform will never dlopen.
#
# WHY A SCRIPT RATHER THAN A RUN ONE-LINER
#
# The first version was a single twenty-line RUN with backslash continuations. It
# failed, and the error pointed at a line number inside a shell string that Docker
# had already mangled twice in the log. Anything with this many steps belongs in a
# file where `set -x` works and the failure names a real line.
#
# Usage: strip-native-libs.sh <fat-jar> <keep-platform>
#
set -eu

JAR="${1:?usage: strip-native-libs.sh <fat-jar> <keep-platform>}"
KEEP="${2:-linux-x64}"

NATIVE_ROOT="ai/onnxruntime/native"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Absolute, because every step below changes directory.
JAR="$(readlink -f "$JAR")"

# The glob must be onnxruntime-[0-9]*, not onnxruntime-*.
#
# Two jars match the looser pattern:
#   onnxruntime-1.21.1.jar         <- Microsoft's, ~130 MB, holds the natives
#   onnxruntime-engine-0.40.0.jar  <- DJL's thin wrapper, no natives at all
#
# The first attempt matched the DJL wrapper, found no ai/onnxruntime/native
# directory inside it, and skipped - silently shipping the full 130 MB.
ONNX_ENTRY="$(unzip -Z1 "$JAR" 'BOOT-INF/lib/onnxruntime-[0-9]*.jar' 2>/dev/null | head -n 1 || true)"

if [ -z "$ONNX_ENTRY" ]; then
    echo "strip-native-libs: no onnxruntime in $(basename "$JAR"), nothing to do"
    exit 0
fi

echo "strip-native-libs: found $ONNX_ENTRY"

# 1. Pull the nested jar out, preserving its BOOT-INF/lib/... path so it can be
#    put back at exactly the same entry name.
unzip -q "$JAR" "$ONNX_ENTRY" -d "$WORK/extracted"
NESTED="$WORK/extracted/$ONNX_ENTRY"
BEFORE_BYTES="$(wc -c < "$NESTED")"

# 2. Unpack it and delete every platform except the one being kept.
mkdir -p "$WORK/unpacked"
( cd "$WORK/unpacked" && unzip -q "$NESTED" )

if [ ! -d "$WORK/unpacked/$NATIVE_ROOT" ]; then
    echo "strip-native-libs: $NATIVE_ROOT absent - jar layout changed, leaving it alone" >&2
    exit 0
fi

echo "strip-native-libs: platforms present:"
( cd "$WORK/unpacked/$NATIVE_ROOT" && du -sh ./* 2>/dev/null | sed 's/^/  /' )

if [ ! -d "$WORK/unpacked/$NATIVE_ROOT/$KEEP" ]; then
    echo "strip-native-libs: target platform '$KEEP' not in the jar - refusing to strip" >&2
    exit 1
fi

find "$WORK/unpacked/$NATIVE_ROOT" -mindepth 1 -maxdepth 1 \
     ! -name "$KEEP" -exec rm -rf {} +

# 3. Repack the nested jar.
rm -f "$NESTED"
( cd "$WORK/unpacked" && zip -qr "$NESTED" . )
AFTER_BYTES="$(wc -c < "$NESTED")"

# 4. Replace the entry in the fat jar.
#
#    STORED, not deflated. Spring Boot's loader reads nested jars by mapping them
#    directly out of the outer archive, which requires them to be uncompressed.
#    A deflated nested entry produces "Unable to open nested entry" at startup -
#    and only at startup, never at build time.
( cd "$WORK/extracted" && zip -q -0 -X "$JAR" "$ONNX_ENTRY" )

echo "strip-native-libs: onnxruntime $((BEFORE_BYTES / 1024 / 1024)) MB -> $((AFTER_BYTES / 1024 / 1024)) MB (kept $KEEP)"
