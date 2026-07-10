plugins {
  `java-library`
}

dependencies {
  api(project(":common"))
  compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
}
