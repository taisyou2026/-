"""Offline canonical store for the JRA historical 10Y backfill.

This module deliberately performs no HTTP access. It accepts already-observed
records, requires a Manifest row before import, preserves corrections as new
versions, and keeps historical data separate from LIVE/Prospective stores.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import sqlite3
from datetime import datetime, timezone
from typing import Iterable, Mapping, Any

SCHEMA_VERSION = "JRA_HISTORICAL_CANONICAL-v1"
PARSER_VERSION = "historical10y-phase-a-v1"
DECODER_VERSION = "historical10y-json-v1"
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def canonical_race_id(race: Mapping[str, Any]) -> str:
    year = str(race["date"])[:4]
    venue = str(race["venue_code"]).zfill(2)
    meeting = int(race["meeting"])
    day = int(race["day"])
    race_no = int(race["race_no"])
    if not (year.isdigit() and len(year) == 4):
        raise ValueError("RACE_YEAR_INVALID")
    if venue not in {f"{i:02d}" for i in range(1, 11)}:
        raise ValueError("VENUE_CODE_INVALID")
    if not (1 <= meeting <= 99 and 1 <= day <= 99 and 1 <= race_no <= 12):
        raise ValueError("RACE_IDENTITY_INVALID")
    expected = f"{year}{venue}{meeting:02d}{day:02d}{race_no:02d}"
    supplied = race.get("race_id")
    if supplied is not None and supplied != expected:
        raise ValueError("RACE_IDENTITY_MISMATCH")
    return expected


def manifest_record_from_race(race: Mapping[str, Any]) -> dict[str, Any]:
    race_id = canonical_race_id(race)
    source = race.get("source") or {}
    locator = (
        source.get("card_cname")
        or source.get("result_cname")
        or source.get("result_pdf")
        or source.get("origin")
        or f"FIXTURE:{race_id}"
    )
    return {
        "race_id": race_id,
        "year": int(str(race["date"])[:4]),
        "date": race["date"],
        "venue_code": str(race["venue_code"]).zfill(2),
        "venue": race["venue"],
        "meeting": int(race["meeting"]),
        "day": int(race["day"]),
        "race_no": int(race["race_no"]),
        "race_status": "COMPLETED_OBSERVED",
        "source_locator": str(locator),
    }


def _facts_for_hash(race: Mapping[str, Any]) -> dict[str, Any]:
    # Retrieval timestamps and raw hashes are provenance, not race facts. A new
    # download of unchanged facts must remain DUPLICATE_IDENTICAL, not a correction.
    return {k: v for k, v in race.items() if k not in {"source"}}


def _winner_numbers(race: Mapping[str, Any]) -> list[int]:
    out = []
    for runner in race.get("runners") or []:
        if runner.get("finish_position") == 1:
            out.append(int(runner["horse_no"]))
    return sorted(out)


def _win_payouts(race: Mapping[str, Any]) -> list[dict[str, Any]]:
    return list(race.get("win_payout") or [])


def _source_entries(race: Mapping[str, Any]) -> list[dict[str, Any]]:
    source = dict(race.get("source") or {})
    provider = source.get("origin") or "JRA_OFFICIAL"
    entries: list[dict[str, Any]] = []

    def add(role: str, record_id: Any, retrieved: Any, raw_hash: Any, raw_size: Any = None) -> None:
        if not record_id:
            return
        entries.append({
            "source_role": role,
            "source_provider": str(provider),
            "source_record_id": str(record_id),
            "source_retrieved_at": retrieved,
            "raw_sha256": raw_hash if isinstance(raw_hash, str) and SHA256_RE.fullmatch(raw_hash) else None,
            "raw_size": raw_size if isinstance(raw_size, int) and raw_size >= 0 else None,
            "source_record_make_date": source.get("source_record_make_date"),
            "source_correction_epoch": int(source.get("source_correction_epoch") or 0),
            "decoder_version": source.get("decoder_version") or DECODER_VERSION,
            "parser_version": source.get("parser_version") or PARSER_VERSION,
            "schema_version": source.get("schema_version") or SCHEMA_VERSION,
            "payload": source,
        })

    add("CARD", source.get("card_cname"), source.get("card_retrieved_at"), source.get("card_raw_sha256"), source.get("card_raw_size"))
    add("RESULT", source.get("result_cname"), source.get("result_retrieved_at"), source.get("result_raw_sha256"), source.get("result_raw_size"))
    add(
        "RESULT_PDF",
        source.get("result_pdf") or source.get("result_pdf_url"),
        source.get("result_pdf_retrieved_at") or source.get("result_retrieved_at"),
        source.get("result_pdf_sha256"),
        source.get("result_pdf_size"),
    )
    if not entries and source:
        add("SOURCE_METADATA", source.get("origin") or race.get("race_id"), None, source.get("raw_sha256"))
    return entries


def classify_race(race: Mapping[str, Any]) -> str:
    try:
        canonical_race_id(race)
    except Exception:
        return "BLOCKED"

    runners = list(race.get("runners") or [])
    horse_numbers = [r.get("horse_no") for r in runners]
    identity_complete = bool(race.get("date") and race.get("venue") and race.get("venue_code"))
    roster_complete = (
        len(runners) >= 2
        and all(isinstance(n, int) and 1 <= n <= 18 for n in horse_numbers)
        and len(set(horse_numbers)) == len(horse_numbers)
        and all(bool(r.get("horse_name")) for r in runners)
    )
    winners = _winner_numbers(race)
    payouts = _win_payouts(race)
    payout_winners = sorted(int(p["horse_no"]) for p in payouts if "horse_no" in p)
    payout_values = [
        p.get("payout_per_100_yen", p.get("payout_per_100"))
        for p in payouts
    ]
    result_complete = bool(winners)
    payout_complete = bool(payouts) and payout_winners == winners and all(
        isinstance(v, int) and v >= 0 for v in payout_values
    )
    sources = _source_entries(race)
    provenance_complete = any(s.get("raw_sha256") for s in sources)
    return (
        "CANONICAL_READY"
        if identity_complete and roster_complete and result_complete and payout_complete and provenance_complete
        else "PARTIAL"
    )


class HistoricalStore:
    def __init__(self, db_path: str | pathlib.Path):
        self.db_path = pathlib.Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys=ON")
        schema = pathlib.Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")
        self.conn.executescript(schema)
        self.conn.execute("INSERT OR IGNORE INTO meta(k,v) VALUES('schema_version',?)", (SCHEMA_VERSION,))
        self.conn.execute("INSERT OR IGNORE INTO meta(k,v) VALUES('cursor','0')")
        self.conn.commit()
        self.integrity()

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "HistoricalStore":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def scalar(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        row = self.conn.execute(sql, params).fetchone()
        return None if row is None else row[0]

    def integrity(self) -> None:
        if self.scalar("PRAGMA quick_check") != "ok":
            raise RuntimeError("SQLITE_QUICK_CHECK_FAILED")
        fk = list(self.conn.execute("PRAGMA foreign_key_check"))
        if fk:
            raise RuntimeError(f"SQLITE_FOREIGN_KEY_CHECK_FAILED:{len(fk)}")

    def cursor(self) -> int:
        return int(self.scalar("SELECT v FROM meta WHERE k='cursor'") or 0)

    def manifest_count(self) -> int:
        return int(self.scalar("SELECT COUNT(*) FROM manifest_races") or 0)

    def race_count(self) -> int:
        return int(self.scalar("SELECT COUNT(*) FROM races") or 0)

    def version_count(self, race_id: str) -> int:
        return int(self.scalar("SELECT COUNT(*) FROM race_versions WHERE race_id=?", (race_id,)) or 0)

    def seed_manifest(self, records: Iterable[Mapping[str, Any]]) -> dict[str, int]:
        inserted = duplicates = 0
        with self.conn:
            for raw in records:
                r = dict(raw)
                race_id = str(r["race_id"])
                if race_id != canonical_race_id({
                    "race_id": race_id,
                    "date": r["date"],
                    "venue_code": r["venue_code"],
                    "meeting": r["meeting"],
                    "day": r["day"],
                    "race_no": r["race_no"],
                }):
                    raise ValueError("MANIFEST_RACE_ID_MISMATCH")
                normalized = {
                    "race_id": race_id,
                    "year": int(r["year"]),
                    "date": str(r["date"]),
                    "venue_code": str(r["venue_code"]).zfill(2),
                    "venue": str(r["venue"]),
                    "meeting": int(r["meeting"]),
                    "day": int(r["day"]),
                    "race_no": int(r["race_no"]),
                    "race_status": str(r["race_status"]),
                    "source_locator": str(r["source_locator"]),
                }
                mh = sha256_json(normalized)
                existing = self.conn.execute(
                    "SELECT manifest_hash FROM manifest_races WHERE race_id=?", (race_id,)
                ).fetchone()
                if existing:
                    if existing["manifest_hash"] != mh:
                        raise ValueError("MANIFEST_CONFLICT")
                    duplicates += 1
                    continue
                self.conn.execute(
                    """INSERT INTO manifest_races(
                       race_id,year,race_date,venue_code,venue,meeting,day,race_no,
                       race_status,source_locator,manifest_hash,created_at
                       ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        race_id, normalized["year"], normalized["date"], normalized["venue_code"],
                        normalized["venue"], normalized["meeting"], normalized["day"],
                        normalized["race_no"], normalized["race_status"],
                        normalized["source_locator"], mh, now_iso(),
                    ),
                )
                inserted += 1
            self._audit("MANIFEST_SEEDED", None, None, {"inserted": inserted, "duplicates": duplicates})
        return {"inserted": inserted, "duplicates": duplicates}

    def _audit(self, event_type: str, race_id: str | None, batch_id: str | None, detail: Any) -> None:
        self.conn.execute(
            "INSERT INTO audit_events(event_type,race_id,batch_id,detail_json,created_at) VALUES(?,?,?,?,?)",
            (event_type, race_id, batch_id, canonical_json(detail), now_iso()),
        )

    def _require_manifest(self, race_id: str) -> None:
        if self.scalar("SELECT 1 FROM manifest_races WHERE race_id=?", (race_id,)) is None:
            raise ValueError("MANIFEST_RACE_MISSING")

    def _insert_version(self, race: Mapping[str, Any], batch_id: str) -> str:
        race = dict(race)
        race_id = canonical_race_id(race)
        self._require_manifest(race_id)
        fact_hash = sha256_json(_facts_for_hash(race))
        current = self.conn.execute(
            """SELECT r.current_version, v.payload_hash
               FROM races r JOIN race_versions v
               ON v.race_id=r.race_id AND v.version=r.current_version
               WHERE r.race_id=?""",
            (race_id,),
        ).fetchone()
        if current and current["payload_hash"] == fact_hash:
            self._audit("DUPLICATE_IDENTICAL", race_id, batch_id, {"payload_hash": fact_hash})
            return "DUPLICATE_IDENTICAL"

        status = classify_race(race)
        ts = now_iso()
        new_version = 0 if current is None else int(current["current_version"]) + 1
        if current is None:
            self.conn.execute(
                """INSERT INTO races(
                   race_id,current_version,race_date,venue_code,venue,meeting,day,race_no,
                   obstacle,canonical_status,updated_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    race_id, new_version, race["date"], str(race["venue_code"]).zfill(2),
                    race["venue"], int(race["meeting"]), int(race["day"]), int(race["race_no"]),
                    1 if race.get("obstacle") else 0, status, ts,
                ),
            )

        epoch = int((race.get("source") or {}).get("source_correction_epoch") or new_version)
        self.conn.execute(
            """INSERT INTO race_versions(
               race_id,version,payload_hash,payload_json,source_correction_epoch,imported_at
               ) VALUES(?,?,?,?,?,?)""",
            (race_id, new_version, fact_hash, canonical_json(race), epoch, ts),
        )

        for runner in race.get("runners") or []:
            self.conn.execute(
                """INSERT INTO runners(
                   race_id,version,horse_no,horse_name,scratched,payload_json
                   ) VALUES(?,?,?,?,?,?)""",
                (
                    race_id, new_version, int(runner["horse_no"]),
                    str(runner.get("horse_name") or "UNKNOWN"),
                    1 if runner.get("scratched") else 0,
                    canonical_json(runner),
                ),
            )

        result = {
            "finish": [
                {
                    "horse_no": int(r["horse_no"]),
                    "finish_position": r.get("finish_position"),
                    "finish_status": r.get("finish_status"),
                }
                for r in race.get("runners") or []
                if r.get("finish_position") is not None or r.get("finish_status")
            ],
            "winners": _winner_numbers(race),
        }
        if result["finish"] or result["winners"]:
            self.conn.execute(
                "INSERT INTO results(race_id,version,result_json) VALUES(?,?,?)",
                (race_id, new_version, canonical_json(result)),
            )

        for payout in _win_payouts(race):
            value = payout.get("payout_per_100_yen", payout.get("payout_per_100"))
            selection = str(int(payout["horse_no"]))
            self.conn.execute(
                """INSERT INTO payouts(
                   race_id,version,bet_type,selection_key,payout_per_100_yen,payload_json
                   ) VALUES(?,?,?,?,?,?)""",
                (race_id, new_version, "WIN", selection, value, canonical_json(payout)),
            )

        for source in _source_entries(race):
            self.conn.execute(
                """INSERT INTO sources(
                   race_id,version,source_role,source_provider,source_record_id,
                   source_retrieved_at,raw_sha256,raw_size,source_record_make_date,
                   source_correction_epoch,decoder_version,parser_version,schema_version,payload_json
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    race_id, new_version, source["source_role"], source["source_provider"],
                    source["source_record_id"], source["source_retrieved_at"], source["raw_sha256"],
                    source["raw_size"], source["source_record_make_date"],
                    source["source_correction_epoch"], source["decoder_version"],
                    source["parser_version"], source["schema_version"],
                    canonical_json(source["payload"]),
                ),
            )

        if current is not None:
            old_version = int(current["current_version"])
            self.conn.execute(
                """INSERT INTO corrections(
                   race_id,from_version,to_version,old_hash,new_hash,detected_at
                   ) VALUES(?,?,?,?,?,?)""",
                (race_id, old_version, new_version, current["payload_hash"], fact_hash, ts),
            )
            self.conn.execute(
                """UPDATE races SET current_version=?,canonical_status=?,updated_at=?
                   WHERE race_id=?""",
                (new_version, status, ts, race_id),
            )
            self._audit(
                "SOURCE_CORRECTION_DETECTED", race_id, batch_id,
                {"from_version": old_version, "to_version": new_version},
            )
            return "SOURCE_CORRECTION_DETECTED"

        self._audit("RACE_IMPORTED", race_id, batch_id, {"version": 0, "canonical_status": status})
        return "IMPORTED"

    def import_chunk(
        self,
        chunk_id: str,
        previous_cursor: int,
        races: list[Mapping[str, Any]],
        fail_at: int | None = None,
    ) -> dict[str, Any]:
        if not re.fullmatch(r"[A-Za-z0-9_.-]{3,96}", chunk_id):
            raise ValueError("CHUNK_ID_INVALID")
        existing = self.conn.execute("SELECT * FROM checkpoints WHERE chunk_id=?", (chunk_id,)).fetchone()
        if existing:
            return dict(existing) | {"result": "DUPLICATE_CHUNK"}
        if previous_cursor != self.cursor():
            raise ValueError("CURSOR_GAP")

        success = duplicate = canonical = raw_hash_count = 0
        ts = now_iso()
        try:
            self.conn.execute("BEGIN IMMEDIATE")
            for index, race in enumerate(races, start=1):
                result = self._insert_version(race, chunk_id)
                if result == "DUPLICATE_IDENTICAL":
                    duplicate += 1
                else:
                    success += 1
                if classify_race(race) == "CANONICAL_READY":
                    canonical += 1
                if any(s.get("raw_sha256") for s in _source_entries(race)):
                    raw_hash_count += 1
                if fail_at is not None and index == fail_at:
                    raise RuntimeError("INJECTED_CHUNK_FAILURE")
            next_cursor = previous_cursor + len(races)
            self.conn.execute(
                """INSERT INTO imports(
                   batch_id,previous_cursor,next_cursor,record_count,status,imported_at
                   ) VALUES(?,?,?,?,?,?)""",
                (chunk_id, previous_cursor, next_cursor, len(races), "COMMITTED", ts),
            )
            self.conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('cursor',?)", (str(next_cursor),))
            self.conn.execute(
                """INSERT INTO checkpoints(
                   chunk_id,previous_cursor,next_cursor,expected,success,failed,skipped,
                   duplicate,raw_hash_count,canonical_count,status,updated_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    chunk_id, previous_cursor, next_cursor, len(races), success, 0, 0,
                    duplicate, raw_hash_count, canonical, "COMMITTED", ts,
                ),
            )
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise

        self.integrity()
        return {
            "result": "IMPORTED",
            "chunk_id": chunk_id,
            "previous_cursor": previous_cursor,
            "next_cursor": previous_cursor + len(races),
            "expected": len(races),
            "success": success,
            "duplicate": duplicate,
            "canonical_count": canonical,
            "raw_hash_count": raw_hash_count,
        }

    def import_correction(self, batch_id: str, race: Mapping[str, Any]) -> str:
        try:
            self.conn.execute("BEGIN IMMEDIATE")
            result = self._insert_version(race, batch_id)
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise
        self.integrity()
        return result

    def current_race(self, race_id: str) -> dict[str, Any]:
        row = self.conn.execute(
            """SELECT v.payload_json FROM races r JOIN race_versions v
               ON v.race_id=r.race_id AND v.version=r.current_version
               WHERE r.race_id=?""",
            (race_id,),
        ).fetchone()
        if not row:
            raise KeyError("RACE_NOT_FOUND")
        return json.loads(row["payload_json"])

    def status(self) -> dict[str, Any]:
        self.integrity()
        return {
            "schema_version": self.scalar("SELECT v FROM meta WHERE k='schema_version'"),
            "cursor": self.cursor(),
            "manifest_count": self.manifest_count(),
            "race_count": self.race_count(),
            "versions": int(self.scalar("SELECT COUNT(*) FROM race_versions") or 0),
            "runners": int(self.scalar("SELECT COUNT(*) FROM runners") or 0),
            "canonical_ready": int(self.scalar("SELECT COUNT(*) FROM races WHERE canonical_status='CANONICAL_READY'") or 0),
            "partial": int(self.scalar("SELECT COUNT(*) FROM races WHERE canonical_status='PARTIAL'") or 0),
            "blocked": int(self.scalar("SELECT COUNT(*) FROM races WHERE canonical_status='BLOCKED'") or 0),
            "corrections": int(self.scalar("SELECT COUNT(*) FROM corrections") or 0),
            "REAL_PROSPECTIVE": False,
            "data_status": "HISTORICAL_ONLY",
            "realMoney": "BLOCKED",
        }
