# Snapshot ingest

Checkpoint 1 of [#17](https://github.com/ahemberg/swedishpolls/issues/17) implements
snapshot capture and baseline poll eligibility. It does not publish estimates.

## Fetch and archive

Spring checks the approved `MansMeg/SwedishPolls/master/Data/Polls.csv` at startup
and every 30 minutes. `polls.source-url` overrides the URL for integration tests;
`polls.ingest.enabled=false` disables the worker. A PostgreSQL transaction advisory
lock allows one worker at a time. Fetches have a 10-second connection timeout and
a 30-second request timeout. The HTTP body subscriber stops downloads above 16 MiB. Errors are logged and the next scheduled check retries.

ETag and Last-Modified validators survive restarts. A 304 updates the successful
check time. A 200 with identical SHA-256 bytes updates validators without parsing
or inserting rows again. A return to previously archived bytes reactivates that
snapshot. Missing validators on a 200 clear the old values. Redirects, HTTP errors,
partial responses, invalid UTF-8, missing or duplicate headers, inconsistent row
widths, malformed CSV, empty snapshots and bodies over 16 MiB fail the check.
Structural completeness cannot detect an upstream file cut exactly at a valid row
boundary. Row-count decreases are allowed because source deletions are meaningful.

`poll_snapshot` stores exact bytes, source URL, SHA-256, capture time and parser
version. `snapshot_poll` stores every parsed row and its assessment as JSONB,
keyed by snapshot and CSV record number. Decimal values remain arbitrary-precision
JSON numbers; raw field strings preserve formatting, extra columns and missingness.
`poll_source` stores validators, the last successful check time and the active
snapshot pointer. These are source timestamps, not publication timestamps.

One transaction writes all rows and then switches the pointer. A parser, fetch or
storage failure rolls back the attempt. `active_source_poll` joins only the active
snapshot. Corrected shares, changed dates/sample-size keys and deleted rows cannot
leave older observations active. Older snapshot bytes and assessments remain
available by snapshot ID; no natural-key upsert or archive deletion occurs.
A future parser-policy change must version its assessments explicitly, rather than
rewriting archived assessments or assuming a content hash identifies a new policy.

## Eligibility, version 1

The baseline requires S, M, SD, V, C, KD, L and MP, a positive integer sample size,
usable collection dates, and source company/institute identities. Missing shares
remain null. OTHER is exactly 100 minus those eight shares, so FI is already in
this remainder. Uncertain is separate and never enters that sum. FI observations
are retained independently; FI-period rosters belong to checkpoint 3.

All reported shares must be in 0–100. Invalid numbers, negative remainders and
incomplete required compositions receive exclusion reasons, without flooring,
rescaling, rounding or silently clipping dates. Exact zero is valid. Invalid dates,
reversed collection windows and publication before collection end are quarantined.
This also applies to approximate windows; approximation does not repair a chronology
conflict. The original `approxPeriod` indicator remains in the raw fields.

Rows beginning before 2010-01-01 remain archived with
`outside_supported_history`. A window crossing that boundary is not truncated.
Unknown publication dates are allowed for otherwise eligible corrected-history
polls; `publicationTimeEligible()` excludes them from publication-time evaluations.
That flag alone does not establish an as-known source vintage or a fold cutoff.

Duplicate natural keys use institute, publication date, both collection dates and
sample size. Missing publication dates compare equal; numeric sample-size spelling
does not create a new identity. All rows in a duplicate group remain archived and
are excluded with `duplicate_natural_key`, avoiding an arbitrary choice between
conflicting rows. Repeated snapshots are deduplicated by content hash.

SVT/VALU, TV4, `Demoskop valdag` and `Zapera exit` are classified as exit/election-day
polls using both source identities and excluded. The documented Inizio continuation
and pre-November-2019 Demoskop era retain separate identifiers and a pinned evidence
URL. The Demoskop boundary uses publication date, matching the upstream helper.
Unknown Demoskop publication dates have no assigned method era. Other undocumented
method eras remain null; no phone/web mode is inferred from a brand.

Denominator notes retain the upstream distinction between respondents and Sentio
party preferences, including uncertainty in older Sentio data. Ipsos values may
already have been normalized upstream; ingestion does not try to reverse this.
Sample size is not asserted to be an effective multinomial sample size.

## Evidence and checks

The CC0 fixture `src/test/resources/polls/audit.csv` comes from
[MansMeg/SwedishPolls at f0390c0](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/Data/Polls.csv),
SHA-256 `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`.
Credit belongs to the source contributors, with data originating from Novus.
The [pinned audit](research/data-audit.md) explains the source semantics, method-era
evidence and the limitations of the separate upstream Sources catalogue. The poll
CSV has no per-poll report URLs; none are invented or joined from ambiguous keys.

`PollCsvTest` checks the pinned 2,650 rows, the audit's 1,665 post-2006 records with
required dates/sample sizes, 33 missing-SD records, 20 unknown publication dates,
seven rows with precision beyond two decimals, and five zero remainders. This
post-2006 audit cohort is distinct from the approved 2010 supported-history start.
Tests also cover malformed documents, duplicate keys and exclusion reasons.

`SnapshotIngestIT` uses a local HTTP server and isolated PostgreSQL schemas to
check replacement/deletion, old snapshots after restart, conditional requests,
hashing, failed responses, transaction rollback, worker exclusion and scheduling
in the packaged application. No test fetches the live polling source.

```sh
./mvnw -Dtest=PollCsvTest test
# With DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD for PostgreSQL 18:
./mvnw -Dtest=PollCsvTest -Dit.test=SnapshotIngestIT verify
```

Election references, FI support evidence and the public API contract remain the
later checkpoints of #17.
