# csbt

coursier-based sbt launcher

Extremely experimental, suffering a few issues. In particular, compiling
several modules at once (e.g. by running `csbt test:compile` from the coursier
sources) often results in spurious scalac errors. Also, it might have issues
with `*.sbt` files under `~/.sbt` - the whole point of csbt would be to scrap
those for standard configuration files.
