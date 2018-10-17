#!/usr/bin/env bash
set -e

VERSION="${VERSION:-1.0.0}"

coursier bootstrap \
  "io.get-coursier:sbt-launcher_2.12:$VERSION" \
  "io.get-coursier:coursier-okhttp_2.12:1.1.0-M7" \
  -r sonatype:releases \
  --no-default \
  -i launcher \
  -I launcher:org.scala-sbt:launcher-interface:1.0.0 \
  -o csbt \
  -J -Djline.shutdownhook=false \
  --embed-files=false \
  "$@"
