plugins {
    `java-library`
}

extra["minecraftVersion"] = "1.21.11"

sourceSets {
    main {
        java.srcDir(project(":platforms:neoforge").projectDir.resolve("src/main/java"))
        resources.srcDir(project(":platforms:neoforge").projectDir.resolve("src/main/resources"))
    }
}

dependencies {
    api(project(":common"))
    compileOnly("net.neoforged:neoforge:${property("neoForgeApiVersion1_21_11")}")
}
