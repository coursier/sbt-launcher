#!/usr/bin/env bash
set -e

cd "$(dirname "${BASH_SOURCE[0]}")/.."

default_version() {
  git describe --tags --abbrev=0 | sed 's@^v@@'
}

VERSION="${VERSION:-$(default_version)}"
OUTPUT="${OUTPUT:-csbt}"

if which coursier >/dev/null 2>&1; then
  COURSIER=coursier
else
  COURSIER="target/coursier"
  if [ ! -e "$COURSIER" ]; then
    mkdir -p "$(dirname "$COURSIER")"
    curl -Lo "$COURSIER" https://github.com/coursier/coursier/releases/download/v2.0.0-RC3-4/coursier
    chmod +x "$COURSIER"
  fi
fi

"$COURSIER" bootstrap \
  "io.get-coursier:sbt-launcher_2.12:$VERSION" \
  "io.get-coursier:coursier-okhttp_2.12:2.0.0-RC3-2" \
  -r central \
  --no-default \
  --shared org.scala-sbt:launcher-interface \
  -o "$OUTPUT" \
  --property jline.shutdownhook=false \
  --property jna.nosys=true \
  --embed-files=false \
  "$@"
