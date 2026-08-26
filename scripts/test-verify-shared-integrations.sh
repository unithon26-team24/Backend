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

non_regular_output="$tmp_dir/non-regular.log"
set +e
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --local-contract --redacted --fixture /dev/zero >"$non_regular_output" 2>&1 &
verifier_pid=$!
(sleep 0.1; kill -TERM "$verifier_pid" 2>/dev/null) &
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
