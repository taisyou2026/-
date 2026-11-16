# JRA Historical 10Y — JRA-VAN Bulk Source Gate

## Verdict

```
JRA_VAN_BULK_SOURCE_GATE = PARTIAL
SOURCE_MANIFEST_READINESS = PARTIAL

SOURCE_CAPABILITY = PASS
PRIVATE_AI_USE_TERMS = PASS
2017_COVERAGE = PASS
CANONICAL_MAPPING = PASS
PROVENANCE_DESIGN = PASS

WINDOWS_JVLINK_RUNTIME = NOT_TESTED
ACTIVE_USER_CONTRACT = NOT_VERIFIED
ACTIVE_USER_KEY = NOT_VERIFIED

smoke_test_ready = NO
bulk_backfill_authorized = NO
```

The remaining blocker is no longer public-web scraping permission. JRA-VAN
Data Lab. is a viable private-use bulk source. The only unresolved gate is
actual local Windows/JV-Link execution under an active Data Lab. contract.

## Terms

JRA-VAN's general terms restrict Data Lab. information to the subscriber's
personal/private use and prohibit access to the Data Lab. server except through
JV-Link.

A JRA-VAN staff answer dated 2026-11-08 addresses the exact intended use:
JV-Link acquisition of about ten years of results/odds, storage on the user's
own PC or access-controlled private cloud, Python processing, and private AI
training. The staff answer states that this is permitted within personal use.

Important retention condition:
- after the free period ends without continuing, or after the paid service is
  cancelled, saved source data, copies, and training data must be deleted;
- derived artifacts that cannot restore the source data do not need deletion
  if they are not externally distributed/published.

Therefore this project must record contract state in its acquisition ledger.

## Price

Current Data Lab. pricing:
- JPY 2,090/month including tax
- JPY 6,270/3 months
- JPY 25,080/12 months

## Windows / SDK

Current JV-Link:
- version 5.0.0
- Windows 10/11 Japanese editions
- 64-bit CPU
- 32-bit and 64-bit JV-Link are available
- ActiveX COM interface

The official 2026 Python development guide shows Python 3.14 + pywin32 and:
`win32com.client.Dispatch("JVDTLab.JVLink")`.

A read-only preflight script is included:
`collector/historical10y/jvlink_preflight.py`.

It does NOT call JVOpen or JVRTOpen and therefore performs no historical
download. PASS requires successful COM dispatch and `JVInit("UNKNOWN")==0`.

## Historical coverage

JRA-VAN states about 40 years / 1986 onward of JRA official data.

Setup RACE includes:
- RA: race details, 1986+
- SE: horse-by-race information, 1986+
- HR: payouts, 1986+
- H1: final votes except trifecta, 1986+
- H6: trifecta votes, 2004-08+
- O1: final win/place/bracket odds, 1993-06+

2017 is fully inside those ranges.

JRA-VAN help states setup data retrieves all past data; thereafter normal data
is used for incremental updates. Normal data is limited to one year, so older
history must be obtained through setup data.

## Canonical race ID

Official RA and SE keys expose:
- year
- month/day
- venue code
- meeting number
- day number
- race number

Therefore the existing canonical ID remains directly reproducible:

```
YYYY + venue_code(2) + meeting(2) + day(2) + race_no(2)
```

No synthetic mapping is required.

## Required records

Canonical minimum:
- RA
- SE
- HR
- UM
- KS
- CH

Cross-check / optional:
- O1
- H1/H6
- YS
- CS
- CK

Historical final odds remain historical facts. They must not be relabeled as
T-20/T-10/T-5/T-2 prospective snapshots.

## Corrections

JRA-VAN states that when supplied data are wrong, correction data can be
provided. JV-Data records contain data creation dates and many record families
contain new/update/delete semantics.

Local rule:
1. hash every received raw record;
2. key by official record key;
3. retain data creation date and retrieval timestamp;
4. if the same key changes, append a new local version;
5. emit SOURCE_CORRECTION_DETECTED;
6. never overwrite the old local version.

This supports correction tracking from first ingest forward.

Historical pre-ingest revision lineage is not guaranteed to be reconstructable
from a newly created setup database, so the initial setup snapshot is treated
as the best available current historical state, not proof of every past
correction epoch.

## Architecture

```
Windows 10/11 Japanese
  -> Python
  -> pywin32
  -> JV-Link 5.0.0
  -> JVOpen setup data
  -> raw JV-Data records + SHA256
  -> decoder
  -> existing Historical Canonical SQLite
  -> audit/reconciliation
```

No Android or LIVE Runtime dependency exists.

## Next gate

Do not start a 2017 data download yet.

Run only the Windows preflight on the user's Data Lab. machine:

```
python collector/historical10y/jvlink_preflight.py
```

Required output:

```
status = PASS
com_dispatch = true
jvinit_code = 0
network_data_download = NONE
```

and independently confirm an active Data Lab. contract/user key.

Only then may:

```
JRA_VAN_BULK_SOURCE_GATE = PASS
SOURCE_MANIFEST_READINESS = PASS
smoke_test_ready = YES
```

be set and the bounded 2017 10-25-race smoke test begin.

Current expected-count controls remain:

```
2017_expected_races = 3455
2017_2025_expected_races = 31094
2026_completed_expected_races = NOT_VERIFIED
10y_expected_races = NOT_VERIFIED
```

LIVE branch, scheduler, PR #1, F0, stake, bankroll and REAL MONEY remain
unchanged.
