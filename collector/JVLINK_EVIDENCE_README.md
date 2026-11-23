# JV-Link evidence handoff

This directory is the LIVE-branch verification boundary for the Windows-only
JRA-VAN JV-Link preflight.

The producer performs **no race-data download**. It never calls JVOpen or
JVRTOpen. It checks only Windows/pywin32/COM/JVInit and writes a sealed,
redacted evidence JSON.

On the Windows machine with JV-Link installed:

```powershell
powershell -ExecutionPolicy Bypass -File collector/run_jvlink_evidence_bundle.ps1 `
  -ContractActive true -UserKeyValid true
```

Do not put a real user key, password or token in the command or evidence.
The two boolean parameters are attestations only.

A technical preflight does not authorize bulk backfill. The readiness output
keeps `BULK_BACKFILL_AUTHORIZED=NO` by design. A later, separately reviewed
bounded smoke-test gate is still required.
