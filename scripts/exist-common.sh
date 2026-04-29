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
