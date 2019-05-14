
lazy val check = taskKey[Unit]("")

check := {
  val repo = fullResolvers.value
    .find(_.name == "local")
    .getOrElse {
      throw new Exception("local repository not found")
    }
  assert(repo.toString.contains("${ivy.home}"), "'${ivy.home}' not found in local repo " + repo)
}
