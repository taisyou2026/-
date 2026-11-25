#!/usr/bin/env python3
"""Deterministic verifier for JRA-VAN JV-Link no-download preflight evidence.

This module is deliberately independent from JV-Link. It can run on Linux CI
and verify evidence produced on a real Windows machine. It never authorizes
bulk backfill and never enables betting/model gates.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
from datetime import datetime, timezone
from typing import Any

EVIDENCE_SCHEMA = "JRA_VAN_JVLINK_PREFLIGHT_EVIDENCE-v2"
LEGACY_SCHEMA = "JRA_VAN_JVLINK_PREFLIGHT-v1"
CONTRACT_SCHEMA = "JRA_VAN_CONTRACT_ATTESTATION-v1"
READINESS_SCHEMA = "JRA_VAN_JVLINK_READINESS-v2"
PROGID = "JVDTLab.JVLink"
EVIDENCE_SCHEMA_EPOCH = datetime(2026, 11, 23, 0, 0, 0, tzinfo=timezone.utc)

_SECRET_KEY = re.compile(r"(?:password|passwd|secret|token|api[_-]?key|user[_-]?key|license[_-]?key|credential)", re.I)
_ALLOWED_SECRETISH_KEYS = {
    "valid_user_key",
    "credential_material_recorded",
    "secret_redaction_status",
}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def sha256_json(value: Any) -> str:
    return sha256_bytes(canonical_json(value).encode("utf-8"))


def _parse_iso8601_value(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        text = value.strip()
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        dt = datetime.fromisoformat(text)
        if dt.tzinfo is None:
            return None
        return dt.astimezone(timezone.utc)
    except ValueError:
        return None


def _parse_iso8601(value: Any) -> bool:
    return _parse_iso8601_value(value) is not None


def _is_sha256(value: Any) -> bool:
    return isinstance(value, str) and bool(re.fullmatch(r"[0-9a-f]{64}", value.lower()))


def _evidence_body(evidence: dict[str, Any]) -> dict[str, Any]:
    body = copy.deepcopy(evidence)
    body.pop("evidence_sha256", None)
    return body


def compute_evidence_sha256(evidence: dict[str, Any]) -> str:
    return sha256_json(_evidence_body(evidence))


def seal_evidence(evidence: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(evidence)
    out["evidence_sha256"] = compute_evidence_sha256(out)
    return out


def _secret_bearing_path(value: Any, path: str = "$") -> str | None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key not in _ALLOWED_SECRETISH_KEYS and _SECRET_KEY.search(str(key)):
                if child not in (None, False, "", "REDACTED", "NOT_RECORDED"):
                    return f"{path}.{key}"
            found = _secret_bearing_path(child, f"{path}.{key}")
            if found:
                return found
    elif isinstance(value, list):
        for i, child in enumerate(value):
            found = _secret_bearing_path(child, f"{path}[{i}]")
            if found:
                return found
    return None


def legacy_v1_to_v2(preflight: dict[str, Any]) -> dict[str, Any]:
    """Wrap the Historical branch v1 producer output in the stronger v2 envelope."""
    if preflight.get("schema") != LEGACY_SCHEMA:
        raise ValueError("LEGACY_PREFLIGHT_SCHEMA_INVALID")
    evidence = {
        "schema": EVIDENCE_SCHEMA,
        "checked_at": preflight.get("checked_at"),
        "platform": {
            "os_name": preflight.get("os_name"),
            "os_release": preflight.get("os_release"),
            "python_version": preflight.get("python_version"),
            "python_bitness": preflight.get("python_bitness"),
        },
        "runtime": {
            "progid": preflight.get("progid"),
            "pywin32": preflight.get("pywin32"),
            "com_dispatch": preflight.get("com_dispatch"),
            "jvlink_version": preflight.get("jvlink_version"),
            "jvinit_called": preflight.get("jvinit_called"),
            "jvinit_code": preflight.get("jvinit_code"),
            "producer_status": preflight.get("status"),
        },
        "safety": {
            "network_data_download": preflight.get("network_data_download"),
            "jvopen_called": preflight.get("jvopen_called"),
            "jvrtopen_called": preflight.get("jvrtopen_called"),
            "secret_redaction_status": "PASS" if _secret_bearing_path(preflight) is None else "FAIL",
        },
        "tool": {
            "producer": "historical10y/jvlink_preflight.py",
            "script_version": "legacy-v1-adapter",
            "source_schema": LEGACY_SCHEMA,
            "source_evidence_sha256": sha256_json(preflight),
        },
        "synthetic_test_only": False,
    }
    return seal_evidence(evidence)


def evaluate(evidence: dict[str, Any], contract: dict[str, Any] | None = None) -> dict[str, Any]:
    reasons: list[str] = []
    if not isinstance(evidence, dict):
        evidence = {}
        reasons.append("EVIDENCE_OBJECT_REQUIRED")

    if evidence.get("schema") != EVIDENCE_SCHEMA:
        reasons.append("EVIDENCE_SCHEMA_MISMATCH")
    evidence_time = _parse_iso8601_value(evidence.get("checked_at"))
    if evidence_time is None:
        reasons.append("EVIDENCE_TIMESTAMP_INVALID")
    elif evidence_time < EVIDENCE_SCHEMA_EPOCH:
        reasons.append("EVIDENCE_STALE_SCHEMA_EPOCH")
    if evidence.get("synthetic_test_only") is True:
        reasons.append("SYNTHETIC_EVIDENCE_NOT_REAL")

    platform = evidence.get("platform")
    runtime = evidence.get("runtime")
    safety = evidence.get("safety")
    tool = evidence.get("tool")
    if not isinstance(platform, dict):
        platform = {}
        reasons.append("PLATFORM_REQUIRED")
    if not isinstance(runtime, dict):
        runtime = {}
        reasons.append("RUNTIME_REQUIRED")
    if not isinstance(safety, dict):
        safety = {}
        reasons.append("SAFETY_REQUIRED")
    if not isinstance(tool, dict):
        tool = {}
        reasons.append("TOOL_REQUIRED")

    if platform.get("os_name") != "Windows":
        reasons.append("WINDOWS_REQUIRED")
    if not isinstance(platform.get("python_version"), str) or not platform.get("python_version", "").strip():
        reasons.append("PYTHON_VERSION_REQUIRED")
    if platform.get("python_bitness") not in (32, 64):
        reasons.append("PYTHON_BITNESS_INVALID")

    if runtime.get("progid") != PROGID:
        reasons.append("JVLINK_PROGID_INVALID")
    if runtime.get("pywin32") is not True:
        reasons.append("PYWIN32_NOT_READY")
    if runtime.get("com_dispatch") is not True:
        reasons.append("JVLINK_COM_NOT_READY")
    if runtime.get("jvinit_called") is not True:
        reasons.append("JVINIT_NOT_CALLED")
    if runtime.get("jvinit_code") != 0:
        reasons.append("JVINIT_NOT_READY")
    if runtime.get("producer_status") != "PASS":
        reasons.append("PRODUCER_STATUS_NOT_PASS")

    network_state = safety.get("network_data_download", "UNKNOWN")
    if network_state != "NONE":
        reasons.append("NETWORK_DATA_DOWNLOAD_VIOLATION" if network_state != "UNKNOWN" else "NETWORK_DATA_DOWNLOAD_UNKNOWN")
    if safety.get("jvopen_called") is not False:
        reasons.append("JVOPEN_MUST_NOT_BE_CALLED")
    if safety.get("jvrtopen_called") is not False:
        reasons.append("JVRTOOPEN_MUST_NOT_BE_CALLED")
    if safety.get("secret_redaction_status") != "PASS":
        reasons.append("SECRET_REDACTION_NOT_VERIFIED")

    if not isinstance(tool.get("producer"), str) or not tool.get("producer", "").strip():
        reasons.append("PRODUCER_REQUIRED")
    if not isinstance(tool.get("script_version"), str) or not tool.get("script_version", "").strip():
        reasons.append("SCRIPT_VERSION_REQUIRED")
    source_hash = tool.get("source_evidence_sha256")
    if source_hash is not None and not _is_sha256(source_hash):
        reasons.append("SOURCE_EVIDENCE_HASH_INVALID")

    supplied_hash = evidence.get("evidence_sha256")
    if not _is_sha256(supplied_hash):
        reasons.append("EVIDENCE_SHA256_INVALID")
    elif supplied_hash.lower() != compute_evidence_sha256(evidence):
        reasons.append("EVIDENCE_SHA256_MISMATCH")

    leaked_path = _secret_bearing_path(evidence)
    if leaked_path:
        reasons.append("SECRET_MATERIAL_DETECTED:" + leaked_path)

    technical_reasons = list(reasons)
    technical_verified = not technical_reasons
    # An unkeyed SHA256 and self-reported fields establish payload consistency,
    # not execution origin. No independent provenance verifier exists yet.
    runtime_provenance_verified = False

    contract = contract if isinstance(contract, dict) else {}
    contract_reasons: list[str] = []
    if contract.get("schema") != CONTRACT_SCHEMA:
        contract_reasons.append("CONTRACT_SCHEMA_MISMATCH")
    if not _parse_iso8601(contract.get("checked_at")):
        contract_reasons.append("CONTRACT_TIMESTAMP_INVALID")
    if contract.get("active_user_contract") is not True:
        contract_reasons.append("ACTIVE_USER_CONTRACT_NOT_VERIFIED")
    if contract.get("valid_user_key") is not True:
        contract_reasons.append("ACTIVE_USER_KEY_NOT_VERIFIED")
    if contract.get("private_use_only") is not True:
        contract_reasons.append("PRIVATE_USE_ATTESTATION_NOT_VERIFIED")
    if contract.get("credential_material_recorded") is not False:
        contract_reasons.append("CREDENTIAL_MATERIAL_MUST_NOT_BE_RECORDED")
    leaked_contract = _secret_bearing_path(contract)
    if leaked_contract:
        contract_reasons.append("SECRET_MATERIAL_DETECTED:" + leaked_contract)

    contract_attestation_verified = not contract_reasons

    # A local boolean attestation is useful audit evidence, but it is not an
    # independent proof of an active subscription or configured valid user key.
    # Those remain NOT_VERIFIED until stronger external evidence is introduced.
    active_contract_verified = False
    active_user_key_verified = False
    smoke_ready = technical_verified and runtime_provenance_verified and active_contract_verified and active_user_key_verified

    if technical_verified and runtime_provenance_verified:
        network_report = "NONE"
    elif network_state not in ("NONE", "UNKNOWN"):
        network_report = "VIOLATION"
    else:
        network_report = "UNKNOWN"

    return {
        "schema": READINESS_SCHEMA,
        "JRA_VAN_PREFLIGHT_EVIDENCE": "VERIFIED" if technical_verified else "BLOCKED",
        "RUNTIME_PROVENANCE": "VERIFIED" if runtime_provenance_verified else "NOT_VERIFIED",
        "REAL_JVLINK_PREFLIGHT": "VERIFIED" if technical_verified and runtime_provenance_verified else "NOT_VERIFIED",
        "WINDOWS_JVLINK_RUNTIME": "VERIFIED" if technical_verified and runtime_provenance_verified else "NOT_VERIFIED",
        "PYWIN32": "VERIFIED" if technical_verified and runtime_provenance_verified and runtime.get("pywin32") is True else "NOT_VERIFIED",
        "COM_DISPATCH": "VERIFIED" if technical_verified and runtime_provenance_verified and runtime.get("com_dispatch") is True else "NOT_VERIFIED",
        "JVINIT": "VERIFIED" if technical_verified and runtime_provenance_verified and runtime.get("jvinit_called") is True and runtime.get("jvinit_code") == 0 else "NOT_VERIFIED",
        "NETWORK_DATA_DOWNLOAD": network_report,
        "CONTRACT_ATTESTATION": "VERIFIED" if contract_attestation_verified else "NOT_VERIFIED",
        "ACTIVE_USER_CONTRACT": "VERIFIED" if active_contract_verified else "NOT_VERIFIED",
        "ACTIVE_USER_KEY": "VERIFIED" if active_user_key_verified else "NOT_VERIFIED",
        "SMOKE_TEST_READY": "YES" if smoke_ready else "NO",
        "BULK_BACKFILL_AUTHORIZED": "NO",
        "F0_STATUS": "NOT_AVAILABLE",
        "STAKE_RULE": "NOT_AVAILABLE",
        "FORMAL_PAPER_BUY": "BLOCKED",
        "REAL_MONEY": "BLOCKED",
        "AUTO_REAL_BET": "DISABLED",
        "technical_reasons": technical_reasons,
        "provenance_reasons": ["INDEPENDENT_RUNTIME_PROVENANCE_REQUIRED"] if not runtime_provenance_verified else [],
        "contract_reasons": contract_reasons,
        "evidence_sha256": supplied_hash if _is_sha256(supplied_hash) else None,
        "readiness_sha256": None,
    }


def seal_readiness(result: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(result)
    out["readiness_sha256"] = sha256_json({k: v for k, v in out.items() if k != "readiness_sha256"})
    return out
