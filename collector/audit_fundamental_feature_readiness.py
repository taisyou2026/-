#!/usr/bin/env python3
"""Read-only audit of real historical fixture fields for F0 feature readiness.

This tool never creates F0 values and never upgrades a field to READY based on
semantic intuition alone. Historical fixture fields are admitted only if their
as-of evidence proves the value was available under the model policy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from datetime import datetime, timezone
from typing import Any

SCHEMA = "JRA_EDGE_FUNDAMENTAL_FEATURE_READINESS-v1"

RACE_FIELD_POLICY = {
    "race_id": ("IDENTITY_ONLY", "canonical race identity"),
    "date": ("IDENTITY_ONLY", "race identity/date"),
    "venue_code": ("IDENTITY_ONLY", "canonical race identity"),
    "venue": ("IDENTITY_ONLY", "canonical race identity"),
    "meeting": ("IDENTITY_ONLY", "canonical race identity"),
    "day": ("IDENTITY_ONLY", "canonical race identity"),
    "race_no": ("IDENTITY_ONLY", "canonical race identity"),
    "scheduled_start": ("CANDIDATE_ASOF_UNVERIFIED", "pre-race schedule candidate; historical retrieval does not prove availability"),
    "race_name": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "condition_text": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "age_condition": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "class_name": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "sex_restriction": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "grade": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "surface": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "course_text": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "distance_m": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "distance_bucket": ("CANDIDATE_ASOF_UNVERIFIED", "derived race-card candidate; source as-of remains unverified"),
    "obstacle": ("CANDIDATE_ASOF_UNVERIFIED", "race-card/model-scope candidate; historical retrieval does not prove availability"),
    "win_payout": ("FORBIDDEN_POST_RACE", "payout is known only after result"),
    "schema": ("CONTROL_ONLY", "fixture metadata"),
    "REAL_PROSPECTIVE": ("CONTROL_ONLY", "fixture metadata"),
    "data_status": ("CONTROL_ONLY", "fixture metadata"),
    "realMoney": ("CONTROL_ONLY", "fixture metadata"),
    "source": ("CONTROL_ONLY", "provenance container"),
    "runners": ("CONTROL_ONLY", "runner container"),
}

RUNNER_FIELD_POLICY = {
    "frame": ("CANDIDATE_ASOF_UNVERIFIED", "race-card candidate; historical retrieval does not prove availability"),
    "horse_no": ("IDENTITY_ONLY", "runner identity"),
    "horse_name": ("IDENTITY_ONLY", "runner identity"),
    "sex_age": ("CANDIDATE_ASOF_UNVERIFIED", "runner-card candidate; historical retrieval does not prove availability"),
    "carried_weight_kg": ("CANDIDATE_ASOF_UNVERIFIED", "runner-card candidate; historical retrieval does not prove availability"),
    "scratched": ("CANDIDATE_ASOF_UNVERIFIED", "pre-race state candidate but exact availability requires timestamped proof"),
    "final_win_odds": ("FORBIDDEN_MARKET_DERIVED", "final market odds belong to M0, never F0"),
    "popularity": ("FORBIDDEN_MARKET_DERIVED", "market rank/popularity belongs to market side, never F0"),
    "finish_position": ("FORBIDDEN_POST_RACE", "race outcome"),
    "finish_status": ("FORBIDDEN_POST_RACE", "race outcome/result status"),
}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_json(value: Any) -> str:
    return sha256_bytes(canonical_json(value).encode("utf-8"))


def parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        text = value.strip()
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except ValueError:
        return None


def _coverage(records: list[dict[str, Any]], policy: dict[str, tuple[str, str]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    total = len(records)
    for field in sorted(policy):
        status, reason = policy[field]
        present = sum(1 for row in records if field in row and row.get(field) is not None)
        out.append(
            {
                "field": field,
                "status": status,
                "reason": reason,
                "present_non_null": present,
                "total_records": total,
                "coverage": 0.0 if total == 0 else round(present / total, 6),
                "ready_for_f0": False,
            }
        )
    return out


def audit_fixture_bytes(raw: bytes) -> dict[str, Any]:
    payload = json.loads(raw.decode("utf-8"))
    races = payload.get("races")
    if not isinstance(races, list) or not races:
        raise ValueError("REAL_FIXTURE_RACES_REQUIRED")

    runners: list[dict[str, Any]] = []
    active_runners: list[dict[str, Any]] = []
    unknown_race_fields: set[str] = set()
    unknown_runner_fields: set[str] = set()

    card_before = card_after = card_unparseable = 0
    result_before = result_after = result_unparseable = 0
    card_hash_present = result_hash_present = 0

    for race in races:
        if not isinstance(race, dict):
            raise ValueError("REAL_FIXTURE_RACE_INVALID")
        unknown_race_fields.update(set(race) - set(RACE_FIELD_POLICY))

        start = parse_time(race.get("scheduled_start"))
        source = race.get("source") if isinstance(race.get("source"), dict) else {}

        card_time = parse_time(source.get("card_retrieved_at"))
        if start is None or card_time is None:
            card_unparseable += 1
        elif card_time <= start:
            card_before += 1
        else:
            card_after += 1

        result_time = parse_time(source.get("result_retrieved_at"))
        if start is None or result_time is None:
            result_unparseable += 1
        elif result_time <= start:
            result_before += 1
        else:
            result_after += 1

        if isinstance(source.get("card_raw_sha256"), str) and len(source["card_raw_sha256"]) == 64:
            card_hash_present += 1
        if isinstance(source.get("result_raw_sha256"), str) and len(source["result_raw_sha256"]) == 64:
            result_hash_present += 1

        rr = race.get("runners")
        if not isinstance(rr, list):
            raise ValueError("REAL_FIXTURE_RUNNERS_REQUIRED")
        for runner in rr:
            if not isinstance(runner, dict):
                raise ValueError("REAL_FIXTURE_RUNNER_INVALID")
            runners.append(runner)
            if not bool(runner.get("scratched")):
                active_runners.append(runner)
            unknown_runner_fields.update(set(runner) - set(RUNNER_FIELD_POLICY))

    race_fields = _coverage(races, RACE_FIELD_POLICY)
    runner_fields = _coverage(active_runners, RUNNER_FIELD_POLICY)
    all_fields = race_fields + runner_fields

    ready = [x["field"] for x in all_fields if x["ready_for_f0"]]
    candidates = [x["field"] for x in all_fields if x["status"] == "CANDIDATE_ASOF_UNVERIFIED"]
    market_forbidden = [x["field"] for x in all_fields if x["status"] == "FORBIDDEN_MARKET_DERIVED"]
    post_forbidden = [x["field"] for x in all_fields if x["status"] == "FORBIDDEN_POST_RACE"]

    report: dict[str, Any] = {
        "schema": SCHEMA,
        "fixture_sha256": sha256_bytes(raw),
        "race_count": len(races),
        "runner_count": len(runners),
        "active_runner_count": len(active_runners),
        "source_asof_evidence": {
            "card_retrieved_at_or_before_start": card_before,
            "card_retrieved_after_start": card_after,
            "card_retrieved_unparseable_or_missing": card_unparseable,
            "result_retrieved_at_or_before_start": result_before,
            "result_retrieved_after_start": result_after,
            "result_retrieved_unparseable_or_missing": result_unparseable,
            "card_raw_sha256_present": card_hash_present,
            "result_raw_sha256_present": result_hash_present,
        },
        "unknown_race_fields": sorted(unknown_race_fields),
        "unknown_runner_fields": sorted(unknown_runner_fields),
        "race_fields": race_fields,
        "runner_fields": runner_fields,
        "ready_for_f0_fields": ready,
        "candidate_asof_unverified_fields": candidates,
        "forbidden_market_fields": market_forbidden,
        "forbidden_post_race_fields": post_forbidden,
        "F0_FEATURE_READINESS": "BLOCKED_ASOF_EVIDENCE" if not ready else "PARTIAL",
        "F0_MODEL_STATUS": "NOT_AVAILABLE",
        "STAKE_RULE": "NOT_AVAILABLE",
        "FORMAL_PAPER_BUY": "BLOCKED",
        "REAL_MONEY": "BLOCKED",
        "AUTO_REAL_BET": "DISABLED",
        "next_requirement": (
            "Provide timestamped authoritative pre-race source provenance for candidate fields "
            "(for example JV-Link record make/retrieval evidence) before any field can enter the frozen F0 registry."
        ),
    }
    report["audit_sha256"] = sha256_json(report)
    return report


def audit_fixture(path: pathlib.Path) -> dict[str, Any]:
    return audit_fixture_bytes(path.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--fixture",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parent
        / "fixtures"
        / "jra-real-historical-fixture-20260919-20261114.json",
    )
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    report = audit_fixture(args.fixture)
    rendered = json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
