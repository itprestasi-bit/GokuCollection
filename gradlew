#!/usr/bin/env sh
# Lightweight bootstrap for this generated milestone.
# It downloads the official Gradle 9.5.0 binary distribution and executes it.
set -eu
GRADLE_VERSION="9.5.0"
BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/collection-field-bootstrap"
DIST_DIR="$BASE_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$BASE_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget "$URL" -O "$ZIP_FILE"
    else
      echo "curl or wget is required to bootstrap Gradle." >&2
      exit 1
    fi
  fi
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP_FILE" -d "$BASE_DIR"
fi
exec "$DIST_DIR/bin/gradle" "$@"
