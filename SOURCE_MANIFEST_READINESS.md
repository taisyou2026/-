# JRA Historical 10Y — Source / Manifest Readiness

## Verdict

```
SOURCE_MANIFEST_READINESS = PARTIAL

MANIFEST_DISCOVERY = PASS
EXPECTED_COUNT_METHOD = PASS

JRA_VAN_SOURCE_CAPABILITY = PASS
JRA_VAN_PRIVATE_AI_USE_TERMS = PASS
JRA_VAN_2017_COVERAGE = PASS
JRA_VAN_CANONICAL_MAPPING = PASS
JRA_VAN_PROVENANCE_DESIGN = PASS

WINDOWS_JVLINK_RUNTIME = NOT_TESTED
ACTIVE_USER_CONTRACT = NOT_VERIFIED
ACTIVE_USER_KEY = NOT_VERIFIED

SMOKE_TEST_READY = NO
BULK_BACKFILL_AUTHORIZED = NO
```

The public-web automation terms are no longer the primary blocker because
JRA-VAN Data Lab. / JV-Link is now the preferred bulk source.

The remaining fail-closed blocker is actual execution proof on the user's
Windows Data Lab. environment under an active contract.

## Verified official coverage

- JRA historical result search: 1986 onward.
- JRA annual result PDFs: 2002 onward.
- JRA-VAN Data Lab. advertises about 40 years of JRA official data.
- JRA-VAN setup RACE data includes RA/SE/HR from 1986 onward.
- The target start year 2017 is fully inside all required data ranges.
- Latest completed date currently verified: 2026-11-15.

## 2017 official control totals

Source: JRA FY2017 business report.

| Venue | Days | Races |
|---|---:|---:|
| Sapporo | 12 | 144 |
| Hakodate | 12 | 144 |
| Fukushima | 20 | 240 |
| Niigata | 26 | 312 |
| Nakayama | 41 | 492 |
| Tokyo | 45 | 539 |
| Chukyo | 26 | 312 |
| Kyoto | 44 | 528 |
| Hanshin | 42 | 504 |
| Kokura | 20 | 240 |
| **Total** | **288** | **3455** |

```
2017_EXPECTED_RACES = 3455
2017_EXPECTED_DAYS = 288
2017_FLAT = 3329
2017_OBSTACLE = 126
```

## 2017-2025 aggregate control total

```
2017 3455
2018 3454
2019 3452
2020 3456
2021 3456
2022 3456
2023 3456
2024 3454
2025 3455

TOTAL = 31094
```

2026 is still a partial year.

```
2026_COMPLETED_EXPECTED_RACES = NOT_VERIFIED
10Y_EXPECTED_RACES = NOT_VERIFIED
```

## Manifest design

Public JRA annual-result pages remain the independent manifest/control-total
path:

```
year
 -> annual results index
 -> venue
 -> meeting
 -> day source
 -> race identity
 -> canonical_race_id
```

JRA-VAN YS schedule and RA records can be used as structured cross-checks after
JV-Link runtime access is proven.

## Canonical race identity

JV-Data RA/SE expose:

```
開催年
開催月日
競馬場コード
開催回
開催日
レース番号
```

Therefore:

```
canonical_race_id =
YYYY + venue_code(2) + meeting(2) + day(2) + race_no(2)
```

is directly reproducible.

## JRA-VAN private-use terms

The general JRA-VAN terms restrict service information to the subscriber's own
personal/private use and prohibit direct access to the Data Lab. server except
through JV-Link.

On 2026-11-08 JRA-VAN staff responded to an inquiry matching this project's use
case: acquire about 10 years of results/odds through JV-Link, save them privately,
process them in Python, and train a private prediction model.

The staff response permits that use within personal use.

Retention constraint:
- if the free period ends without continuing, or the paid service is cancelled,
  saved source data, copies, and training data must be deleted;
- derived artifacts from which the source data cannot be reconstructed need not
  be deleted if they are not externally distributed/published.

The acquisition ledger must therefore retain an active-contract flag.

## Current JRA-VAN price

```
monthly = JPY 2,090 tax included
3 months = JPY 6,270
12 months = JPY 25,080
```

## Windows / JV-Link

Current official runtime:

```
JV-Link = 5.0.0
OS = Windows 10 / Windows 11 Japanese edition
interface = ActiveX COM
32bit = available
64bit = available
```

The official 2026 Python developer guide documents Python 3.14 + pywin32 and:

```python
win32com.client.Dispatch("JVDTLab.JVLink")
```

The project now contains:

```
collector/historical10y/jvlink_preflight.py
```

It performs no JVOpen/JVRTOpen call and downloads no race data.

## Required JV-Data records

Canonical minimum:

```
RA  race detail
SE  horse-by-race
HR  payout
UM  horse master
KS  jockey master
CH  trainer master
```

Validation / enrichment:

```
O1  win/place/bracket odds
H1  final vote counts excluding trifecta
H6  trifecta final vote counts
YS  schedule
CS  course information
CK  race-time snapshot statistics
```

2017 is within the published coverage range for the required records.

## Corrections

JRA-VAN states that correction data may be provided when supplied data are
wrong.

Local handling remains append-only:

```
official key
+ data creation date
+ retrieval timestamp
+ raw record SHA256
+ payload hash
```

Changed data for the same key emits:

```
SOURCE_CORRECTION_DETECTED
```

and creates a new local version.

Important limitation:
a new setup acquisition is treated as the current best historical snapshot.
The full lineage of every correction that occurred before the first local
ingest is not claimed to be reconstructable.

## Raw provenance

Required fields:

```
jv_sdk_version
jv_link_version
jv_data_spec_version
decoder_version
parser_version
source_retrieved_at
source_record_make_date
source_correction_epoch
record_type
raw_record_sha256
```

No JV-Data source records are committed to a public Git repository.

Private source storage must remain access-controlled.

## Public JRA site

Public JRA pages remain:

```
manifest
control totals
bounded verification
```

only.

No 10-year bulk public-Web retrieval is authorized.

## Next gate

Do not run the 2017 smoke download yet.

First run only the no-download Windows preflight on a machine with JV-Link:

```
python collector/historical10y/jvlink_preflight.py
```

PASS requires:

```
os = Windows
pywin32 = true
com_dispatch = true
jvinit_code = 0
network_data_download = NONE
```

and independently:

```
active Data Lab contract = confirmed
valid JV-Link user key = confirmed
```

Only after those facts are verified may:

```
JRA_VAN_BULK_SOURCE_GATE = PASS
SOURCE_MANIFEST_READINESS = PASS
SMOKE_TEST_READY = YES
```

be declared.

Then and only then proceed to a bounded 2017 10-25-race smoke test.

Until then:

```
HISTORICAL_2017_PILOT = NOT_STARTED
HISTORICAL_10Y_CANONICAL = NOT_COMPLETE
REAL_PROSPECTIVE = false
REAL_MONEY = BLOCKED
```
