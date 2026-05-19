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
readonly STAGE_JAR_CHUNK_SIZE=$((8 * 1024 * 1024))

usage() {
  cat <<'EOF'
Usage: scripts/exist-stage.sh <command> [--skip-build] [--skip-reindex] [--force] [collection-path]

Commands:
  build            Build the plugin assembly JAR locally.
  upload           Upload the plugin artifact and helper scripts to the staging host.
  deploy           Upload artifacts and execute the remote staging helper.
  reindex-collection
                  Reindex one configured collection on the remote eXist host.
  verify-collection-sync
                  Compare live Algolia records with the remote local-store snapshot.
  inspect-collection-sync
                  Print read-only sync classification, per-collection counts, and replay blast radius.
  replay-collection-live
                  Delete only matching live records and republish this collection from the local store.
  refresh-indexing-status
                  Rewrite status.json for a synced collection when the status file is stale or incomplete.
  reconcile-collection
                  Dangerous fallback: mutate remote local-store snapshots, reindex, and verify convergence.
  run              Alias for deploy; reindexes by default after successful install.
  help             Show this help message.

Options:
  --skip-build     Reuse the existing local assembly JAR instead of rebuilding it.
  --skip-reindex   Skip the default post-install reindex step.
  --force          With reconcile-collection, reindex even if sync already matches.

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

shell_join() {
  local parts=()
  local value

  for value in "$@"; do
    parts+=("$(printf '%q' "${value}")")
  done

  printf '%s' "${parts[*]}"
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
  require_cmd python3
  require_cmd split
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

local_file_sha256() {
  local path=$1

  python3 - "${path}" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with path.open("rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)
print(digest.hexdigest())
PY
}

local_file_size() {
  local path=$1

  python3 - "${path}" <<'PY'
import pathlib
import sys

print(pathlib.Path(sys.argv[1]).stat().st_size)
PY
}

remote_stage_chunk_dir() {
  printf '%s/target/scala-%s/.%s.parts' \
    "${EXIST_STAGE_REMOTE_DIR}" \
    "${SCALA_BINARY_VERSION}" \
    "${PLUGIN_JAR_FILENAME}"
}

remote_stage_jar_path() {
  printf '%s/target/scala-%s/%s' \
    "${EXIST_STAGE_REMOTE_DIR}" \
    "${SCALA_BINARY_VERSION}" \
    "${PLUGIN_JAR_FILENAME}"
}

cleanup_remote_chunk_upload() {
  local remote_chunk_dir=$1
  local remote_partial_path=$2

  remote_cmd \
    "rm -rf $(printf '%q' "${remote_chunk_dir}") && rm -f $(printf '%q' "${remote_partial_path}")" \
    >/dev/null 2>&1 || true
}

upload_plugin_artifact_chunked() {
  local tmp_dir remote_chunk_dir remote_jar_path remote_partial_path expected_sha256 expected_size
  local chunk_prefix
  local -a chunk_paths=()
  local chunk_path
  local remote_target_prefix

  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN

  remote_chunk_dir=$(remote_stage_chunk_dir)
  remote_jar_path=$(remote_stage_jar_path)
  remote_partial_path="${remote_jar_path}.partial"
  chunk_prefix="${tmp_dir}/${PLUGIN_JAR_FILENAME}.part"
  expected_sha256=$(local_file_sha256 "${PLUGIN_JAR_PATH}")
  expected_size=$(local_file_size "${PLUGIN_JAR_PATH}")

  split -b "${STAGE_JAR_CHUNK_SIZE}" -d -a 4 "${PLUGIN_JAR_PATH}" "${chunk_prefix}"
  for chunk_path in "${chunk_prefix}"*; do
    chunk_paths+=("${chunk_path}")
  done

  if [[ ${#chunk_paths[@]} -eq 0 ]]; then
    echo "Chunked upload did not create any parts for ${PLUGIN_JAR_PATH}" >&2
    exit 1
  fi

  echo "[stage] Uploading plugin artifact in ${#chunk_paths[@]} chunk(s)"
  remote_cmd "mkdir -p $(printf '%q' "${remote_chunk_dir}")"
  remote_target_prefix="$(remote_target):${remote_chunk_dir}/"
  if ! copy_to_remote "${chunk_paths[@]}" "${remote_target_prefix}"; then
    cleanup_remote_chunk_upload "${remote_chunk_dir}" "${remote_partial_path}"
    exit 1
  fi

  if ! remote_cmd \
    "REMOTE_CHUNK_DIR=$(printf '%q' "${remote_chunk_dir}") REMOTE_JAR_PATH=$(printf '%q' "${remote_jar_path}") REMOTE_PARTIAL_PATH=$(printf '%q' "${remote_partial_path}") EXPECTED_SHA256=$(printf '%q' "${expected_sha256}") EXPECTED_SIZE=$(printf '%q' "${expected_size}") python3 -c $(printf '%q' 'import hashlib
import os
import pathlib
import shutil
import sys

chunk_dir = pathlib.Path(os.environ["REMOTE_CHUNK_DIR"])
jar_path = pathlib.Path(os.environ["REMOTE_JAR_PATH"])
partial_path = pathlib.Path(os.environ["REMOTE_PARTIAL_PATH"])
expected_sha256 = os.environ["EXPECTED_SHA256"]
expected_size = int(os.environ["EXPECTED_SIZE"])
parts = sorted(path for path in chunk_dir.iterdir() if path.is_file())
if not parts:
    raise SystemExit(f"No uploaded chunks found in {chunk_dir}")
sha256 = hashlib.sha256()
size = 0
success = False
try:
    partial_path.parent.mkdir(parents=True, exist_ok=True)
    with partial_path.open("wb") as destination:
        for part in parts:
            with part.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    destination.write(chunk)
                    sha256.update(chunk)
                    size += len(chunk)
    digest = sha256.hexdigest()
    if size != expected_size:
        raise SystemExit(f"Uploaded jar size mismatch: expected {expected_size}, got {size}")
    if digest != expected_sha256:
        raise SystemExit(f"Uploaded jar sha256 mismatch: expected {expected_sha256}, got {digest}")
    partial_path.replace(jar_path)
    success = True
    print(f"[stage] Remote jar verified: {jar_path} ({size} bytes, sha256={digest})")
finally:
    shutil.rmtree(chunk_dir, ignore_errors=True)
    if not success and partial_path.exists():
        partial_path.unlink()')"; then
    cleanup_remote_chunk_upload "${remote_chunk_dir}" "${remote_partial_path}"
    exit 1
  fi
}

upload_payload() {
  require_plugin_artifact

  echo "[stage] Uploading project metadata"
  upload_helper_files

  upload_plugin_artifact_chunked
}

upload_helper_files() {
  echo "[stage] Uploading project metadata"
  copy_to_remote \
    "${ROOT_DIR}/build.sbt" \
    "${ROOT_DIR}/version.sbt" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/"

  echo "[stage] Uploading helper scripts"
  copy_to_remote \
    "${ROOT_DIR}/scripts/algolia_collection_sync.py" \
    "${ROOT_DIR}/scripts/exist-common.sh" \
    "${ROOT_DIR}/scripts/exist-stage-remote.sh" \
    "${ROOT_DIR}/scripts/manage-exist-config.py" \
    "$(remote_target):${EXIST_STAGE_REMOTE_DIR}/scripts/"
}

run_remote_helper() {
  local remote_command=${1:-run}
  local collection_path=${2:-}
  local skip_reindex=${3:-0}
  local force=${4:-0}
  local remote_script="${EXIST_STAGE_REMOTE_DIR}/scripts/exist-stage-remote.sh"
  local remote_command_string
  local -a remote_env=()
  local -a remote_args=()

  remote_env+=("EXISTDB_CONTAINER_NAME=${EXIST_STAGE_CONTAINER_NAME}")
  remote_env+=("EXIST_STAGE_ADMIN_PASSWORD=${EXIST_STAGE_ADMIN_PASSWORD}")
  remote_env+=("ALGOLIA_APPLICATION_ID=${EXIST_STAGE_ALGOLIA_APPLICATION_ID}")
  remote_env+=("ALGOLIA_ADMIN_API_KEY=${EXIST_STAGE_ALGOLIA_ADMIN_API_KEY}")
  remote_env+=("EXIST_STAGE_CONF_XML=${EXIST_STAGE_CONF_XML:-}")
  remote_env+=("EXIST_STAGE_STARTUP_XML=${EXIST_STAGE_STARTUP_XML:-}")
  remote_env+=("EXIST_STAGE_PLUGIN_LIB_DIR=${EXIST_STAGE_PLUGIN_LIB_DIR:-}")
  remote_env+=("EXIST_STAGE_RESTART_CMD=${EXIST_STAGE_RESTART_CMD:-}")
  remote_env+=("ALGOLIA_SMOKE_INDEX_NAME=${EXIST_STAGE_ALGOLIA_SMOKE_INDEX_NAME:-}")
  remote_env+=("EXIST_REINDEX_COLLECTION=${EXIST_STAGE_REINDEX_COLLECTION:-}")
  remote_env+=("EXIST_SKIP_REINDEX=${skip_reindex}")
  remote_env+=("EXIST_SYNC_INSPECT_HINT_PREFIX=${EXIST_SYNC_INSPECT_HINT_PREFIX:-scripts/exist-stage.sh inspect-collection-sync}")
  remote_env+=("EXIST_SYNC_REPLAY_HINT_PREFIX=${EXIST_SYNC_REPLAY_HINT_PREFIX:-scripts/exist-stage.sh replay-collection-live}")
  remote_env+=("EXIST_SYNC_REFRESH_STATUS_HINT_PREFIX=${EXIST_SYNC_REFRESH_STATUS_HINT_PREFIX:-scripts/exist-stage.sh refresh-indexing-status}")
  remote_env+=("EXIST_ALGOLIA_ALLOW_DANGEROUS_RECONCILE=${EXIST_ALGOLIA_ALLOW_DANGEROUS_RECONCILE:-0}")
  remote_env+=("LOCAL_STORE_DOCKER_HELPER_IMAGE=${LOCAL_STORE_DOCKER_HELPER_IMAGE:-busybox:latest}")

  remote_args+=(bash "${remote_script}" "${remote_command}")
  case "${remote_command}" in
    run)
      if [[ -n "${collection_path}" ]]; then
        remote_args+=("${collection_path}")
      fi
      ;;
    reindex-collection|verify-collection-sync|inspect-collection-sync|replay-collection-live|refresh-indexing-status)
      remote_args+=("${collection_path}")
      ;;
    reconcile-collection)
      if [[ "${force}" == "1" ]]; then
        remote_args+=(--force)
      fi
      remote_args+=("${collection_path}")
      ;;
  esac

  remote_command_string="cd $(printf '%q' "${EXIST_STAGE_REMOTE_DIR}") && $(shell_join "${remote_env[@]}") $(shell_join "${remote_args[@]}")"

  echo "[stage] Executing remote helper on $(remote_target)"
  remote_cmd "${remote_command_string}"
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

verify_remote_collection_sync() {
  local collection_path=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/apps/raskovnik-data/data/GE.RKMD" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper verify-collection-sync "${collection_path}"
}

inspect_remote_collection_sync() {
  local collection_path=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/apps/raskovnik-data/data/GE.RKMD" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper inspect-collection-sync "${collection_path}"
}

replay_remote_collection_live() {
  local collection_path=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/apps/raskovnik-data/data/GE.RKMD" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper replay-collection-live "${collection_path}"
}

refresh_remote_indexing_status() {
  local collection_path=$1

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/apps/raskovnik-data/data/GE.RKMD" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper refresh-indexing-status "${collection_path}"
}

reconcile_remote_collection() {
  local collection_path=$1
  local force=$2

  require_stage_prereqs
  require_stage_target
  require_stage_secrets
  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/apps/raskovnik-data/data/GE.RKMD" >&2
    exit 1
  fi
  prepare_remote_tree
  upload_helper_files
  run_remote_helper reconcile-collection "${collection_path}" 0 "${force}"
}

main() {
  local command=${1:-help}
  local skip_build=0
  local skip_reindex=0
  local force=0
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
      --force)
        force=1
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
    verify-collection-sync)
      verify_remote_collection_sync "${collection_path}"
      ;;
    inspect-collection-sync)
      inspect_remote_collection_sync "${collection_path}"
      ;;
    replay-collection-live)
      replay_remote_collection_live "${collection_path}"
      ;;
    refresh-indexing-status)
      refresh_remote_indexing_status "${collection_path}"
      ;;
    reconcile-collection)
      reconcile_remote_collection "${collection_path}" "${force}"
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
