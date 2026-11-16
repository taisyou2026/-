param(
    [string]$Python = "python",
    [string]$Output = "jvlink-preflight.json",
    [string]$GateOutput = "jvlink-preflight-gate.json",
    [string]$ContractEvidence = "jvlink-contract-attestation.json"
)

$ErrorActionPreference = "Stop"

Write-Host "=== JRA-VAN JV-Link no-download preflight ==="

$os = Get-CimInstance Win32_OperatingSystem
$osCaption = [string]$os.Caption
if ($osCaption -notmatch "Windows 10|Windows 11") {
    throw "WINDOWS_VERSION_NOT_SUPPORTED: $osCaption"
}

& $Python --version
if ($LASTEXITCODE -ne 0) {
    throw "PYTHON_NOT_READY"
}

$bitness = & $Python -c "import struct; print(struct.calcsize('P')*8)"
if ($LASTEXITCODE -ne 0) {
    throw "PYTHON_BITNESS_CHECK_FAILED"
}
Write-Host "Python bitness: $bitness"

& $Python -c "import win32com.client; print('pywin32=ready')"
if ($LASTEXITCODE -ne 0) {
    Write-Host "pywin32 is not installed. Install only if needed with:"
    Write-Host "  $Python -m pip install pywin32"
    throw "PYWIN32_NOT_READY"
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$preflight = Join-Path $scriptRoot "jvlink_preflight.py"
$verifier = Join-Path $scriptRoot "verify_jvlink_preflight.py"

& $Python $preflight --output $Output
$preflightExit = $LASTEXITCODE

if (Test-Path $Output) {
    Write-Host "--- preflight evidence ---"
    Get-Content $Output
}

if (-not (Test-Path $ContractEvidence)) {
    Write-Host "Contract evidence missing: $ContractEvidence"
    Write-Host "Create it from jvlink-contract-attestation.template.json after confirming the active Data Lab contract and valid user key."
    exit 6
}

& $Python $verifier --preflight $Output --contract $ContractEvidence --output $GateOutput
$gateExit = $LASTEXITCODE

if (Test-Path $GateOutput) {
    Write-Host "--- gate evidence ---"
    Get-Content $GateOutput
}

if ($preflightExit -ne 0) {
    exit $preflightExit
}
exit $gateExit
