# Validation policy

## Scope

The Android harness validates Android/SQLite platform behavior only.

It does not validate horse-racing prediction quality, model profitability, MF0, BUY thresholds, race selection, market features, or proprietary data acquisition.

## Synthetic scale

The harness generates deterministic synthetic data at the same engineering scale used by the current device-data validation:

- 308 synthetic races
- 4,197 synthetic runners
- 76 correction versions
- 16 post-race odds records

No real horse, jockey, trainer, race, payout, or odds record is committed here. The separate public-source collector described below writes a temporary validation artifact.

## Required gates

1. Initial import commits atomically.
2. Duplicate batch is idempotent.
3. Failure after partial correction writes rolls back completely.
4. Database can close and reopen after a failed import.
5. Explicit uncommitted transaction rolls back through the supported Android lifecycle.
6. Quota rejection leaves the database empty and reopenable.
7. v1 -> v2 migration preserves existing data.
8. Corruption blocks access without silently deleting the evidence file.
9. Invalid backup is rejected without destroying the live database.
10. Post-race odds are excluded from prediction lookup.
11. Separate instrumentation invocation verifies committed state after app force-stop/restart-style reopen.

## Explicit limitation

PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED

The harness does not claim validation of an OS kill while a transaction is open, device power loss, or WAL crash recovery.


## Separate public-source collector

`collector/` is independently authorized read-only official public-page observation, not proprietary acquisition or prediction validation. Its exact-date workflow can upload only a short-lived validation JSON (numbers/timestamps/hashes; no names/raw HTML/private databases). Fixed milestone acceptance and same-race completeness are tested independently. See `collector/README.md`. The existing Android runtime classes and tests are unchanged.
