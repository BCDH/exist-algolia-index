#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/exist-common.sh"

EXIST_ADMIN_USER=${EXIST_ADMIN_USER:-admin}
EXIST_STAGE_ADMIN_PASSWORD=${EXIST_STAGE_ADMIN_PASSWORD:-${EXIST_ADMIN_PASSWORD:-}}
EXISTDB_CONTAINER_NAME=${EXISTDB_CONTAINER_NAME:-existdb-stage}
REMOTE_CONF_XML=${EXIST_STAGE_CONF_XML:-}
REMOTE_STARTUP_XML=${EXIST_STAGE_STARTUP_XML:-}
REMOTE_PLUGIN_LIB_DIR=${EXIST_STAGE_PLUGIN_LIB_DIR:-}

usage() {
  cat <<'EOF'
Usage: scripts/exist-stage-remote.sh <command>

Commands:
  install-plugin     Copy the plugin JAR into the running eXist container.
  configure-plugin   Ensure conf.xml contains the Algolia module stanza.
  configure-startup  Ensure startup.xml contains the plugin dependency entry.
  restart            Restart the eXist container or run EXIST_STAGE_RESTART_CMD.
  verify             Verify install state and run the smoke reindex check.
  run                Execute the full staging install flow.
  help               Show this help message.
EOF
}

require_prereqs() {
  require_cmd docker
  require_cmd python3
  require_cmd curl
}

require_secrets() {
  require_algolia_credentials
  if [[ -z "${EXIST_STAGE_ADMIN_PASSWORD:-}" ]]; then
    echo "EXIST_STAGE_ADMIN_PASSWORD must be set." >&2
    exit 1
  fi
}

require_container_running() {
  if [[ "$(docker inspect -f '{{.State.Running}}' "${EXISTDB_CONTAINER_NAME}" 2>/dev/null || true)" != "true" ]]; then
    echo "eXist-db container is not running: ${EXISTDB_CONTAINER_NAME}" >&2
    exit 1
  fi
}

container_sh() {
  docker exec "${EXISTDB_CONTAINER_NAME}" sh -lc "$*"
}

client_query_admin() {
  local query=$1
  require_container_running
  docker exec "${EXISTDB_CONTAINER_NAME}" \
    java org.exist.start.Main client \
      --no-gui \
      -u "${EXIST_ADMIN_USER}" \
      -P "${EXIST_STAGE_ADMIN_PASSWORD}" \
      -x "${query}"
}

client_collection_cmd() {
  require_container_running
  docker exec "${EXISTDB_CONTAINER_NAME}" \
    java org.exist.start.Main client \
      --no-gui \
      -u "${EXIST_ADMIN_USER}" \
      -P "${EXIST_STAGE_ADMIN_PASSWORD}" \
      "$@"
}

resolve_container_file() {
  local override_value=$1
  shift

  if [[ -n "${override_value}" ]]; then
    if container_sh "[ -f $(printf '%q' "${override_value}") ]"; then
      printf '%s' "${override_value}"
      return 0
    fi
    echo "Configured file does not exist in container ${EXISTDB_CONTAINER_NAME}: ${override_value}" >&2
    exit 1
  fi

  local candidate
  for candidate in "$@"; do
    if container_sh "[ -f $(printf '%q' "${candidate}") ]"; then
      printf '%s' "${candidate}"
      return 0
    fi
  done

  echo "Could not resolve file path inside ${EXISTDB_CONTAINER_NAME}. Set an explicit override." >&2
  exit 1
}

resolve_container_dir() {
  local override_value=$1
  shift

  if [[ -n "${override_value}" ]]; then
    if container_sh "[ -d $(printf '%q' "${override_value}") ]"; then
      printf '%s' "${override_value}"
      return 0
    fi
    echo "Configured directory does not exist in container ${EXISTDB_CONTAINER_NAME}: ${override_value}" >&2
    exit 1
  fi

  local candidate
  for candidate in "$@"; do
    if container_sh "[ -d $(printf '%q' "${candidate}") ]"; then
      printf '%s' "${candidate}"
      return 0
    fi
  done

  echo "Could not resolve directory path inside ${EXISTDB_CONTAINER_NAME}. Set an explicit override." >&2
  exit 1
}

resolve_remote_conf_xml() {
  resolve_container_file \
    "${REMOTE_CONF_XML}" \
    "/exist/etc/conf.xml" \
    "/opt/exist/etc/conf.xml" \
    "/usr/local/exist/etc/conf.xml" \
    "/exist-db/etc/conf.xml" \
    "/exist/conf.xml"
}

resolve_remote_startup_xml() {
  resolve_container_file \
    "${REMOTE_STARTUP_XML}" \
    "/exist/etc/startup.xml" \
    "/opt/exist/etc/startup.xml" \
    "/usr/local/exist/etc/startup.xml" \
    "/exist-db/etc/startup.xml" \
    "/exist/startup.xml"
}

resolve_remote_plugin_lib_dir() {
  resolve_container_dir \
    "${REMOTE_PLUGIN_LIB_DIR}" \
    "/exist/lib" \
    "/opt/exist/lib" \
    "/usr/local/exist/lib" \
    "/exist-db/lib"
}

resolve_remote_data_dir() {
  resolve_container_dir \
    "${EXIST_STAGE_DATA_DIR:-}" \
    "/exist/data" \
    "/opt/exist/data" \
    "/usr/local/exist/data" \
    "/exist-db/data" \
    "/exist/webapp/WEB-INF/data"
}

copy_container_file_to_tmp() {
  local container_path=$1
  local tmp_path=$2
  docker cp "${EXISTDB_CONTAINER_NAME}:${container_path}" "${tmp_path}"
}

copy_tmp_file_to_container() {
  local tmp_path=$1
  local container_path=$2
  docker cp "${tmp_path}" "${EXISTDB_CONTAINER_NAME}:${container_path}"
}

install_plugin() {
  local plugin_lib_dir container_jar_path

  require_plugin_artifact
  require_container_running
  plugin_lib_dir=$(resolve_remote_plugin_lib_dir)
  container_jar_path="${plugin_lib_dir}/${PLUGIN_JAR_FILENAME}"

  echo "[remote] Installing plugin JAR into ${container_jar_path}"
  container_sh "rm -f $(printf '%q' "${plugin_lib_dir}")/${PLUGIN_ARTIFACT_ID}-assembly-*.jar"
  docker cp "${PLUGIN_JAR_PATH}" "${EXISTDB_CONTAINER_NAME}:${container_jar_path}"
}

configure_plugin() {
  local conf_xml tmp_dir tmp_file

  require_container_running
  conf_xml=$(resolve_remote_conf_xml)
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  tmp_file="${tmp_dir}/conf.xml"

  copy_container_file_to_tmp "${conf_xml}" "${tmp_file}"
  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    update-conf \
    "${tmp_file}" \
    "${ALGOLIA_APPLICATION_ID}" \
    "${ALGOLIA_ADMIN_API_KEY}" \
    "${MANAGED_CONF_BEGIN}" \
    "${MANAGED_CONF_END}"
  copy_tmp_file_to_container "${tmp_file}" "${conf_xml}"
}

configure_startup() {
  local startup_xml tmp_dir tmp_file

  require_container_running
  startup_xml=$(resolve_remote_startup_xml)
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  tmp_file="${tmp_dir}/startup.xml"

  copy_container_file_to_tmp "${startup_xml}" "${tmp_file}"
  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    update-startup \
    "${tmp_file}" \
    "${PLUGIN_GROUP_ID}" \
    "${PLUGIN_ARTIFACT_ID}" \
    "${PROJECT_VERSION}" \
    "${PLUGIN_JAR_FILENAME}" \
    "${MANAGED_STARTUP_BEGIN}" \
    "${MANAGED_STARTUP_END}"
  copy_tmp_file_to_container "${tmp_file}" "${startup_xml}"
}

restart_exist() {
  if [[ -n "${EXIST_STAGE_RESTART_CMD:-}" ]]; then
    run_optional_hook "[remote] eXist restart" "${EXIST_STAGE_RESTART_CMD}" true
    return $?
  fi

  echo "[remote] Restarting container ${EXISTDB_CONTAINER_NAME}"
  docker restart "${EXISTDB_CONTAINER_NAME}" >/dev/null
}

verify_static_state() {
  local conf_xml startup_xml plugin_lib_dir container_jar_path tmp_dir conf_tmp startup_tmp

  require_container_running
  conf_xml=$(resolve_remote_conf_xml)
  startup_xml=$(resolve_remote_startup_xml)
  plugin_lib_dir=$(resolve_remote_plugin_lib_dir)
  container_jar_path="${plugin_lib_dir}/${PLUGIN_JAR_FILENAME}"

  if ! container_sh "[ -f $(printf '%q' "${container_jar_path}") ]"; then
    echo "Plugin JAR is missing from ${container_jar_path}" >&2
    return 1
  fi

  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  conf_tmp="${tmp_dir}/conf.xml"
  startup_tmp="${tmp_dir}/startup.xml"

  copy_container_file_to_tmp "${conf_xml}" "${conf_tmp}"
  copy_container_file_to_tmp "${startup_xml}" "${startup_tmp}"

  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    verify-conf \
    "${conf_tmp}" \
    --application-id "${ALGOLIA_APPLICATION_ID}" \
    --admin-api-key "${ALGOLIA_ADMIN_API_KEY}"

  python3 "${ROOT_DIR}/scripts/manage-exist-config.py" \
    verify-startup \
    "${startup_tmp}" \
    "${PLUGIN_ARTIFACT_ID}" \
    "${PROJECT_VERSION}" \
    "${PLUGIN_JAR_FILENAME}"
}

run_smoke_test() {
  local tmp_dir smoke_file smoke_conf marker_file container_smoke_doc container_smoke_conf output data_dir found_file

  require_container_running
  tmp_dir=$(mktemp -d)
  trap 'rm -rf "'"${tmp_dir}"'"' RETURN
  smoke_file="${tmp_dir}/${SMOKE_DOC_FILENAME}"
  smoke_conf="${tmp_dir}/${SMOKE_CONFIG_FILENAME}"
  marker_file="${tmp_dir}/marker"
  touch "${marker_file}"

  smoke_document_xml >"${smoke_file}"
  smoke_collection_config >"${smoke_conf}"

  container_smoke_doc="/tmp/${SMOKE_DOC_FILENAME}"
  container_smoke_conf="/tmp/${SMOKE_CONFIG_FILENAME}"

  docker cp "${smoke_file}" "${EXISTDB_CONTAINER_NAME}:${container_smoke_doc}"
  docker cp "${smoke_conf}" "${EXISTDB_CONTAINER_NAME}:${container_smoke_conf}"

  client_query_admin "$(ensure_collection_query "${SMOKE_COLLECTION_PATH}")" >/dev/null
  client_query_admin "$(ensure_collection_query "${SMOKE_CONFIG_COLLECTION_PATH}")" >/dev/null
  client_collection_cmd -c "${SMOKE_COLLECTION_PATH}" -p "${container_smoke_doc}" >/dev/null
  client_collection_cmd -c "${SMOKE_CONFIG_COLLECTION_PATH}" -p "${container_smoke_conf}" >/dev/null

  output=$(client_query_admin "$(reindex_collection_query "${SMOKE_COLLECTION_PATH}")")
  echo "${output}"
  if ! grep -q "true" <<<"${output}"; then
    echo "Smoke reindex failed for ${SMOKE_COLLECTION_PATH}" >&2
    return 1
  fi

  sleep 2
  data_dir=$(resolve_remote_data_dir)
  found_file=$(docker exec "${EXISTDB_CONTAINER_NAME}" find "${data_dir}/algolia-index/indexes/${SMOKE_INDEX_NAME}" -type f -name '*.json' -newer "${container_smoke_doc}" 2>/dev/null | head -n 1)
  if [[ -z "${found_file}" ]]; then
    echo "No new local Algolia store file was created under ${data_dir}/algolia-index/indexes/${SMOKE_INDEX_NAME}" >&2
    return 1
  fi

  echo "[remote] Smoke reindex wrote ${found_file}"
  delete_algolia_index "${SMOKE_INDEX_NAME}"
}

verify_install() {
  verify_static_state
  run_smoke_test
}

run_all() {
  install_plugin
  configure_plugin
  configure_startup
  restart_exist
  verify_install
}

main() {
  local command=${1:-help}

  case "${command}" in
    install-plugin)
      require_prereqs
      install_plugin
      ;;
    configure-plugin)
      require_prereqs
      require_secrets
      configure_plugin
      ;;
    configure-startup)
      require_prereqs
      configure_startup
      ;;
    restart)
      require_prereqs
      restart_exist
      ;;
    verify)
      require_prereqs
      require_secrets
      verify_install
      ;;
    run)
      require_prereqs
      require_secrets
      run_all
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

main "${1:-help}"
