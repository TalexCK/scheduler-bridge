plugins {
  `java-library`
}

dependencies {
  api(project(":common"))
  compileOnly("org.spigotmc:spigot-api:${property("spigotApiVersion")}")
}
