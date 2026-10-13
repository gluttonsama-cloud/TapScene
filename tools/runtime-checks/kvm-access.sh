#!/usr/bin/env bash
# One explicitly approved, disposable GitHub-hosted runner. Never run on a user computer.
set -euo pipefail
mode=${1:?preflight, grant, or restore required}
test -n "${RUNNER_TEMP:-}"
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
test "${RUNNER_OS:-}" = Linux
test "${ImageOS:-}" = ubuntu24
[[ "${ImageVersion:-}" =~ ^[0-9]{8}\.[0-9]+(\.[0-9]+)?$ ]]
# Ubuntu image metadata is supplied by the official runner-image provisioning.
test -f /etc/os-release
(. /etc/os-release; test "$ID" = ubuntu && test "$VERSION_ID" = 24.04)
command -v getfacl >/dev/null
command -v setfacl >/dev/null
command -v setsid >/dev/null
state="$RUNNER_TEMP/tapscene-kvm-lease"
out="$RUNNER_TEMP/runtime-results"
mkdir -p "$out"

scope() {
  test "${GITHUB_EVENT_NAME:-}" = create
  python3 - <<'PYTHON'
import json, os
with open(os.environ['GITHUB_EVENT_PATH']) as source:
    event = json.load(source)
assert event.get('ref_type') == 'branch'
assert event.get('ref') == 'test/approved-kvm-once-20261013-1312'
assert event.get('repository', {}).get('full_name') == 'gluttonsama-cloud/TapScene'
PYTHON
  test "${GITHUB_RUN_ATTEMPT:-}" = 1
  test "${GITHUB_REPOSITORY:-}" = gluttonsama-cloud/TapScene
  test "${GITHUB_REF:-}" = refs/heads/test/approved-kvm-once-20261013-1312
  test "$(git rev-parse HEAD)" = "$GITHUB_SHA"
}

device() {
  test -c /dev/kvm && test ! -L /dev/kvm
  test "$(stat -c '%U:%G:%a' /dev/kvm)" = root:kvm:660
}

case "$mode" in
  preflight)
    scope
    device
    # Refuse named users/groups, an ACL mask, defaults, or wider permissions.
    test "$(getfacl -cp /dev/kvm)" = $'user::rw-\ngroup::rw-\nother::---'
    test ! -e "$state"
    (umask 077; mkdir "$state"; getfacl -p /dev/kvm > "$state/baseline.acl";
      stat -c '%d:%i:%t:%T' /dev/kvm > "$state/device-id")
    printf 'preflight=PASS\nimage=%s/%s\nrunner_uid=%s\nrun_id=%s\nattempt=%s\n' \
      "$ImageOS" "$ImageVersion" "$(id -u)" "$GITHUB_RUN_ID" "$GITHUB_RUN_ATTEMPT" > "$out/kvm-access.txt"
    ;;
  grant)
    scope
    device
    test -d "$state" && test ! -e "$state/grant-attempted"
    test "$(stat -c '%d:%i:%t:%T' /dev/kvm)" = "$(cat "$state/device-id")"
    cmp -s "$state/baseline.acl" <(getfacl -p /dev/kvm)
    # Mark before mutation: always restore even if setfacl's result is uncertain.
    (umask 077; : > "$state/grant-attempted")
    sudo -n setfacl -m "u:$(id -u):rw" /dev/kvm
    test -r /dev/kvm && test -w /dev/kvm
    printf 'temporary_runner_uid_acl=APPLIED\n' >> "$out/kvm-access.txt"
    timeout --signal=TERM --kill-after=5 30 "$ANDROID_HOME/emulator/emulator" -accel-check > "$out/accel-check.txt" 2>&1
    grep -Eq 'KVM .*installed and usable|KVM .*is installed and usable' "$out/accel-check.txt"
    printf 'kvm_accel_check=PASS\n' >> "$out/kvm-access.txt"
    ;;
  restore)
    # Cleanup must also run after a failed preflight or rejected repeated attempt.
    if [[ ! -e "$state/grant-attempted" ]]; then
      echo 'restore=NOT_NEEDED; no ACL mutation attempted' >> "$out/kvm-access.txt"
      exit 0
    fi
    if [[ ! -c /dev/kvm || -L /dev/kvm ]] ||
       ! current_id=$(stat -c '%d:%i:%t:%T' /dev/kvm) ||
       ! original_id=$(cat "$state/device-id") || [[ "$current_id" != "$original_id" ]]; then
      echo 'restore=REFUSED; original device identity unavailable or changed; VM teardown required' >> "$out/kvm-access.txt"
      exit 1
    fi
    # The runtime trap must stop its emulator before ACL restoration. If interrupted
    # before that point, restore access anyway and explicitly report unproven stop.
    stop_confirmed=true
    if [[ -f "$state/emulator-started" && ! -f "$state/emulator-stopped" ]]; then
      stop_confirmed=false
      echo 'emulator_stop=UNCONFIRMED; disposable VM teardown required' >> "$out/kvm-access.txt"
    fi
    if ! sudo -n setfacl --restore="$state/baseline.acl"; then
      echo 'restore=FAILED; disposable VM teardown required' >> "$out/kvm-access.txt"
      exit 1
    fi
    if ! getfacl -p /dev/kvm > "$state/restored.acl" ||
       ! cmp -s "$state/baseline.acl" "$state/restored.acl"; then
      echo 'restore=UNVERIFIED; disposable VM teardown required' >> "$out/kvm-access.txt"
      exit 1
    fi
    echo 'restore=EXACT_BASELINE_CONFIRMED' >> "$out/kvm-access.txt"
    "$stop_confirmed"
    ;;
  *) echo 'Unknown KVM lease action' >&2; exit 2 ;;
esac
