#!/usr/bin/env bash
# One disposable emulator, one dedicated test entry point, no retry loop.
set -euo pipefail
out=${1:?result directory required}
mkdir -p "$out"
out=$(realpath "$out")
test -n "${RUNNER_TEMP:-}"
test "${GITHUB_ACTIONS:-}" = true
# adb still uses HOME/.android, whereas emulator also honors ANDROID_USER_HOME.
# Keep both on the same disposable directory; never reuse the runner's adb key.
export HOME="$RUNNER_TEMP/tapscene-runtime-home"
export ANDROID_USER_HOME="$HOME/.android"
export ANDROID_SDK_HOME="$HOME"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$RUNNER_TEMP/tapscene-runtime-avd"
export ADB="$ANDROID_HOME/platform-tools/adb"
export ANDROID_I_WANT_MY_TCG=yes
serial=emulator-5554
app=com.tapscene.preview.hevc
emulator_pid=
logcat_pid=
device_owned=false
adb_owned=false
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
deadline=$((SECONDS + 1320))

bounded() {
  local limit=$1 remaining=$((deadline - SECONDS))
  shift
  if ((remaining <= 0)); then echo 'Runtime wall-time budget exhausted' >&2; return 124; fi
  if ((limit > remaining)); then limit=$remaining; fi
  timeout --signal=TERM --kill-after=10 "$limit" "$@"
}

collect_and_destroy() {
  original=$?
  trap - EXIT INT TERM
  set +e
  if "$device_owned" && timeout 5 "$ADB" -s "$serial" get-state >/dev/null 2>&1; then
    timeout 10 "$ADB" -s "$serial" logcat -b crash -d -v threadtime > "$out/crash-logcat.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys activity processes > "$out/activity-processes.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys activity exit-info "$app" > "$out/app-exit-info.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys media_projection > "$out/media-projection.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys accessibility > "$out/accessibility.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys media.codec > "$out/media-codec.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell dumpsys meminfo "$app" > "$out/app-memory.txt" 2>&1
    timeout 30 "$ADB" -s "$serial" exec-out run-as "$app" tar -cf - files/runtime-smoke \
      > "$out/app-evidence.tar" 2> "$out/app-evidence-error.txt"
    # Keep the tar byte-for-byte. Only our exact synthetic package was installed here.
    timeout 10 "$ADB" -s "$serial" shell am force-stop "$app" >> "$out/cleanup.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" shell am force-stop com.tapscene.runtime.target >> "$out/cleanup.txt" 2>&1
    timeout 10 "$ADB" -s "$serial" emu kill >> "$out/cleanup.txt" 2>&1
  fi
  if [[ -n "$logcat_pid" ]]; then kill "$logcat_pid" 2>/dev/null; wait "$logcat_pid" 2>/dev/null; fi
  if [[ -n "$emulator_pid" ]]; then
    kill "$emulator_pid" 2>/dev/null
    for _ in {1..10}; do kill -0 "$emulator_pid" 2>/dev/null || break; sleep 1; done
    kill -KILL "$emulator_pid" 2>/dev/null
    wait "$emulator_pid" 2>/dev/null
  fi
  if "$adb_owned"; then timeout 10 "$ADB" kill-server >> "$out/cleanup.txt" 2>&1; fi
  # These fixed RUNNER_TEMP paths were created solely for this disposable run.
  rm -rf -- "$ANDROID_AVD_HOME" "$HOME"
  removed=$?
  if ((removed != 0)) || [[ -e "$ANDROID_AVD_HOME" || -e "$HOME" ]]; then
    echo 'cleanup_failed: temporary AVD/home deletion not confirmed' >> "$out/cleanup.txt"
    if ((original == 0)); then original=1; fi
  else
    echo 'AVD and temporary Android user directory removed' >> "$out/cleanup.txt"
  fi
  if ((original == 0)); then
    python3 tools/runtime-checks/check-artifacts.py "$out/app-evidence.tar" > "$out/artifact-check.txt" 2>&1
    evidence_status=$?
    if ((evidence_status != 0)); then original=$evidence_status; fi
  fi
  printf 'exit=%s\n' "$original" >> "$out/cleanup.txt"
  exit "$original"
}
trap collect_and_destroy EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

{
  date -u +%FT%TZ
  uname -a
  nproc
  free -m
  df -h "$RUNNER_TEMP"
  "$ANDROID_HOME/emulator/emulator" -version
  "$ADB" version
} > "$out/host.txt" 2>&1
# No KVM chmod/group/udev changes, emulator setup action, account, or new grant.
echo no | bounded 60 "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force \
  --name tapscene-runtime --path "$ANDROID_AVD_HOME/tapscene-runtime.avd" \
  --package 'system-images;android-35;default;x86_64' > "$out/avd-create.txt" 2>&1
cat >> "$ANDROID_AVD_HOME/tapscene-runtime.avd/config.ini" <<'AVD'
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=160
hw.keyboard=yes
hw.camera.back=none
hw.camera.front=none
showDeviceFrame=no
AVD
bounded 20 "$ADB" start-server > "$out/adb.txt" 2>&1
bounded 15 "$ADB" devices > "$out/devices-before.txt"
if awk 'NR > 1 && NF { found=1 } END { exit !found }' "$out/devices-before.txt"; then
  echo 'Refusing an existing Android device; it is not owned by this run' >&2
  exit 1
fi
adb_owned=true
"$ANDROID_HOME/emulator/emulator" -avd tapscene-runtime -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -skin 480x800 \
  -gpu swiftshader -accel off -cores 2 -memory 2560 \
  > "$out/emulator.txt" 2>&1 &
emulator_pid=$!
export EMULATOR_PID="$emulator_pid"
printf '%s boot: waiting for sys.boot_completed\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
bounded 900 bash -c '
  until "$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r" | grep -qx "1"; do
    kill -0 "$EMULATOR_PID" || exit 1
    sleep 5
  done'
kill -0 "$emulator_pid"
device_owned=true
printf '%s boot: complete and owned emulator PID confirmed\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
# Stream all buffers before installing or launching either package, including native crashes.
"$ADB" -s "$serial" logcat -b all -v threadtime > "$out/logcat.txt" 2>&1 &
logcat_pid=$!
bounded 30 "$ADB" -s "$serial" shell getprop > "$out/device-properties.txt"
bounded 30 "$ADB" -s "$serial" shell dumpsys SurfaceFlinger > "$out/surfaceflinger.txt"
# First-boot PackageInstaller validation on TCG exceeded 120 seconds in run 38061310417.
# Only deployment gets extra time, inside the unchanged total runtime budget.
printf '%s install: app (maximum 360 seconds)\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
stat -c '%n %s bytes' android/app/build/outputs/apk/debug/app-debug.apk > "$out/apk-sizes.txt"
bounded 360 "$ADB" -s "$serial" install android/app/build/outputs/apk/debug/app-debug.apk \
  2>&1 | tee "$out/install-app.txt"
printf '%s install: harness (maximum 180 seconds)\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
stat -c '%n %s bytes' android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$out/apk-sizes.txt"
bounded 180 "$ADB" -s "$serial" install -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  2>&1 | tee "$out/install-harness.txt"
printf '%s install: synthetic target (maximum 120 seconds)\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
stat -c '%n %s bytes' android/runtime-target/build/outputs/apk/debug/runtime-target-debug.apk >> "$out/apk-sizes.txt"
bounded 120 "$ADB" -s "$serial" install -t android/runtime-target/build/outputs/apk/debug/runtime-target-debug.apk \
  2>&1 | tee "$out/install-target.txt"
printf '%s instrumentation: dedicated EGL probe and click scenario\n' "$(date -u +%FT%TZ)" >> "$out/runtime-stages.txt"
# No adb input business clicks, pm grant, appops, settings put, adb root, or test token reuse.
bounded 480 "$ADB" -s "$serial" shell am instrument -w -e syntheticOnly true \
  "$app.test/com.tapscene.runtime.ClickRuntimeInstrumentation" \
  2>&1 | tee "$out/instrumentation.txt"
grep -q '^.*TAPSCENE_RUNTIME_SMOKE_OK' "$out/instrumentation.txt"
if grep -Eq 'Process crashed|INSTRUMENTATION_FAILED|TAPSCENE_RUNTIME_SMOKE_FAILED' "$out/instrumentation.txt"; then
  exit 1
fi
