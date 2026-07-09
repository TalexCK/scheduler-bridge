#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

PLATFORM=""
MC_VERSION=""
DO_CLEAN=0
DO_ALL=0
DO_LIST=0
GRADLE_ARGS=()

usage() {
  cat <<'USAGE'
Usage:
  ./build.sh --fabric --1.21.11
  ./build.sh --fabric --26.2
  ./build.sh --paper --clean
  ./build.sh --velocity
  ./build.sh --platform fabric --version 1.21.11
  ./build.sh --list
  ./build.sh --all

Supported platform flags:
  --fabric --paper --spigot --velocity --folia --neoforge

Fabric and NeoForge use version-specific target modules. Paper, Spigot, Velocity,
and Folia build as single compatibility modules.

Extra Gradle arguments can be passed after --, for example:
  ./build.sh --fabric --1.21.11 -- --info
USAGE
}

list_targets() {
  cat <<'TARGETS'
fabric   1.20.4   :platforms:fabric:v1_20_4       Java 17
fabric   1.21.1   :platforms:fabric:v1_21_1       Java 21
fabric   1.21.11  :platforms:fabric:v1_21_11      Java 21
fabric   26.1.2   :platforms:fabric:v26_1_2       Java 21
fabric   26.2     :platforms:fabric:v26_2         Java 21
paper    all      :platforms:paper                 Java 8
spigot   all      :platforms:spigot                Java 8
velocity all      :platforms:velocity              Java 17
folia    all      :platforms:folia                 Java 17
neoforge 1.21.11  :platforms:neoforge:v1_21_11    Java 21
TARGETS
}

resolve_target() {
  local platform="$1"
  local version="$2"

  case "${platform}:${version}" in
    fabric:1.20.4)   TARGET_PROJECT=":platforms:fabric:v1_20_4"; TARGET_JAVA=17; TARGET_VERSION="1.20.4" ;;
    fabric:1.21.1)   TARGET_PROJECT=":platforms:fabric:v1_21_1"; TARGET_JAVA=21; TARGET_VERSION="1.21.1" ;;
    fabric:1.21.11)  TARGET_PROJECT=":platforms:fabric:v1_21_11"; TARGET_JAVA=21; TARGET_VERSION="1.21.11" ;;
    fabric:26.1.2)   TARGET_PROJECT=":platforms:fabric:v26_1_2"; TARGET_JAVA=21; TARGET_VERSION="26.1.2" ;;
    fabric:26.2)     TARGET_PROJECT=":platforms:fabric:v26_2"; TARGET_JAVA=21; TARGET_VERSION="26.2" ;;
    paper:*|paper:all) TARGET_PROJECT=":platforms:paper"; TARGET_JAVA=8; TARGET_VERSION="${version/all/}" ;;
    spigot:*|spigot:all) TARGET_PROJECT=":platforms:spigot"; TARGET_JAVA=8; TARGET_VERSION="${version/all/}" ;;
    velocity:*|velocity:all) TARGET_PROJECT=":platforms:velocity"; TARGET_JAVA=17; TARGET_VERSION="${version/all/}" ;;
    folia:*|folia:all) TARGET_PROJECT=":platforms:folia"; TARGET_JAVA=17; TARGET_VERSION="${version/all/}" ;;
    neoforge:|neoforge:all|neoforge:1.21.11) TARGET_PROJECT=":platforms:neoforge:v1_21_11"; TARGET_JAVA=21; TARGET_VERSION="1.21.11" ;;
    *)
      echo "Unsupported target: ${platform} ${version}" >&2
      echo >&2
      list_targets >&2
      exit 2
      ;;
  esac
}

gradle_cmd() {
  if [[ -x "$ROOT_DIR/gradlew" ]]; then
    echo "$ROOT_DIR/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    echo "gradle"
  else
    echo "Gradle was not found. Install Gradle or add a Gradle wrapper with ./gradlew." >&2
    exit 127
  fi
}

run_gradle_target() {
  local project="$1"
  local version="$2"
  local java_release="$3"
  local gradle_bin
  gradle_bin="$(gradle_cmd)"

  local tasks=()
  if [[ "$DO_CLEAN" -eq 1 ]]; then
    tasks+=("${project}:clean")
  fi
  tasks+=("${project}:build")

  local args=("-PtargetJavaRelease=$java_release")
  if [[ -n "$version" ]]; then
    args+=("-PtargetMinecraftVersion=$version")
    echo "Building ${project} for Minecraft ${version} with Java release ${java_release}"
  else
    echo "Building ${project} compatibility module with Java release ${java_release}"
  fi

  "$gradle_bin" "${args[@]}" "${tasks[@]}" "${GRADLE_ARGS[@]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --list)
      DO_LIST=1
      shift
      ;;
    --all)
      DO_ALL=1
      shift
      ;;
    --clean)
      DO_CLEAN=1
      shift
      ;;
    --platform)
      PLATFORM="${2:-}"
      shift 2
      ;;
    --platform=*)
      PLATFORM="${1#--platform=}"
      shift
      ;;
    --version)
      MC_VERSION="${2:-}"
      shift 2
      ;;
    --version=*)
      MC_VERSION="${1#--version=}"
      shift
      ;;
    --fabric|--paper|--spigot|--velocity|--folia|--neoforge)
      PLATFORM="${1#--}"
      shift
      ;;
    --[0-9]*)
      MC_VERSION="${1#--}"
      shift
      ;;
    --)
      shift
      GRADLE_ARGS+=("$@")
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$DO_LIST" -eq 1 ]]; then
  list_targets
  exit 0
fi

if [[ "$DO_ALL" -eq 1 ]]; then
  while read -r platform version _project _java_label java_release; do
    [[ -z "$platform" ]] && continue
    resolve_target "$platform" "$version"
    run_gradle_target "$TARGET_PROJECT" "$TARGET_VERSION" "$TARGET_JAVA"
  done < <(list_targets)
  exit 0
fi

if [[ -z "$PLATFORM" ]]; then
  usage >&2
  exit 2
fi

if [[ -z "$MC_VERSION" ]]; then
  case "$PLATFORM" in
    paper|spigot|velocity|folia) MC_VERSION="all" ;;
    neoforge) MC_VERSION="1.21.11" ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
fi

resolve_target "$PLATFORM" "$MC_VERSION"
run_gradle_target "$TARGET_PROJECT" "$TARGET_VERSION" "$TARGET_JAVA"
