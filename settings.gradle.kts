pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.viaversion.com")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
  }
}

rootProject.name = "scheduler-bridge"

include(
  "common",
  "platforms:fabric",
  "platforms:fabric:v1_20_4",
  "platforms:fabric:v1_21_1",
  "platforms:fabric:v1_21_11",
  "platforms:fabric:v26_1_2",
  "platforms:fabric:v26_2",
  "platforms:paper",
  "platforms:spigot",
  "platforms:velocity",
  "platforms:folia",
  "platforms:neoforge",
  "platforms:neoforge:v1_21_11",
)
