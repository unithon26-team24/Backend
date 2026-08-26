#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mode=
redacted=false
fixture="$script_dir/fixtures/shared-integrations/valid.fixture"

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
      ;;
    *) fail invalid_arguments ;;
  esac
  shift
done

[ -n "$mode" ] || fail invalid_arguments
[ "$redacted" = true ] || fail redaction_required
[ -r "$fixture" ] || fail fixture_unreadable

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
done <"$fixture"

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
  'REMOTE_CONFIGURATION_WRITES=FORBIDDEN' \
  'RESULT=PASS'
