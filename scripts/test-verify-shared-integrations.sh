#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
verifier="$script_dir/verify-shared-integrations.sh"
fixtures="$script_dir/fixtures/shared-integrations"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/uniton-preflight-test.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

run_case() {
  name=$1
  expected_status=$2
  fixture=$3
  shift 3
  output="$tmp_dir/$name.log"

  set +e
  env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
    "$@" "$verifier" --local-contract --redacted --fixture "$fixture" >"$output" 2>&1
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

set +e
env -u SLACK_APP_TOKEN -u SLACK_BOT_TOKEN -u NOTION_API_TOKEN -u LM_STUDIO_BASE_URL -u LM_STUDIO_API_KEY \
  "$verifier" --live --redacted --fixture "$fixtures/valid.fixture" >"$tmp_dir/live.log" 2>&1
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
