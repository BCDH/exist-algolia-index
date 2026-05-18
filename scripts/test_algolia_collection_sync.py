#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

import algolia_collection_sync as sync


class AlgoliaCollectionSyncTest(unittest.TestCase):
    def test_exact_match_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_local_record(root, "ras", "doc-a", 100, "a.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-a")
            self.write_local_record(root, "ras", "doc-b", 100, "b.json", "/db/apps/raskovnik-data/data/GE.RKMD/sub", "obj-b")

            report = sync.compute_report(
                indexes_root=root,
                index_name="ras",
                collection_path="/db/apps/raskovnik-data/data/GE.RKMD",
                sample_limit=3,
                batch_size=1000,
                app_id=None,
                api_key=None,
                live_records_json=self.write_live_fixture(
                    root,
                    [
                        {"objectID": "obj-a", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                        {"objectID": "obj-b", "collection": "/db/apps/raskovnik-data/data/GE.RKMD/sub"},
                    ],
                ),
            )

            self.assertTrue(report["synced"])
            self.assertEqual(2, report["localCount"])
            self.assertEqual(2, report["liveCount"])
            self.assertEqual([], report["sampleMissingInLive"])
            self.assertEqual([], report["sampleUnexpectedInLive"])

    def test_missing_live_objects_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_local_record(root, "ras", "doc-a", 100, "a.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-a")
            self.write_local_record(root, "ras", "doc-b", 100, "b.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-b")

            report = sync.compute_report(
                indexes_root=root,
                index_name="ras",
                collection_path="/db/apps/raskovnik-data/data/GE.RKMD",
                sample_limit=3,
                batch_size=1000,
                app_id=None,
                api_key=None,
                live_records_json=self.write_live_fixture(
                    root,
                    [{"objectID": "obj-a", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"}],
                ),
            )

            self.assertFalse(report["synced"])
            self.assertEqual(1, report["missingInLiveCount"])
            self.assertEqual(["obj-b"], report["sampleMissingInLive"])
            self.assertEqual(0, report["unexpectedInLiveCount"])

    def test_unexpected_live_objects_fail(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_local_record(root, "ras", "doc-a", 100, "a.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-a")

            report = sync.compute_report(
                indexes_root=root,
                index_name="ras",
                collection_path="/db/apps/raskovnik-data/data/GE.RKMD",
                sample_limit=3,
                batch_size=1000,
                app_id=None,
                api_key=None,
                live_records_json=self.write_live_fixture(
                    root,
                    [
                        {"objectID": "obj-a", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                        {"objectID": "obj-extra", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                    ],
                ),
            )

            self.assertFalse(report["synced"])
            self.assertEqual(0, report["missingInLiveCount"])
            self.assertEqual(1, report["unexpectedInLiveCount"])
            self.assertEqual(["obj-extra"], report["sampleUnexpectedInLive"])

    def test_mixed_mismatch_reports_both_sides(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_local_record(root, "ras", "doc-a", 100, "a.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-a")
            self.write_local_record(root, "ras", "doc-b", 100, "b.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-b")

            report = sync.compute_report(
                indexes_root=root,
                index_name="ras",
                collection_path="/db/apps/raskovnik-data/data/GE.RKMD",
                sample_limit=3,
                batch_size=1000,
                app_id=None,
                api_key=None,
                live_records_json=self.write_live_fixture(
                    root,
                    [
                        {"objectID": "obj-a", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                        {"objectID": "obj-extra", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                    ],
                ),
            )

            self.assertFalse(report["synced"])
            self.assertEqual(1, report["missingInLiveCount"])
            self.assertEqual(1, report["unexpectedInLiveCount"])
            self.assertEqual(["obj-b"], report["sampleMissingInLive"])
            self.assertEqual(["obj-extra"], report["sampleUnexpectedInLive"])

    def test_collection_tree_filtering_uses_latest_snapshot_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_local_record(root, "ras", "doc-target", 100, "a.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-target")
            self.write_local_record(root, "ras", "doc-child", 100, "b.json", "/db/apps/raskovnik-data/data/GE.RKMD/sub", "obj-child")
            self.write_local_record(root, "ras", "doc-sibling", 100, "c.json", "/db/apps/raskovnik-data/data/GE.RKMD2", "obj-sibling")
            self.write_local_record(root, "ras", "doc-prefix", 100, "d.json", "/db/apps/raskovnik-data/data/GE.RKM", "obj-prefix")
            self.write_local_record(root, "ras", "doc-latest", 100, "e-old.json", "/db/apps/raskovnik-data/data/GE.RKMD", "obj-old")
            self.write_local_record(root, "ras", "doc-latest", 200, "e-new.json", "/db/apps/raskovnik-data/data/GE.RKMD2", "obj-new")

            report = sync.compute_report(
                indexes_root=root,
                index_name="ras",
                collection_path="/db/apps/raskovnik-data/data/GE.RKMD",
                sample_limit=3,
                batch_size=1000,
                app_id=None,
                api_key=None,
                live_records_json=self.write_live_fixture(
                    root,
                    [
                        {"objectID": "obj-target", "collection": "/db/apps/raskovnik-data/data/GE.RKMD"},
                        {"objectID": "obj-child", "collection": "/db/apps/raskovnik-data/data/GE.RKMD/sub"},
                    ],
                ),
            )

            self.assertTrue(report["synced"])
            self.assertEqual(2, report["localCount"])
            self.assertEqual(["doc-child", "doc-target"], report["matchingDocumentDirs"])

    @staticmethod
    def write_local_record(
        root: Path,
        index_name: str,
        document_dir: str,
        timestamp: int,
        filename: str,
        collection: str,
        object_id: str,
    ) -> None:
        target_dir = root / index_name / document_dir / str(timestamp)
        target_dir.mkdir(parents=True, exist_ok=True)
        with (target_dir / filename).open("w", encoding="utf-8") as handle:
            json.dump({"objectID": object_id, "collection": collection}, handle)

    @staticmethod
    def write_live_fixture(root: Path, records) -> Path:
        fixture = root / "live-records.json"
        with fixture.open("w", encoding="utf-8") as handle:
            json.dump({"records": records}, handle)
        return fixture


if __name__ == "__main__":
    unittest.main()
