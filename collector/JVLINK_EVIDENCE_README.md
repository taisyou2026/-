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
The two boolean parameters are attestations only. Use `false` for any state
that has not been confirmed.

`JRA_VAN_PREFLIGHT_EVIDENCE=VERIFIED` means the technical payload passed
validation; it does not authenticate its execution origin. The producer exit
code 0 reports local technical success only. Offline verification leaves
`RUNTIME_PROVENANCE`, `REAL_JVLINK_PREFLIGHT`, and runtime subclaims
`NOT_VERIFIED` until independent provenance can be verified. The network
state is `UNKNOWN` unless a violation is reported or provenance is verified.
The verifier exits 5 while smoke authorization is blocked, including when a
valid payload has been produced. A matching self-generated SHA256 does not
prove that the evidence came from Windows or JV-Link.

A technical preflight does not authorize bulk backfill. The readiness output
keeps `BULK_BACKFILL_AUTHORIZED=NO` by design. A later, separately reviewed
bounded smoke-test gate is still required.
