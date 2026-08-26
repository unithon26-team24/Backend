#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
verifier="$script_dir/verify-shared-integrations.sh"
fixtures="$script_dir/fixtures/shared-integrations"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/uniton-preflight-test.XXXXXX")
qa_pids=

cleanup() {
  for qa_pid in $qa_pids; do
    kill "$qa_pid" 2>/dev/null || true
  done
  rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM

run_case() {
  name=$1
  expected_status=$2
  fixture=$3
  shift 3
  output="$tmp_dir/$name.log"
  private_fixture="$tmp_dir/$name.fixture"
  cp "$fixture" "$private_fixture"
  chmod 0600 "$private_fixture"

  set +e
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
    "$@" "$verifier" --local-contract --redacted --fixture "$private_fixture" >"$output" 2>&1
  status=$?
  set -e

  if [ "$status" -ne "$expected_status" ]; then
    printf 'FAIL %s: expected status %s, got %s\n' "$name" "$expected_status" "$status" >&2
    sed -n '1,80p' "$output" >&2
    exit 1
  fi
  if grep -Eq '(xapp-|xoxb-|secret-value|Bearer[[:space:]])' "$output"; then
    printf 'FAIL %s: output was not redacted\n' "$name" >&2
    exit 1
  fi
  printf 'PASS %s status=%s redacted=yes\n' "$name" "$status"
}

run_case happy 0 "$fixtures/valid.fixture"
grep -q '^LM_STUDIO_TAILSCALE_ROUTE=PENDING_LIVE$' "$tmp_dir/happy.log"
grep -q '^GPU_1234_PUBLIC_EXPOSURE=PENDING_LIVE$' "$tmp_dir/happy.log"

run_case missing-name 1 "$fixtures/missing-name.fixture"
run_case bad-mount 1 "$fixtures/bad-mount.fixture"
run_case malformed-allowlist 1 "$fixtures/malformed-allowlist.fixture"
run_case demo-enabled 1 "$fixtures/demo-enabled.fixture"
run_case credential-env 1 "$fixtures/valid.fixture" SLACK_APP_TOKEN=
run_case least-privilege 1 "$fixtures/least-privilege.fixture"
run_case unauthenticated-bridge 1 "$fixtures/unauthenticated-bridge.fixture"

expect_fixture_limit() {
  name=$1
  fixture=$2
  output="$tmp_dir/$name.log"
  set +e
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
    "$verifier" --local-contract --redacted --fixture "$fixture" >"$output" 2>&1
  fixture_status=$?
  set -e
  if [ "$fixture_status" -ne 1 ] ||
    ! grep -q '^RESULT=FAIL$' "$output" ||
    ! grep -q '^ERROR=fixture_limits_exceeded$' "$output"; then
    printf 'FAIL %s: expected bounded fixture rejection\n' "$name" >&2
    exit 1
  fi
  printf 'PASS %s status=1 prompt=yes redacted=yes\n' "$name"
}

awk 'BEGIN { for (i = 0; i < 513; i++) printf "x"; print "" }' >"$tmp_dir/overlong.fixture"
awk 'BEGIN { for (i = 0; i < 33; i++) print "x" }' >"$tmp_dir/too-many-lines.fixture"
awk 'BEGIN { for (line = 0; line < 20; line++) { for (i = 0; i < 500; i++) printf "x"; print "" } }' >"$tmp_dir/oversized.fixture"
chmod 0600 "$tmp_dir/overlong.fixture" "$tmp_dir/too-many-lines.fixture" "$tmp_dir/oversized.fixture"
expect_fixture_limit overlong-fixture "$tmp_dir/overlong.fixture"
expect_fixture_limit too-many-lines-fixture "$tmp_dir/too-many-lines.fixture"
expect_fixture_limit oversized-fixture "$tmp_dir/oversized.fixture"

expect_open_permissions_rejected() {
  mode=$1
  open_fixture="$tmp_dir/open-$mode.fixture"
  output="$tmp_dir/open-$mode.log"
  cp "$fixtures/valid.fixture" "$open_fixture"
  chmod "$mode" "$open_fixture"
  set +e
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
    "$verifier" --local-contract --redacted --fixture "$open_fixture" >"$output" 2>&1
  open_status=$?
  set -e
  if [ "$open_status" -ne 1 ] ||
    ! grep -q '^RESULT=FAIL$' "$output" ||
    ! grep -q '^ERROR=fixture_permissions_too_open$' "$output"; then
    printf 'FAIL open fixture mode %s: expected private-file rejection, got status %s\n' "$mode" "$open_status" >&2
    exit 1
  fi
  printf 'PASS open fixture mode=%s status=1 redacted=yes\n' "$mode"
}

expect_open_permissions_rejected 0644
expect_open_permissions_rejected 0666

private_0400_fixture="$tmp_dir/private-0400.fixture"
cp "$fixtures/valid.fixture" "$private_0400_fixture"
chmod 0400 "$private_0400_fixture"
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$private_0400_fixture" >"$tmp_dir/private-0400.log" 2>&1
grep -q '^RESULT=PASS$' "$tmp_dir/private-0400.log"
printf 'PASS private fixture mode=0400 status=0 redacted=yes\n'

symlink_fixture="$tmp_dir/symlink.fixture"
ln -s "$private_0400_fixture" "$symlink_fixture"
set +e
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$symlink_fixture" >"$tmp_dir/symlink.log" 2>&1
symlink_status=$?
set -e
[ "$symlink_status" -eq 1 ]
grep -q '^ERROR=fixture_must_be_regular$' "$tmp_dir/symlink.log"
printf 'PASS symlink fixture status=1 redacted=yes\n'

hostile_fixture="$tmp_dir/hostile-path.fixture"
hostile_bin="$tmp_dir/hostile-bin"
hostile_output="$tmp_dir/hostile-path.log"
mkdir "$hostile_bin"
cp "$fixtures/valid.fixture" "$hostile_fixture"
chmod 0644 "$hostile_fixture"
printf '%s\n' '#!/bin/sh' "printf '600\\n'" >"$hostile_bin/stat"
chmod 0700 "$hostile_bin/stat"
set +e
PATH="$hostile_bin:$PATH" env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$hostile_fixture" >"$hostile_output" 2>&1
hostile_status=$?
set -e
if [ "$hostile_status" -ne 1 ] ||
  ! grep -q '^RESULT=FAIL$' "$hostile_output" ||
  ! grep -q '^ERROR=fixture_permissions_too_open$' "$hostile_output" ||
  grep -q '^RESULT=PASS$' "$hostile_output"; then
  printf 'FAIL hostile PATH: expected actual-mode rejection, got status %s\n' "$hostile_status" >&2
  exit 1
fi
printf 'PASS hostile PATH status=1 actual-mode=enforced redacted=yes\n'

hostile_bash_marker="$tmp_dir/hostile-bash-executed"
printf '%s\n' '#!/bin/sh' ': >"$UNITON_HOSTILE_BASH_MARKER"' 'exec /bin/bash "$@"' >"$hostile_bin/bash"
chmod 0700 "$hostile_bin/bash"
set +e
UNITON_HOSTILE_BASH_MARKER="$hostile_bash_marker" PATH="$hostile_bin:$PATH" \
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$private_0400_fixture" >"$tmp_dir/hostile-interpreter.log" 2>&1
hostile_interpreter_status=$?
set -e
if [ "$hostile_interpreter_status" -ne 0 ] || [ -e "$hostile_bash_marker" ]; then
  printf 'FAIL hostile interpreter PATH: caller bash executed or verifier failed\n' >&2
  exit 1
fi
printf 'PASS hostile interpreter PATH status=0 caller-bash-executed=no\n'
unlink "$hostile_bin/bash"

bash_env_sentinel=uniton-redaction-sentinel
bash_env_fixture="$tmp_dir/$bash_env_sentinel.fixture"
bash_env_output="$tmp_dir/bash-env.log"
bash_env_startup="$tmp_dir/bash-env-startup"
cp "$fixtures/valid.fixture" "$bash_env_fixture"
chmod 0600 "$bash_env_fixture"
printf '%s\n' 'set -x' >"$bash_env_startup"
set +e
BASH_ENV="$bash_env_startup" BASH_XTRACEFD=2 \
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$bash_env_fixture" >"$bash_env_output" 2>&1
bash_env_status=$?
set -e
if [ "$bash_env_status" -ne 0 ] || grep -q "$bash_env_sentinel" "$bash_env_output"; then
  printf 'FAIL inherited BASH_ENV: status=%s sentinel leaked\n' "$bash_env_status" >&2
  exit 1
fi
grep -q '^RESULT=PASS$' "$bash_env_output"
printf 'PASS inherited BASH_ENV status=0 sentinel-count=0 redacted=yes\n'

ci_java_home="$tmp_dir/ci-java-home"
mkdir "$ci_java_home" "$ci_java_home/bin"
hostile_java_marker="$tmp_dir/hostile-java-executed"
printf '%s\n' '#!/bin/sh' ': >"$UNITON_HOSTILE_JAVA_MARKER"' 'printf '\''openjdk version "21.0.0"\\n'\'' >&2' >"$ci_java_home/bin/java"
chmod 0700 "$ci_java_home/bin/java"
set +e
UNITON_HOSTILE_JAVA_MARKER="$hostile_java_marker" JAVA_HOME="$ci_java_home" PATH="$hostile_bin:$PATH" \
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$private_0400_fixture" >"$tmp_dir/ci-java-home.log" 2>&1
hostile_java_status=$?
set -e
if [ "$hostile_java_status" -ne 0 ] || [ -e "$hostile_java_marker" ]; then
  printf 'FAIL hostile JAVA_HOME: wrapper executed or trusted Java unavailable\n' >&2
  exit 1
fi
grep -q '^JAVA_21=VERIFIED$' "$tmp_dir/ci-java-home.log"
grep -q '^RESULT=PASS$' "$tmp_dir/ci-java-home.log"
printf 'PASS hostile JAVA_HOME status=0 wrapper-executed=no\n'

fifo_fixture="$tmp_dir/non-regular.fifo"
fifo_output="$tmp_dir/fifo.log"
mkfifo "$fifo_fixture"
chmod 0600 "$fifo_fixture"
set +e
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture "$fifo_fixture" >"$fifo_output" 2>&1 &
fifo_pid=$!
(sleep 2; kill -TERM "$fifo_pid" 2>/dev/null) &
fifo_watchdog_pid=$!
qa_pids="$fifo_pid $fifo_watchdog_pid"
wait "$fifo_pid"
fifo_status=$?
kill "$fifo_watchdog_pid" 2>/dev/null
wait "$fifo_watchdog_pid" 2>/dev/null
set -e
qa_pids=
if [ "$fifo_status" -ne 1 ]; then
  printf 'FAIL FIFO fixture: expected prompt status 1, got %s\n' "$fifo_status" >&2
  exit 1
fi
grep -q '^ERROR=fixture_must_be_regular$' "$fifo_output"
printf 'PASS FIFO fixture status=1 prompt=yes redacted=yes\n'

non_regular_output="$tmp_dir/non-regular.log"
set +e
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture /dev/zero >"$non_regular_output" 2>&1 &
verifier_pid=$!
(sleep 2; kill -TERM "$verifier_pid" 2>/dev/null) &
watchdog_pid=$!
qa_pids="$verifier_pid $watchdog_pid"
wait "$verifier_pid"
non_regular_status=$?
kill "$watchdog_pid" 2>/dev/null
wait "$watchdog_pid" 2>/dev/null
set -e
qa_pids=
if [ "$non_regular_status" -ne 1 ]; then
  printf 'FAIL non-regular fixture: expected prompt status 1, got %s\n' "$non_regular_status" >&2
  exit 1
fi
grep -q '^RESULT=FAIL$' "$non_regular_output"
grep -q '^ERROR=fixture_must_be_regular$' "$non_regular_output"
printf 'PASS non-regular fixture status=1 prompt=yes redacted=yes\n'

set +e
live_fixture="$tmp_dir/live.fixture"
cp "$fixtures/valid.fixture" "$live_fixture"
chmod 0600 "$live_fixture"
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --live --redacted --fixture "$live_fixture" >"$tmp_dir/live.log" 2>&1
live_status=$?
set -e
if [ "$live_status" -ne 2 ]; then
  printf 'FAIL live protocol: expected status 2, got %s\n' "$live_status" >&2
  exit 1
fi
grep -q '^REMOTE_CONFIGURATION_WRITES=FORBIDDEN$' "$tmp_dir/live.log"
grep -q '^LM_STUDIO_TAILSCALE_ROUTE=PENDING_LIVE$' "$tmp_dir/live.log"
grep -q '^GPU_1234_PUBLIC_EXPOSURE=PENDING_LIVE$' "$tmp_dir/live.log"
grep -q '^RESULT=BLOCKED$' "$tmp_dir/live.log"
if grep -q '^RESULT=PASS$' "$tmp_dir/live.log"; then
  printf 'FAIL live protocol: reported pass without live proof\n' >&2
  exit 1
fi
printf 'PASS live protocol status=2 result=blocked remote-writes=forbidden\n'

printf 'PASS verifier contract suite\n'
