plugins {
    base
    `java-library` apply false
}

allprojects {
    group = providers.gradleProperty("projectGroup").get()
    version = providers.gradleProperty("projectVersion").get()
}

subprojects {
    apply(plugin = "java-library")

    base {
        val archivePath = project.path.removePrefix(":").replace(':', '-')
        archivesName.set("${rootProject.name}-${archivePath}")
    }

    extensions.configure<JavaPluginExtension>("java") {
        withSourcesJar()
    }

    val defaultJavaRelease = providers.gradleProperty("javaRelease")
    val moduleJavaRelease = providers.gradleProperty("${project.path.removePrefix(":").replace(':', '.')}.javaRelease")
    val targetJavaRelease = providers.gradleProperty("targetJavaRelease")

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaRelease.orElse(moduleJavaRelease).orElse(defaultJavaRelease).map(String::toInt))
    }

    tasks.withType<ProcessResources>().configureEach {
        fun propertyValue(name: String): String = providers.gradleProperty(name).orNull
            ?: project.findProperty(name)?.toString()
            ?: error("Missing Gradle property '$name'")

        fun optionalPropertyValue(name: String, fallback: String): String = providers.gradleProperty(name).orNull
            ?: project.findProperty(name)?.toString()
            ?: fallback

        val explicitTargetMinecraftVersion = providers.gradleProperty("targetMinecraftVersion").orNull
            ?: project.findProperty("targetMinecraftVersion")?.toString()
        val targetMinecraftVersion = explicitTargetMinecraftVersion ?: propertyValue("minecraftSupportRange")
        val fabricMinecraftVersionPredicate = optionalPropertyValue(
            "targetFabricMinecraftVersionPredicate",
            explicitTargetMinecraftVersion?.let { "=$it" }
                ?: optionalPropertyValue("fabricMinecraftVersionPredicate", "=$targetMinecraftVersion")
        )
        val modMinecraftVersionRange = optionalPropertyValue(
            "targetModMinecraftVersionRange",
            explicitTargetMinecraftVersion?.let { "[$it,$it]" }
                ?: optionalPropertyValue("modMinecraftVersionRange", "[$targetMinecraftVersion,$targetMinecraftVersion]")
        )
        val resourceProperties = mapOf(
            "id" to propertyValue("modId"),
            "name" to propertyValue("modName"),
            "version" to project.version.toString(),
            "description" to propertyValue("modDescription"),
            "minecraftSupportRange" to propertyValue("minecraftSupportRange"),
            "targetMinecraftVersion" to targetMinecraftVersion,
            "fabricMinecraftVersionPredicate" to fabricMinecraftVersionPredicate,
            "modMinecraftVersionRange" to modMinecraftVersionRange,
        )

        inputs.properties(resourceProperties)
        filesMatching(
            listOf(
                "fabric.mod.json",
                "plugin.yml",
                "paper-plugin.yml",
                "velocity-plugin.json",
                "META-INF/mods.toml",
                "META-INF/neoforge.mods.toml",
            )
        ) {
            expand(resourceProperties)
        }
    }
}

tasks.register("printMinecraftSupport") {
    group = "help"
    description = "Prints the configured Minecraft support range for this release train."

    doLast {
        println("${providers.gradleProperty("modName").get()} supports Minecraft ${providers.gradleProperty("minecraftSupportRange").get()} (${providers.gradleProperty("releaseTrain").get()})")
    }
}
