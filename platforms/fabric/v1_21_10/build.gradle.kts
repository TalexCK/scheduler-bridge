plugins {
  `java-library`
}

extra["minecraftVersion"] = "1.21.10"

sourceSets {
  main {
    java.srcDir(project(":platforms:fabric").projectDir.resolve("src/main/java"))
    resources.srcDir(project(":platforms:fabric").projectDir.resolve("src/main/resources"))
  }
}

dependencies {
  api(project(":common"))
  compileOnly("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
}
