#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
COMPILER=(javac --release 17 -Xlint:all -Werror)
if ! command -v javac >/dev/null 2>&1; then
  COMPILER=(java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all,-options -Werror)
  echo 'HOST_HOSTED_JAVA compiler=installed-jdk.compiler source/target17; Java17 API surface NOT_RUN'
fi
"${COMPILER[@]}" -encoding UTF-8 -d "$OUT" \
  "$ROOT"/android/app/src/main/java/com/tapscene/hosting/*.java \
  "$ROOT"/tools/hosting-checks/HostedCoreChecks.java
java -cp "$OUT" com.tapscene.hosting.HostedCoreChecks
