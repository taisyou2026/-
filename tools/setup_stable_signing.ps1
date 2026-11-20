param(
  [string]$OutputDir = "$env:USERPROFILE\JRA-EDGE-signing",
  [string]$Alias = "jra-edge"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function New-HexSecret([int]$Bytes = 32) {
  $buf = New-Object byte[] $Bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($buf)
  return ([System.BitConverter]::ToString($buf)).Replace("-", "").ToLowerInvariant()
}

$keytool = (Get-Command keytool -ErrorAction Stop).Source
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$keystore = Join-Path $OutputDir "jra-edge-release.jks"
$secretFile = Join-Path $OutputDir "github-secrets.txt"
$reportFile = Join-Path $OutputDir "signing-report.txt"

if (Test-Path $keystore) {
  throw "Keystore already exists: $keystore. Refusing to overwrite a signing identity."
}

$storePass = New-HexSecret 32
$keyPass = $storePass
$dn = "CN=JRA-EDGE Personal Distribution,O=Personal,C=JP"

& $keytool -genkeypair -v -storetype JKS -keystore $keystore -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname $dn -storepass $storePass -keypass $keyPass

if ($LASTEXITCODE -ne 0 -or !(Test-Path $keystore)) {
  throw "keytool generation failed"
}

$list = & $keytool -list -v -keystore $keystore -alias $Alias -storepass $storePass 2>&1
if ($LASTEXITCODE -ne 0) {
  throw "keytool fingerprint read failed"
}

$line = $list | Where-Object { $_ -match "SHA256:\s*([0-9A-Fa-f:]{32,})" } | Select-Object -First 1
if (-not $line) {
  throw "SHA256 certificate fingerprint not found"
}
$signer = ([regex]::Match($line, "SHA256:\s*([0-9A-Fa-f:]{32,})").Groups[1].Value).Replace(":", "").ToLowerInvariant()

$bytes = [IO.File]::ReadAllBytes($keystore)
$b64 = [Convert]::ToBase64String($bytes)

@"
# SENSITIVE - DO NOT COMMIT / DO NOT PASTE INTO CHAT
JRA_EDGE_KEYSTORE_B64=$b64
JRA_EDGE_KEYSTORE_PASSWORD=$storePass
JRA_EDGE_KEY_ALIAS=$Alias
JRA_EDGE_KEY_PASSWORD=$keyPass
JRA_EDGE_SIGNER_SHA256=$signer
"@ | Set-Content -Encoding ascii $secretFile

$ksSha = (Get-FileHash -Algorithm SHA256 $keystore).Hash.ToLowerInvariant()
@"
JRA-EDGE stable signing setup
keystore=$keystore
keystore_sha256=$ksSha
alias=$Alias
signer_sha256=$signer
secrets_file=$secretFile

REAL_MONEY=BLOCKED
AUTO_REAL_BET=DISABLED

IMPORTANT:
1. Back up the .jks and github-secrets.txt securely.
2. Add the five values from github-secrets.txt to GitHub repository Actions secrets.
3. Never commit, upload to an issue, or paste these secrets into chat.
4. Losing this keystore prevents future in-place updates signed with this identity.
"@ | Set-Content -Encoding utf8 $reportFile

try {
  & icacls $OutputDir /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F" | Out-Null
} catch {
  Write-Warning "Could not tighten ACL automatically. Secure the folder manually."
}

Write-Host "STABLE_SIGNING_LOCAL_SETUP=PASS"
Write-Host "Keystore: $keystore"
Write-Host "Signer SHA256: $signer"
Write-Host "GitHub secrets file: $secretFile"
Write-Host "Report: $reportFile"
Write-Host "DO NOT paste secret values into chat or commit them."
