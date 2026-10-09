#!/usr/bin/env bash
# Uses installed JDK, ffprobe and ffmpeg; no Android SDK, downloads or test framework.
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' 'TAPSCENE_PACKAGE_CHECKS_NOT_RUN: java is missing; no software was installed.' >&2
  exit 77
fi
for media_tool in ffprobe ffmpeg; do
  if ! command -v "$media_tool" >/dev/null 2>&1; then
    printf 'TAPSCENE_PACKAGE_CHECKS_NOT_RUN: %s is missing; no software was installed.\n' "$media_tool" >&2
    exit 77
  fi
done
COMPILER=()
FLAGS=()
if command -v javac >/dev/null 2>&1; then
  COMPILER=(javac)
  FLAGS=(--release 17 -Xlint:all -Werror)
  COMPILER_DESCRIPTION='javac --release 17 (Java 17 API surface enforced)'
elif java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; then
  # Trimmed runtime images may expose the installed compiler module but omit
  # javac and ct.sym. Source/target checks are useful, but are NOT --release 17.
  COMPILER=(java -m jdk.compiler/com.sun.tools.javac.Main)
  FLAGS=(-source 17 -target 17 -Xlint:all,-options -Werror)
  COMPILER_DESCRIPTION='installed jdk.compiler module; source 17 / target 17; Java 17 API-surface check NOT_RUN'
else
  printf '%s\n' 'TAPSCENE_PACKAGE_CHECKS_NOT_RUN: JDK 17+ javac or an installed jdk.compiler module is required; no software was installed.' >&2
  exit 77
fi
if [[ $# -gt 1 ]]; then
  printf 'Usage: %s [new-output-directory]\n' "$0" >&2
  exit 2
fi
if [[ $# -eq 1 ]]; then
  if [[ -e "$1" ]]; then
    printf '%s\n' 'Output directory must not already exist.' >&2
    exit 2
  fi
  mkdir -p -- "$1"
  WORK="$(CDPATH='' cd -- "$1" && pwd)"
else
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/tapscene-package-checks.XXXXXXXX")"
  trap 'rm -rf -- "$WORK"' EXIT
fi
mkdir -p "$WORK/classes" "$WORK/fixtures"
{
  printf 'TAPSCENE_PACKAGE_CHECKS_COMPILER: %s\n' "$COMPILER_DESCRIPTION"
  java -version 2>&1
} | tee "$WORK/compiler.txt"
"${COMPILER[@]}" "${FLAGS[@]}" -encoding UTF-8 -d "$WORK/classes" \
  "$ROOT"/android/app/src/main/java/com/tapscene/packageformat/*.java \
  "$ROOT"/tools/package-checks/*.java
java -Djava.awt.headless=true -cp "$WORK/classes" PackageSecurityChecks "$WORK/fixtures" | tee "$WORK/results.txt"
java -Djava.awt.headless=true -cp "$WORK/classes" AiPackageChecks "$WORK/fixtures/ai" | tee "$WORK/ai-results.txt"
