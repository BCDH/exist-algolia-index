#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

pass_count=0
TEST_ROOT=""
TEST_QUARANTINE_LOG=""
TEST_REINDEX_LOG=""
TEST_SYNC_MARKER=""

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

assert_file_empty() {
  local path=$1
  if [[ -s "${path}" ]]; then
    fail "expected empty file: ${path}"
  fi
}

assert_file_lines() {
  local expected=$1
  local path=$2
  local actual
  actual=$(wc -l < "${path}" | tr -d ' ')
  if [[ "${actual}" != "${expected}" ]]; then
    fail "expected ${expected} line(s) in ${path}, got ${actual}"
  fi
}

test_report_synced() {
  cat <<'JSON'
{"index":"ras","collectionPath":"/db/apps/raskovnik-data/data/GE.RKMD","localCount":2,"liveCount":2,"missingInLiveCount":0,"unexpectedInLiveCount":0,"sampleMissingInLive":[],"sampleUnexpectedInLive":[],"localDocumentDirCount":1,"matchingDocumentDirs":["doc-a"],"synced":true}
JSON
}

test_report_mismatch() {
  cat <<'JSON'
{"index":"ras","collectionPath":"/db/apps/raskovnik-data/data/GE.RKMD","localCount":2,"liveCount":1,"missingInLiveCount":1,"unexpectedInLiveCount":0,"sampleMissingInLive":["obj-b"],"sampleUnexpectedInLive":[],"localDocumentDirCount":1,"matchingDocumentDirs":["doc-a"],"synced":false}
JSON
}

stub_report_synced() {
  test_report_synced
}

stub_report_until_reindexed() {
  if [[ -f "${TEST_SYNC_MARKER}" ]]; then
    test_report_synced
  else
    test_report_mismatch
  fi
}

stub_quarantine() {
  local collection_path=$1
  local report_json=$2
  printf '%s|%s\n' "${collection_path}" "$(algolia_collection_sync_json_field "${report_json}" index)" >> "${TEST_QUARANTINE_LOG}"
}

stub_reindex() {
  local collection_path=$1
  printf '%s\n' "${collection_path}" >> "${TEST_REINDEX_LOG}"
  touch "${TEST_SYNC_MARKER}"
}

run_noop_when_already_synced_test() {
  local output
  TEST_ROOT=$(mktemp -d)
  TEST_QUARANTINE_LOG="${TEST_ROOT}/quarantine.log"
  TEST_REINDEX_LOG="${TEST_ROOT}/reindex.log"
  TEST_SYNC_MARKER="${TEST_ROOT}/synced"
  : > "${TEST_QUARANTINE_LOG}"
  : > "${TEST_REINDEX_LOG}"

  output=$(run_collection_sync_reconcile_flow \
    "/db/apps/raskovnik-data/data/GE.RKMD" \
    stub_report_synced \
    stub_quarantine \
    stub_reindex \
    "scripts/exist-local.sh reconcile-collection" \
    0 \
    1 \
    0)

  grep -q "Collection already synced; skipping reconcile." <<< "${output}" || fail "no-op message missing"
  assert_file_empty "${TEST_QUARANTINE_LOG}"
  assert_file_empty "${TEST_REINDEX_LOG}"
  rm -rf "${TEST_ROOT}"
  pass_count=$((pass_count + 1))
}

run_reconcile_after_mismatch_test() {
  local output
  TEST_ROOT=$(mktemp -d)
  TEST_QUARANTINE_LOG="${TEST_ROOT}/quarantine.log"
  TEST_REINDEX_LOG="${TEST_ROOT}/reindex.log"
  TEST_SYNC_MARKER="${TEST_ROOT}/synced"
  : > "${TEST_QUARANTINE_LOG}"
  : > "${TEST_REINDEX_LOG}"

  output=$(run_collection_sync_reconcile_flow \
    "/db/apps/raskovnik-data/data/GE.RKMD" \
    stub_report_until_reindexed \
    stub_quarantine \
    stub_reindex \
    "scripts/exist-local.sh reconcile-collection" \
    0 \
    1 \
    0)

  grep -q "Collection reconcile completed" <<< "${output}" || fail "completion message missing"
  assert_file_lines 1 "${TEST_QUARANTINE_LOG}"
  assert_file_lines 1 "${TEST_REINDEX_LOG}"
  rm -rf "${TEST_ROOT}"
  pass_count=$((pass_count + 1))
}

run_noop_when_already_synced_test
run_reconcile_after_mismatch_test

printf '%d collection-sync flow tests passed.\n' "${pass_count}"
