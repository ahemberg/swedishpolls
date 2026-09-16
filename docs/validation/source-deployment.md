# Source poll deployment checks

Run on 2026-09-16 for [#179](https://github.com/ahemberg/swedishpolls/issues/179) against the
arm64 host reached over the SSH alias `pinas`, with the application on
`http://192.168.68.70:8085`. These checks cover how the deployed release serves collected polls
while publication stays blocked. They certify nothing about the estimator, and they are not a
gated build step.

## What was deployed

Revision `abbed1cebbfb25c02ef9d7ddb0919ea098a6a922`, published by its own `Verify` run
([35034925059](https://github.com/ahemberg/swedishpolls/actions/runs/35034925059)) after
`Build and integration`, `Fallow`, `Model validation` and the image job all passed. The image is
`ghcr.io/ahemberg/swedishpolls:abbed1cebbfb25c02ef9d7ddb0919ea098a6a922-arm64`, digest
`sha256:2d5d9939299d5109dc0018c7fd47fb78b0a0158a8ade34b08471d9f2855df6ff`. The running container
reports the same revision in `/app/resources/git.properties`. The previous revision
`e9fcb921ae48de1015d72f65061cc48af6a88884` stayed in the host image store and its `.env` was kept
as `.env.rollback-179`, so the rollout was recoverable throughout.

The host keeps two deviations from the committed `compose.yaml`, both predating this deployment
and both preserved: the application port binds on every interface rather than loopback only, and
the PostgreSQL service uses the unpinned `postgres` tag. `compose.yaml` and `.env.example` are
identical between the deployed and the previous revision, so no configuration migration was
needed.

The release state is the real one: `src/main/resources/publication/model-freeze.json` reports
`release.status` `blocked` on the `development_gates` gate, with no waiver. No test release
fixture and no waiver were deployed. The publication volume held no files before or after the
rollout, and `publication` has no rows.

## Blocking defect: the deployed image references no compiled frontend

The image carries the compiled bundle at `/app/resources/static/assets/main-xI081smE.js` and
`main-JBBaYfX_.css`, and both are served with HTTP 200. It does not carry
`static/.vite/manifest.json`. `SiteAssets` reads the entry from that manifest and serves no
script and no stylesheet without it, so every page is the basic HTML with no bundle reference at
all: `document.querySelectorAll('script[src]')` and `link[rel=stylesheet]` are both empty in a
real browser at 1440x1000.

The manifest is lost at the CI artifact boundary, not in the build. Locally
`target/classes/static/.vite/manifest.json` exists; the `verified-classes` artifact of the run
above contains `static/assets/main-xI081smE.js` and the other three asset files but no
`static/.vite` entry, because `actions/upload-artifact` omits hidden files unless
`include-hidden-files` is set.

The fix is in this commit: `include-hidden-files` on the `verified-classes` upload, plus an image
smoke assertion that the served overview references a module script and a stylesheet and that each
one is fetchable. The image job runs only on a push to `main`, so neither the option nor the
assertion is exercised before the fix merges. The defect therefore stays open on the deployment
until the rebuilt image is deployed and the checks below are re-run.

Consequence for acceptance: every criterion that needs the mounted page is unverifiable on this
release. The source chart never draws, so the interval charts on the homepage, the party
controls, the solid and approximate marker styling, and keyboard and touch usability at narrow
and wide layouts cannot be checked on the deployment. The server-rendered half of each of those
criteria was checked and passes.

## What the deployed release does serve correctly

Startup and reachability. The application started in 5.036 s on Java 25.0.4 against PostgreSQL
18.6, Flyway validated five migrations with nothing to apply, and `/` answers 200 on both the
loopback and the LAN address.

Both languages and the basic HTML. `/` and `/en` each return a complete page before any script
runs, carrying the heading, the source-data timestamp, and an eight-row latest-polls table with
pollster, fieldwork period, sample size and reported shares. Neither page mentions an
unavailable estimate, a release verdict or an internal plan. Navigation lists only Overview and
Polls; every family that needs model results is absent. The language switch maps `/` to `/en`
rather than prefixing a path.

Temporary redirects. `/mandat`, `/regeringsunderlag`, `/institut`, `/metod` and
`/parti/{party}` answer 302 to `/`; `/en/seats`, `/en/coalitions`, `/en/pollsters`, `/en/method`
and `/en/party/{party}` answer 302 to `/en`. An unknown party slug answers 404 rather than
redirecting.

Last-year default and marker kinds. The bootstrap reports `defaultRange` `oneYear`, resolved to
2025-09-11 through 2026-09-11, with `sinceElection`, `fourYears` and `all` offered beside it.
Over the full history the chart data carries 1,442 observations: 1,410 with a reported period,
32 with `approximatePeriod` true, and none whose fieldwork is a single day. The live archive
therefore exercises the solid and approximate cases but not the one-day case, which
`markerSpan` handles through `MINIMUM_SPAN` and remains covered only by the repository tests.

Complete details. Each table row carries pollster, company, method era and its evidence link,
survey type, publication date, fieldwork period, the approximate-period flag, sample size, the
denominator note, coverage period, unmodeled components, reported and display shares, the
remainder, eligibility and any exclusion reasons.

Filtering and overlap boundaries. `from=2026-09-01&to=2026-09-10&institute=Novus` returns the
three Novus polls overlapping that window. Boundaries are inclusive at both ends: the poll
interviewing 2026-09-07 through 2026-09-10 matches a one-day window on either endpoint and no
window outside it. An unsupported `party` filter on the CSV route answers 400 naming the
parameter; an unknown snapshot answers 404.

Paging. 1,442 eligible polls page at 50 per page across 29 pages, the last holding 42, with no
row repeated between pages. A page number past the last clamps to 29 rather than erroring.

Downloads and the shared snapshot. The table, the chart and the CSV link all name snapshot 2,
captured 2026-09-16T08:38:00Z with SHA-256 `133f568d…`. The CSV exports every matching row
rather than the current page: 1,442 data rows unfiltered, and the same three rows as the table
under the Novus filter. A range change on the chart keeps the visit's snapshot, and snapshot 1
is still served unchanged beside it.

Missing shares and the remainder. An unreported share is empty in the CSV and rendered as
"Missing" and "Saknas" rather than zero. The remainder column is labelled
`Other parties, including FI`.

Include-excluded. The default view holds the 1,442 eligible polls; `includeExcluded=true`
returns all 2,672 and retains each exclusion reason, including `exit_or_election_day`,
`missing_sample_size`, `missing_share:SD`, `outside_supported_history` and
`duplicate_natural_key`.

Refresh and retained data. `PublisherScheduler` declares one
`@Scheduled(cron = "0 0 3 * * *", zone = "Europe/Stockholm")` refresh and a startup refresh that
runs only when no snapshot is active. The restart onto this revision kept both retained
snapshots and collected nothing. Roughly forty page, chart and CSV requests afterwards left the
snapshot count at two and logged no upstream fetch; nothing in the `web` package references the
publisher or a refresh. The empty-database startup and upstream-failure cases were taken from
the isolated tests below rather than by clearing the live archive.

## Implementation evidence relied on

From the `build-reports` artifact of run 35034925059, on the deployed revision:
`PageIT` 57 tests, `SourcePagesIT` 7, `PublicationIT` 9 with one skip, `RefreshSchedulingIT` 4,
`RefreshRestartIT` 1, `SnapshotIngestIT` 7, `ApiV1IT` 15, `ApiContractIT` 1,
`ApplicationIT` 1, `CoveragePeriodIT` 1 and `ElectionReferenceIT` 1, all with no failures and no
errors. The released-fixture overlay case is
`PageIT.currentEstimateChartsCarryPollMarkersFromTheVisitsSourceSnapshot`, and the immutable
publication cases are in `PublicationIT`. The single skip is
`PublicationIT.benchmarkTheFullPipelineOnTheSelectedHost`, an opt-in benchmark behind the
`coalition.benchmark` system property rather than an acceptance check.
