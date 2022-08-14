> This repository is deprecated, and will likely be archived in the near future. The default sbt launcher now uses coursier upfront to download the sbt class path, so that the launcher here doesn't bring any significant feature any more. It is advised to switch to the default sbt launcher rather than using the sbt launcher of this repository.

# csbt

coursier-based sbt launcher

[![Build Status](https://travis-ci.org/coursier/sbt-launcher.svg?branch=master)](https://travis-ci.org/coursier/sbt-launcher)
[![Maven Central](https://img.shields.io/maven-central/v/io.get-coursier/sbt-launcher_2.12.svg)](https://maven-badges.herokuapp.com/maven-central/io.get-coursier/sbt-launcher_2.12)


## How to use

Grab the custom sbt-extras script relying on the launcher from this repository [here](https://raw.githubusercontent.com/coursier/sbt-extras/master/sbt):
```bash
$ curl -Lo sbt https://raw.githubusercontent.com/coursier/sbt-extras/master/sbt
$ chmod +x sbt
$ ./sbt …
```
