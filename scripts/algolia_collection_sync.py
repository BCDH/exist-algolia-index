#!/usr/bin/env python3

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

HIDDEN_FILENAMES = {".DS_Store"}
COLLECTION_HEALTH_VERSION = 1
STATUS_OPERATION = "manual_refresh"
STATUS_STATE_CURRENT = "current"


def collection_path_matches_tree(collection_path: str, candidate_path: str) -> bool:
    return candidate_path == collection_path or candidate_path.startswith(f"{collection_path}/")


def visible_children(path: Path) -> Iterable[Path]:
    for child in path.iterdir():
        name = child.name
        if name in HIDDEN_FILENAMES or name.startswith("."):
            continue
        yield child


def latest_timestamp_dir(document_dir: Path) -> Optional[Path]:
    latest: Optional[Tuple[int, Path]] = None
    if not document_dir.is_dir():
        return None

    for child in visible_children(document_dir):
        if not child.is_dir():
            continue
        try:
            timestamp = int(child.name)
        except ValueError:
            continue
        if latest is None or timestamp > latest[0]:
            latest = (timestamp, child)

    return latest[1] if latest else None


def read_json_file(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def is_visible_json_file(path: Path) -> bool:
    return path.is_file() and path.suffix == ".json" and not path.name.startswith(".")


def local_record_matches_tree(record: Dict[str, Any], collection_path: str) -> bool:
    record_collection = record.get("collection")
    return isinstance(record_collection, str) and collection_path_matches_tree(collection_path, record_collection)


def document_id_for_record(record: Dict[str, Any]) -> Optional[str]:
    value = record.get("documentID")
    return value if isinstance(value, str) and value else None


def object_id_for_record(record: Dict[str, Any]) -> Optional[str]:
    value = record.get("objectID")
    return value if isinstance(value, str) and value else None


def record_collection_path(record: Dict[str, Any]) -> Optional[str]:
    value = record.get("collection")
    return value if isinstance(value, str) and value else None


def collect_local_state(
    indexes_root: Path,
    index_name: str,
    collection_path: str,
) -> Tuple[Dict[str, Dict[str, Any]], List[str], Set[str], Dict[str, int]]:
    index_dir = indexes_root / index_name
    records_by_object_id: Dict[str, Dict[str, Any]] = {}
    matching_document_dirs: List[str] = []
    matching_document_ids: Set[str] = set()
    counts_by_collection: Counter[str] = Counter()

    if not index_dir.is_dir():
        return records_by_object_id, matching_document_dirs, matching_document_ids, {}

    for document_dir in sorted(path for path in visible_children(index_dir) if path.is_dir()):
        timestamp_dir = latest_timestamp_dir(document_dir)
        if timestamp_dir is None:
            continue

        document_matches = False
        for record_path in sorted(path for path in visible_children(timestamp_dir) if is_visible_json_file(path)):
            record = read_json_file(record_path)
            object_id = object_id_for_record(record)
            record_collection = record_collection_path(record)
            if object_id is None or record_collection is None:
                continue
            if not collection_path_matches_tree(collection_path, record_collection):
                continue
            records_by_object_id[object_id] = record
            document_matches = True
            counts_by_collection[record_collection] += 1
            document_id = document_id_for_record(record)
            if document_id:
                matching_document_ids.add(document_id)

        if document_matches:
            matching_document_dirs.append(document_dir.name)

    return records_by_object_id, matching_document_dirs, matching_document_ids, dict(sorted(counts_by_collection.items()))


def algolia_request(
    app_id: str,
    api_key: str,
    method: str,
    path: str,
    payload: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    endpoint = "https://" + f"{app_id}-dsn.algolia.net{path}"
    headers = {
        "accept": "application/json",
        "content-type": "application/json",
        "x-algolia-api-key": api_key,
        "x-algolia-application-id": app_id,
    }

    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def browse_live_records(app_id: str, api_key: str, index_name: str, batch_size: int) -> List[Dict[str, Any]]:
    path = f"/1/indexes/{urllib.parse.quote(index_name, safe='')}/browse"
    records: List[Dict[str, Any]] = []
    cursor: Optional[str] = None

    while True:
        params: Dict[str, Any] = {"hitsPerPage": batch_size}
        if cursor:
            params["cursor"] = cursor
        body = algolia_request(
            app_id,
            api_key,
            "POST",
            path,
            {"params": urllib.parse.urlencode(params)},
        )

        hits = body.get("hits", [])
        if not isinstance(hits, list):
            raise ValueError("Algolia browse response did not contain a hits array.")
        for hit in hits:
            if isinstance(hit, dict):
                records.append(hit)

        next_cursor = body.get("cursor")
        if not isinstance(next_cursor, str) or not next_cursor:
            break
        cursor = next_cursor

    return records


def count_by_collection(records: Iterable[Dict[str, Any]]) -> Dict[str, int]:
    counts: Counter[str] = Counter()
    for record in records:
        record_collection = record_collection_path(record)
        if record_collection:
            counts[record_collection] += 1
    return dict(sorted(counts.items()))


def filter_live_records_for_tree(records: Iterable[Dict[str, Any]], collection_path: str) -> List[Dict[str, Any]]:
    return [
        record
        for record in records
        if isinstance(record_collection_path(record), str)
        and collection_path_matches_tree(collection_path, record_collection_path(record) or "")
    ]


def wrong_path_live_records(
    records: Iterable[Dict[str, Any]],
    collection_path: str,
    document_ids: Set[str],
) -> List[Dict[str, Any]]:
    wrong_path: List[Dict[str, Any]] = []
    for record in records:
        record_collection = record_collection_path(record)
        document_id = document_id_for_record(record)
        if not record_collection or not document_id:
            continue
        if document_id not in document_ids:
            continue
        if collection_path_matches_tree(collection_path, record_collection):
            continue
        wrong_path.append(record)
    return wrong_path


def sample_object_ids(records: Iterable[Dict[str, Any]], limit: int) -> List[str]:
    values: List[str] = []
    for record in records:
        object_id = object_id_for_record(record)
        if object_id:
            values.append(object_id)
    return sorted(values)[:limit]


def status_record_for_collection(
    status_payload: Dict[str, Any],
    index_name: str,
    collection_path: str,
) -> Optional[Dict[str, Any]]:
    collection_health = status_payload.get("collectionHealth")
    if isinstance(collection_health, list):
        for record in collection_health:
            if not isinstance(record, dict):
                continue
            if record.get("index") != index_name:
                continue
            if record.get("collection") != collection_path:
                continue
            return record

    records = status_payload.get("records")
    if isinstance(records, list):
        for record in records:
            if not isinstance(record, dict):
                continue
            if record.get("index") != index_name:
                continue
            if record.get("collection") != collection_path:
                continue
            return record

    return None


def load_status_payload(status_json_path: Optional[Path]) -> Optional[Dict[str, Any]]:
    if status_json_path is None or not status_json_path.is_file():
        return None

    payload = read_json_file(status_json_path)
    if not isinstance(payload, dict):
        raise ValueError("Status JSON must be a JSON object.")
    return payload


def classify_report(
    synced: bool,
    live_count: int,
    local_count: int,
    wrong_path_count: int,
    missing_in_live_count: int,
    unexpected_in_live_count: int,
    status_payload: Optional[Dict[str, Any]],
    index_name: str,
    collection_path: str,
) -> str:
    if synced:
        if status_payload is not None:
            status_record = status_record_for_collection(status_payload, index_name, collection_path)
            if status_record is None or status_record.get("state") != STATUS_STATE_CURRENT:
                return "stale_status"
        return "synced"

    if local_count == 0 and live_count > 0:
        return "local_store_corruption"

    if wrong_path_count > 0:
        return "wrong_path_drift"

    if missing_in_live_count > 0 and unexpected_in_live_count == 0:
        return "true_live_loss"

    return "mismatch"


def compute_report(
    indexes_root: Path,
    index_name: str,
    collection_path: str,
    sample_limit: int,
    batch_size: int,
    app_id: Optional[str],
    api_key: Optional[str],
    live_records_json: Optional[Path],
    status_json_path: Optional[Path],
) -> Dict[str, Any]:
    (
        local_records_by_object_id,
        matching_document_dirs,
        local_document_ids,
        local_count_by_collection,
    ) = collect_local_state(indexes_root, index_name, collection_path)

    if live_records_json is not None:
        fixture = read_json_file(live_records_json)
        if isinstance(fixture, list):
            live_records = [record for record in fixture if isinstance(record, dict)]
        elif isinstance(fixture, dict) and isinstance(fixture.get("records"), list):
            live_records = [record for record in fixture["records"] if isinstance(record, dict)]
        else:
            raise ValueError("Live records fixture must be a JSON array or an object with a records array.")
    else:
        if not app_id or not api_key:
            raise ValueError("Algolia credentials are required when no live-records fixture is provided.")
        live_records = browse_live_records(app_id, api_key, index_name, batch_size)

    local_object_ids = set(local_records_by_object_id.keys())
    live_records_for_tree = filter_live_records_for_tree(live_records, collection_path)
    live_object_ids = {
        object_id
        for object_id in (object_id_for_record(record) for record in live_records_for_tree)
        if object_id is not None
    }
    missing_in_live = sorted(local_object_ids - live_object_ids)
    unexpected_in_live = sorted(live_object_ids - local_object_ids)
    wrong_path_records = wrong_path_live_records(live_records, collection_path, local_document_ids)
    status_payload = load_status_payload(status_json_path)
    synced = not missing_in_live and not unexpected_in_live and not wrong_path_records

    classification = classify_report(
        synced=synced,
        live_count=len(live_object_ids),
        local_count=len(local_object_ids),
        wrong_path_count=len(wrong_path_records),
        missing_in_live_count=len(missing_in_live),
        unexpected_in_live_count=len(unexpected_in_live),
        status_payload=status_payload,
        index_name=index_name,
        collection_path=collection_path,
    )

    live_count_by_collection = count_by_collection(live_records_for_tree)
    wrong_path_count_by_collection = count_by_collection(wrong_path_records)
    live_delete_candidates = {
        object_id
        for object_id in (
            object_id_for_record(record)
            for record in list(live_records_for_tree) + wrong_path_records
        )
        if object_id is not None
    }

    return {
        "index": index_name,
        "collectionPath": collection_path,
        "classification": classification,
        "localCount": len(local_object_ids),
        "liveCount": len(live_object_ids),
        "missingInLiveCount": len(missing_in_live),
        "unexpectedInLiveCount": len(unexpected_in_live),
        "wrongPathLiveCount": len(wrong_path_records),
        "sampleMissingInLive": missing_in_live[:sample_limit],
        "sampleUnexpectedInLive": unexpected_in_live[:sample_limit],
        "sampleWrongPathLive": sample_object_ids(wrong_path_records, sample_limit),
        "localDocumentDirCount": len(matching_document_dirs),
        "localDocumentIdCount": len(local_document_ids),
        "matchingDocumentDirs": list(matching_document_dirs),
        "localCountByCollection": local_count_by_collection,
        "liveCountByCollection": live_count_by_collection,
        "wrongPathLiveCountByCollection": wrong_path_count_by_collection,
        "replayDeleteCount": len(live_delete_candidates),
        "replayUploadCount": len(local_object_ids),
        "synced": synced,
    }


def chunked(items: Sequence[Dict[str, Any]], size: int) -> Iterable[Sequence[Dict[str, Any]]]:
    for index in range(0, len(items), size):
        yield items[index : index + size]


def wait_for_task(app_id: str, api_key: str, index_name: str, task_id: int) -> None:
    path = f"/1/indexes/{urllib.parse.quote(index_name, safe='')}/task/{task_id}"
    started_at = time.time()
    timeout_seconds = 300

    while True:
        body = algolia_request(app_id, api_key, "GET", path)
        if body.get("status") == "published":
            return
        if time.time() - started_at >= timeout_seconds:
            raise TimeoutError(f"Timed out waiting for Algolia task {task_id} on index {index_name}.")
        time.sleep(2)


def replay_collection_live(
    indexes_root: Path,
    index_name: str,
    collection_path: str,
    batch_size: int,
    app_id: str,
    api_key: str,
    sample_limit: int,
) -> Dict[str, Any]:
    (
        local_records_by_object_id,
        matching_document_dirs,
        local_document_ids,
        local_count_by_collection,
    ) = collect_local_state(indexes_root, index_name, collection_path)
    live_records = browse_live_records(app_id, api_key, index_name, batch_size)
    live_records_for_tree = filter_live_records_for_tree(live_records, collection_path)
    wrong_path_records = wrong_path_live_records(live_records, collection_path, local_document_ids)

    delete_object_ids = sorted(
        {
            object_id
            for object_id in (
                object_id_for_record(record)
                for record in list(live_records_for_tree) + wrong_path_records
            )
            if object_id is not None
        }
    )
    upload_records = [local_records_by_object_id[object_id] for object_id in sorted(local_records_by_object_id.keys())]

    requests: List[Dict[str, Any]] = []
    for object_id in delete_object_ids:
        requests.append({"action": "deleteObject", "body": {"objectID": object_id}})
    for record in upload_records:
        requests.append({"action": "addObject", "body": record})

    task_ids: List[int] = []
    path = f"/1/indexes/{urllib.parse.quote(index_name, safe='')}/batch"
    for batch in chunked(requests, 500):
        body = algolia_request(app_id, api_key, "POST", path, {"requests": list(batch)})
        task_id = body.get("taskID")
        if not isinstance(task_id, int):
            raise ValueError("Algolia batch response did not contain an integer taskID.")
        task_ids.append(task_id)

    for task_id in task_ids:
        wait_for_task(app_id, api_key, index_name, task_id)

    post_report = compute_report(
        indexes_root=indexes_root,
        index_name=index_name,
        collection_path=collection_path,
        sample_limit=sample_limit,
        batch_size=batch_size,
        app_id=app_id,
        api_key=api_key,
        live_records_json=None,
        status_json_path=None,
    )

    return {
        "index": index_name,
        "collectionPath": collection_path,
        "deletedObjectCount": len(delete_object_ids),
        "uploadedObjectCount": len(upload_records),
        "localDocumentDirCount": len(matching_document_dirs),
        "localDocumentIdCount": len(local_document_ids),
        "localCountByCollection": local_count_by_collection,
        "taskIds": task_ids,
        "postReplayReport": post_report,
    }


def now_iso8601() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def refresh_status_file(
    indexes_root: Path,
    index_name: str,
    collection_path: str,
    status_json_path: Path,
    batch_size: int,
    app_id: str,
    api_key: str,
    sample_limit: int,
) -> Dict[str, Any]:
    report = compute_report(
        indexes_root=indexes_root,
        index_name=index_name,
        collection_path=collection_path,
        sample_limit=sample_limit,
        batch_size=batch_size,
        app_id=app_id,
        api_key=api_key,
        live_records_json=None,
        status_json_path=status_json_path,
    )

    if report["classification"] not in {"synced", "stale_status"}:
        raise ValueError(
            "Refusing to refresh status.json for an unsynced collection. "
            f"Current classification is {report['classification']}."
        )

    payload = load_status_payload(status_json_path) or {}
    records = payload.get("records")
    if not isinstance(records, list):
        records = []
    collection_health = payload.get("collectionHealth")
    if not isinstance(collection_health, list):
        collection_health = []

    timestamp = now_iso8601()
    object_count = report["localCount"]

    refreshed_record = {
        "index": index_name,
        "collection": collection_path,
        "operation": STATUS_OPERATION,
        "state": STATUS_STATE_CURRENT,
        "timestamp": timestamp,
        "lastSuccessfulOperationTimestamp": timestamp,
        "objectCount": object_count,
    }

    def upsert(rows: List[Any]) -> List[Any]:
        updated = False
        result: List[Any] = []
        for row in rows:
            if not isinstance(row, dict):
                result.append(row)
                continue
            if row.get("index") == index_name and row.get("collection") == collection_path:
                result.append(refreshed_record)
                updated = True
            else:
                result.append(row)
        if not updated:
            result.append(refreshed_record)
        return result

    payload["updatedAt"] = timestamp
    payload["healthContractVersion"] = COLLECTION_HEALTH_VERSION
    payload["records"] = upsert(records)
    payload["collectionHealth"] = upsert(collection_health)

    status_json_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = status_json_path.with_name(status_json_path.name + ".tmp")
    temp_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temp_path, status_json_path)

    return {
        "index": index_name,
        "collectionPath": collection_path,
        "statusJsonPath": str(status_json_path),
        "refreshedState": STATUS_STATE_CURRENT,
        "objectCount": object_count,
        "timestamp": timestamp,
        "report": report,
    }


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Diagnose and repair Algolia live/local divergence for one collection tree."
    )
    parser.add_argument("command", choices=["report", "verify", "inspect", "replay", "refresh-status"])
    parser.add_argument("--indexes-root", required=True, help="Path to algolia-index/indexes")
    parser.add_argument("--index", required=True, help="Algolia index name")
    parser.add_argument("--collection-path", required=True, help="Collection tree to inspect")
    parser.add_argument("--sample-limit", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=1000)
    parser.add_argument("--app-id")
    parser.add_argument("--api-key")
    parser.add_argument("--live-records-json", help="JSON fixture used instead of live Algolia browse")
    parser.add_argument("--status-json-path", help="Path to algolia-index/status.json")
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    indexes_root = Path(args.indexes_root)
    live_records_json = Path(args.live_records_json) if args.live_records_json else None
    status_json_path = Path(args.status_json_path) if args.status_json_path else None

    try:
        if args.command == "replay":
            if not args.app_id or not args.api_key:
                raise ValueError("Algolia credentials are required for replay.")
            result = replay_collection_live(
                indexes_root=indexes_root,
                index_name=args.index,
                collection_path=args.collection_path,
                batch_size=args.batch_size,
                app_id=args.app_id,
                api_key=args.api_key,
                sample_limit=args.sample_limit,
            )
            print(json.dumps(result, indent=2, sort_keys=True))
            return 0 if result["postReplayReport"]["synced"] else 1

        if args.command == "refresh-status":
            if status_json_path is None:
                raise ValueError("--status-json-path is required for refresh-status.")
            if not args.app_id or not args.api_key:
                raise ValueError("Algolia credentials are required for refresh-status.")
            result = refresh_status_file(
                indexes_root=indexes_root,
                index_name=args.index,
                collection_path=args.collection_path,
                status_json_path=status_json_path,
                batch_size=args.batch_size,
                app_id=args.app_id,
                api_key=args.api_key,
                sample_limit=args.sample_limit,
            )
            print(json.dumps(result, indent=2, sort_keys=True))
            return 0

        report = compute_report(
            indexes_root=indexes_root,
            index_name=args.index,
            collection_path=args.collection_path,
            sample_limit=args.sample_limit,
            batch_size=args.batch_size,
            app_id=args.app_id,
            api_key=args.api_key,
            live_records_json=live_records_json,
            status_json_path=status_json_path,
        )
    except (OSError, ValueError, urllib.error.URLError, TimeoutError) as exc:
        print(f"Failed to inspect Algolia collection sync: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(report, indent=2, sort_keys=True))
    if args.command == "verify" and not report["synced"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
