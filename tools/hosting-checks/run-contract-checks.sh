#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/tapscene-hosted-contract.XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
COMPILER=(javac --release 17 -Xlint:all -Werror)
if ! command -v javac >/dev/null 2>&1; then
  COMPILER=(java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all,-options -Werror)
  echo 'HOST_HOSTED_JAVA compiler=installed-jdk.compiler source/target17; Java17 API surface NOT_RUN'
fi
node --import tsx "$ROOT/tools/hosting-checks/canonical-fixture.ts" "$WORK/fixtures"
mkdir "$WORK/classes"
"${COMPILER[@]}" -encoding UTF-8 -d "$WORK/classes" \
  "$ROOT"/android/app/src/main/java/com/tapscene/packageformat/*.java \
  "$ROOT/tools/hosting-checks/CanonicalFixtureChecks.java"
java -cp "$WORK/classes" CanonicalFixtureChecks "$WORK/fixtures"
