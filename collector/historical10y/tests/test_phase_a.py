from __future__ import annotations

import copy
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from store import HistoricalStore, manifest_record_from_race, canonical_race_id


FIXTURE = pathlib.Path(__file__).resolve().parents[2] / "fixtures" / "jra-real-historical-fixture-20260919-20261114.json"


class PhaseAStoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
        cls.races = cls.payload["races"]

    def new_store(self):
        td = tempfile.TemporaryDirectory()
        self.addCleanup(td.cleanup)
        store = HistoricalStore(pathlib.Path(td.name) / "historical.db")
        self.addCleanup(store.close)
        return store

    def seed(self, store, races=None):
        races = races or self.races
        return store.seed_manifest([manifest_record_from_race(r) for r in races])

    def test_fixture22_reconciliation_and_integrity(self):
        store = self.new_store()
        seeded = self.seed(store)
        self.assertEqual({"inserted": 22, "duplicates": 0}, seeded)
        out = store.import_chunk("FIXTURE22", 0, self.races)
        self.assertEqual(22, out["next_cursor"])
        self.assertEqual(22, store.race_count())
        self.assertEqual(22, store.manifest_count())
        self.assertEqual(0, store.status()["blocked"])

        active_expected = sum(
            1 for race in self.races for r in race["runners"] if not r.get("scratched")
        )
        active_actual = int(store.scalar("SELECT COUNT(*) FROM runners WHERE scratched=0"))
        self.assertEqual(active_expected, active_actual)

        for source in self.races:
            rid = canonical_race_id(source)
            stored = store.current_race(rid)
            self.assertEqual(source["venue"], stored["venue"])
            self.assertEqual(source["race_no"], stored["race_no"])
            self.assertEqual(source["surface"], stored["surface"])
            self.assertEqual(source["distance_m"], stored["distance_m"])
            source_winner = sorted(
                r["horse_no"] for r in source["runners"] if r.get("finish_position") == 1
            )
            stored_winner = sorted(
                r["horse_no"] for r in stored["runners"] if r.get("finish_position") == 1
            )
            self.assertEqual(source_winner, stored_winner)
            self.assertEqual(source["win_payout"], stored["win_payout"])
        store.integrity()

    def test_idempotent_same_facts_do_not_create_new_version(self):
        store = self.new_store()
        self.seed(store, [self.races[0]])
        store.import_chunk("ONE-RACE", 0, [self.races[0]])
        rid = self.races[0]["race_id"]
        self.assertEqual(1, store.version_count(rid))
        self.assertEqual(
            "DUPLICATE_IDENTICAL",
            store.import_correction("REDOWNLOAD-SAME", copy.deepcopy(self.races[0])),
        )
        self.assertEqual(1, store.version_count(rid))
        self.assertEqual(0, store.status()["corrections"])

    def test_correction_is_append_only(self):
        store = self.new_store()
        self.seed(store, [self.races[0]])
        store.import_chunk("BASE", 0, [self.races[0]])
        rid = self.races[0]["race_id"]
        corrected = copy.deepcopy(self.races[0])
        corrected["race_name"] = corrected["race_name"] + " [CORRECTION_TEST]"
        corrected.setdefault("source", {})["source_correction_epoch"] = 1
        self.assertEqual(
            "SOURCE_CORRECTION_DETECTED",
            store.import_correction("CORRECTION-1", corrected),
        )
        self.assertEqual(2, store.version_count(rid))
        self.assertEqual(1, store.status()["corrections"])
        versions = list(store.conn.execute(
            "SELECT version,payload_json FROM race_versions WHERE race_id=? ORDER BY version",
            (rid,),
        ))
        self.assertEqual([0, 1], [v["version"] for v in versions])
        self.assertNotEqual(versions[0]["payload_json"], versions[1]["payload_json"])

    def test_chunk_failure_rolls_back_and_resume_is_clean(self):
        races = self.races[:3]
        store = self.new_store()
        self.seed(store, races)
        with self.assertRaisesRegex(RuntimeError, "INJECTED_CHUNK_FAILURE"):
            store.import_chunk("ROLLBACK-TEST", 0, races, fail_at=2)
        self.assertEqual(0, store.cursor())
        self.assertEqual(0, store.race_count())
        self.assertEqual(0, int(store.scalar("SELECT COUNT(*) FROM checkpoints") or 0))

        out = store.import_chunk("ROLLBACK-TEST", 0, races)
        self.assertEqual(3, out["next_cursor"])
        self.assertEqual(3, store.cursor())
        self.assertEqual(3, store.race_count())
        store.integrity()

    def test_manifest_conflict_is_fail_closed(self):
        store = self.new_store()
        record = manifest_record_from_race(self.races[0])
        store.seed_manifest([record])
        bad = dict(record)
        bad["source_locator"] = "CONFLICTING_LOCATOR"
        with self.assertRaisesRegex(ValueError, "MANIFEST_CONFLICT"):
            store.seed_manifest([bad])


if __name__ == "__main__":
    unittest.main()
