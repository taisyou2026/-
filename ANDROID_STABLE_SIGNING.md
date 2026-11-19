# JRA-EDGE Android stable signing

## Why this exists

The GitHub Actions debug APK is signed with a runner-local Android debug key.
Separate workflow runs can therefore produce APKs with different signer
certificates even when the Git commit is identical.

That is acceptable for CI, but it is **not** safe for user-facing updates:
Android requires the same application ID and signer for an in-place update.

The repository is public. Never commit an Android signing keystore, private key,
password, or base64-encoded keystore.

## Required GitHub Actions secrets

Configure these repository secrets before running
`.github/workflows/android-stable-distribution.yml`:

- `JRA_EDGE_KEYSTORE_B64`
- `JRA_EDGE_KEYSTORE_PASSWORD`
- `JRA_EDGE_KEY_ALIAS`
- `JRA_EDGE_KEY_PASSWORD`
- `JRA_EDGE_SIGNER_SHA256`

`JRA_EDGE_SIGNER_SHA256` is the SHA-256 fingerprint of the certificate in the
keystore. The distribution workflow independently verifies that fingerprint
before uploading an APK.

## Generate the signing key on a trusted Windows PC

Example:

```powershell
keytool -genkeypair -v -keystore jra-edge-release.jks -alias jra-edge -keyalg RSA -keysize 3072 -validity 10000
```

Display the certificate fingerprint:

```powershell
keytool -list -v -keystore jra-edge-release.jks -alias jra-edge
```

Base64 encode the keystore for the GitHub secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("jra-edge-release.jks"))
```

Do **not** paste the keystore, passwords, or private key into source code,
issues, commits, logs, or chat.

## Migration note

Previously distributed debug APKs may have a different debug signer. The first
move to the stable-signed APK can therefore require uninstalling the old debug
APK before installing the stable build. That uninstall deletes app-local data,
so export anything important first.

After the stable signer is established, keep that private key backed up securely.
Losing it prevents future in-place Android updates under the same application
ID.

## Safety invariants

The signing workflow changes only APK distribution identity. It does not change:

- Frozen model logic
- CapabilityGate
- TIMECHAIN
- F0
- stake rules
- bankroll logic
- `REAL_MONEY = BLOCKED`
- `AUTO_REAL_BET = DISABLED`
