plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    compileOnly("net.neoforged:neoforge:${property("neoForgeApiVersion")}")
}
