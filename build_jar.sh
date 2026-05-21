#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out/production/ApksMetaDataReader"
JAR_DIR="$ROOT/out/artifacts/ApksMetaDataReader_jar"
JAR="$JAR_DIR/ApksMetaDataReader.jar"
MAIN="com.wanyor.android.app.MetaDataReader"
SRCS=$(mktemp /tmp/apk_sources.XXXXXX)
MF=$(mktemp /tmp/apk_manifest.XXXXXX)

echo "[1/5] Clean output..."
rm -rf "$OUT" "$JAR_DIR"
mkdir -p "$OUT" "$JAR_DIR"

echo "[2/5] Collect source files..."
find "$SRC" -name "*.java" > "$SRCS"
if [ ! -s "$SRCS" ]; then
    echo "ERROR: no .java files found"
    rm -f "$SRCS"
    exit 1
fi

echo "[3/5] Compile..."
javac -encoding UTF-8 -d "$OUT" @"$SRCS"
rm -f "$SRCS"

echo "[4/5] Package JAR..."
printf "Manifest-Version: 1.0\nMain-Class: %s\n\n" "$MAIN" > "$MF"
jar cfm "$JAR" "$MF" -C "$OUT" .
rm -f "$MF"

echo "[5/5] Copy JAR to project root..."
cp "$JAR" "$ROOT/ApksMetaDataReader.jar"

echo ""
echo "Done: $JAR"
echo "Copy: $ROOT/ApksMetaDataReader.jar"
echo "Run:  java -jar \"$ROOT/ApksMetaDataReader.jar\" [apk-path]"
