val pluginName = "sbt-lm-coursier"
val pluginVersion = "1.1.0-M12"
// can't be matched by the launcher to find the plugin or its version
// things should still work fine anyway
addSbtPlugin("io.get-coursier" % pluginName % pluginVersion)
