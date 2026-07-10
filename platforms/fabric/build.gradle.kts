plugins {
  `java-library`
}

dependencies {
  api(project(":common"))
  compileOnly("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
}
