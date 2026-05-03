#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

tmp_dir=$(mktemp -d)
trap 'rm -rf "${tmp_dir}"' EXIT

pass() {
  printf '[ok] %s\n' "$1"
}

expect_success() {
  local label=$1
  local status_file=$2

  verify_indexing_status_file "${status_file}" "${label}" >/dev/null
  pass "${label}"
}

expect_failure() {
  local label=$1
  local status_file=$2
  local stderr_file="${tmp_dir}/${label// /-}.stderr"

  if verify_indexing_status_file "${status_file}" "${label}" >/dev/null 2>"${stderr_file}"; then
    echo "Expected verifier to fail for ${label}" >&2
    return 1
  fi

  if ! grep -Eq "index=ras|state=degraded|state=stale_local_store" "${stderr_file}"; then
    echo "Verifier failure output did not include useful status detail for ${label}" >&2
    cat "${stderr_file}" >&2
    return 1
  fi

  pass "${label}"
}

missing_file="${tmp_dir}/missing/status.json"
current_file="${tmp_dir}/current.json"
degraded_file="${tmp_dir}/degraded.json"
stale_file="${tmp_dir}/stale.json"

cat >"${current_file}" <<'JSON'
{"records":[{"index":"ras","collection":"/db/apps/raskovnik-data/data","operation":"batch_write","state":"current","timestamp":"2026-05-03T00:00:00Z"}]}
JSON

cat >"${degraded_file}" <<'JSON'
{"records":[{"index":"ras","collection":"/db/apps/raskovnik-data/data/VSK.SR","operation":"batch_write","state":"degraded","timestamp":"2026-05-03T00:00:00Z","failureMessage":"quota"}]}
JSON

cat >"${stale_file}" <<'JSON'
{"records":[{"index":"ras","collection":"/db/apps/raskovnik-data/data/VSK.SR","operation":"collection_delete","state":"stale_local_store","timestamp":"2026-05-03T00:00:00Z"}]}
JSON

expect_success "missing status file" "${missing_file}"
expect_success "current status file" "${current_file}"
expect_failure "degraded status file" "${degraded_file}"
expect_failure "stale local store status file" "${stale_file}"
