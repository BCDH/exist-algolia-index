#!/usr/bin/env bash

if [[ -n "${EXIST_ALGOLIA_COMMON_SH_LOADED:-}" ]]; then
  return 0
fi
readonly EXIST_ALGOLIA_COMMON_SH_LOADED=1

COMMON_SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "${COMMON_SCRIPT_DIR}/.." && pwd)
ENV_FILE="${ROOT_DIR}/.env"

if [[ -f "${ENV_FILE}" ]]; then
  EXIST_COMMON_OVERRIDE_VARS=""
  while IFS= read -r env_name; do
    if [[ -n "${!env_name+x}" ]]; then
      export "EXIST_COMMON_SAVED_${env_name}=${!env_name}"
      EXIST_COMMON_OVERRIDE_VARS="${EXIST_COMMON_OVERRIDE_VARS} ${env_name}"
    fi
  done < <(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "${ENV_FILE}")

  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a

  for env_name in ${EXIST_COMMON_OVERRIDE_VARS}; do
    eval "export ${env_name}=\${EXIST_COMMON_SAVED_${env_name}}"
    unset "EXIST_COMMON_SAVED_${env_name}"
  done

  unset EXIST_COMMON_OVERRIDE_VARS
fi

PLUGIN_GROUP_ID="org.humanistika.exist.index.algolia"
PLUGIN_ARTIFACT_ID="exist-algolia-index"
PLUGIN_ID="algolia-index"
PLUGIN_CLASS="org.humanistika.exist.index.algolia.AlgoliaIndex"
MANAGED_CONF_BEGIN="<!-- BEGIN exist-algolia-index managed block -->"
MANAGED_CONF_END="<!-- END exist-algolia-index managed block -->"
MANAGED_STARTUP_BEGIN="<!-- BEGIN exist-algolia-index startup dependency -->"
MANAGED_STARTUP_END="<!-- END exist-algolia-index startup dependency -->"
SMOKE_COLLECTION_NAME="exist-algolia-smoke"
SMOKE_COLLECTION_PATH="/db/${SMOKE_COLLECTION_NAME}"
SMOKE_CONFIG_COLLECTION_PATH="/db/system/config/db/${SMOKE_COLLECTION_NAME}"
SMOKE_DOC_FILENAME="smoke.xml"
SMOKE_CONFIG_FILENAME="collection.xconf"
SMOKE_INDEX_NAME="${ALGOLIA_SMOKE_INDEX_NAME:-exist-algolia-index-smoke}"
ALGOLIA_SYNC_INDEX_NAME="${ALGOLIA_SYNC_INDEX_NAME:-ras}"
ALGOLIA_SYNC_VERIFY_TIMEOUT_SECONDS="${ALGOLIA_SYNC_VERIFY_TIMEOUT_SECONDS:-300}"
ALGOLIA_SYNC_VERIFY_INTERVAL_SECONDS="${ALGOLIA_SYNC_VERIFY_INTERVAL_SECONDS:-5}"

PROJECT_VERSION=$(
  sed -n 's/^ThisBuild \/ version := "\(.*\)"/\1/p' "${ROOT_DIR}/version.sbt" | head -n 1
)
if [[ -z "${PROJECT_VERSION}" ]]; then
  echo "Could not determine project version from ${ROOT_DIR}/version.sbt" >&2
  exit 1
fi

EXIST_VERSION=$(
  sed -n 's/^[[:space:]]*val existV = "\(.*\)"/\1/p' "${ROOT_DIR}/build.sbt" | head -n 1
)
if [[ -z "${EXIST_VERSION}" ]]; then
  echo "Could not determine eXist version from ${ROOT_DIR}/build.sbt" >&2
  exit 1
fi

SCALA_BINARY_VERSION=$(
  sed -n 's/^[[:space:]]*scalaVersion := "\(.*\)"/\1/p' "${ROOT_DIR}/build.sbt" | head -n 1 | sed -E 's/^([0-9]+\.[0-9]+).*/\1/'
)
if [[ -z "${SCALA_BINARY_VERSION}" ]]; then
  echo "Could not determine Scala binary version from ${ROOT_DIR}/build.sbt" >&2
  exit 1
fi

PLUGIN_JAR_FILENAME="${PLUGIN_ARTIFACT_ID}-assembly-${PROJECT_VERSION}.jar"
PLUGIN_JAR_PATH="${ROOT_DIR}/target/scala-${SCALA_BINARY_VERSION}/${PLUGIN_JAR_FILENAME}"

readonly COMMON_SCRIPT_DIR ROOT_DIR ENV_FILE
readonly PLUGIN_GROUP_ID PLUGIN_ARTIFACT_ID PLUGIN_ID PLUGIN_CLASS
readonly MANAGED_CONF_BEGIN MANAGED_CONF_END MANAGED_STARTUP_BEGIN MANAGED_STARTUP_END
readonly SMOKE_COLLECTION_NAME SMOKE_COLLECTION_PATH SMOKE_CONFIG_COLLECTION_PATH
readonly SMOKE_DOC_FILENAME SMOKE_CONFIG_FILENAME
readonly ALGOLIA_SYNC_INDEX_NAME ALGOLIA_SYNC_VERIFY_TIMEOUT_SECONDS ALGOLIA_SYNC_VERIFY_INTERVAL_SECONDS
readonly PROJECT_VERSION EXIST_VERSION SCALA_BINARY_VERSION
readonly PLUGIN_JAR_FILENAME PLUGIN_JAR_PATH

require_cmd() {
  local cmd=$1
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Missing required command: ${cmd}" >&2
    exit 1
  fi
}

ensure_dir() {
  mkdir -p "$1"
}

require_file() {
  local path=$1
  if [[ ! -f "${path}" ]]; then
    echo "Missing required file: ${path}" >&2
    exit 1
  fi
}

require_dir() {
  local path=$1
  if [[ ! -d "${path}" ]]; then
    echo "Missing required directory: ${path}" >&2
    exit 1
  fi
}

run_optional_hook() {
  local label=$1
  local command=${2:-}
  local fail_on_error=${3:-true}

  if [[ -z "${command}" ]]; then
    echo "${label} not configured; skipping."
    return 2
  fi

  echo "${label}: ${command}"
  if bash -lc "${command}"; then
    echo "${label} completed."
    return 0
  fi

  echo "${label} failed." >&2
  if [[ "${fail_on_error}" == "true" ]]; then
    return 1
  fi

  return 0
}

build_plugin() {
  require_cmd sbt
  (cd "${ROOT_DIR}" && sbt -batch assembly)
}

require_plugin_artifact() {
  require_file "${PLUGIN_JAR_PATH}"
}

require_algolia_credentials() {
  if [[ -z "${ALGOLIA_APPLICATION_ID:-}" ]]; then
    echo "ALGOLIA_APPLICATION_ID must be set." >&2
    exit 1
  fi
  if [[ -z "${ALGOLIA_ADMIN_API_KEY:-}" ]]; then
    echo "ALGOLIA_ADMIN_API_KEY must be set." >&2
    exit 1
  fi
}

xquery_string_literal() {
  python3 - "$1" <<'PY'
import sys
value = sys.argv[1].replace("'", "''")
print("'" + value + "'")
PY
}

collection_sync_slug() {
  printf '%s' "${1#/}" | sed 's#[^A-Za-z0-9._-]#_#g'
}

collection_sync_quarantine_name() {
  local collection_path=$1
  local timestamp=$2
  printf '%s__%s' "${timestamp}" "$(collection_sync_slug "${collection_path}")"
}

algolia_collection_sync_report_json() {
  local indexes_root=$1
  local index_name=$2
  local collection_path=$3

  require_cmd python3
  require_algolia_credentials

  python3 "${ROOT_DIR}/scripts/algolia_collection_sync.py" \
    report \
    --indexes-root "${indexes_root}" \
    --index "${index_name}" \
    --collection-path "${collection_path}" \
    --app-id "${ALGOLIA_APPLICATION_ID}" \
    --api-key "${ALGOLIA_ADMIN_API_KEY}"
}

algolia_collection_sync_json_field() {
  local report_json=$1
  local field_name=$2

  REPORT_JSON="${report_json}" FIELD_NAME="${field_name}" python3 - <<'PY'
import json
import os

report = json.loads(os.environ["REPORT_JSON"])
value = report[os.environ["FIELD_NAME"]]
if isinstance(value, bool):
    print("true" if value else "false")
elif isinstance(value, list):
    for item in value:
        print(item)
elif value is None:
    print("")
else:
    print(value)
PY
}

collection_sync_document_dirs_from_report() {
  algolia_collection_sync_json_field "$1" matchingDocumentDirs
}

summarize_xquery_file_module_error() {
  local query_output_file=$1
  local summary

  summary=$(grep -m1 "http://expath.org/ns/file" "${query_output_file}" || true)
  if [[ -z "${summary}" ]]; then
    summary=$(grep -m1 -E "module namespace file|XPST|XQST|expath" "${query_output_file}" || true)
  fi
  if [[ -z "${summary}" ]]; then
    summary=$(awk 'NF { print; exit }' "${query_output_file}")
  fi
  printf '%s' "${summary}"
}

print_algolia_collection_sync_report() {
  local report_json=$1
  local reconcile_hint=${2:-}

  REPORT_JSON="${report_json}" RECONCILE_HINT="${reconcile_hint}" python3 - <<'PY'
import json
import os

report = json.loads(os.environ["REPORT_JSON"])
status = "OK" if report["synced"] else "MISMATCH"
print(
    f"Algolia collection sync {status}: "
    f"index={report['index']} collection={report['collectionPath']} "
    f"local={report['localCount']} live={report['liveCount']} "
    f"missing-in-live={report['missingInLiveCount']} "
    f"unexpected-in-live={report['unexpectedInLiveCount']}"
)
if report["sampleMissingInLive"]:
    print("Sample missing-in-live: " + ", ".join(report["sampleMissingInLive"]))
if report["sampleUnexpectedInLive"]:
    print("Sample unexpected-in-live: " + ", ".join(report["sampleUnexpectedInLive"]))
if not report["synced"] and os.environ.get("RECONCILE_HINT"):
    print("Recommended reconcile command: " + os.environ["RECONCILE_HINT"] + " " + report["collectionPath"])
PY
}

run_collection_sync_verification() {
  local collection_path=$1
  local report_callback=$2
  local reconcile_hint=${3:-}
  local report_json

  report_json=$("${report_callback}" "${collection_path}")
  print_algolia_collection_sync_report "${report_json}" "${reconcile_hint}"
  [[ "$(algolia_collection_sync_json_field "${report_json}" synced)" == "true" ]]
}

run_collection_sync_reconcile_flow() {
  local collection_path=$1
  local report_callback=$2
  local quarantine_callback=$3
  local reindex_callback=$4
  local reconcile_hint=$5
  local force=${6:-0}
  local timeout_seconds=${7:-${ALGOLIA_SYNC_VERIFY_TIMEOUT_SECONDS}}
  local interval_seconds=${8:-${ALGOLIA_SYNC_VERIFY_INTERVAL_SECONDS}}

  local report_json synced started_at elapsed

  report_json=$("${report_callback}" "${collection_path}")
  print_algolia_collection_sync_report "${report_json}" "${reconcile_hint}"
  synced=$(algolia_collection_sync_json_field "${report_json}" synced)
  if [[ "${synced}" == "true" && "${force}" != "1" ]]; then
    echo "Collection already synced; skipping reconcile."
    return 0
  fi

  "${quarantine_callback}" "${collection_path}" "${report_json}"
  "${reindex_callback}" "${collection_path}"

  started_at=$(date +%s)
  while true; do
    report_json=$("${report_callback}" "${collection_path}")
    print_algolia_collection_sync_report "${report_json}" "${reconcile_hint}"
    synced=$(algolia_collection_sync_json_field "${report_json}" synced)
    if [[ "${synced}" == "true" ]]; then
      echo "Collection reconcile completed for ${collection_path}"
      return 0
    fi

    elapsed=$(( $(date +%s) - started_at ))
    if (( elapsed >= timeout_seconds )); then
      echo "Timed out waiting for Algolia collection sync to converge for ${collection_path} after ${elapsed}s." >&2
      return 1
    fi

    sleep "${interval_seconds}"
  done
}

quarantine_local_store_dirs_on_host() {
  local host_data_dir=$1
  local collection_path=$2
  local report_json=$3

  local index_name timestamp quarantine_name quarantine_dir index_dir doc_literals="" xquery
  local doc_dir
  local -a doc_dirs=()

  index_name=$(algolia_collection_sync_json_field "${report_json}" index)
  timestamp=$(date -u +%Y%m%dT%H%M%SZ)
  quarantine_name=$(collection_sync_quarantine_name "${collection_path}" "${timestamp}")
  quarantine_dir="${data_dir}/algolia-index/quarantine/${index_name}/${quarantine_name}"
  index_dir="${data_dir}/algolia-index/indexes/${index_name}"

  while IFS= read -r doc_dir; do
    [[ -z "${doc_dir}" ]] && continue
    doc_dirs+=("${doc_dir}")
    if [[ -n "${doc_literals}" ]]; then
      doc_literals+=", "
    fi
    doc_literals+="$(xquery_string_literal "${doc_dir}")"
  done < <(collection_sync_document_dirs_from_report "${report_json}")

  echo "Quarantine metadata: collection=${collection_path} index=${index_name} timestamp=${timestamp}"
  echo "Quarantine backup dir: ${quarantine_dir}"
  if [[ "${#doc_dirs[@]}" -eq 0 ]]; then
    echo "Quarantined document dirs: none"
    return 0
  fi
  echo "Quarantined document dirs: ${doc_dirs[*]}"

  set +e
  HOST_DATA_DIR="${host_data_dir}" \
  INDEX_NAME="${index_name}" \
  QUARANTINE_NAME="${quarantine_name}" \
  DOC_DIRS="$(printf '%s\n' "${doc_dirs[@]}")" \
  python3 - <<'PY'
import os
import pathlib
import shutil

host_data_dir = pathlib.Path(os.environ["HOST_DATA_DIR"])
index_name = os.environ["INDEX_NAME"]
quarantine_name = os.environ["QUARANTINE_NAME"]
doc_dirs = [line for line in os.environ["DOC_DIRS"].splitlines() if line]

index_dir = host_data_dir / "algolia-index" / "indexes" / index_name
quarantine_dir = host_data_dir / "algolia-index" / "quarantine" / index_name / quarantine_name
quarantine_dir.mkdir(parents=True, exist_ok=True)

for doc_dir in doc_dirs:
    source = index_dir / doc_dir
    target = quarantine_dir / doc_dir
    if source.exists():
        shutil.move(str(source), str(target))
        print(f"<dir name=\"{doc_dir}\" moved=\"true\" source=\"{source}\" target=\"{target}\"/>")
    else:
        print(f"<dir name=\"{doc_dir}\" moved=\"false\" reason=\"missing-source\" source=\"{source}\" target=\"{target}\"/>")
PY
  local python_status=$?
  set -e
  if [[ "${python_status}" -eq 0 ]]; then
    return 0
  fi

  if [[ -n "${LOCAL_STORE_DOCKER_HELPER_IMAGE:-}" ]]; then
    require_cmd docker
    HOST_DATA_DIR="${host_data_dir}" \
    INDEX_NAME="${index_name}" \
    QUARANTINE_NAME="${quarantine_name}" \
    DOC_DIRS="$(printf '%s\n' "${doc_dirs[@]}")" \
    docker run --rm \
      -v "${host_data_dir}:/algolia-data" \
      -e INDEX_NAME \
      -e QUARANTINE_NAME \
      -e DOC_DIRS \
      "${LOCAL_STORE_DOCKER_HELPER_IMAGE}" \
      sh -eu -c '
quarantine_dir="/algolia-data/algolia-index/quarantine/${INDEX_NAME}/${QUARANTINE_NAME}"
index_dir="/algolia-data/algolia-index/indexes/${INDEX_NAME}"
mkdir -p "${quarantine_dir}"
printf "%s\n" "${DOC_DIRS}" | while IFS= read -r doc_dir; do
  [ -z "${doc_dir}" ] && continue
  source="${index_dir}/${doc_dir}"
  target="${quarantine_dir}/${doc_dir}"
  if [ -e "${source}" ]; then
    mv "${source}" "${target}"
    printf "<dir name=\"%s\" moved=\"true\" source=\"%s\" target=\"%s\"/>\n" "${doc_dir}" "${source}" "${target}"
  else
    printf "<dir name=\"%s\" moved=\"false\" reason=\"missing-source\" source=\"%s\" target=\"%s\"/>\n" "${doc_dir}" "${source}" "${target}"
  fi
done
'
    return 0
  fi

  return "${python_status}"
}

quarantine_local_store_dirs_via_xquery() {
  local query_runner=$1
  local xquery_data_dir=$2
  local host_data_dir=$3
  local collection_path=$4
  local report_json=$5

  local index_name timestamp quarantine_name quarantine_dir index_dir doc_literals="" xquery
  local query_output_file
  local query_status=0
  local doc_dir
  local -a doc_dirs=()

  index_name=$(algolia_collection_sync_json_field "${report_json}" index)
  timestamp=$(date -u +%Y%m%dT%H%M%SZ)
  quarantine_name=$(collection_sync_quarantine_name "${collection_path}" "${timestamp}")
  quarantine_dir="${xquery_data_dir}/algolia-index/quarantine/${index_name}/${quarantine_name}"
  index_dir="${xquery_data_dir}/algolia-index/indexes/${index_name}"

  while IFS= read -r doc_dir; do
    [[ -z "${doc_dir}" ]] && continue
    doc_dirs+=("${doc_dir}")
    if [[ -n "${doc_literals}" ]]; then
      doc_literals+=", "
    fi
    doc_literals+="$(xquery_string_literal "${doc_dir}")"
  done < <(collection_sync_document_dirs_from_report "${report_json}")

  echo "Quarantine metadata: collection=${collection_path} index=${index_name} timestamp=${timestamp}"
  echo "Quarantine backup dir: ${quarantine_dir}"
  if [[ "${#doc_dirs[@]}" -eq 0 ]]; then
    echo "Quarantined document dirs: none"
    return 0
  fi
  echo "Quarantined document dirs: ${doc_dirs[*]}"

  xquery=$(cat <<EOF
xquery version "3.1";
import module namespace file="http://expath.org/ns/file";
let \$index-dir := $(xquery_string_literal "${index_dir}")
let \$quarantine-dir := $(xquery_string_literal "${quarantine_dir}")
let \$doc-dirs := (${doc_literals})
let \$create := file:create-dir(\$quarantine-dir)
return
<quarantine collection="${collection_path}" index="${index_name}" timestamp="${timestamp}">
{
  for \$doc-dir in \$doc-dirs
  let \$source := file:resolve-path(concat(\$index-dir, "/", \$doc-dir))
  let \$target := file:resolve-path(concat(\$quarantine-dir, "/", \$doc-dir))
  return
    if (file:exists(\$source)) then (
      file:move(\$source, \$target),
      <dir name="{\$doc-dir}" moved="true" source="{\$source}" target="{\$target}"/>
    ) else
      <dir name="{\$doc-dir}" moved="false" reason="missing-source" source="{\$source}" target="{\$target}"/>
}
</quarantine>
EOF
)

  query_output_file=$(mktemp)
  set +e
  "${query_runner}" "${xquery}" >"${query_output_file}" 2>&1
  query_status=$?
  set -e
  if [[ "${query_status}" -eq 0 ]]; then
    cat "${query_output_file}"
    rm -f "${query_output_file}"
    return 0
  fi

  if grep -q "http://expath.org/ns/file" "${query_output_file}" && [[ -n "${host_data_dir}" ]]; then
    local summary
    summary=$(summarize_xquery_file_module_error "${query_output_file}")
    echo "File-capable XQuery module unavailable; falling back to host-mounted local-store quarantine." >&2
    if [[ -n "${summary}" ]]; then
      echo "XQuery fallback reason: ${summary}" >&2
    fi
    rm -f "${query_output_file}"
    quarantine_local_store_dirs_on_host "${host_data_dir}" "${collection_path}" "${report_json}"
    return 0
  fi

  cat "${query_output_file}" >&2
  rm -f "${query_output_file}"
  return "${query_status}"
}

smoke_collection_config() {
  cat <<EOF
<collection xmlns="http://exist-db.org/collection-config/1.0">
  <index xmlns:tei="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
    <algolia>
      <namespaceMappings>
        <namespaceMapping>
          <prefix>tei</prefix>
          <namespace>http://www.tei-c.org/ns/1.0</namespace>
        </namespaceMapping>
        <namespaceMapping>
          <prefix>xml</prefix>
          <namespace>http://www.w3.org/XML/1998/namespace</namespace>
        </namespaceMapping>
      </namespaceMappings>
      <index name="${SMOKE_INDEX_NAME}">
        <rootObject path="/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree">
          <attribute name="lemma" path="/tei:form/tei:orth"/>
          <attribute name="dict" path="@xml:id"/>
          <attribute name="tr" path="/tei:sense/tei:cit/tei:quote"/>
        </rootObject>
      </index>
    </algolia>
  </index>
</collection>
EOF
}

smoke_document_xml() {
  cat <<'EOF'
<TEI xmlns="http://www.tei-c.org/ns/1.0" xml:id="ALGOLIA.SMOKE">
  <teiHeader>
    <fileDesc>
      <titleStmt>
        <title>Smoke Title</title>
      </titleStmt>
      <publicationStmt>
        <p>Smoke Publication</p>
      </publicationStmt>
      <sourceDesc>
        <p>Smoke Source</p>
      </sourceDesc>
    </fileDesc>
  </teiHeader>
  <text>
    <body>
      <div xml:id="ALGOLIA.SMOKE.div">
        <entryFree xml:id="ALGOLIA.SMOKE.entry">
          <form type="lemma">
            <orth>SmokeLemma</orth>
          </form>
          <sense xml:id="ALGOLIA.SMOKE.sense">
            <cit type="tr">
              <quote>Smoke Translation</quote>
            </cit>
          </sense>
        </entryFree>
      </div>
    </body>
  </text>
</TEI>
EOF
}

ensure_collection_query() {
  local collection_path=$1
  cat <<EOF
xquery version "3.1";
import module namespace xmldb="http://exist-db.org/xquery/xmldb";
declare function local:ensure(\$path as xs:string) as item()* {
  if (\$path = "" or \$path = "/db") then
    ()
  else
    let \$parent := replace(\$path, "/[^/]+$", "")
    let \$name := replace(\$path, "^.*/", "")
    return (
      local:ensure(\$parent),
      if (xmldb:collection-available(\$path)) then
        ()
      else
        xmldb:create-collection(\$parent, \$name)
    )
};
local:ensure("${collection_path}")
EOF
}

reindex_collection_query() {
  local collection_path=$1
  cat <<EOF
xquery version "3.1";
import module namespace xmldb="http://exist-db.org/xquery/xmldb";
xmldb:reindex("${collection_path}")
EOF
}

cleanup_smoke_resources_query() {
  cat <<EOF
xquery version "3.1";
import module namespace xmldb="http://exist-db.org/xquery/xmldb";
let \$targets := (
  map {
    "collection": "${SMOKE_COLLECTION_PATH}",
    "resources": ("${SMOKE_DOC_FILENAME}")
  },
  map {
    "collection": "${SMOKE_CONFIG_COLLECTION_PATH}",
    "resources": ("${SMOKE_CONFIG_FILENAME}")
  }
)
return
  for \$target in \$targets
  let \$collection := \$target?collection
  where xmldb:collection-available(\$collection)
  return
    for \$resource in \$target?resources
    where some \$name in xmldb:get-child-resources(\$collection) satisfies \$name = \$resource
    return xmldb:remove(\$collection, \$resource)
EOF
}

delete_algolia_index() {
  local index_name=$1

  if [[ -z "${ALGOLIA_APPLICATION_ID:-}" || -z "${ALGOLIA_ADMIN_API_KEY:-}" ]]; then
    return 0
  fi

  if ! command -v curl >/dev/null 2>&1; then
    echo "curl not available; skipping Algolia smoke index cleanup for ${index_name}."
    return 0
  fi

  local encoded_index
  encoded_index=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "${index_name}")

  if curl -fsS -X DELETE \
    -H "X-Algolia-API-Key: ${ALGOLIA_ADMIN_API_KEY}" \
    -H "X-Algolia-Application-Id: ${ALGOLIA_APPLICATION_ID}" \
    "https://${ALGOLIA_APPLICATION_ID}.algolia.net/1/indexes/${encoded_index}" >/dev/null 2>&1; then
    return 0
  fi

  if curl -fsS -X DELETE \
    -H "X-Algolia-API-Key: ${ALGOLIA_ADMIN_API_KEY}" \
    -H "X-Algolia-Application-Id: ${ALGOLIA_APPLICATION_ID}" \
    "https://${ALGOLIA_APPLICATION_ID}-dsn.algolia.net/1/indexes/${encoded_index}" >/dev/null 2>&1; then
    return 0
  fi

  echo "Warning: failed to delete Algolia smoke index ${index_name}; clean it up manually if needed." >&2
  return 1
}

algolia_query_index() {
  local index_name=$1
  local payload=${2:-'{"query":"","hitsPerPage":10}'}
  local encoded_index
  encoded_index=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "${index_name}")

  curl -fsS \
    -H "X-Algolia-API-Key: ${ALGOLIA_ADMIN_API_KEY}" \
    -H "X-Algolia-Application-Id: ${ALGOLIA_APPLICATION_ID}" \
    -H "Content-Type: application/json" \
    -X POST \
    --data "${payload}" \
    "https://${ALGOLIA_APPLICATION_ID}-dsn.algolia.net/1/indexes/${encoded_index}/query" 2>/dev/null || \
  curl -fsS \
    -H "X-Algolia-API-Key: ${ALGOLIA_ADMIN_API_KEY}" \
    -H "X-Algolia-Application-Id: ${ALGOLIA_APPLICATION_ID}" \
    -H "Content-Type: application/json" \
    -X POST \
    --data "${payload}" \
    "https://${ALGOLIA_APPLICATION_ID}.algolia.net/1/indexes/${encoded_index}/query"
}

wait_for_algolia_smoke_upload() {
  local index_name=$1
  local timeout_seconds=${2:-60}
  local started_at response summary

  require_algolia_credentials
  require_cmd curl
  require_cmd python3

  started_at=$(date +%s)
  while :; do
    response=$(algolia_query_index "${index_name}" '{"query":"","hitsPerPage":10}' 2>/dev/null || true)
    if [[ -n "${response}" ]]; then
      summary=$(RESPONSE="${response}" python3 <<'PY'
import json
import os

payload = os.environ["RESPONSE"]
data = json.loads(payload)
hits = data.get("hits") or []
if not hits:
    raise SystemExit(1)
hit = hits[0]
parts = [
    f'hits={data.get("nbHits", len(hits))}',
    f'objectID={hit.get("objectID", "")}',
]
for key in ("lemma", "dict", "tr"):
    value = hit.get(key)
    if value:
        parts.append(f"{key}={value}")
print(" ".join(parts))
PY
)
      if [[ -n "${summary}" ]]; then
        echo "${summary}"
        return 0
      fi
    fi

    if (( $(date +%s) - started_at >= timeout_seconds )); then
      echo "Timed out waiting for Algolia smoke index ${index_name} to become searchable." >&2
      return 1
    fi

    sleep 2
  done
}

wait_for_algolia_index_deletion() {
  local index_name=$1
  local timeout_seconds=${2:-60}
  local started_at response

  require_algolia_credentials
  require_cmd curl
  require_cmd python3

  started_at=$(date +%s)
  while :; do
    response=$(algolia_query_index "${index_name}" '{"query":"","hitsPerPage":1}' 2>/dev/null || true)
    if [[ -z "${response}" ]]; then
      return 0
    fi

    if (( $(date +%s) - started_at >= timeout_seconds )); then
      echo "Timed out waiting for Algolia smoke index ${index_name} to be deleted." >&2
      return 1
    fi

    sleep 2
  done
}

verify_indexing_status_file() {
  local status_file=$1
  local label=${2:-indexing status}

  require_cmd python3

  if [[ ! -f "${status_file}" ]]; then
    echo "${label}: no status file found at ${status_file}; treating as OK."
    return 0
  fi

  STATUS_FILE="${status_file}" STATUS_LABEL="${label}" python3 <<'PY'
import json
import os
import sys

status_file = os.environ["STATUS_FILE"]
label = os.environ["STATUS_LABEL"]
blocking_states = {"degraded", "stale_local_store"}

try:
    with open(status_file, "r", encoding="utf-8") as handle:
        payload = json.load(handle)
except Exception as exc:
    print(f"{label}: could not parse {status_file}: {exc}", file=sys.stderr)
    sys.exit(1)

records = payload.get("records") or []
blocking = [
    record for record in records
    if str(record.get("state", "")).strip() in blocking_states
]

if not blocking:
    print(f"{label}: OK ({len(records)} record(s) checked).")
    sys.exit(0)

print(f"{label}: blocking indexing status in {status_file}", file=sys.stderr)
for record in blocking:
    parts = [
        f"index={record.get('index', '')}",
        f"collection={record.get('collection', '*')}",
        f"operation={record.get('operation', '')}",
        f"state={record.get('state', '')}",
        f"timestamp={record.get('timestamp', '')}",
    ]
    failure_message = record.get("failureMessage")
    if failure_message:
        parts.append(f"failureMessage={failure_message}")
    print("  " + " ".join(parts), file=sys.stderr)

sys.exit(1)
PY
}

find_new_local_store_file() {
  local data_dir=$1
  local marker_file=$2
  find "${data_dir}/algolia-index/indexes/${SMOKE_INDEX_NAME}" -type f -name '*.json' -newer "${marker_file}" 2>/dev/null | head -n 1
}

exist_home_roots() {
  local home=${EXIST_HOME}

  printf '%s\n' "${home}"

  if [[ "${home}" == *.app ]] && [[ -d "${home}/Contents/Resources" ]]; then
    printf '%s\n' "${home}/Contents/Resources"
  fi

  if [[ "${home}" == */Contents/Resources/eXist-db ]] && [[ -d "${home%/eXist-db}" ]]; then
    printf '%s\n' "${home%/eXist-db}"
  fi
}

first_matching_root() {
  local relative_path=$1
  local root

  while IFS= read -r root; do
    if [[ -e "${root}/${relative_path}" ]]; then
      printf '%s' "${root}"
      return 0
    fi
  done < <(exist_home_roots)

  return 1
}

require_exist_home() {
  local root

  if [[ -z "${EXIST_HOME:-}" ]]; then
    echo "EXIST_HOME must be set to your eXist-db installation directory." >&2
    exit 1
  fi

  while IFS= read -r root; do
    if [[ -d "${root}" ]]; then
      return 0
    fi
  done < <(exist_home_roots)

  echo "EXIST_HOME does not exist or is not a directory: ${EXIST_HOME}" >&2
  exit 1
}

resolve_local_conf_xml() {
  local root

  if [[ -n "${EXIST_CONF_XML:-}" ]]; then
    require_file "${EXIST_CONF_XML}"
    printf '%s' "${EXIST_CONF_XML}"
    return 0
  fi

  require_exist_home
  if root=$(first_matching_root "etc/conf.xml"); then
    printf '%s' "${root}/etc/conf.xml"
    return 0
  fi
  if root=$(first_matching_root "conf.xml"); then
    printf '%s' "${root}/conf.xml"
    return 0
  fi

  echo "Could not locate conf.xml under ${EXIST_HOME}. Set EXIST_CONF_XML." >&2
  exit 1
}

resolve_local_startup_xml() {
  local root

  if [[ -n "${EXIST_STARTUP_XML:-}" ]]; then
    require_file "${EXIST_STARTUP_XML}"
    printf '%s' "${EXIST_STARTUP_XML}"
    return 0
  fi

  require_exist_home
  if root=$(first_matching_root "etc/startup.xml"); then
    printf '%s' "${root}/etc/startup.xml"
    return 0
  fi
  if root=$(first_matching_root "startup.xml"); then
    printf '%s' "${root}/startup.xml"
    return 0
  fi

  echo "Could not locate startup.xml under ${EXIST_HOME}. Set EXIST_STARTUP_XML." >&2
  exit 1
}

resolve_local_plugin_lib_dir() {
  local root

  if [[ -n "${EXIST_PLUGIN_LIB_DIR:-}" ]]; then
    require_dir "${EXIST_PLUGIN_LIB_DIR}"
    printf '%s' "${EXIST_PLUGIN_LIB_DIR}"
    return 0
  fi

  require_exist_home
  if root=$(first_matching_root "lib"); then
    printf '%s' "${root}/lib"
    return 0
  fi

  echo "Could not locate eXist lib directory under ${EXIST_HOME}. Set EXIST_PLUGIN_LIB_DIR." >&2
  exit 1
}

resolve_local_bin_dir() {
  local root

  require_exist_home
  if root=$(first_matching_root "bin"); then
    printf '%s' "${root}/bin"
    return 0
  fi

  echo "Could not locate the eXist bin directory under ${EXIST_HOME}." >&2
  exit 1
}

resolve_local_startup_script() {
  local bin_dir
  bin_dir=$(resolve_local_bin_dir)
  require_file "${bin_dir}/startup.sh"
  printf '%s' "${bin_dir}/startup.sh"
}

resolve_local_shutdown_script() {
  local bin_dir
  bin_dir=$(resolve_local_bin_dir)
  require_file "${bin_dir}/shutdown.sh"
  printf '%s' "${bin_dir}/shutdown.sh"
}

is_macos_app_bundle_install() {
  local home=${EXIST_HOME:-}

  if [[ "${home}" == */Contents/Resources/eXist-db ]]; then
    home=${home%/eXist-db}
  fi

  if [[ "${home}" == *.app ]] && [[ -d "${home}/Contents/Java" ]]; then
    return 0
  fi

  if [[ "${home}" == */Contents/Resources ]] && [[ -d "${home%/Resources}/Java" ]]; then
    return 0
  fi

  return 1
}

resolve_local_data_dir() {
  local configured_dir root

  EXIST_CONF_XML_RESOLVED=$(resolve_local_conf_xml)
  export EXIST_CONF_XML_RESOLVED

  if configured_dir=$(python3 - <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

conf_xml = os.environ.get("EXIST_CONF_XML_RESOLVED")
if not conf_xml:
    sys.exit(1)

try:
    root = ET.parse(conf_xml).getroot()
except Exception:
    sys.exit(1)

db_connection = root.find(".//db-connection")
if db_connection is None:
    sys.exit(1)

files = db_connection.get("files", "").strip()
if not files:
    sys.exit(1)

print(files)
PY
  ); then
    if [[ -n "${configured_dir}" && -d "${configured_dir}" ]]; then
      printf '%s' "${configured_dir}"
      return 0
    fi
  fi

  require_exist_home
  if root=$(first_matching_root "data"); then
    printf '%s' "${root}/data"
    return 0
  fi
  if root=$(first_matching_root "webapp/WEB-INF/data"); then
    printf '%s' "${root}/webapp/WEB-INF/data"
    return 0
  fi

  echo "Could not locate the eXist data directory under ${EXIST_HOME}." >&2
  exit 1
}

resolve_local_client_cmd() {
  local candidate root

  require_exist_home
  if [[ -n "${EXIST_CLIENT_CMD:-}" ]]; then
    candidate=${EXIST_CLIENT_CMD}
  elif root=$(first_matching_root "bin/client.sh"); then
    candidate="${root}/bin/client.sh"
  elif root=$(first_matching_root "bin/client"); then
    candidate="${root}/bin/client"
  else
    echo "Could not find a usable eXist-db client command under ${EXIST_HOME}. Set EXIST_CLIENT_CMD." >&2
    exit 1
  fi

  if [[ ! -x "${candidate}" ]]; then
    echo "EXIST_CLIENT_CMD is not executable: ${candidate}" >&2
    exit 1
  fi

  printf '%s' "${candidate}"
}

resolve_local_log_dir() {
  local root

  require_exist_home
  if root=$(first_matching_root "logs"); then
    printf '%s' "${root}/logs"
    return 0
  fi
  if root=$(first_matching_root "webapp/WEB-INF/logs"); then
    printf '%s' "${root}/webapp/WEB-INF/logs"
    return 0
  fi

  return 1
}
