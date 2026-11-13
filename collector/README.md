# Read-only public proxy observation — 2026-11-14

This collector is separate from the synthetic Android harness. It has no prediction model, staking logic, database adapter, signing material or secrets. Its only outbound source is the official public `www.jra.go.jp/JRADB/accessD.html` / `accessS.html` read-only CNAME requests. It follows only observed links and verifies body/token identities. Public source publication time is unknown; fetch/parse times are recorded.

## One scheduled job

Workflow: `.github/workflows/public-proxy-timechain.yml`

Cron `30 23 13 11 *` corresponds to 2026-11-14 08:30 JST. GitHub cron has no year field; the collector enforces the exact 2026-11-14 JST date and never fetches a later campaign. Scheduled launch is best-effort and can be delayed by GitHub. A manual `observe` dispatch around08:30 is also supported, using the same single-job implementation. Concurrency queues, rather than cancels, a running observer. There are no separate milestone cron jobs.

The job re-discovers Tokyo/Kyoto/Fukushima and selects the earliest future non-obstacle race with >=2 active runners, published odds for every runner, verified official identity and scheduled start. It prefers the known earliest race only if that remains the earliest eligible observed catalog entry. An observation that cannot fit the 120-minute job budget fails explicitly. Each target is recomputed from the official catalog, not a fixed09:20 string.

Fixed targets/tolerances in seconds:

|Milestone|Before start|Tolerance|
|---|---:|---:|
|T-20|1200|180|
|T-10|600|120|
|T-5|300|75|
|T-2|120|45|

One process waits in <=30-second increments to each exact target. Each milestone has at most3 attempts, >=15 seconds between attempt starts, stops on success and retains failures. Body identity, scheduled start, roster, complete odds, HTTP200, raw-byte SHA256, source-Date clock skew<=10 seconds, receipt/parse ordering and parsed-time tolerance are required. A changed start/identity fails closed. The independent summary gate rechecks the successful records and requires all four for the **same raceKey**. A second call for an already successful milestone performs no fetch. Existing output files are rejected rather than overwritten on process relaunch. Workflow reruns create independent artifacts; incomplete runs are never combined.

## Report, not raw pages

Only `proxy-timechain-report.json` is uploaded, with7-day retention. Raw HTML is kept in memory only, never written to the repository, filesystem or artifact. No horse/jockey/trainer names are retained. Report runners contain only public horse numbers and observed win odds. These are current official public observations, not private historical data.

Every observation is READINESS_ONLY / VENUE_PROXY_ONLY / formal_cohort=false / formal_prediction=false / paper_stake=0 / realMoney=BLOCKED. No formal DB is opened. The report always has REAL_prospective_count=0, PROSPECTIVE_READY=false and HANSHIN_REAL_TIMECHAIN=NOT_YET_VERIFIED. Success is named REAL_SOURCE_PROXY_TIMECHAIN_VERIFIED, never Hanshin verification.

`workflow_dispatch` default **preflight** only checks current official discovery/catalog/roster/odds. A green preflight job is explicitly NOT four-milestone success. The actual observer returns failure if the four-point chain is incomplete. A runner timeout/kill may leave the last saved report with PENDING stages; that is not PASS and is process-interruption evidence, not a prediction result. Upload uses `if: always()` but GitHub infrastructure failure can still prevent artifact delivery.

After the race, the same observer waits until start+5min and tries observed official result menu→meeting→race links up to5 times with60-second spacing. Result body/token identity, finishing positions, winning horse/payout agreement and raw hash are required. No prediction or settlement arithmetic is performed. Result-path failure is explicit and independent of four-milestone acceptance.

## PC comparison

`compare_snapshots.py` consumes the report and normalized public-only PC snapshots (same fields, no names). It records both timestamps, raw hashes, runner/odds counts, completeness and odds values. Different hashes are not automatically failures. A retrieval gap>30s is labeled SEPARATE_SNAPSHOTS; this comparison threshold does not change capture tolerances. PC's earlier-in-window fetch can differ by minutes from the exact-target GitHub fetch. No matching PC snapshot stays unavailable.

```
python -m unittest discover -s collector -p 'test_*.py' -v
python collector/public_proxy.py --preflight --output /tmp/new-preflight.json
python collector/compare_snapshots.py public.json pc-normalized.json --output /tmp/comparison.json
```

Synthetic tests use a virtual clock and synthetic markup only. Actual current source tests must run separately. Nothing in this repository enables formal prospective operation.
