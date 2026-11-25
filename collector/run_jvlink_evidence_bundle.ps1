param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("true","false")]
    [string]$ContractActive,

    [Parameter(Mandatory=$true)]
    [ValidateSet("true","false")]
    [string]$UserKeyValid,

    [string]$Python = "python",
    [string]$OutDir = "jvlink-evidence"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$producer = Join-Path $root "jvlink_no_download_preflight.py"
$verifier = Join-Path $root "verify_jvlink_evidence.py"

$evidence = Join-Path $OutDir "jvlink-preflight-evidence.json"
$contract = Join-Path $OutDir "jvlink-contract-attestation.json"
$readiness = Join-Path $OutDir "jvlink-readiness.json"
$hashes = Join-Path $OutDir "sha256.txt"
$summary = Join-Path $OutDir "SUMMARY.txt"

$contractObj = [ordered]@{
    schema = "JRA_VAN_CONTRACT_ATTESTATION-v1"
    checked_at = (Get-Date).ToUniversalTime().ToString("o")
    active_user_contract = ($ContractActive -eq "true")
    valid_user_key = ($UserKeyValid -eq "true")
    private_use_only = $true
    credential_material_recorded = $false
    notes = "Boolean attestation only. No user key, password, token, or credential material is recorded."
}
$contractObj | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $contract

& $Python $producer --output $evidence
$producerRc = $LASTEXITCODE

& $Python $verifier --evidence $evidence --contract $contract --output $readiness
$verifyRc = $LASTEXITCODE

$files = @($evidence, $contract, $readiness)
$hashLines = foreach ($f in $files) {
    $h = (Get-FileHash -Algorithm SHA256 $f).Hash.ToLowerInvariant()
    "$h  $([System.IO.Path]::GetFileName($f))"
}
$hashLines | Set-Content -Encoding ASCII $hashes

$r = Get-Content $readiness -Raw | ConvertFrom-Json
@"
JRA_VAN_PREFLIGHT_EVIDENCE=$($r.JRA_VAN_PREFLIGHT_EVIDENCE)
RUNTIME_PROVENANCE=$($r.RUNTIME_PROVENANCE)
REAL_JVLINK_PREFLIGHT=$($r.REAL_JVLINK_PREFLIGHT)
WINDOWS_JVLINK_RUNTIME=$($r.WINDOWS_JVLINK_RUNTIME)
PYWIN32=$($r.PYWIN32)
COM_DISPATCH=$($r.COM_DISPATCH)
JVINIT=$($r.JVINIT)
NETWORK_DATA_DOWNLOAD=$($r.NETWORK_DATA_DOWNLOAD)
CONTRACT_ATTESTATION=$($r.CONTRACT_ATTESTATION)
ACTIVE_USER_CONTRACT=$($r.ACTIVE_USER_CONTRACT)
ACTIVE_USER_KEY=$($r.ACTIVE_USER_KEY)
SMOKE_TEST_READY=$($r.SMOKE_TEST_READY)
BULK_BACKFILL_AUTHORIZED=$($r.BULK_BACKFILL_AUTHORIZED)
F0_STATUS=$($r.F0_STATUS)
FORMAL_PAPER_BUY=$($r.FORMAL_PAPER_BUY)
REAL_MONEY=$($r.REAL_MONEY)
AUTO_REAL_BET=$($r.AUTO_REAL_BET)
"@ | Set-Content -Encoding UTF8 $summary

Write-Host "Evidence directory: $OutDir"
Get-Content $summary

if ($producerRc -ne 0) { exit $producerRc }
exit $verifyRc
