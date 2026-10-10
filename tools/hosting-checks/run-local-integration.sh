#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)"
if [[ -z "${TEST_DATABASE_URL:-}" ]]; then
  echo 'NOT_RUN: TEST_DATABASE_URL is required for real Android Java HTTP/Fastify/PostgreSQL integration' >&2
  exit 77
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/tapscene-android-http-classes.XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
COMPILER=(javac --release 17 -Xlint:all -Werror)
if ! command -v javac >/dev/null 2>&1; then
  COMPILER=(java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -Xlint:all,-options -Werror)
  echo 'HOST_HOSTED_JAVA compiler=installed-jdk.compiler source/target17; Java17 API surface NOT_RUN'
fi
"${COMPILER[@]}" -encoding UTF-8 -d "$WORK" \
  "$ROOT"/android/app/src/main/java/com/tapscene/packageformat/*.java \
  "$ROOT"/android/app/src/main/java/com/tapscene/hosting/*.java \
  "$ROOT/tools/hosting-checks/HostedHttpIntegration.java"
cd "$ROOT"
node --import tsx tools/hosting-checks/local-integration.ts "$WORK"
