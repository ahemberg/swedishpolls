# Frozen v1 API contract

Checkpoint 4 of [issue #17](https://github.com/ahemberg/swedishpolls/issues/17)
freezes the versioned read-only surfaces, their schemas and the request rules that
[publication work](https://github.com/ahemberg/swedishpolls/issues/21) must implement.
Nothing here is served yet: the checkpoint freezes the contract, and no endpoint,
publication or estimate exists in this repository.

[`api/v1/contract.json`](../src/main/resources/api/v1/contract.json) is the manifest.
Each surface names its path, media type, query parameters, cache class, error codes
and one example payload under `api/v1/examples/`. The examples show the frozen shape
with illustrative numbers; only the poll rows and election references are real data.

## Surfaces

| Surface | Path | Example |
| --- | --- | --- |
| Publication metadata | `/api/v1/publication`, `/api/v1/publications/{publicationId}` | [`examples/publication.json`](../src/main/resources/api/v1/examples/publication.json) |
| Latest estimate | `/api/v1/estimates/latest` | [`examples/estimates-latest.json`](../src/main/resources/api/v1/examples/estimates-latest.json) |
| Estimate history | `/api/v1/estimates/history` | [`examples/estimates-history.json`](../src/main/resources/api/v1/examples/estimates-history.json) |
| Filtered polls | `/api/v1/polls` | [`examples/polls.json`](../src/main/resources/api/v1/examples/polls.json) |
| Matching download | `/api/v1/polls.csv` | [`examples/polls.csv`](../src/main/resources/api/v1/examples/polls.csv) |
| Institutes and house effects | `/api/v1/institutes` | [`examples/institutes.json`](../src/main/resources/api/v1/examples/institutes.json) |
| Election references | `/api/v1/elections` | [`examples/elections.json`](../src/main/resources/api/v1/examples/elections.json) |
| National seats | `/api/v1/seats` | [`examples/seats.json`](../src/main/resources/api/v1/examples/seats.json) |
| Preset coalitions | `/api/v1/coalitions` | [`examples/coalitions.json`](../src/main/resources/api/v1/examples/coalitions.json) |

Errors have their own examples in
[`examples/errors.json`](../src/main/resources/api/v1/examples/errors.json).

## Publication and run identity

The publication response carries the publication id, model run, source snapshot,
asset links and the three distinct times: last fieldwork date, source-check time
and publication time. `stale` marks a publication kept live after a failed update.

Every other response repeats `publication` as the publication id, run id and
snapshot id. A page resolves the publication once and passes it back through the
`publication` parameter, so an ingestion correction cannot change a pinned page.
Poll responses read that publication's snapshot rather than the active one.
A permanent publication link returns that publication's saved results or
`unknown_publication`; it never falls back to the current publication.

## Coverage periods, rosters and OTHER

Estimate responses list every coverage period with its inclusive effective dates,
roster, explicit OTHER membership, individual-FI flag, validation state and owner
decision. `eight_party_2010` is validated and puts FI inside OTHER;
`fi_candidate_2014_2018` is the unvalidated candidate segment, so its individual FI
estimate stays null with a reason rather than zero. `comparableRemainder` is the
support outside the fixed eight and includes FI in every period, which is what an
explicit cross-boundary comparison uses. The stored periods are the source of
truth; [party rosters](party-rosters.md) describe them.

## History, ranges and sampling

History is one columnar representation: a `dates` array, a `coveragePeriodByDate`
array of the same length and one `series` entry per component with equal-length
`mean`, `lower` and `upper` arrays. Unsupported dates are null. Date bounds are
inclusive, `step` is bounded to 1, 3 or 7, sampling retains the last requested
supported date, and no step refits the model. `boundaries` marks each membership
boundary so a break is never rendered as voter movement. `change30d` stays inside
one publication and returns `available: false` with a reason when its comparison
date is unsupported.

## Polls and the CSV download

The poll table and the CSV download declare identical filters in the manifest, and
the download applies them without paging. Both preserve source precision exactly as
archived: `26` stays `26`, a missing FI share is empty in CSV and null in JSON, and
neither is filled with zero. `other` is the eight-party remainder, which already
contains FI. Excluded rows appear only with `includeExcluded=true` and carry their
exclusion reasons.

## Seats, coalitions and elections

Seat responses keep the integer point allocation separate from posterior mean seats
and intervals, and carry per-party threshold probabilities from the model rather
than from rounded seats. OTHER is excluded from the allocation. The allocation rule
names the election year being approximated, its first divisor, the inclusive 4%
threshold, the documented tie order and the omitted constituency exceptions.

Coalition responses carry the ten approved memberships, a 175-seat majority line,
the overview defaults and probabilities from the same joint draws; an exact tie
counts as neither side winning. Election responses report integer votes and official
seats with the grouping the estimate is compared against, keeping FI and RESIDUAL
separate as [election references](election-references.md) stores them.

## Caching and errors

Current responses cache for at most 300 seconds with content-based ETags, so query,
schema and translation differences validate separately. Permanent publication links
and versioned assets are immutable and cache for a year. Unknown versions, routes
and publications return 404, invalid filters return 400 with the offending
parameters, and a request needing a publication before the first one exists returns
503 `estimates_unavailable` with no estimate fields.

## Verification

`ApiContractTest` checks the manifest, every example and the shared rules: surface
coverage, pinning parameters, error codes, publication and run identity on every
dependent response, coverage periods and null unsupported values, columnar history
with inclusive ranges and bounded sampling, the ten coalition memberships, point
seats separate from posterior means, and this document naming every frozen path.
It parses the poll example against the pinned `audit.csv` snapshot, so the JSON and
CSV examples must keep real source precision. `ApiContractIT` checks the examples
against the stored coverage periods, election references and allocation rules.

```sh
./mvnw -Dtest=ApiContractTest test
# With DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD for PostgreSQL 18:
./mvnw test-compile failsafe:integration-test failsafe:verify -Dit.test=ApiContractIT
```

[Snapshot ingest](ingestion.md), [election references](election-references.md) and
[party rosters](party-rosters.md) are the earlier checkpoints this contract exposes.
