# csbt

coursier-based sbt launcher

[![Build Status](https://travis-ci.org/coursier/sbt-launcher.svg?branch=master)](https://travis-ci.org/coursier/sbt-launcher)
[![Maven Central](https://img.shields.io/maven-central/v/io.get-coursier/sbt-launcher.svg)](https://maven-badges.herokuapp.com/maven-central/io.get-coursier/sbt-launcher)

Extremely experimental, suffering a few issues. In particular, compiling
several modules at once (e.g. by running `csbt test:compile` from the coursier
sources) often results in spurious scalac errors. Also, it might have issues
with `*.sbt` files under `~/.sbt` - the whole point of csbt would be to scrap
those for standard configuration files.
