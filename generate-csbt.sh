#!/usr/bin/env bash
set -e

VERSION="${VERSION:-1.1.0-M1}"

coursier bootstrap \
  "io.get-coursier:sbt-launcher_2.12:$VERSION" \
  "io.get-coursier:coursier-okhttp_2.12:1.1.0-M7" \
  -r central \
  --no-default \
  -i launcher \
  -I launcher:org.scala-sbt:launcher-interface:1.0.0 \
  -o csbt \
  -J -Djline.shutdownhook=false \
  --embed-files=false \
  "$@"
