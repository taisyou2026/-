# Windows JV-Link Preflight Bundle

This bundle is the only required step before the 2017 bounded smoke test.

It does **not** call `JVOpen` or `JVRTOpen` and therefore does not download
historical race data.

## Run on the actual Windows PC that has JV-Link installed

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File collector\historical10y\run_jvlink_preflight_bundle.ps1 `
  -ContractActive true `
  -UserKeyValid true
```

Set either boolean to `false` unless it has actually been verified.

No JRA-VAN user key or credential is written to disk.

## Output

The script creates:

```
jvlink-preflight-evidence/
  jvlink-preflight.json
  jvlink-contract-attestation.json
  jvlink-preflight-gate.json
  sha256.txt
  SUMMARY.txt
```

The gate may advance only when all of the following are true:

```
WINDOWS_JVLINK_PREFLIGHT = PASS
JRA_VAN_BULK_SOURCE_GATE = PASS
SOURCE_MANIFEST_READINESS = PASS
smoke_test_ready = true
```

Expected technical evidence:

```
os_name = Windows
pywin32 = true
com_dispatch = true
jvinit_code = 0
network_data_download = NONE
jvopen_called = false
jvrtopen_called = false
```

If any requirement is missing, the verifier remains fail-closed.

## Important

Do not commit:
- a real JRA-VAN user key,
- credentials,
- downloaded JV-Data source records.

The evidence bundle contains only runtime status, boolean contract attestation,
and SHA-256 evidence.
