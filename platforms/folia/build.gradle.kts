plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    compileOnly("dev.folia:folia-api:${property("foliaApiVersion")}")
}
