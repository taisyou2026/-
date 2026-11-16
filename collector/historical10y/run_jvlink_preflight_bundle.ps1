param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("true","false")]
    [string]$ContractActive,

    [Parameter(Mandatory=$true)]
    [ValidateSet("true","false")]
    [string]$UserKeyValid,

    [string]$Python = "python",
    [string]$OutDir = "jvlink-preflight-evidence"
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$preflightScript = Join-Path $scriptRoot "jvlink_preflight.py"
$verifyScript = Join-Path $scriptRoot "verify_jvlink_preflight.py"

$preflightJson = Join-Path $OutDir "jvlink-preflight.json"
$contractJson = Join-Path $OutDir "jvlink-contract-attestation.json"
$gateJson = Join-Path $OutDir "jvlink-preflight-gate.json"
$shaFile = Join-Path $OutDir "sha256.txt"
$summaryFile = Join-Path $OutDir "SUMMARY.txt"

$contractBool = $ContractActive -eq "true"
$keyBool = $UserKeyValid -eq "true"

$contract = [ordered]@{
    schema = "JRA_VAN_CONTRACT_ATTESTATION-v1"
    checked_at = (Get-Date).ToUniversalTime().ToString("o")
    active_user_contract = $contractBool
    valid_user_key = $keyBool
    private_use_only = $true
    credential_material_recorded = $false
    notes = "Boolean attestation only. No JRA-VAN user key or credential is stored."
}
$contract | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $contractJson

& $Python $preflightScript --output $preflightJson
$preflightRc = $LASTEXITCODE

& $Python $verifyScript --preflight $preflightJson --contract $contractJson --output $gateJson
$gateRc = $LASTEXITCODE

$files = @($preflightJson, $contractJson, $gateJson)
$hashLines = foreach ($f in $files) {
    $hash = (Get-FileHash -Algorithm SHA256 $f).Hash.ToLowerInvariant()
    "$hash  $([System.IO.Path]::GetFileName($f))"
}
$hashLines | Set-Content -Encoding ASCII $shaFile

$gate = Get-Content $gateJson -Raw | ConvertFrom-Json
$pre = Get-Content $preflightJson -Raw | ConvertFrom-Json

@"
JRA Historical 10Y - Windows JV-Link Preflight Evidence

WINDOWS_JVLINK_PREFLIGHT=$($gate.WINDOWS_JVLINK_PREFLIGHT)
JRA_VAN_BULK_SOURCE_GATE=$($gate.JRA_VAN_BULK_SOURCE_GATE)
SOURCE_MANIFEST_READINESS=$($gate.SOURCE_MANIFEST_READINESS)
smoke_test_ready=$($gate.smoke_test_ready)

os_name=$($pre.os_name)
os_release=$($pre.os_release)
python_version=$($pre.python_version)
python_bitness=$($pre.python_bitness)
pywin32=$($pre.pywin32)
com_dispatch=$($pre.com_dispatch)
jvinit_code=$($pre.jvinit_code)
network_data_download=$($pre.network_data_download)
jvopen_called=$($pre.jvopen_called)
jvrtopen_called=$($pre.jvrtopen_called)

active_user_contract=$contractBool
valid_user_key=$keyBool
credential_material_recorded=false

REAL_MONEY=BLOCKED
REAL_PROSPECTIVE=false
"@ | Set-Content -Encoding UTF8 $summaryFile

Write-Host ""
Write-Host "=== PRE-FLIGHT SUMMARY ==="
Get-Content $summaryFile
Write-Host ""
Write-Host "Evidence directory: $OutDir"

if ($preflightRc -ne 0) {
    exit $preflightRc
}
exit $gateRc
