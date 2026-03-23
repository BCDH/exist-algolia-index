#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

EXIST_ADMIN_USER=${EXIST_ADMIN_USER:-admin}
EXIST_LOCAL_ADMIN_PASSWORD=${EXIST_LOCAL_ADMIN_PASSWORD:-${EXIST_ADMIN_PASSWORD:-}}

usage() {
  cat <<'EOF'
Usage: scripts/exist-local.sh <command> [--skip-reindex] [collection-path]

Commands:
  build              Build the plugin assembly JAR with sbt.
  install-plugin     Copy the plugin JAR into the local eXist lib directory.
  configure-plugin   Ensure conf.xml contains the Algolia index module stanza.
  configure-startup  Ensure startup.xml contains the plugin dependency entry.
  reindex-collection Reindex one configured collection to backfill Algolia.
  restart            Restart eXist using EXIST_RESTART_CMD if configured.
  verify             Verify install state and run the smoke reindex check.
  run                Build, install, configure, restart if configured, verify, then reindex.
  help               Show this help message.

Required environment:
  EXIST_HOME
  ALGOLIA_APPLICATION_ID
  ALGOLIA_ADMIN_API_KEY

Additional required environment for verify/run:
  EXIST_LOCAL_ADMIN_PASSWORD

Optional environment:
  EXIST_CLIENT_CMD
  EXIST_CONF_XML
  EXIST_STARTUP_XML
  EXIST_PLUGIN_LIB_DIR
  EXIST_RESTART_CMD
  ALGOLIA_SMOKE_INDEX_NAME
  EXIST_REINDEX_COLLECTION=/db
EOF
}

require_collection_path() {
  local collection_path=$1

  if [[ -z "${collection_path}" ]]; then
    echo "A collection path is required, e.g. /db/my-collection" >&2
    exit 1
  fi

  if [[ "${collection_path}" != /db/* && "${collection_path}" != "/db" ]]; then
    echo "Collection path must start with /db: ${collection_path}" >&2
    exit 1
  fi
}

resolve_reindex_collection() {
  local override_value=${1:-}
  local collection_path=${override_value:-${EXIST_REINDEX_COLLECTION:-/db}}
  require_collection_path "${collection_path}"
  printf '%s' "${collection_path}"
}

require_local_admin() {
  if [[ -z "${EXIST_LOCAL_ADMIN_PASSWORD:-}" ]]; then
    echo "EXIST_LOCAL_ADMIN_PASSWORD must be set for verification and smoke reindex checks." >&2
    exit 1
  fi
}

local_exist_runtime_mode() {
  local conf_xml process_lines
  conf_xml=$(resolve_local_conf_xml)
  process_lines=$(ps -axo pid=,command= | rg -F -- "-Dexist.configurationFile=${conf_xml}" || true)
  process_lines=$(printf '%s\n' "${process_lines}" | rg -v -- "-Dapp.name=client|-Dapp.name=shutdown" || true)

  if [[ -z "${process_lines}" ]]; then
    printf '%s' "stopped"
    return 0
  fi

  if grep -q "org.exist.launcher.Launcher" <<<"${process_lines}" || grep -q -- "-Dapp.name=launcher" <<<"${process_lines}" || grep -q -- "-Dapple.awt.UIElement=true" <<<"${process_lines}"; then
    printf '%s' "gui"
    return 0
  fi

  printf '%s' "running"
}

ensure_local_automation_allowed() {
  local runtime_mode

  if ! is_macos_app_bundle_install; then
    return 0
  fi

  runtime_mode=$(local_exist_runtime_mode)
  if [[ "${runtime_mode}" != "gui" ]]; then
    return 0
  fi

  cat >&2 <<EOF
Automatic local restart is disabled for this macOS app-bundle eXist while it is running via the GUI launcher.

Stop the GUI-launched instance and start eXist from:

  $(resolve_local_startup_script)

Then rerun the script.
EOF
  return 1
}

default_local_restart_cmd() {
  local runtime_mode=$1

  if [[ "${runtime_mode}" == "stopped" ]]; then
    default_local_start_cmd
    return 0
  fi

  default_local_shutdown_cmd
}

default_local_start_cmd() {
  local startup_script
  startup_script=$(resolve_local_startup_script)
  printf 'nohup %q >/tmp/exist-algolia-index-startup.log 2>&1 </dev/null &' "${startup_script}"
}

default_local_shutdown_cmd() {
  local shutdown_script

  require_local_admin
  shutdown_script=$(resolve_local_shutdown_script)
  printf '%q -u %q -p %q' \
    "${shutdown_script}" \
    "${EXIST_ADMIN_USER}" \
    "${EXIST_LOCAL_ADMIN_PASSWORD}"
}

wait_for_local_stop() {
  for _ in $(seq 1 60); do
    if [[ "$(local_exist_runtime_mode)" == "stopped" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Local eXist did not stop in time." >&2
  return 1
}

wait_for_local_ready() {
  local client_cmd

  client_cmd=$(resolve_local_client_cmd)
  require_local_admin

  for _ in $(seq 1 60); do
    if "${client_cmd}" \
      --no-gui \
      --no-embedded-mode \
      -u "${EXIST_ADMIN_USER}" \
      -P "${EXIST_LOCAL_ADMIN_PASSWORD}" \
      -x "1" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "Local eXist did not become ready in time." >&2
  return 1
}

local_client_query() {
  local query=$1
  local client_cmd
  client_cmd=$(resolve_local_client_cmd)
  require_local_admin

  "${client_cmd}" \
    --no-gui \
    --no-embedded-mode \
    -u "${EXIST_ADMIN_USER}" \
    -P "${EXIST_LOCAL_ADMIN_PASSWORD}" \
    -x "${query}"
}

local_client_collection_cmd() {
  local client_cmd
  client_cmd=$(resolve_local_client_cmd)
  require_local_admin

  "${client_cmd}" \
    --no-gui \
    --no-embedded-mode \
    -u "${EXIST_ADMIN_USER}" \
    -P "${EXIST_LOCAL_ADMIN_PASSWORD}" \
    "$@"
}

build_cmd() {
  build_plugin
  require_plugin_artifact
  echo "Built ${PLUGIN_JAR_PATH}"
}

install_plugin() {
  local plugin_lib_dir

  require_plugin_artifact
  plugin_lib_dir=$(resolve_local_plugin_lib_dir)
  ensure_dir "${plugin_lib_dir}"

  echo "Installing plugin JAR into ${plugin_lib_dir}"
  rm -f "${plugin_lib_dir}/${PLUGIN_ARTIFACT_ID}-assembly-"*.jar
  cp "${PLUGIN_JAR_PATH}" "${plugin_lib_dir}/${PLUGIN_JAR_FILENAME}"
}

configure_plugin() {
  local conf_xml

  require_cmd python3
  require_algolia_credentials
  conf_xml=$(resolve_local_conf_xml)

  echo "Updating ${conf_xml}"
  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    update-conf \
    "${conf_xml}" \
    "${ALGOLIA_APPLICATION_ID}" \
    "${ALGOLIA_ADMIN_API_KEY}" \
    "${MANAGED_CONF_BEGIN}" \
    "${MANAGED_CONF_END}"
}

configure_startup() {
  local startup_xml

  require_cmd python3
  startup_xml=$(resolve_local_startup_xml)

  echo "Updating ${startup_xml}"
  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    update-startup \
    "${startup_xml}" \
    "${PLUGIN_GROUP_ID}" \
    "${PLUGIN_ARTIFACT_ID}" \
    "${PROJECT_VERSION}" \
    "${PLUGIN_JAR_FILENAME}" \
    "${MANAGED_STARTUP_BEGIN}" \
    "${MANAGED_STARTUP_END}"
}

restart_exist() {
  local restart_log runtime_mode restart_cmd shutdown_cmd start_cmd

  ensure_local_automation_allowed || return 1
  runtime_mode=$(local_exist_runtime_mode)
  restart_cmd=${EXIST_RESTART_CMD:-}
  if [[ -n "${restart_cmd}" ]]; then
    restart_log=$(mktemp -t exist-algolia-index-restart.XXXXXX.log)
    echo "Local eXist restart: ${restart_cmd}"
    if bash -lc "${restart_cmd}" >"${restart_log}" 2>&1 && wait_for_local_ready >>"${restart_log}" 2>&1; then
      echo "Local eXist restart completed. Log: ${restart_log}"
      export EXIST_LAST_RESTART_LOG="${restart_log}"
      return 0
    fi

    echo "Local eXist restart failed. Log: ${restart_log}" >&2
    cat "${restart_log}" >&2
    return 1
  fi

  if ! is_macos_app_bundle_install; then
    echo "Local eXist restart not configured; skipping."
    return 2
  fi

  restart_log=$(mktemp -t exist-algolia-index-restart.XXXXXX.log)
  : >"${restart_log}"

  if [[ "${runtime_mode}" != "stopped" ]]; then
    shutdown_cmd=$(default_local_shutdown_cmd)
    echo "Local eXist shutdown: ${shutdown_cmd}"
    if ! bash -lc "${shutdown_cmd}" >"${restart_log}" 2>&1 || ! wait_for_local_stop >>"${restart_log}" 2>&1; then
      echo "Local eXist shutdown failed. Log: ${restart_log}" >&2
      cat "${restart_log}" >&2
      return 1
    fi
  fi

  start_cmd=$(default_local_start_cmd)
  echo "Local eXist startup: ${start_cmd}"
  if bash -lc "${start_cmd}" >>"${restart_log}" 2>&1 && wait_for_local_ready >>"${restart_log}" 2>&1; then
    echo "Local eXist restart completed. Log: ${restart_log}"
    export EXIST_LAST_RESTART_LOG="${restart_log}"
    return 0
  fi

  echo "Local eXist restart failed. Log: ${restart_log}" >&2
  cat "${restart_log}" >&2
  return 1
}

verify_restart_log() {
  local restart_log=${1:-}

  if [[ -z "${restart_log}" ]]; then
    return 0
  fi

  if [[ ! -f "${restart_log}" ]]; then
    echo "Restart log does not exist: ${restart_log}" >&2
    return 1
  fi

  if rg -n "ClassNotFoundException|NoClassDefFoundError|Exception in thread" "${restart_log}" >/dev/null 2>&1; then
    echo "Restart log contains classloading or startup errors: ${restart_log}" >&2
    return 1
  fi
}

run_smoke_test() {
  local tmp_dir marker_file data_dir output smoke_file smoke_conf found_file algolia_summary

  require_cmd mktemp
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  marker_file="${tmp_dir}/marker"
  smoke_file="${tmp_dir}/${SMOKE_DOC_FILENAME}"
  smoke_conf="${tmp_dir}/${SMOKE_CONFIG_FILENAME}"

  smoke_document_xml >"${smoke_file}"
  smoke_collection_config >"${smoke_conf}"
  touch "${marker_file}"

  local_client_query "$(ensure_collection_query "${SMOKE_COLLECTION_PATH}")" >/dev/null
  local_client_query "$(ensure_collection_query "${SMOKE_CONFIG_COLLECTION_PATH}")" >/dev/null
  local_client_collection_cmd -c "${SMOKE_COLLECTION_PATH}" -p "${smoke_file}" >/dev/null
  local_client_collection_cmd -c "${SMOKE_CONFIG_COLLECTION_PATH}" -p "${smoke_conf}" >/dev/null

  output=$(local_client_query "$(reindex_collection_query "${SMOKE_COLLECTION_PATH}")")
  echo "${output}"
  if ! grep -q "true" <<<"${output}"; then
    echo "Smoke reindex failed for ${SMOKE_COLLECTION_PATH}" >&2
    return 1
  fi

  sleep 2
  data_dir=$(resolve_local_data_dir)
  found_file=$(find_new_local_store_file "${data_dir}" "${marker_file}")
  if [[ -z "${found_file}" ]]; then
    echo "No new local Algolia store file was created under ${data_dir}/algolia-index/indexes/${SMOKE_INDEX_NAME}" >&2
    return 1
  fi

  echo "Smoke reindex wrote ${found_file}"
  algolia_summary=$(wait_for_algolia_smoke_upload "${SMOKE_INDEX_NAME}")
  echo "Smoke upload reached Algolia: ${algolia_summary}"
  local_client_query "$(cleanup_smoke_resources_query)" >/dev/null
  delete_algolia_index "${SMOKE_INDEX_NAME}"
  wait_for_algolia_index_deletion "${SMOKE_INDEX_NAME}"
}

verify_install() {
  local conf_xml startup_xml plugin_lib_dir installed_jar restart_log=${1:-}

  require_cmd python3
  require_algolia_credentials
  conf_xml=$(resolve_local_conf_xml)
  startup_xml=$(resolve_local_startup_xml)
  plugin_lib_dir=$(resolve_local_plugin_lib_dir)
  installed_jar="${plugin_lib_dir}/${PLUGIN_JAR_FILENAME}"

  require_file "${installed_jar}"

  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    verify-conf \
    "${conf_xml}" \
    --application-id "${ALGOLIA_APPLICATION_ID}" \
    --admin-api-key "${ALGOLIA_ADMIN_API_KEY}"

  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    verify-startup \
    "${startup_xml}" \
    "${PLUGIN_ARTIFACT_ID}" \
    "${PROJECT_VERSION}" \
    "${PLUGIN_JAR_FILENAME}"

  verify_restart_log "${restart_log}"
  run_smoke_test
}

reindex_collection() {
  local collection_path=$1
  local output tmp_dir output_file error_file status_file started_at elapsed

  require_collection_path "${collection_path}"
  require_cmd mktemp

  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  output_file="${tmp_dir}/stdout"
  error_file="${tmp_dir}/stderr"
  status_file="${tmp_dir}/status"
  started_at=$(date +%s)

  echo "Reindex started for ${collection_path}. Progress updates will be printed every 15 seconds."
  (
    set +e
    local_client_query "$(reindex_collection_query "${collection_path}")" >"${output_file}" 2>"${error_file}"
    printf '%s' "$?" >"${status_file}"
  ) &
  local reindex_pid=$!

  while kill -0 "${reindex_pid}" >/dev/null 2>&1; do
    sleep 15
    if kill -0 "${reindex_pid}" >/dev/null 2>&1; then
      elapsed=$(( $(date +%s) - started_at ))
      echo "Reindex still running for ${collection_path} (${elapsed}s elapsed)..."
    fi
  done

  wait "${reindex_pid}" >/dev/null 2>&1 || true
  output=$(cat "${output_file}")
  if [[ -s "${error_file}" ]]; then
    cat "${error_file}" >&2
  fi
  if [[ ! -f "${status_file}" ]] || [[ "$(cat "${status_file}")" != "0" ]]; then
    echo "${output}"
    echo "Reindex failed for ${collection_path}" >&2
    return 1
  fi

  echo "${output}"
  if ! grep -q "true" <<<"${output}"; then
    echo "Reindex failed for ${collection_path}" >&2
    return 1
  fi

  echo "Reindex completed for ${collection_path}"
}

install_and_verify() {
  local restart_status=0

  ensure_local_automation_allowed
  build_cmd
  install_plugin
  configure_plugin
  configure_startup

  set +e
  restart_exist
  restart_status=$?
  set -e

  if [[ "${restart_status}" -eq 2 ]]; then
    echo "eXist restart is not configured. Installation files were updated, but restart is required before verification." >&2
    return 2
  fi
  if [[ "${restart_status}" -ne 0 ]]; then
    echo "eXist restart failed; stopping before verification." >&2
    return 1
  fi

  verify_install "${EXIST_LAST_RESTART_LOG:-}"
}

run_all() {
  local collection_path=${1:-}

  install_and_verify
  collection_path=$(resolve_reindex_collection "${collection_path}")
  reindex_collection "${collection_path}"
}

main() {
  local command=${1:-help}
  local collection_path=
  local skip_reindex=0

  shift $(( $# > 0 ? 1 : 0 ))
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-reindex)
        skip_reindex=1
        ;;
      /db|/db/*)
        collection_path=$1
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
      build_cmd
      ;;
    install-plugin)
      install_plugin
      ;;
    configure-plugin)
      configure_plugin
      ;;
    configure-startup)
      configure_startup
      ;;
    reindex-collection)
      reindex_collection "${collection_path}"
      ;;
    restart)
      restart_exist
      ;;
    verify)
      verify_install "${EXIST_LAST_RESTART_LOG:-}"
      ;;
    run)
      if [[ "${skip_reindex}" -eq 1 ]]; then
        install_and_verify
      else
        run_all "${collection_path}"
      fi
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
