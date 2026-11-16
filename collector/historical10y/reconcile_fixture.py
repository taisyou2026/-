#!/usr/bin/env python3
"""Offline reconciliation of the existing 22-race real fixture into the new DB."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from store import HistoricalStore, manifest_record_from_race, canonical_race_id


def winner_numbers(race):
    return sorted(
        int(r["horse_no"])
        for r in race.get("runners", [])
        if r.get("finish_position") == 1
    )


def win_payout_map(race):
    out = {}
    for p in race.get("win_payout", []):
        out[int(p["horse_no"])] = int(
            p.get("payout_per_100_yen", p.get("payout_per_100"))
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--db", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--replace", action="store_true")
    args = parser.parse_args()

    fixture_path = pathlib.Path(args.fixture)
    db_path = pathlib.Path(args.db)
    report_path = pathlib.Path(args.report)

    if db_path.exists():
        if not args.replace:
            raise SystemExit("DB_EXISTS_USE_NEW_PATH_OR_REPLACE")
        db_path.unlink()

    fixture_bytes = fixture_path.read_bytes()
    payload = json.loads(fixture_bytes.decode("utf-8"))
    races = payload["races"]
    summary = payload["summary"]

    manifest = [manifest_record_from_race(r) for r in races]
    expected_ids = [canonical_race_id(r) for r in races]
    duplicate_input_ids = len(expected_ids) - len(set(expected_ids))
    missing_identity = 0
    for r in races:
        try:
            canonical_race_id(r)
        except Exception:
            missing_identity += 1

    with HistoricalStore(db_path) as store:
        manifest_result = store.seed_manifest(manifest)
        import_result = store.import_chunk("FIXTURE22-V1", 0, races)

        mismatches = []
        fields = ["venue", "race_no", "surface", "distance_m"]
        for source in races:
            rid = source["race_id"]
            stored = store.current_race(rid)
            for field in fields:
                if stored.get(field) != source.get(field):
                    mismatches.append({
                        "race_id": rid,
                        "field": field,
                        "expected": source.get(field),
                        "actual": stored.get(field),
                    })
            if winner_numbers(stored) != winner_numbers(source):
                mismatches.append({
                    "race_id": rid,
                    "field": "winner",
                    "expected": winner_numbers(source),
                    "actual": winner_numbers(stored),
                })
            if win_payout_map(stored) != win_payout_map(source):
                mismatches.append({
                    "race_id": rid,
                    "field": "win_payout",
                    "expected": win_payout_map(source),
                    "actual": win_payout_map(stored),
                })

        status = store.status()
        active_expected = sum(
            1
            for race in races
            for runner in race.get("runners", [])
            if not runner.get("scratched")
        )
        active_db = int(store.scalar("SELECT COUNT(*) FROM runners WHERE scratched=0") or 0)
        manifest_db_ids = [
            row[0] for row in store.conn.execute(
                "SELECT race_id FROM manifest_races ORDER BY race_id"
            )
        ]
        store.integrity()

    pass_gate = all([
        len(races) == int(summary["race_count"]),
        len(races) == 22,
        manifest_result["inserted"] == 22,
        import_result["next_cursor"] == 22,
        status["race_count"] == 22,
        status["blocked"] == 0,
        duplicate_input_ids == 0,
        missing_identity == 0,
        manifest_db_ids == sorted(expected_ids),
        active_db == active_expected,
        not mismatches,
    ])

    report = {
        "schema": "JRA_HISTORICAL_10Y_PHASE_A_RECONCILIATION-v1",
        "fixture": str(fixture_path),
        "fixture_sha256": hashlib.sha256(fixture_bytes).hexdigest(),
        "network_access": "NONE",
        "REAL_PROSPECTIVE": False,
        "data_status": "HISTORICAL_ONLY",
        "realMoney": "BLOCKED",
        "phase": "A_OFFLINE_FOUNDATION",
        "manifest": {
            "expected_from_fixture": 22,
            "inserted": manifest_result["inserted"],
            "duplicates_on_seed": manifest_result["duplicates"],
            "duplicate_input_race_id": duplicate_input_ids,
            "missing_identity": missing_identity,
        },
        "import": import_result,
        "db": status,
        "reconciliation": {
            "race_id_match": f"{len(expected_ids) - len(set(expected_ids) ^ set(manifest_db_ids))}/22",
            "major_field_checks": [
                "venue", "race_no", "surface", "distance_m", "winner", "win_payout"
            ],
            "mismatch_count": len(mismatches),
            "mismatches": mismatches,
            "active_runners_expected": active_expected,
            "active_runners_db": active_db,
        },
        "HISTORICAL_10Y_CANONICAL": "NOT_YET_STARTED_PHASE_A_ONLY",
        "PHASE_A_FIXTURE_RECONCILIATION": "PASS" if pass_gate else "FAIL",
    }

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if pass_gate else 1


if __name__ == "__main__":
    raise SystemExit(main())
