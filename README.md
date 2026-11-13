# HANSHIN EDGE Android Validation Harness

Public, sanitized Android/SQLite validation harness.

This repository intentionally contains **no** prediction model, MF0 logic, BUY thresholds, credentials, signing material, JRA-VAN keys, committed real race data, real horse names, or production database archives.

It exists only to validate Android API 35 / Android 15 behavior for:

- SQLite transaction rollback and reopen
- duplicate batch handling
- correction-version handling
- migration
- corruption preservation
- quota failure recovery
- post-race odds exclusion from prediction lookups
- synthetic scale equivalent to 308 races / 4,197 runners / 76 corrections / 16 post-race odds

`PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED` remains explicit: normal rollback/reopen testing is not proof of OS-kill, power-loss, or WAL crash recovery.


## Separate official public-source proxy collector

The user-authorized read-only collector in [collector/README.md](collector/README.md) observes one race on2026-11-14 using a single scheduled public job. It does not use the Android harness or any private records/models. Small, short-lived validation artifacts may contain public race identity, horse numbers, odds, result numbers/payout and raw hashes. No raw HTML or captured real records are committed. Preflight success is not four-milestone success, and proxy success is never formal/Hanshin validation.
