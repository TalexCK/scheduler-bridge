# Scheduler Bridge

Gradle Java scaffold for a cross-platform Minecraft scheduler bridge.

## Modules

- `common`: platform-neutral scheduler contracts and bridge facade.
- `platforms:paper`, `platforms:spigot`, and `platforms:velocity`: single compatibility modules; these APIs already cover broad Minecraft ranges well.
- `platforms:folia`: single Folia compatibility module.
- `platforms:fabric`: shared Fabric adapter source.
- `platforms:fabric:v<version>`: buildable Fabric target module for one Minecraft version.
- `platforms:neoforge:v1_21_11`: NeoForge target module.

Current target modules:

```text
fabric   1.20.4   :platforms:fabric:v1_20_4
fabric   1.21.1   :platforms:fabric:v1_21_1
fabric   1.21.11  :platforms:fabric:v1_21_11
fabric   26.1.2   :platforms:fabric:v26_1_2
fabric   26.2     :platforms:fabric:v26_2
paper    all      :platforms:paper
spigot   all      :platforms:spigot
velocity all      :platforms:velocity
folia    all      :platforms:folia
neoforge 1.21.11  :platforms:neoforge:v1_21_11
```

## Support Target

The scaffold records the requested support window as Minecraft `1.8-1.26.2` for the `2026.06` release train in `gradle.properties`, with loader-specific range formats for Fabric and NeoForge-style metadata.

The current platform modules use starter API dependency pins so the project has a concrete shape. Real multi-version support should be filled in with version-specific source sets, CI matrix builds, loader-specific Gradle plugins, and remapped Minecraft classpaths before release.

## Build Commands

```sh
./build.sh --list
./build.sh --fabric --1.21.11
./build.sh --fabric --26.2
./build.sh --paper --clean
./build.sh --velocity
./build.sh --neoforge
./build.sh --all
./build.sh --fabric --1.21.11 -- --info
```

`build.sh` uses `./gradlew` when a Gradle wrapper exists, otherwise it falls back to `gradle` from `PATH`.

Direct Gradle examples:

```sh
gradle :platforms:fabric:v1_21_11:build -PtargetMinecraftVersion=1.21.11 -PtargetJavaRelease=21
gradle :platforms:fabric:v26_2:build -PtargetMinecraftVersion=26.2 -PtargetJavaRelease=21
gradle :platforms:paper:build -PtargetJavaRelease=8
gradle printMinecraftSupport
```
