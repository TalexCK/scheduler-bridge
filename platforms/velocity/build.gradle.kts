plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    compileOnly("com.velocitypowered:velocity-api:${property("velocityApiVersion")}")
}
