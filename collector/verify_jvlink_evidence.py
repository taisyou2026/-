#!/usr/bin/env python3
"""Offline CLI verifier for real/redacted JV-Link preflight evidence."""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

if __package__ in (None, ""):
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from collector.jvlink_evidence import evaluate, legacy_v1_to_v2, seal_readiness


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load(path: str):
    return json.loads(
        pathlib.Path(path).read_text(encoding="utf-8-sig"),
        object_pairs_hook=_reject_duplicate_keys,
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    source = ap.add_mutually_exclusive_group(required=True)
    source.add_argument("--evidence")
    source.add_argument("--legacy-preflight")
    ap.add_argument("--contract")
    ap.add_argument("--normalized-evidence-output")
    ap.add_argument("--output", default="jvlink-readiness.json")
    args = ap.parse_args()

    evidence = load(args.evidence) if args.evidence else legacy_v1_to_v2(load(args.legacy_preflight))
    contract = load(args.contract) if args.contract else None
    result = seal_readiness(evaluate(evidence, contract))

    if args.normalized_evidence_output:
        pathlib.Path(args.normalized_evidence_output).write_text(
            json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
    pathlib.Path(args.output).write_text(
        json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if result["SMOKE_TEST_READY"] == "YES" else 5


if __name__ == "__main__":
    raise SystemExit(main())
