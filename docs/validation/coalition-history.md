# Coalition history validation

Issue #31 adds support histories for the 255 nonempty subsets of
`S,M,SD,V,C,KD,L,MP`. Every subset sums unrounded shares within each daily joint
draw before calculating its mean and pointwise 95% interval. Party summaries use
the same ensemble. Dates run through the existing parallel pool; only summaries
and the final day's draws are retained.

## Registered numerical study

[The protocol](coalition-history-protocol.json) registered the date-selection
rule, three seeds, 10,000 draws, an independent 40,000-draw reference, and maximum
mean and endpoint errors of 0.12 percentage points before acceptance. It selects
each fit's first, middle and last days, plus its smallest and largest smoothed
covariance norms. Coincident dates are checked once.

[The development study](coalition-precision-study.json) passed on both rosters.
It checked 6,120 subset summaries across eight distinct dates. The largest mean
error was 0.0303893 percentage points; the largest endpoint error was 0.1128574.
The study uses the pinned source through the registered development cutoff. It
does not rerun the reserved release audit or change that audit's blocked verdict.

Reproduce the study explicitly:

```sh
./mvnw -Dcoalition.study=true -Dit.test=CoalitionStudyIT test-compile failsafe:integration-test failsafe:verify
```

The study writes `target/coalition-precision-study.json`. Candidate publication
repeats the registered check on its own fitted spans and stops on failure. The
ordinary small-draw pipeline tests use a permissive fixture budget; those tests
provide lifecycle coverage and are not precision evidence.

## Deployment-host benchmark

The owner selected this local machine, with 16 GB installed RAM and 6 GB
available. The limits are 1,800 seconds for the pipeline and 6,000,000,000 bytes.
The benchmark uses the complete archived source and production numerical rules,
including 10,000 final-day draws. It runs the real publisher, document writes,
image rendering, staging and atomic switch in a disposable test database.
Only the isolated fixture's release verdict is changed to exercise publication.
The running service and its release verdict are untouched.

Run after compilation and the ordinary verification suite:

```sh
./mvnw verify
python3 scripts/benchmark-coalition-history.py
```

The script caps the pipeline JVM heap at 4 GiB and Maven's heap at 512 MiB. Once
per second it measures the benchmark process group's combined RSS and the memory
of containers started during the run. Shared process pages can be counted twice.
It terminates the run if measured usage exceeds 6 GB or elapsed time exceeds
30 minutes. Run it without other container starts. The pipeline report also
records the JVM's kernel high-water mark. Reports are written to
`target/coalition-pipeline-benchmark.json` and
`target/coalition-benchmark-host.json`.

The completed run on `alex-lenovo`, commit
`030d9c1774318a90cc54bb513a64d470ef3d81ea`, passed. The
[pipeline report](coalition-pipeline-benchmark.json) records 449.525 seconds,
`PUBLISHED`, and all 26 rendered images. The
[host report](coalition-benchmark-host.json) records 467.668 seconds including
test startup and a sampled aggregate peak of 5,271,355,392 bytes, below the
6,000,000,000-byte limit. The JVM high-water mark was 3,612,236 KiB.

The persisted artifact's canonical JSON is 109,201,059 bytes per language.
The existing document layout stores Swedish and English copies, totaling
218,402,118 logical JSON bytes before database compression, row/index overhead
and WAL. The report records the canonical artifact hash. This measures logical
artifact storage, not the database's physical disk footprint.

The full-source candidate precision gate also passed, checking 6,120 summaries
across eight selected dates in the supported eight-party coverage period. Its
largest endpoint error was 0.0766024 percentage points. The separate registered
development study above covers both historical rosters.

## Behavioral checks

Java tests cover negative correlation with a fixed sum, varying-sum quantiles,
all subset means, singleton parity, daily estimates between polls, final-day
reproduction, both-roster remainder accounting, all 6,561 disjoint assignments,
strict API filters, ETags, missing values, huge ranges, sampling boundaries,
artifact-write failure, restart and retaining a pinned publication after the
current publication changes.

The bilingual page integration test checks actual served HTML and its pinned
bootstrap, including the interval table, and requires the complete history to
equal the paired HTTP API response for the same publication. Browser inspection
uses that HTML and the current built assets. Desktop and narrow mobile layouts, light and dark
appearance, keyboard and mouse scrubbing, fixed latest summaries/colors, and the
accessible table were checked. Controlled reversed responses verified that a
late request cannot replace a newer range; failed requests retain the previous
chart and show a retry message. These response-order checks exercise the client;
the API behavior is checked separately by Java tests.

The first-publication resolver retains `estimates_unavailable`, as explicitly
confirmed by the owner on #31. `latest.date` is the actual last estimated day;
`lastFieldworkDate` separately records the snapshot's latest fieldwork end.

The full ordinary suite passed all 228 unit tests and 88 enabled integration
tests. Its only build failure was two SpotBugs defensive-copy findings, fixed
without suppression. The subsequent targeted `verify` run passed the selection
and API tests, served-page/API parity, the real precision study, and all frontend,
formatting, PMD and SpotBugs gates. Fallow reported zero findings. Separate
standards and specification reviews checked the completed implementation; their
correctness findings were fixed before the benchmark.
