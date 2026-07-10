plugins {
  `java-library`
}

dependencies {
  api(project(":common"))
  compileOnly("com.velocitypowered:velocity-api:${property("velocityApiVersion")}")
  compileOnly("com.viaversion:viaversion-api:${property("viaVersionApiVersion")}")
}
