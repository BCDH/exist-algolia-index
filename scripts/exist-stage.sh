#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

EXIST_STAGE_PORT=${EXIST_STAGE_PORT:-22}
EXIST_STAGE_REMOTE_DIR=${EXIST_STAGE_REMOTE_DIR:-/tmp/exist-algolia-stage}
EXIST_STAGE_SSH_USER=${EXIST_STAGE_SSH_USER:-}
EXIST_STAGE_CONTAINER_NAME=${EXIST_STAGE_CONTAINER_NAME:-existdb-stage}
EXIST_STAGE_ADMIN_PASSWORD=${EXIST_STAGE_ADMIN_PASSWORD:-${EXIST_ADMIN_PASSWORD:-}}
EXIST_STAGE_ALGOLIA_APPLICATION_ID=${EXIST_STAGE_ALGOLIA_APPLICATION_ID:-}
EXIST_STAGE_ALGOLIA_ADMIN_API_KEY=${EXIST_STAGE_ALGOLIA_ADMIN_API_KEY:-}
EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME=${EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME:-exist-algolia-index-smoke-staging}
EXIST_STAGE_REINDEX_COLLECTION=${EXIST_STAGE_REINDEX_COLLECTION:-}

usage() {
  cat <<'EOF'
Usage: scripts/exist-stage.sh <command> [--skip-build] [--skip-reindex] [collection-path]

Commands:
  build            Build the plugin assembly JAR locally.
  upload           Upload the plugin artifact and helper scripts to the staging host.
  deploy           Upload artifacts and execute the remote staging helper.
  reindex-collection
                  Reindex one configured collection on the remote eXist host.
  run              Alias for deploy; reindexes by default after successful install.
  help             Show this help message.

Options:
  --skip-build     Reuse the existing local assembly JAR instead of rebuilding it.
  --skip-reindex   Skip the default post-install reindex step.

Required environment for upload:
  EXIST_STAGE_HOST
  EXIST_STAGE_SSH_USER

Additional required environment for deploy/run:
  EXIST_STAGE_ALGOLIA_APPLICATION_ID
  EXIST_STAGE_ALGOLIA_ADMIN_API_KEY
  EXIST_STAGE_ADMIN_PASSWORD
  EXIST_STAGE_REINDEX_COLLECTION, unless run gets an explicit /db path
  or --skip-reindex is used

Optional environment:
  EXIST_STAGE_PORT=22
  EXIST_STAGE_REMOTE_DIR=/tmp/exist-algolia-stage
  EXIST_STAGE_CONTAINER_NAME=existdb-stage
  EXIST_STAGE_CONF_XML=/exist/etc/conf.xml
  EXIST_STAGE_STARTUP_XML=/exist/etc/startup.xml
  EXIST_STAGE_PLUGIN_LIB_DIR=/exist/lib
  EXIST_STAGE_RESTART_CMD="docker restart existdb-stage"
  EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME=exist-algolia-index-smoke-staging
  EXIST_STAGE_REINDEX_COLLECTION=/db/apps/raskovnik-data/data
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
  if [[ -z "${EXIST_STAGE_ADMIN_PASSWORD:-}" ]]; then
    echo "EXIST_STAGE_ADMIN_PASSWORD must be set for staging deploys." >&2
    exit 1
  fi
  if [[ -z "${EXIST_STAGE_ALGOLIA_APPLICATION_ID:-}" ]]; then
    echo "EXIST_STAGE_ALGOLIA_APPLICATION_ID must be set for staging deploys." >&2
    exit 1
  fi
  if [[ -z "${EXIST_STAGE_ALGOLIA_ADMIN_API_KEY:-}" ]]; then
    echo "EXIST_STAGE_ALGOLIA_ADMIN_API_KEY must be set for staging deploys." >&2
    exit 1
  fi
}

require_stage_reindex_target() {
  local collection_path=${1:-}

  if [[ -z "${collection_path}" && -z "${EXIST_STAGE_REINDEX_COLLECTION:-}" ]]; then
    echo "EXIST_STAGE_REINDEX_COLLECTION must be set when run will reindex by default, or pass an explicit /db collection path." >&2
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
  upload_helper_files

  echo "[stage] Uploading plugin artifact"
  copy_to_remote \
    "${PLUGIN_JAR_PATH}" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/target/scala-${SCALA_BINARY_VERSION}/${PLUGIN_JAR_FILENAME}"
}

upload_helper_files() {
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
}

run_remote_helper() {
  local remote_command=${1:-run}
  local collection_path=${2:-}
  local skip_reindex=${3:-0}
  local remote_script="${EXIST_STAGE_REMOTE_DIR}/scripts/exist-stage-remote.sh"
  local remote_dir_quoted container_quoted stage_password_quoted app_id_quoted api_key_quoted conf_quoted startup_quoted lib_quoted restart_quoted smoke_index_quoted reindex_quoted skip_reindex_quoted
  local remote_command_quoted collection_path_quoted

  remote_dir_quoted=$(printf '%q' "${EXIST_STAGE_REMOTE_DIR}")
  container_quoted=$(printf '%q' "${EXIST_STAGE_CONTAINER_NAME}")
  stage_password_quoted=$(printf '%q' "${EXIST_STAGE_ADMIN_PASSWORD}")
  app_id_quoted=$(printf '%q' "${EXIST_STAGE_ALGOLIA_APPLICATION_ID}")
  api_key_quoted=$(printf '%q' "${EXIST_STAGE_ALGOLIA_ADMIN_API_KEY}")
  conf_quoted=$(printf '%q' "${EXIST_STAGE_CONF_XML:-}")
  startup_quoted=$(printf '%q' "${EXIST_STAGE_STARTUP_XML:-}")
  lib_quoted=$(printf '%q' "${EXIST_STAGE_PLUGIN_LIB_DIR:-}")
  restart_quoted=$(printf '%q' "${EXIST_STAGE_RESTART_CMD:-}")
  smoke_index_quoted=$(printf '%q' "${EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME:-}")
  reindex_quoted=$(printf '%q' "${EXIST_STAGE_REINDEX_COLLECTION:-}")
  skip_reindex_quoted=$(printf '%q' "${skip_reindex}")
  remote_command_quoted=$(printf '%q' "${remote_command}")
  collection_path_quoted=$(printf '%q' "${collection_path}")

  echo "[stage] Executing remote helper on $(remote_target)"
  remote_cmd "cd ${remote_dir_quoted} && EXISTDB_CONTAINER_NAME=${container_quoted} EXIST_STAGE_ADMIN_PASSWORD=${stage_password_quoted} ALGOLIA_APPLICATION_ID=${app_id_quoted} ALGOLIA_ADMIN_API_KEY=${api_key_quoted} EXIST_STAGE_CONF_XML=${conf_quoted} EXIST_STAGE_STARTUP_XML=${startup_quoted} EXIST_STAGE_PLUGIN_LIB_DIR=${lib_quoted} EXIST_STAGE_RESTART_CMD=${restart_quoted} ALGOLIA_SMOKE_INDEX_NAME=${smoke_index_quoted} EXIST_REINDEX_COLLECTION=${reindex_quoted} EXIST_SKIP_REINDEX=${skip_reindex_quoted} bash $(printf '%q' "${remote_script}") ${remote_command_quoted} ${collection_path_quoted}"
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
  local skip_reindex=$2
  local collection_path=${3:-}

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ "${skip_reindex}" -eq 0 ]]; then
    require_stage_reindex_target "${collection_path}"
  fi
  upload_only "${skip_build}"
  run_remote_helper run "${collection_path}" "${skip_reindex}"
}

reindex_remote_collection() {
  local collection_path=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/my-collection" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper reindex-collection "${collection_path}"
}

main() {
  local command=${1:-help}
  local skip_build=0
  local skip_reindex=0
  local collection_path=

  shift $(( $# > 0 ? 1 : 0 ))
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-build)
        skip_build=1
        ;;
      --skip-reindex)
        skip_reindex=1
        ;;
      help|-h|--help)
        usage
        return 0
        ;;
      /db|/db/*)
        collection_path=$1
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
    reindex-collection)
      reindex_remote_collection "${collection_path}"
      ;;
    deploy|run)
      deploy_all "${skip_build}" "${skip_reindex}" "${collection_path}"
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
