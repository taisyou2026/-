# JRA Historical 10Y — Source / Manifest Readiness

## Verdict

```
SOURCE_MANIFEST_READINESS = PARTIAL
MANIFEST_DISCOVERY = PASS
EXPECTED_COUNT_METHOD = PASS
TERMS_STATUS = PARTIAL_AUTOMATION_UNSPECIFIED
SMOKE_TEST_READY = NO
BULK_BACKFILL_AUTHORIZED = NO
```

This is a fail-closed verdict. The technical source path is proven, but bulk
automation against the public JRA website is not authorized by this project
until the access/usage constraint is clarified or the bulk path is switched to
a contracted structured source such as JRA-VAN Data Lab / JV-Link.

## Verified official coverage

- JRA FAQ states historical race results are available from 1986 onward.
- JRA states annual result PDFs are available from 2002 onward.
- The target start year 2017 is therefore inside the official published range.
- The 2017 annual-results page enumerates every venue / meeting / day PDF.
- A bounded inspection of 2017 Tokyo meeting 1 day 1 confirms the PDF includes:
  race identity, scheduled time, runners, final win odds, result, and payouts.
- Latest completed date verified for the current target boundary: 2026-11-15.

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

2017 race-type control:
- flat = 3329
- obstacle = 126
- total = 3455

Therefore:

```
2017_EXPECTED_RACES = 3455
2017_EXPECTED_DAYS = 288
```

No estimate is used.

## 2017-2025 aggregate control total

JRA official annual race-count series:

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
```

Sum:

```
2017_2025_EXPECTED_RACES = 31094
```

2026 is still in progress. The target is only through the latest completed
race date, so the 10-year total remains NOT_VERIFIED until the 2026 completed
manifest is enumerated and reconciled.

## Manifest design

Primary low-request discovery path:

```
year
 -> JRA annual-results index
 -> venue
 -> meeting
 -> day PDF locator
 -> races observed inside that daily PDF
 -> canonical race_id
```

Manifest record requires:

```
race_id
year
date
venue_code
venue
meeting
day
race_no
obstacle
race_status
source_locator
manifest_source
manifest_retrieved_at
manifest_raw_sha256
```

The annual business report is used as an independent control total. Planned
calendar pages are cross-checks only because cancellations, substitutions or
program changes can occur.

## Race identity

Existing canonical ID remains unchanged:

```
YYYY + venue_code(2) + meeting(2) + day(2) + race_no(2)
```

The 2017 official PDF explicitly carries venue/meeting/day/race-number identity,
so the ID is reproducible without inventing identifiers.

## Terms / access review

### JRA public website

Verified:
- robots.txt currently contains `User-agent: *` and an empty `Disallow:`.
- JRA's site-use page states that site media/articles are copyrighted and
  secondary use beyond legal private-use/quotation boundaries may require
  permission.
- No explicit public bulk-scraping permission or numeric request-rate limit was
  found in the reviewed official pages.

Project interpretation:
- robots permission is not treated as contractual/legal permission.
- bounded manual/read-only probes are retained as research evidence.
- bulk HTTP backfill remains blocked until the automation terms are clarified.

### JRA-VAN Data Lab

Verified:
- Windows PC service with long historical JRA official data coverage.
- JV-Link is the required server access module.
- JRA-VAN terms limit use to the contract scope and prohibit bypassing JV-Link.
- Private-use data access is materially clearer than public-site bulk scraping.

Therefore the preferred bulk architecture is:

```
Public JRA pages:
  manifest/control-total/bounded verification

JRA-VAN Data Lab + JV-Link:
  preferred bulk structured historical acquisition
  if subscription + compatible Windows execution are available
```

## Provisional request policy

This is an engineering safety ceiling, NOT a vendor-published allowance:

```
max_concurrency = 1
base_interval_seconds = 2
retryable = network timeout / DNS / HTTP 429 / HTTP 503
retry_attempts = 3
backoff_seconds = [2, 5, 10]
jitter_seconds = [0.5, 1.5]
smoke_chunk = 10-25 races
pilot_chunk_max = 100 races
daily_public_web_cap = 250 requests
```

Because the public-site automation terms are still unspecified, this policy
does not authorize execution by itself.

## Raw storage

- No bulk HTML/PDF bytes committed to Git.
- Raw bytes: temporary artifact / private external storage.
- Git stores locator, SHA-256, retrieved_at, parser/decoder/schema versions,
  source correction epoch, and audit summaries.

## Next gate

Do not start the 2017 smoke test yet.

Resolve one of:

1. obtain/confirm a JRA-VAN Data Lab + JV-Link bulk path for private use, or
2. obtain sufficient JRA clarification/permission for automated historical
   public-site retrieval.

Until then:

```
HISTORICAL_2017_PILOT = NOT_STARTED
HISTORICAL_10Y_CANONICAL = NOT_COMPLETE
REAL_PROSPECTIVE = false
REAL_MONEY = BLOCKED
```
