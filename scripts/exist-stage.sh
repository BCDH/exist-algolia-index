#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

EXIST_STAGE_PORT=${EXIST_STAGE_PORT:-22}
EXIST_STAGE_REMOTE_DIR=${EXIST_STAGE_REMOTE_DIR:-/tmp/exist-algolia-stage}
EXIST_STAGE_SSH_USER=${EXIST_STAGE_SSH_USER:-}
EXISTDB_CONTAINER_NAME=${EXISTDB_CONTAINER_NAME:-existdb-stage}
EXIST_STAGE_ADMIN_PASSWORD=${EXIST_STAGE_ADMIN_PASSWORD:-${EXIST_ADMIN_PASSWORD:-}}

usage() {
  cat <<'EOF'
Usage: scripts/exist-stage.sh <command> [--skip-build]

Commands:
  build            Build the plugin assembly JAR locally.
  upload           Upload the plugin artifact and helper scripts to the staging host.
  deploy           Upload artifacts and execute the remote staging helper.
  run              Alias for deploy.
  help             Show this help message.

Options:
  --skip-build     Reuse the existing local assembly JAR instead of rebuilding it.

Required environment for upload:
  EXIST_STAGE_HOST
  EXIST_STAGE_SSH_USER

Additional required environment for deploy/run:
  ALGOLIA_APPLICATION_ID
  ALGOLIA_ADMIN_API_KEY
  EXIST_STAGE_ADMIN_PASSWORD

Optional environment:
  EXIST_STAGE_PORT=22
  EXIST_STAGE_REMOTE_DIR=/tmp/exist-algolia-stage
  EXISTDB_CONTAINER_NAME=existdb-stage
  EXIST_STAGE_CONF_XML=/exist/etc/conf.xml
  EXIST_STAGE_STARTUP_XML=/exist/etc/startup.xml
  EXIST_STAGE_PLUGIN_LIB_DIR=/exist/lib
  EXIST_STAGE_RESTART_CMD="docker restart existdb-stage"
  ALGOLIA_SMOKE_INDEX_NAME=exist-algolia-index-smoke
EOF
}

remote_target() {
  printf '%s@%s' "${EXIST_STAGE_SSH_USER}" "${EXIST_STAGE_HOST}"
}

remote_cmd() {
  ssh -p "${EXIST_STAGE_PORT}" "$(remote_target)" "$@"
}

copy_to_remote() {
  scp -P "${EXIST_STAGE_PORT}" "$@"
}

require_stage_target() {
  if [[ -z "${EXIST_STAGE_HOST:-}" ]]; then
    echo "EXIST_STAGE_HOST must be set for staging deploys." >&2
    exit 1
  fi
  if [[ -z "${EXIST_STAGE_SSH_USER:-}" ]]; then
    echo "EXIST_STAGE_SSH_USER must be set for staging deploys." >&2
    exit 1
  fi
}

require_stage_prereqs() {
  require_cmd ssh
  require_cmd scp
}

require_stage_secrets() {
  require_algolia_credentials
  if [[ -z "${EXIST_STAGE_ADMIN_PASSWORD:-}" ]]; then
    echo "EXIST_STAGE_ADMIN_PASSWORD must be set for staging deploys." >&2
    exit 1
  fi
}

prepare_remote_tree() {
  echo "[stage] Preparing remote directory ${EXIST_STAGE_REMOTE_DIR}"
  remote_cmd "mkdir -p $(printf '%q' "${EXIST_STAGE_REMOTE_DIR}/scripts") $(printf '%q' "${EXIST_STAGE_REMOTE_DIR}/target/scala-${SCALA_BINARY_VERSION}")"
}

upload_payload() {
  require_plugin_artifact

  echo "[stage] Uploading project metadata"
  copy_to_remote \
    "${ROOT_DIR}/build.sbt" \
    "${ROOT_DIR}/version.sbt" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/"

  echo "[stage] Uploading helper scripts"
  copy_to_remote \
    "${ROOT_DIR}/scripts/exist-common.sh" \
    "${ROOT_DIR}/scripts/exist-stage-remote.sh" \
    "${ROOT_DIR}/scripts/manage-exist-config.py" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/scripts/"

  echo "[stage] Uploading plugin artifact"
  copy_to_remote \
    "${PLUGIN_JAR_PATH}" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/target/scala-${SCALA_BINARY_VERSION}/${PLUGIN_JAR_FILENAME}"
}

run_remote_helper() {
  local remote_script="${EXIST_STAGE_REMOTE_DIR}/scripts/exist-stage-remote.sh"
  local remote_dir_quoted container_quoted stage_password_quoted app_id_quoted api_key_quoted conf_quoted startup_quoted lib_quoted restart_quoted smoke_index_quoted

  remote_dir_quoted=$(printf '%q' "${EXIST_STAGE_REMOTE_DIR}")
  container_quoted=$(printf '%q' "${EXISTDB_CONTAINER_NAME}")
  stage_password_quoted=$(printf '%q' "${EXIST_STAGE_ADMIN_PASSWORD}")
  app_id_quoted=$(printf '%q' "${ALGOLIA_APPLICATION_ID}")
  api_key_quoted=$(printf '%q' "${ALGOLIA_ADMIN_API_KEY}")
  conf_quoted=$(printf '%q' "${EXIST_STAGE_CONF_XML:-}")
  startup_quoted=$(printf '%q' "${EXIST_STAGE_STARTUP_XML:-}")
  lib_quoted=$(printf '%q' "${EXIST_STAGE_PLUGIN_LIB_DIR:-}")
  restart_quoted=$(printf '%q' "${EXIST_STAGE_RESTART_CMD:-}")
  smoke_index_quoted=$(printf '%q' "${ALGOLIA_SMOKE_INDEX_NAME:-}")

  echo "[stage] Executing remote helper on $(remote_target)"
  remote_cmd "cd ${remote_dir_quoted} && EXISTDB_CONTAINER_NAME=${container_quoted} EXIST_STAGE_ADMIN_PASSWORD=${stage_password_quoted} ALGOLIA_APPLICATION_ID=${app_id_quoted} ALGOLIA_ADMIN_API_KEY=${api_key_quoted} EXIST_STAGE_CONF_XML=${conf_quoted} EXIST_STAGE_STARTUP_XML=${startup_quoted} EXIST_STAGE_PLUGIN_LIB_DIR=${lib_quoted} EXIST_STAGE_RESTART_CMD=${restart_quoted} ALGOLIA_SMOKE_INDEX_NAME=${smoke_index_quoted} bash $(printf '%q' "${remote_script}") run"
}

maybe_build() {
  local skip_build=$1

  if [[ "${skip_build}" -eq 1 ]]; then
    echo "[stage] Reusing existing local assembly JAR"
    require_plugin_artifact
    return 0
  fi

  echo "[stage] Building local assembly JAR"
  build_plugin
  require_plugin_artifact
}

upload_only() {
  local skip_build=$1

  require_stage_prereqs
  require_stage_target
  maybe_build "${skip_build}"
  prepare_remote_tree
  upload_payload
  echo "[stage] Upload complete"
}

deploy_all() {
  local skip_build=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  upload_only "${skip_build}"
  run_remote_helper
}

main() {
  local command=${1:-help}
  local skip_build=0

  shift $(( $# > 0 ? 1 : 0 ))
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-build)
        skip_build=1
        ;;
      help|-h|--help)
        usage
        return 0
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        return 1
        ;;
    esac
    shift
  done

  case "${command}" in
    build)
      maybe_build "${skip_build}"
      ;;
    upload)
      upload_only "${skip_build}"
      ;;
    deploy|run)
      deploy_all "${skip_build}"
      ;;
    help|-h|--help)
      usage
      ;;
    *)
      echo "Unknown command: ${command}" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "${@}"
