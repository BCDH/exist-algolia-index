#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

pass_count=0
TEST_ROOT=""
STUB_BIN=""
SSH_LOG=""
SCP_LOG=""
ARTIFACT_BACKUP=""
ARTIFACT_EXISTED=0

cleanup() {
  if [[ "${ARTIFACT_EXISTED}" == "1" && -n "${ARTIFACT_BACKUP}" && -f "${ARTIFACT_BACKUP}" ]]; then
    mv "${ARTIFACT_BACKUP}" "${PLUGIN_JAR_PATH}"
  elif [[ -f "${PLUGIN_JAR_PATH}" ]]; then
    rm -f "${PLUGIN_JAR_PATH}"
  fi

  if [[ -n "${TEST_ROOT}" && -d "${TEST_ROOT}" ]]; then
    rm -rf "${TEST_ROOT}"
  fi
}

trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

pass() {
  printf '[ok] %s\n' "$1"
  pass_count=$((pass_count + 1))
}

assert_contains() {
  local pattern=$1
  local path=$2
  local label=$3

  if ! grep -Eq "${pattern}" "${path}"; then
    fail "${label}"
  fi
}

assert_not_contains() {
  local pattern=$1
  local path=$2
  local label=$3

  if grep -Eq "${pattern}" "${path}"; then
    fail "${label}"
  fi
}

count_matches() {
  local pattern=$1
  local path=$2
  local count

  count=$(grep -Eo "${pattern}" "${path}" | wc -l | tr -d ' ')
  printf '%s' "${count}"
}

setup_fake_artifact() {
  mkdir -p "$(dirname "${PLUGIN_JAR_PATH}")"
  if [[ -f "${PLUGIN_JAR_PATH}" ]]; then
    ARTIFACT_EXISTED=1
    ARTIFACT_BACKUP="${TEST_ROOT}/original-plugin.jar"
    mv "${PLUGIN_JAR_PATH}" "${ARTIFACT_BACKUP}"
  fi

  python3 - "${PLUGIN_JAR_PATH}" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
chunk = b"exist-algolia-index-test\n" * 1024
target_size = 17 * 1024 * 1024

with path.open("wb") as handle:
    written = 0
    while written < target_size:
        remaining = target_size - written
        piece = chunk[:remaining]
        handle.write(piece)
        written += len(piece)
PY
}

setup_stubs() {
  STUB_BIN="${TEST_ROOT}/bin"
  SSH_LOG="${TEST_ROOT}/ssh.log"
  SCP_LOG="${TEST_ROOT}/scp.log"
  mkdir -p "${STUB_BIN}"
  : > "${SSH_LOG}"
  : > "${SCP_LOG}"

  cat > "${STUB_BIN}/ssh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >> "${SSH_LOG}"
printf '%s\n' "--" >> "${SSH_LOG}"
EOF
  chmod +x "${STUB_BIN}/ssh"

  cat > "${STUB_BIN}/scp" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >> "${SCP_LOG}"
printf '%s\n' "--" >> "${SCP_LOG}"
EOF
  chmod +x "${STUB_BIN}/scp"
}

reset_logs() {
  : > "${SSH_LOG}"
  : > "${SCP_LOG}"
}

stage_env() {
  export PATH="${STUB_BIN}:${ORIGINAL_PATH}"
  export SSH_LOG
  export SCP_LOG
  export EXIST_STAGE_HOST="stage.example.test"
  export EXIST_STAGE_SSH_USER="deploy"
  export EXIST_STAGE_PORT="2222"
  export EXIST_STAGE_REMOTE_DIR="/tmp/exist-algolia-stage-test"
  export EXIST_STAGE_CONTAINER_NAME="existdb-stage"
  export EXIST_STAGE_ADMIN_PASSWORD="test-admin-password"
  export EXIST_STAGE_ALGOLIA_APPLICATION_ID="test-app-id"
  export EXIST_STAGE_ALGOLIA_ADMIN_API_KEY="test-admin-key"
  export EXIST_STAGE_REINDEX_COLLECTION="/db/apps/raskovnik-data/data"
}

run_upload_wrapper_test() {
  reset_logs
  stage_env

  bash "${SCRIPT_DIR}/exist-stage.sh" upload --skip-build >/dev/null

  assert_contains 'build\.sbt' "${SCP_LOG}" "metadata upload missing"
  assert_contains 'exist-stage-remote\.sh' "${SCP_LOG}" "helper script upload missing"
  assert_contains '\.parts/' "${SCP_LOG}" "chunk upload target missing"
  assert_not_contains "${PLUGIN_JAR_PATH}" "${SCP_LOG}" "wrapper should not scp the assembled jar directly"

  if [[ "$(count_matches '\.part[0-9]{4}' "${SCP_LOG}")" -lt 2 ]]; then
    fail "expected multiple chunk uploads"
  fi

  assert_contains "REMOTE_JAR_PATH=${EXIST_STAGE_REMOTE_DIR}/target/scala-${SCALA_BINARY_VERSION}/${PLUGIN_JAR_FILENAME}" "${SSH_LOG}" "remote jar reassembly target missing"
  assert_contains "REMOTE_PARTIAL_PATH=${EXIST_STAGE_REMOTE_DIR}/target/scala-${SCALA_BINARY_VERSION}/${PLUGIN_JAR_FILENAME}\\.partial" "${SSH_LOG}" "remote partial jar path missing"
  assert_contains 'os\.environ\["REMOTE_CHUNK_DIR"\]' "${SSH_LOG}" "remote python reassembly snippet missing unescaped env lookup"
  assert_not_contains '\\\\"REMOTE_CHUNK_DIR\\\\"' "${SSH_LOG}" "remote python reassembly snippet should not contain escaped quotes"
  pass "upload uses chunked jar transfer and remote reassembly"
}

run_run_forwarding_test() {
  reset_logs
  stage_env

  bash "${SCRIPT_DIR}/exist-stage.sh" run --skip-build --skip-reindex >/dev/null

  assert_contains 'EXIST_SKIP_REINDEX=1' "${SSH_LOG}" "run should forward skip-reindex state"
  assert_contains "bash ${EXIST_STAGE_REMOTE_DIR}/scripts/exist-stage-remote\\.sh run" "${SSH_LOG}" "run should invoke the remote helper"
  pass "run forwards the remote helper command explicitly"
}

run_reconcile_forwarding_test() {
  reset_logs
  stage_env

  bash "${SCRIPT_DIR}/exist-stage.sh" reconcile-collection --force /db/apps/raskovnik-data/data/GE.RKMD >/dev/null

  assert_contains "bash ${EXIST_STAGE_REMOTE_DIR}/scripts/exist-stage-remote\\.sh reconcile-collection --force /db/apps/raskovnik-data/data/GE\\.RKMD" "${SSH_LOG}" "reconcile should forward force and collection path"
  pass "reconcile forwards --force and the collection path explicitly"
}

ORIGINAL_PATH=${PATH}
TEST_ROOT=$(mktemp -d)
setup_fake_artifact
setup_stubs
run_upload_wrapper_test
run_run_forwarding_test
run_reconcile_forwarding_test

printf '%d stage-wrapper tests passed.\n' "${pass_count}"
