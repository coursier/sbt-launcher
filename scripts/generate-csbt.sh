#!/usr/bin/env bash
set -e

cd "$(dirname "${BASH_SOURCE[0]}")/.."

VERSION="${VERSION:-1.1.0}"
OUTPUT="${OUTPUT:-csbt}"

if which coursier >/dev/null 2>&1; then
  COURSIER=coursier
else
  COURSIER="target/coursier"
  if [ ! -e "$COURSIER" ]; then
    mkdir -p "$(dirname "$COURSIER")"
    curl -Lo "$COURSIER" https://github.com/coursier/coursier/releases/download/v1.1.0-M12/coursier
    chmod +x "$COURSIER"
  fi
fi

"$COURSIER" bootstrap \
  "io.get-coursier:sbt-launcher_2.12:$VERSION" \
  "io.get-coursier:coursier-okhttp_2.12:1.1.0-M9" \
  -r central \
  --no-default \
  -i launcher \
  -I launcher:org.scala-sbt:launcher-interface:1.0.4 \
  -o "$OUTPUT" \
  --property jline.shutdownhook=false \
  --property jna.nosys=true \
  --embed-files=false \
  "$@"
