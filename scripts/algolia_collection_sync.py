#!/usr/bin/env python3

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple


def collection_path_matches_tree(collection_path: str, candidate_path: str) -> bool:
    return candidate_path == collection_path or candidate_path.startswith(f"{collection_path}/")


def latest_timestamp_dir(document_dir: Path) -> Optional[Path]:
    latest: Optional[Tuple[int, Path]] = None
    if not document_dir.is_dir():
        return None

    for child in document_dir.iterdir():
        if not child.is_dir():
            continue
        try:
            timestamp = int(child.name)
        except ValueError:
            continue
        if latest is None or timestamp > latest[0]:
            latest = (timestamp, child)

    return latest[1] if latest else None


def read_json_file(path: Path) -> Dict[str, object]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def collect_local_state(indexes_root: Path, index_name: str, collection_path: str) -> Tuple[Set[str], List[str]]:
    index_dir = indexes_root / index_name
    object_ids: Set[str] = set()
    matching_document_dirs: List[str] = []

    if not index_dir.is_dir():
        return object_ids, matching_document_dirs

    for document_dir in sorted(path for path in index_dir.iterdir() if path.is_dir()):
        timestamp_dir = latest_timestamp_dir(document_dir)
        if timestamp_dir is None:
            continue

        document_matches = False
        for record_path in sorted(path for path in timestamp_dir.iterdir() if path.is_file() and path.suffix == ".json"):
            record = read_json_file(record_path)
            record_collection = record.get("collection")
            object_id = record.get("objectID")
            if not isinstance(record_collection, str) or not isinstance(object_id, str):
                continue
            if collection_path_matches_tree(collection_path, record_collection):
                object_ids.add(object_id)
                document_matches = True

        if document_matches:
            matching_document_dirs.append(document_dir.name)

    return object_ids, matching_document_dirs


def browse_live_records(app_id: str, api_key: str, index_name: str, batch_size: int) -> List[Dict[str, object]]:
    endpoint = (
        "https://"
        + f"{app_id}-dsn.algolia.net/1/indexes/{urllib.parse.quote(index_name, safe='')}/browse"
    )
    headers = {
        "accept": "application/json",
        "content-type": "application/json",
        "x-algolia-api-key": api_key,
        "x-algolia-application-id": app_id,
    }

    records: List[Dict[str, object]] = []
    cursor: Optional[str] = None

    while True:
        params: Dict[str, object] = {
            "hitsPerPage": batch_size,
        }
        if cursor:
            params["cursor"] = cursor
        payload: Dict[str, object] = {
            "params": urllib.parse.urlencode(params),
        }

        request = urllib.request.Request(
            endpoint,
            data=json.dumps(payload).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            body = json.loads(response.read().decode("utf-8"))

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


def collect_live_state(records: Iterable[Dict[str, object]], collection_path: str) -> Set[str]:
    object_ids: Set[str] = set()
    for record in records:
        record_collection = record.get("collection")
        object_id = record.get("objectID")
        if not isinstance(record_collection, str) or not isinstance(object_id, str):
            continue
        if collection_path_matches_tree(collection_path, record_collection):
            object_ids.add(object_id)
    return object_ids


def build_report(
    index_name: str,
    collection_path: str,
    local_object_ids: Set[str],
    live_object_ids: Set[str],
    matching_document_dirs: Sequence[str],
    sample_limit: int,
) -> Dict[str, object]:
    missing_in_live = sorted(local_object_ids - live_object_ids)
    unexpected_in_live = sorted(live_object_ids - local_object_ids)
    return {
        "index": index_name,
        "collectionPath": collection_path,
        "localCount": len(local_object_ids),
        "liveCount": len(live_object_ids),
        "missingInLiveCount": len(missing_in_live),
        "unexpectedInLiveCount": len(unexpected_in_live),
        "sampleMissingInLive": missing_in_live[:sample_limit],
        "sampleUnexpectedInLive": unexpected_in_live[:sample_limit],
        "localDocumentDirCount": len(matching_document_dirs),
        "matchingDocumentDirs": list(matching_document_dirs),
        "synced": not missing_in_live and not unexpected_in_live,
    }


def compute_report(
    indexes_root: Path,
    index_name: str,
    collection_path: str,
    sample_limit: int,
    batch_size: int,
    app_id: Optional[str],
    api_key: Optional[str],
    live_records_json: Optional[Path],
) -> Dict[str, object]:
    local_object_ids, matching_document_dirs = collect_local_state(indexes_root, index_name, collection_path)

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

    live_object_ids = collect_live_state(live_records, collection_path)
    return build_report(
        index_name=index_name,
        collection_path=collection_path,
        local_object_ids=local_object_ids,
        live_object_ids=live_object_ids,
        matching_document_dirs=matching_document_dirs,
        sample_limit=sample_limit,
    )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare live Algolia records for a collection tree with the local-store snapshot."
    )
    parser.add_argument("command", choices=["report", "verify"])
    parser.add_argument("--indexes-root", required=True, help="Path to algolia-index/indexes")
    parser.add_argument("--index", required=True, help="Algolia index name")
    parser.add_argument("--collection-path", required=True, help="Collection tree to verify")
    parser.add_argument("--sample-limit", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=1000)
    parser.add_argument("--app-id")
    parser.add_argument("--api-key")
    parser.add_argument("--live-records-json", help="JSON fixture used instead of live Algolia browse")
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)

    try:
        report = compute_report(
            indexes_root=Path(args.indexes_root),
            index_name=args.index,
            collection_path=args.collection_path,
            sample_limit=args.sample_limit,
            batch_size=args.batch_size,
            app_id=args.app_id,
            api_key=args.api_key,
            live_records_json=Path(args.live_records_json) if args.live_records_json else None,
        )
    except (OSError, ValueError, urllib.error.URLError) as exc:
        print(f"Failed to reconcile Algolia collection sync: {exc}", file=sys.stderr)
        return 2

    print(json.dumps(report, indent=2, sort_keys=True))
    if args.command == "verify" and not report["synced"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
