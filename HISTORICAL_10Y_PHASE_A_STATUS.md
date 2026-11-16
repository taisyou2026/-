# JRA Historical 10Y — Phase A

Status: IMPLEMENTATION_IN_PROGRESS

Scope is deliberately offline. This branch does not change LIVE Prospective
Runtime, the scheduler, PR #1, Frozen MF0, stake logic, or REAL MONEY gates.

Phase A builds:
- manifest-first identity storage,
- separate canonical SQLite,
- append-only race versions and correction audit,
- chunk checkpoint/resume semantics,
- duplicate suppression,
- SQLite integrity/foreign-key checks,
- reconciliation against the existing 22 real JRA races.

No bulk HTTP acquisition is authorized by this phase. Source terms, discovery
coverage, expected 10-year race count, and safe request policy remain separate
gates before the 2017 pilot begins.
