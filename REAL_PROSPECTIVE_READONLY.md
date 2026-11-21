# REAL_PROSPECTIVE_READONLY

This is a real-source observation gate, not a new model or a BUY engine. R3 is
NOT_MET until one actual preregistered race has all four verified milestones.
Tests, historical replay, preflight, NO_JRA_MEETING and a green Actions badge do
not supply that evidence. R4 needs three distinct complete races.

## One Android implementation

The dedicated API35 workflow invokes RealProspectiveObservationTest, which runs
the application's JraSource, ProspectiveRuntime.canonical/clock/capture and six
existing SQLite audit stores. There is no Python observation model to drift from
Android. Python only checks workflow exit policy after native evidence export.
The observer's namespace is real-prospective-readonly, separate from normal app
and test data. No report importer may turn artifacts into formal BUYs.

Official discovery validates today's JST date and the source clock. It never
infers a meeting from the weekday. An unrecognized/empty menu is UNVERIFIED,
not proof of no meetings. Verified zero current meetings is NO_JRA_MEETING.
All discovered races are preregistered before selection. Late/known-result/
unverified-clock registrations remain denied. At most three future T-20 targets
are selected, with venue diversity and Hanshin priority when present, then the
earliest eligible races to fill remaining slots. Fewer candidates is explicit.
No race, horse, venue/date combination or future source token is fabricated.

The fixed windows remain 1200±180, 600±120, 300±75 and 120±45 seconds.
Each capture rechecks full source identity, the registered roster, scratches,
clock, odds and post/result cutoff. Explicit scratches are accepted; unexplained
number changes, duplicates, missing roster members and reinstatement fail closed.
Failed snapshots are retained; they cannot be replaced by another stage or race.
Source exceptions get at most three attempts, at least 15 seconds apart. A stored
failed capture is terminal, because rewriting its immutable stage key is forbidden.
Result fetches get at most six attempts, 120 seconds apart, after post+180 seconds.
No model or stake is generated. Missing F0/stake does not stop market observation.

## Workflow activation and safe dispatch

The schedule is daily 08:15 JST (23:15 UTC). GitHub schedules only workflows on
the repository's default branch. Merely pushing this file to the development
branch does NOT activate its cron. Until reviewed code/workflow is installed on
default, report SCHEDULE_NOT_ACTIVE_DEFAULT_BRANCH, never autonomous readiness.
PR #1 is not merged by this implementation.

The already registered Android validation workflow also has an explicit manual
mode, allowing a branch-pinned validation without merging the development PR:

```sh
gh workflow run android15-validation.yml --repo taisyou2026/- \
  --ref jra-runtime-capability-gate -f mode=real_prospective
```

Both workflows use the same real-observer concurrency group without cancellation.
Before dispatch, check queued/in-progress REAL runs and their inputs. Do not
dispatch a duplicate run. Only observe; there is no preflight credit or prediction.
Scheduled starts can be delayed and API35 boot takes time. Selection is performed
after actual discovery and only accepts still-future T-20 targets. No window is
widened to hide runner lateness. Tasks beyond the 150-minute observer budget are
not selected. This is a first-three-race gate, not all-day background validation.

## Evidence

Artifacts include real-prospective-summary.json, real-prospective-registration.json,
timechain-T20/T10/T5/T2.json, result.json, raw-sha256.txt, clock-report.json and
attempts.json. JSON files are atomically replaced and flushed after registration,
each attempt and periodic waits. They are views of append-only Runtime rows.
Unobserved/failed milestones are explicit; no raw HTML is saved. Clock receipts
include source URL/token, raw size/hash, HTTP status/Date and reception timestamps.
The native parser recomputes gates and rejects forged PASS, missing proof,
cross-race milestones, changed tolerances and duplicate report race IDs.

The extraction trap and upload-always preserve available evidence on failure.
An OS/runner interruption can still prevent final export; a missing artifact is
NOT_VERIFIED. An OBSERVING/partial report is not a completed or passing run.
NO_JRA_MEETING exits normally with all real gates NOT_MET. Source failure or a
meeting-day R3/result failure exits nonzero after saving evidence.

F0_STATUS=NOT_AVAILABLE; STAKE_RULE=NOT_AVAILABLE; FORMAL_PAPER_BUY=BLOCKED;
REAL_MONEY=BLOCKED; AUTO_REAL_BET=DISABLED. The existing bankroll read model is
unchanged: no real observation creates stakes, payouts or formal BUYs.
ANDROID_PHYSICAL_DEVICE=NOT_TESTED. No phone reboot, force-stop, manufacturer
battery-policy, overnight survival or power-loss/WAL recovery claim is made.
PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED.
