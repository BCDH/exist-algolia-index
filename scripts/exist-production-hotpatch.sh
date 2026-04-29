#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

EXIST_PRODUCTION_PORT=${EXIST_PRODUCTION_PORT:-22}
EXIST_PRODUCTION_REMOTE_DIR=${EXIST_PRODUCTION_REMOTE_DIR:-/tmp/exist-algolia-production-hotpatch}
EXIST_PRODUCTION_CONTAINER_NAME=${EXIST_PRODUCTION_CONTAINER_NAME:-existdb-production}
EXIST_PRODUCTION_ALGOLIA_SMOKE_INDEX_NAME=${EXIST_PRODUCTION_ALGOLIA_SMOKE_INDEX_NAME:-exist-algolia-index-smoke-production-hotpatch}

usage() {
  cat <<'EOF'
Usage: scripts/exist-production-hotpatch.sh <command> [options] [collection-path]

Commands:
  build               Build the plugin assembly JAR locally.
  upload              Upload the plugin artifact and helper scripts to production.
  run                 Hotpatch production, restart eXist, and run smoke verification.
                      By default this reindexes production dictionary data.
  reindex-collection  Reindex one configured production collection.
  help                Show this help message.

Options:
  --skip-build        Reuse the existing local assembly JAR instead of rebuilding it.
  --skip-reindex      With run, skip reindexing production dictionary data after
                      smoke verification.

Required environment for upload/run/reindex:
  EXIST_PRODUCTION_HOST
  EXIST_PRODUCTION_SSH_USER

Additional required environment for run/reindex:
  EXIST_PRODUCTION_ADMIN_PASSWORD
  EXIST_PRODUCTION_ALGOLIA_APPLICATION_ID
  EXIST_PRODUCTION_ALGOLIA_ADMIN_API_KEY
  EXIST_PRODUCTION_REINDEX_COLLECTION, unless run gets an explicit /db path
  or --skip-reindex is used

Optional environment:
  EXIST_PRODUCTION_PORT=22
  EXIST_PRODUCTION_REMOTE_DIR=/tmp/exist-algolia-production-hotpatch
  EXIST_PRODUCTION_CONTAINER_NAME=existdb-production
  EXIST_PRODUCTION_CONF_XML=/exist/etc/conf.xml
  EXIST_PRODUCTION_STARTUP_XML=/exist/etc/startup.xml
  EXIST_PRODUCTION_PLUGIN_LIB_DIR=/exist/lib
  EXIST_PRODUCTION_RESTART_CMD="docker restart existdb-production"
  EXIST_PRODUCTION_ALGOLIA_SMOKE_INDEX_NAME=exist-algolia-index-smoke-production-hotpatch
  EXIST_PRODUCTION_REINDEX_COLLECTION=/db/apps/raskovnik-data/data
EOF
}

require_production_target() {
  if [[ -z "${EXIST_PRODUCTION_HOST:-}" ]]; then
    echo "EXIST_PRODUCTION_HOST must be set for production hotpatches." >&2
    exit 1
  fi
  if [[ -z "${EXIST_PRODUCTION_SSH_USER:-}" ]]; then
    echo "EXIST_PRODUCTION_SSH_USER must be set for production hotpatches." >&2
    exit 1
  fi
}

require_production_secrets() {
  if [[ -z "${EXIST_PRODUCTION_ADMIN_PASSWORD:-}" ]]; then
    echo "EXIST_PRODUCTION_ADMIN_PASSWORD must be set for production hotpatches." >&2
    exit 1
  fi
  if [[ -z "${EXIST_PRODUCTION_ALGOLIA_APPLICATION_ID:-}" ]]; then
    echo "EXIST_PRODUCTION_ALGOLIA_APPLICATION_ID must be set for production hotpatches." >&2
    exit 1
  fi
  if [[ -z "${EXIST_PRODUCTION_ALGOLIA_ADMIN_API_KEY:-}" ]]; then
    echo "EXIST_PRODUCTION_ALGOLIA_ADMIN_API_KEY must be set for production hotpatches." >&2
    exit 1
  fi
}

require_production_reindex_target() {
  local collection_path=${1:-}

  if [[ -z "${collection_path}" && -z "${EXIST_PRODUCTION_REINDEX_COLLECTION:-}" ]]; then
    echo "EXIST_PRODUCTION_REINDEX_COLLECTION must be set when run will reindex by default, or pass an explicit /db collection path." >&2
    exit 1
  fi
}

export_stage_compat_env() {
  export EXIST_STAGE_HOST="${EXIST_PRODUCTION_HOST}"
  export EXIST_STAGE_PORT="${EXIST_PRODUCTION_PORT}"
  export EXIST_STAGE_SSH_USER="${EXIST_PRODUCTION_SSH_USER}"
  export EXIST_STAGE_REMOTE_DIR="${EXIST_PRODUCTION_REMOTE_DIR}"
  export EXIST_STAGE_CONTAINER_NAME="${EXIST_PRODUCTION_CONTAINER_NAME}"
  export EXIST_STAGE_ADMIN_PASSWORD="${EXIST_PRODUCTION_ADMIN_PASSWORD:-}"
  export EXIST_STAGE_CONF_XML="${EXIST_PRODUCTION_CONF_XML:-}"
  export EXIST_STAGE_STARTUP_XML="${EXIST_PRODUCTION_STARTUP_XML:-}"
  export EXIST_STAGE_PLUGIN_LIB_DIR="${EXIST_PRODUCTION_PLUGIN_LIB_DIR:-}"
  export EXIST_STAGE_RESTART_CMD="${EXIST_PRODUCTION_RESTART_CMD:-}"
  export EXIST_STAGE_ALGOLIA_APPLICATION_ID="${EXIST_PRODUCTION_ALGOLIA_APPLICATION_ID:-}"
  export EXIST_STAGE_ALGOLIA_ADMIN_API_KEY="${EXIST_PRODUCTION_ALGOLIA_ADMIN_API_KEY:-}"
  export EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME="${EXIST_PRODUCTION_ALGOLIA_SMOKE_INDEX_NAME}"
  export EXIST_STAGE_REINDEX_COLLECTION="${EXIST_PRODUCTION_REINDEX_COLLECTION:-}"
}

run_stage_wrapper() {
  "${SCRIPT_DIR}/exist-stage.sh" "$@"
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
      local args=(build)
      if [[ "${skip_build}" -eq 1 ]]; then
        args+=(--skip-build)
      fi
      run_stage_wrapper "${args[@]}"
      ;;
    upload)
      require_production_target
      export_stage_compat_env
      local args=(upload)
      if [[ "${skip_build}" -eq 1 ]]; then
        args+=(--skip-build)
      fi
      run_stage_wrapper "${args[@]}"
      ;;
    run|deploy)
      require_production_target
      require_production_secrets
      if [[ "${skip_reindex}" -eq 0 ]]; then
        require_production_reindex_target "${collection_path}"
      fi
      export_stage_compat_env
      local args=(run)
      if [[ "${skip_build}" -eq 1 ]]; then
        args+=(--skip-build)
      fi
      if [[ "${skip_reindex}" -eq 1 ]]; then
        args+=(--skip-reindex)
      elif [[ -n "${collection_path}" ]]; then
        args+=("${collection_path}")
      fi
      run_stage_wrapper "${args[@]}"
      ;;
    reindex-collection)
      require_production_target
      require_production_secrets
      export_stage_compat_env
      if [[ -z "${collection_path}" ]]; then
        echo "A collection path is required, e.g. /db/apps/raskovnik-data/data" >&2
        return 1
      fi
      run_stage_wrapper reindex-collection "${collection_path}"
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

main "$@"
