"""Workflow outcome only. Observation/gate rules are in Android, not reimplemented here."""
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    report = json.load(source)
assert report["schema"] == "REAL_PROSPECTIVE_READONLY-v1"
assert report["REAL_MONEY"] == "BLOCKED" and report["AUTO_REAL_BET"] == "DISABLED"
assert report["formal_buy_count"] == 0 and report["bankroll"]["stake"] == 0
assert report["bankroll"]["payout"] == 0 and report["bankroll"]["current_bankroll"] == 100000
print(json.dumps({k: report[k] for k in ("status", "selected_count", "gates")}, ensure_ascii=False))
if report["status"] == "NO_JRA_MEETING":
    assert len(report["discovery"]["meetings"]) == 0 and report["discovery"]["clock"]["verified"]
    assert all(report["gates"][k] == "NOT_MET" for k in ("REAL_GATE_R1", "REAL_GATE_R2", "REAL_GATE_R3", "REAL_GATE_R4"))
    print("NO_JRA_MEETING: normal no-op; REAL_PROSPECTIVE_GATE=NOT_MET")
    sys.exit(0)
if report["gates"]["REAL_GATE_R3"] != "PASS":
    sys.exit("REAL_PROSPECTIVE_GATE=NOT_MET; inspect preserved milestone evidence")
# Require the result for a four-milestone race, not an unrelated race's result.
complete = [r for r in report["races"] if len(r["milestones"]) == 4
            and all(s.get("status") == "PASS" for s in r["milestones"])]
if not any(r["result"].get("status") == "PASS"
           and r["result"].get("race_id") == r["registration"]["race_id"] for r in complete):
    sys.exit("R3 observed but same-race result is NOT_VERIFIED")
print("REAL_GATE_R3=PASS; same-race RESULT available; no formal BUY")
