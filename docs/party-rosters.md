# Party rosters and the candidate FI segment

Checkpoint 3 of [issue #17](https://github.com/ahemberg/swedishpolls/issues/17)
records which parties are represented individually, composes an eligible poll
into the components of one coverage period, and documents the candidate FI
collection-date boundaries. It enables no individual FI estimate: final support
dates require validated fits in the estimator ticket.

## Coverage periods

Flyway V4 stores each coverage period with its effective dates, roster, owner
decision and validation state. The
[approved coverage decision](https://github.com/ahemberg/swedishpolls/issues/12#issuecomment-5575789888)
is the canonical policy. A roster change needs a new row with its own effective
period and decision URL, never an edit to an existing row. The database rejects a
roster that drops one of the eight parties, adds an unapproved party, or declares
FI membership inconsistently, and prevents two validated periods from overlapping.

| Period | Collection dates | Roster | Validated |
| --- | --- | --- | --- |
| `eight_party_2010` | 2010-01-01 onwards | S, M, SD, V, C, KD, L, MP | yes |
| `fi_candidate_2014_2018` | 2014-04-09 to 2018-09-07 | the eight parties plus FI | no |

The candidate segment lies inside the validated eight-party period. Until the
estimator validates its fits, every poll resolves to the eight-party roster and
FI stays inside OTHER. Source observations and their missingness are archived
independently of this membership.

## Composing a poll

Outside an FI segment the components are the eight parties and OTHER, where
`OTHER = 100 - sum(eight)`. FI is already inside that remainder; adding it again
would double-count it. Inside an FI segment the components are the eight parties,
FI and `RESIDUAL = 100 - sum(eight) - FI`. Both rosters therefore give the same
comparable remainder, the support outside the fixed eight, and FI is subtracted
exactly once.

A composition is either complete or excluded with reasons; an excluded poll
carries no components. It inherits the ingest exclusion reasons and adds
`outside_coverage_period:<id>` for a collection window outside the period,
`missing_share:FI` for a poll without FI inside an FI segment, and
`negative_residual` when a reported FI share exceeds the eight-party remainder.
Missing FI never means zero, and membership never changes poll by poll: a poll
missing FI is excluded from the FI composition and remains usable for the
eight-party one.

## Candidate FI boundaries

The boundaries are the outermost collection dates of the eligible FI polls in the
pinned snapshot: collection begins on 2014-04-09 and ends on 2018-09-07, two days
before the 2018 election. They come from collection dates rather than publication
dates or whole calendar years, and they are evidence for the estimator review,
not an approved support range. Five eligible polls have collection windows that
start before 2014-04-09 and end inside the segment; none reports FI.

The segment holds 388 eligible FI polls from ten institutes: Demoskop, Inizio,
Ipsos, Novus, SCB, Sentio, Sifo, Skop, United Minds and YouGov. FI shares run
from 0.6 to 4.4 with a median of 2.1, and five observations reach 4 or above.

| Collection year | Eligible FI polls | Institutes |
| --- | ---: | ---: |
| 2014 | 74 | 10 |
| 2015 | 81 | 8 |
| 2016 | 80 | 8 |
| 2017 | 78 | 8 |
| 2018 | 75 | 7 |

Merging the collection windows leaves one gap wider than a month: no FI poll
collects between 2016-07-05 and 2016-08-01, 27 days. July 2016 is also the only
month whose FI observations come from a single institute, Demoskop. The remaining
18 gaps are 15 days or shorter. Novus stops reporting FI after August 2017 and
SCB contributes one observation, so institute coverage thins towards the 2018
election even though the poll count stays stable.

Of the 439 eligible polls collected inside the segment, 51 omit FI and are
excluded from the FI composition: 17 Novus, 10 Inizio, nine Sentio, eight SCB,
four Ipsos, two Sifo and one Demoskop. These cluster late, with 25 in 2018 and
12 in 2017 against seven, five and two in the earlier years. Two further FI rows
are ineligible at ingest for a missing sample size, both YouGov, published
2016-08-19 and 2017-07-16. The 2006 and 2014 SVT/VALU rows are excluded as
exit or election-day polls.

## Isolated 2022 observations

Two Sentio polls report FI after the segment, collected 2022-06-16 to 2022-06-21
at 0.4 and 2022-08-25 to 2022-08-29 at 0.3. No eligible poll reports FI between
2018-09-08 and 2022-06-15. Both rows stay archived, remain eligible under the
eight-party roster, and are outside every FI period. They are preserved source
observations; no individual FI curve is inferred from them and the 2019 to 2021
gap is not bridged.

## Verification

`RosterTest` checks both compositions on synthetic rows and derives every count
and boundary above from the pinned `audit.csv` snapshot. `CoveragePeriodIT`
upgrades an isolated PostgreSQL schema from checkpoint 2, checks the stored
rosters, effective periods, decision URLs and the unvalidated candidate, and
proves the roster and overlap constraints reject invalid rows.

```sh
./mvnw -Dtest=RosterTest test
# With DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD for PostgreSQL 18:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=CoveragePeriodIT
```

[Snapshot ingest](ingestion.md) archives the source observations these
compositions read, and [election references](election-references.md) stores the
official outcomes separately. The public API contract is the last checkpoint
of #17.
