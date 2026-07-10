plugins {
  `java-library`
}

dependencies {
  api(project(":common"))
  compileOnly("net.neoforged:neoforge:${property("neoForgeApiVersion")}")
  compileOnly("net.neoforged:bus:8.0.2")
  compileOnly("net.neoforged.fancymodloader:loader:4.0.29")
}
