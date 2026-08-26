#!/usr/bin/env bash
set -euo pipefail

PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin
export PATH
TMPDIR=/tmp
export TMPDIR
unset -f dirname uname stat mktemp dd wc awk sed tr java docker rm 2>/dev/null || true

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mode=
redacted=false
fixture="$script_dir/fixtures/shared-integrations/valid.fixture"
fixture_supplied=false

fail() {
  printf 'RESULT=FAIL\nERROR=%s\n' "$1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case $1 in
    --local-contract|--live)
      [ -z "$mode" ] || fail invalid_arguments
      mode=$1
      ;;
    --redacted)
      redacted=true
      ;;
    --fixture)
      shift
      [ "$#" -gt 0 ] || fail invalid_arguments
      fixture=$1
      fixture_supplied=true
      ;;
    *) fail invalid_arguments ;;
  esac
  shift
done

[ -n "$mode" ] || fail invalid_arguments
[ "$redacted" = true ] || fail redaction_required
[ -r "$fixture" ] || fail fixture_unreadable
[ "$fixture_supplied" = false ] || [ ! -L "$fixture" ] || fail fixture_must_be_regular

platform=$(uname -s)
if [ "$fixture_supplied" = true ]; then
  case $platform in
    Darwin)
      fixture_mode_before=$(stat -f '%Lp' "$fixture") || fail fixture_metadata_unavailable
      path_identity_before=$(stat -f '%d:%i' "$fixture") || fail fixture_metadata_unavailable
      ;;
    Linux)
      fixture_mode_before=$(stat -Lc '%a' "$fixture") || fail fixture_metadata_unavailable
      path_identity_before=$(stat -Lc '%d:%i' "$fixture") || fail fixture_metadata_unavailable
      ;;
    *) fail platform_unsupported ;;
  esac
fi

exec 3<"$fixture" || fail fixture_unreadable
case $platform in
  Darwin)
    descriptor_path=/dev/fd/3
    descriptor_type=$(stat -f '%HT' "$descriptor_path") || fail fixture_metadata_unavailable
    [ "$descriptor_type" = 'Regular File' ] || fail fixture_must_be_regular
    if [ "$fixture_supplied" = true ]; then
      fixture_mode_after=$(stat -f '%Lp' "$fixture") || fail fixture_metadata_unavailable
      path_identity_after=$(stat -f '%d:%i' "$fixture") || fail fixture_metadata_unavailable
    fi
    ;;
  Linux)
    descriptor_path=/proc/self/fd/3
    descriptor_type=$(stat -Lc '%F' "$descriptor_path") || fail fixture_metadata_unavailable
    [ "$descriptor_type" = 'regular file' ] || fail fixture_must_be_regular
    if [ "$fixture_supplied" = true ]; then
      fixture_mode_after=$(stat -Lc '%a' "$fixture") || fail fixture_metadata_unavailable
      path_identity_after=$(stat -Lc '%d:%i' "$fixture") || fail fixture_metadata_unavailable
      descriptor_identity=$(stat -Lc '%d:%i' "$descriptor_path") || fail fixture_metadata_unavailable
    fi
    ;;
  *) fail platform_unsupported ;;
esac

if [ "$fixture_supplied" = true ]; then
  [ ! -L "$fixture" ] || fail fixture_must_be_regular
  [ "$path_identity_before" = "$path_identity_after" ] || fail fixture_changed_during_validation
  [ "$fixture_mode_before" = "$fixture_mode_after" ] || fail fixture_changed_during_validation
  if [ "$platform" = Linux ]; then
    [ "$path_identity_after" = "$descriptor_identity" ] || fail fixture_changed_during_validation
  fi
  case $fixture_mode_after in
    400|600) ;;
    *) fail fixture_permissions_too_open ;;
  esac
fi

fixture_snapshot=$(mktemp "${TMPDIR:-/tmp}/uniton-preflight-fixture.XXXXXX") || fail fixture_snapshot_failed
trap 'rm -f "$fixture_snapshot"' EXIT HUP INT TERM
dd of="$fixture_snapshot" bs=8193 count=1 <&3 2>/dev/null || fail fixture_snapshot_failed
exec 3<&-
fixture_bytes=$(wc -c <"$fixture_snapshot")
[ "$fixture_bytes" -le 8192 ] || fail fixture_limits_exceeded
LC_ALL=C awk 'length($0) > 512 || NR > 32 { exit 1 }' "$fixture_snapshot" || fail fixture_limits_exceeded

if [ "${SLACK_APP_TOKEN+x}" = x ] ||
  [ "${SLACK_BOT_TOKEN+x}" = x ] ||
  [ "${NOTION_API_TOKEN+x}" = x ] ||
  [ "${LM_STUDIO_BASE_URL+x}" = x ] ||
  [ "${LM_STUDIO_API_KEY+x}" = x ]; then
  fail credential_environment_rejected
fi

java_line=$(java -version 2>&1 | sed -n '1p') || fail java_21_required
case $java_line in
  *'version "21.'*) ;;
  *) fail java_21_required ;;
esac
docker compose version >/dev/null 2>&1 || fail docker_compose_required

seen='|'
count=0
bridge_auth=
while IFS= read -r line || [ -n "$line" ]; do
  [ -n "$line" ] || continue
  case $line in
    bridge_auth=*)
      [ -z "$bridge_auth" ] || fail duplicate_bridge_metadata
      bridge_auth=${line#bridge_auth=}
      continue
      ;;
  esac

  old_ifs=$IFS
  IFS='|'
  read -r name kind owner mount uid mode_bits mount_access validation extra <<EOF
$line
EOF
  IFS=$old_ifs

  [ -z "${extra:-}" ] || fail malformed_fixture_metadata
  case $seen in
    *"|$name|"*) fail duplicate_configuration_name ;;
  esac

  expected_kind=
  expected_owner=
  expected_mount=
  case $name in
    SLACK_APP_TOKEN|SLACK_BOT_TOKEN)
      expected_kind=credential
      expected_owner=bolt
      ;;
    NOTION_API_TOKEN)
      expected_kind=credential
      expected_owner=notion-worker
      ;;
    LM_STUDIO_BASE_URL|LM_STUDIO_API_KEY)
      expected_kind=credential
      expected_owner=lm-studio-worker
      ;;
    UNITON_SLACK_CHANNEL_ALLOWLIST)
      expected_kind=config
      expected_owner=bolt
      ;;
    UNITON_NOTION_PARENT_ALLOWLIST)
      expected_kind=config
      expected_owner=notion-worker
      ;;
    UNITON_DEMO_MODE)
      expected_kind=config
      expected_owner=worker
      ;;
    *) fail unexpected_configuration_name ;;
  esac
  expected_mount="/run/secrets/uniton/$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')"

  [ "$kind" = "$expected_kind" ] || fail component_least_privilege
  [ "$owner" = "$expected_owner" ] || fail component_least_privilege
  [ "$mount" = "$expected_mount" ] || fail invalid_mount_metadata
  [ "$uid" = 0 ] || fail invalid_mount_metadata
  [ "$mode_bits" = 0400 ] || fail invalid_mount_metadata
  [ "$mount_access" = ro ] || fail invalid_mount_metadata

  case $name in
    SLACK_APP_TOKEN|SLACK_BOT_TOKEN|NOTION_API_TOKEN|LM_STUDIO_BASE_URL|LM_STUDIO_API_KEY)
      [ "$validation" = redacted ] || fail credential_value_forbidden
      ;;
    UNITON_SLACK_CHANNEL_ALLOWLIST|UNITON_NOTION_PARENT_ALLOWLIST)
      [[ $validation =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*(,[A-Za-z0-9][A-Za-z0-9._:-]*)*$ ]] || fail malformed_allowlist
      ;;
    UNITON_DEMO_MODE)
      [ "$validation" = false ] || fail demo_mode_must_be_false
      ;;
  esac

  seen="$seen$name|"
  count=$((count + 1))
done <"$fixture_snapshot"

[ "$count" -eq 8 ] || fail missing_configuration_name
[ "$bridge_auth" = service_identity ] || fail unauthenticated_private_bridge

case $mode in
  --local-contract) report_mode=LOCAL_CONTRACT ;;
  --live) report_mode=LIVE_PROTOCOL ;;
esac

for required_name in SLACK_APP_TOKEN SLACK_BOT_TOKEN NOTION_API_TOKEN LM_STUDIO_BASE_URL LM_STUDIO_API_KEY UNITON_SLACK_CHANNEL_ALLOWLIST UNITON_NOTION_PARENT_ALLOWLIST UNITON_DEMO_MODE; do
  case $seen in
    *"|$required_name|"*) printf '%s=VERIFIED\n' "$required_name" ;;
    *) fail missing_configuration_name ;;
  esac
done

printf 'MODE=%s\n' "$report_mode"
printf '%s\n' \
  'JAVA_21=VERIFIED' \
  'DOCKER_COMPOSE=VERIFIED' \
  'MOUNT_METADATA=ROOT_READABLE_READ_ONLY' \
  'COMPONENT_LEAST_PRIVILEGE=VERIFIED' \
  'ALLOWLIST_SYNTAX=VERIFIED' \
  'UNITON_DEMO_MODE_BASELINE=FALSE_ONLY' \
  'CREDENTIAL_ENVIRONMENT=REJECTED' \
  'PRIVATE_BRIDGE_AUTH=SERVICE_IDENTITY' \
  'SLACK_READ_HEALTH=PENDING_LIVE' \
  'SLACK_SETUP_CAPABILITY=PENDING_LIVE' \
  'NOTION_READ_HEALTH=PENDING_LIVE' \
  'NOTION_SETUP_CAPABILITY=PENDING_LIVE' \
  'LM_STUDIO_AUTH_HEALTH=PENDING_LIVE' \
  'LM_STUDIO_TAILSCALE_ROUTE=PENDING_LIVE' \
  'GPU_1234_PUBLIC_EXPOSURE=PENDING_LIVE' \
  'REMOTE_CONFIGURATION_WRITES=FORBIDDEN'

if [ "$mode" = --live ]; then
  printf '%s\n' 'LIVE_PROOF=INCOMPLETE' 'RESULT=BLOCKED'
  exit 2
fi

printf 'RESULT=PASS\n'
