# Source poll deployment checks

Run on 2026-09-16 for [#179](https://github.com/ahemberg/swedishpolls/issues/179) against the
arm64 host reached over the SSH alias `pinas`, with the application on
`http://192.168.68.70:8085`. These checks cover how the deployed release serves collected polls
while publication stays blocked. They certify nothing about the estimator, and they are not a
gated build step.

## What was deployed

The first rollout of `abbed1c` served no compiled frontend, so the checks were run twice: once on
that revision, and once on `432318b1f3bc2bcfc8b7b2c7a3e5a96c9df59489`, which carries the fix. The
current deployment is `432318b`, and every check below passes on it.

### First rollout

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

### Second rollout

Revision `432318b1f3bc2bcfc8b7b2c7a3e5a96c9df59489`, published by its own `Verify` run
([35089379822](https://github.com/ahemberg/swedishpolls/actions/runs/35089379822)) with all four
jobs green. The image is
`ghcr.io/ahemberg/swedishpolls:432318b1f3bc2bcfc8b7b2c7a3e5a96c9df59489-arm64`, digest
`sha256:f0367fb3c4ba429213f10f7804f3daeae3f1fa6f509bb7df35c4569ae9b7a968`; the running container
reports the same revision. It carries `/app/resources/static/.vite/manifest.json`, which the
previous image did not. The application started in 4.949 s, both retained snapshots survived, and
the previous `.env` was kept as `.env.rollback-179b`. The host's two `compose.yaml` deviations are
still in place.

## Resolved defect: the first image referenced no compiled frontend

The `abbed1c` image carried the compiled bundle at `/app/resources/static/assets/main-xI081smE.js`
and `main-JBBaYfX_.css`, and served both with HTTP 200. It did not carry
`static/.vite/manifest.json`. `SiteAssets` reads the entry from that manifest and serves no script
and no stylesheet without it, so every page was the basic HTML with no bundle reference at all:
`document.querySelectorAll('script[src]')` and `link[rel=stylesheet]` were both empty in a real
browser at 1440x1000. Every criterion needing the mounted page was therefore unverifiable on that
rollout.

The manifest was lost at the CI artifact boundary, not in the build. Locally
`target/classes/static/.vite/manifest.json` exists; the `verified-classes` artifact of run
35034925059 contained `static/assets/main-xI081smE.js` and the other three asset files but no
`static/.vite` entry, because `actions/upload-artifact` omits hidden files unless
`include-hidden-files` is set.

[#205](https://github.com/ahemberg/swedishpolls/pull/205) set that option and added an image smoke
assertion that the served overview references a module script and a stylesheet and that every one
of them is fetchable. The `verified-classes` artifact of run 35089379822 carries
`static/.vite/manifest.json`, the image job passed the new assertion on both architectures, and
the redeployed service serves `<script type="module" src="/assets/main-xI081smE.js">` and
`<link rel="stylesheet" href="/assets/main-JBBaYfX_.css">` on both `/` and `/en`.

## What the deployed release serves correctly

Startup and reachability. The application starts on Java 25.0.4 against PostgreSQL 18.6 in 5.036 s
on the first rollout and 4.949 s on the second, Flyway validated five migrations with nothing to
apply, and `/` answers 200 on both the loopback and the LAN address.

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

In the mounted chart the default one-year window draws 672 marker lines, all solid: 84 polls times
the eight parties each reports, with FI absent rather than zero. The one dashed line at that range
is the 4% threshold reference, not a marker. Switching to All history draws 11,663 solid and 263
approximate markers, the latter dashed `4 3`, which is the styling distinction under live data.

Party controls. The chart offers the four range buttons, a show-one-party-at-a-time control with
Show all beside it, and one button per party for all nine. Isolating the Left Party took the
All-history chart from 11,948 lines to 10,506 and restored it on a second press.

Complete details in the chart. Selecting a marker names the institute, the interview period, the
sample size and the party in the readout below the figure: "Demoskop 4 Jan 2010 – 11 Jan 2010
Sample 1,002".

Keyboard and touch. Every button is in the tab order with no negative `tabindex`, tabbing reaches
the range and party controls, and the global `:focus-visible` rule draws a 2px outline at a 2px
offset; the skip link reveals itself on focus. Neither layout overflows horizontally: at 1440 CSS
pixels the figure is 1040 wide and at 390 it re-renders to 347, with the full marker set in both
and the table at 50 rows. On the overview at 390 no control is under 24x24. On the polls page at
390 three targets are: the SV and EN language links at 18x30, the include-excluded checkbox at
13x13, and the range input at 129x16. They are reachable and operable, but they fall under the
WCAG 2.2 target-size minimum and are worth a separate look.

Complete details in the table. Each row carries pollster, company, method era and its evidence link,
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
