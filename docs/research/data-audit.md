# Poll provenance, ingestion, and Swedish seat rules

Research resolution for [Audit poll provenance, ingestion edge cases, and Swedish seat rules](https://github.com/ahemberg/swedishpolls/issues/2). Investigated 2026-09-07. This is an initial evidence audit, not approval of the statistical or product policy.

## Proposal being examined

The supplied v1 spec fetches `MansMeg/SwedishPolls/Data/Polls.csv` every 30 minutes with ETag/SHA detection, archives raw bytes, and upserts `(house, PublDate, collectPeriodFrom, collectPeriodTo, n)`. It skips missing collection endpoints or sample size, stores earlier polls, and models from 2006-09-01. `house` is the canonical institute; shares are stored as `numeric(5,2)`. Nine model parts are M, L, C, KD, S, V, MP, SD and OTHER; FI remains in the remainder `100 − sum(eight parties)`. Missing values are null, and compositions get a small numerical floor. Overlaps reduce daily effective sample size using concurrent same-house polls. All institutes default to included. Election seeds cover 2010, 2014, 2018 and 2022.

Seats use national shares, exclude parties below 4% and OTHER, distribute 349 seats with divisors 1.2, 3, 5, …, and resolve ties by party order. The spec proposes a 2022 official-result comparison, attributing any difference to constituency effects. It also proposes comparison with a historical pollofpolls.se table. These assumptions need the corrections below.

## Pinned evidence and reproduction

Source commit: [`f0390c05854d87bbf21db9d31c6431ffa0f07f7e`](https://github.com/MansMeg/SwedishPolls/commit/f0390c05854d87bbf21db9d31c6431ffa0f07f7e), committed 2026-09-07 05:09:49 UTC. CSV SHA-256: `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`. [Pinned CSV](https://raw.githubusercontent.com/MansMeg/SwedishPolls/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/Data/Polls.csv).

Run `python3 docs/research/audit-polls.py` (stdlib only), or supply a downloaded CSV path. [Audit script](audit-polls.py) and [captured output](data-audit-output.txt) provide the calculations below and a runnable 2022 allocation assertion. No redistributed CSV is needed; the download is commit pinned. The report's “post-start” cohort means known collection end on or after 2006-09-01; the final specification must decide how crossing-start windows are handled.

## Source ownership, maintenance, and semantics

The [pinned README](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/README.md) explicitly releases data under CC0 and code under MIT. GitHub's repository API returns `license: null`, so that metadata alone gives the wrong impression. Retain visible credit and source links for provenance even though CC0 is permissive. The dataset originated from Novus in 2013 and receives community corrections; this is not an institute publication feed or a timeliness SLA. The current commit combines new 2026 polls with corrections to historical Gallup data: archival revisions are a present-day concern.

The README describes `n` as respondents, with a different party-preference denominator for Sentio and uncertainty about older entries. It also documents normalization of Ipsos totals that otherwise reach 101. Thus raw source shares can already be transformed. It does not establish a uniform weighting/design-effect interpretation of `n`; the model cannot safely assume it is an effective multinomial sample size. `Uncertain` remains separate from the party composition, but institute reports are needed to establish denominator consistency in contentious cases.

The [sources file](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/Sources/sources.csv) has 481 entries, including archived URLs, against 2,650 poll rows. This count is not a validated join/coverage percentage: keys and dates can be absent or duplicated. Preserve those links where available, without promising complete poll-level provenance.

## Quantified CSV findings

All counts below derive from the pinned CSV and script, not estimates.

| Check | Entire CSV | Post-start cohort |
|---|---:|---:|
| Rows | 2,650 | 1,684 |
| Missing sample size | 152 | 18 |
| Missing collection start | 337 | 1 |
| Missing collection end | 336 | 0 by cohort definition |
| Missing publication date | 351 | 20 |
| Retained by proposed date/sample rule | 2,247 | 1,665 |
| Retained but missing a modelled party | 615 | 33 (all SD) |
| Duplicate proposed-key groups before skipping | 29 | 0 |
| Extra rows in those groups | 90 | 0 |
| Approximate collection periods before skipping | 50 | 49 |
| Retained publication date before collection end | 20 | 10 |
| Retained rows overlapping another same-house window, inclusive | 325 | 213 |
| Same-house days with more than one poll | 618 | 312 |
| Maximum concurrent same-house polls | 7 | 7 |

Retained sample sizes are positive integers, range 731–12,909; no reversed retained date windows were found. All retained post-start reported shares are within 0–100. Among complete eight-party retained compositions, OTHER ranges from 0 to 6.8%, with five exact zeros and no negatives. This does not justify treating a future negative remainder as a valid zero: quarantine invalid inputs before numerical flooring.

There are 20 retained post-start rows with null publication date. All duplicate keys in the entire file have at least one missing key component, and none survive the proposed required-fields filter. PostgreSQL's ordinary unique constraint still does not deduplicate repeated inserts with null publication dates. Use explicit null equality if keeping this identity (PostgreSQL 16 supports `UNIQUE NULLS NOT DISTINCT`), and test repeated import. [PostgreSQL constraints documentation](https://www.postgresql.org/docs/16/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS).

More fundamentally, correcting `n` or a date changes the proposed key, leaving the superseded row present under a pure upsert. A raw snapshot must define the active input set; distinguish replacement/deletion from addition and keep prior snapshots separately. A per-row `source_sha` pointing only to its latest update does not by itself reconstruct which unchanged rows belonged to a historical run. The spec also names `raw_poll_file` without providing its DDL.

Seven retained post-start rows have party shares more precise than two decimal places. `numeric(5,2)` silently changes these source values; keep raw precision for raw poll exports and computational provenance. Derived chart rounding can be separate.

The 33 missing-SD polls persist through 2007 (e.g. SCB publication 2007-12-13). Starting in September 2006 does not create complete nine-part observations. Decide exclusion versus an explicit missing-component likelihood; never substitute zero for unreported SD. Historical raw storage also needs to tolerate incomplete rows: the proposed non-null poll schema cannot store every earlier raw record as a parsed poll, although raw-file storage can retain it.

One non-approximate inconsistency is Sentio publication 2011-03-07 with collection 2011-03-14–27. Approximate month-long Sentio windows explain some other publication-before-end cases. Define quarantine/correction policy with reasons; do not automatically “repair” every such row by clipping dates.

## Institute identity, survey type, and overlap

There are 15 source houses, 14 in the post-start cohort; the model's example H=8 is not the default input. `Company` aliases include TEMO/Synovate → Ipsos and Zapera → YouGov. Preserve both fields.

The upstream [Demoskop/Inizio helper](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/RPackage/R/handle_demoskop_inizio_merger.R) explicitly separates old Demoskop from the Inizio series that continued under the Demoskop name from November 2019. A constant house effect across one canonical brand is not a constant measurement process. Keep source identity distinct from model series/era. The CSV contains no method column; populating phone/web/mixed requires additional dated, sourced metadata rather than inference from brand.

The dataset also contains SVT VALU, TV4 election-day entries, `Demoskop valdag`, and `Zapera exit`. Five SVT rows and one TV4-house row are post-start; “all included” mixes exit polls with ordinary intention polls. In particular, SVT VALU 2014 spans September 8–14 and has n=12,909. Back-projecting it across that interval would use election-day information earlier than it was published. Classify survey type and decide eligibility explicitly, including entries whose canonical house resembles an ordinary pollster.

Overlap is common, but date overlap alone does not prove that respondents are duplicated. Upstream's [tracking helper](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/RPackage/R/handle_tracking_polls.R) chooses latest non-overlapping polls and defaults to allowing a one-day overlap. The proposed `1/k` treatment is a different statistical assumption. Use the measured overlaps to test sensitivity against a non-overlapping baseline; do not label the proposed correction empirically validated.

## Historical availability and leakage

Repository history for this CSV begins with [Initialization, 2014-07-08](https://github.com/MansMeg/SwedishPolls/commit/05a378bcf3449798401c399ec7d27d4cc7812e91). Commit snapshots permit a repository-as-known reconstruction thereafter, not proof of exactly what an institute published at every moment. The last repository commit before the end of 2022-08-31 Stockholm time is [`60c569b1775948693838c67e862e355a2bcaf8c6`](https://github.com/MansMeg/SwedishPolls/tree/60c569b1775948693838c67e862e355a2bcaf8c6). Pin candidate vintages and inspect actual CSV membership; commit dates alone do not establish publication timing.

Backtests must filter publication availability as well as collection periods, and specify handling of unknown publication dates and later corrections. A fit ending 2022-08-31 using today's revised data is a retrospective test, not an as-known-at-the-time evaluation. Polls spanning the cutoff cannot be truncated and treated as independently observed partial results. Archive observed revisions going forward.

## Official results and seat interpretation

The authority supplies [official historical summaries for 2010, 2014 and 2018](https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/tidigare-valresultat), a [2022 summary](https://www.val.se/valresultat-och-statistik/riksdags--region--och-kommunval/valresultat-2022), and [raw results/downloads](https://www.val.se/valresultat-och-statistik/statistik-och-data/radata-fran-val-2002-2022). These are suitable sources for seeds; raw data can be used with Valmyndigheten attribution. Prefer final integer party vote counts to rounded percentages, map historical Folkpartiet to L, and derive OTHER consistently with the nine-part model. A schema storing FI separately must not count it twice in OTHER. This initial audit verifies source availability; it does not deliver all election seed fixtures.

The default start date precedes the 2006 election: a “most recent election before start” initializer needs 2002, which the proposed seeds omit, or an explicit first-poll fallback. The 2006 result is also needed as a historical chart marker. Both have official results in the historical source.

The [authority's allocation rules](https://www.val.se/det-svenska-valsystemet/rostrakning-och-mandatfordelning/sa-fordelas-mandaten) specify 310 fixed seats plus 39 adjustment seats, national 4% eligibility or 12% in a constituency for its fixed seats, and divisors 1.2, 3, 5, … . The [allocation manual](https://www.val.se/download/18.162047b519a91d05331183a9/1761747515752/manual-mandatfordelning-val-v785-05.pdf) describes the 2018 rule changes, including divisor reduction from 1.4 to 1.2 and return of excess fixed seats. Historical seat checks need the rules for that election.

Ties under election law are resolved by lot, not party order; see [Vallagen, chapter 14 section 2](https://www.riksdagen.se/sv/dokument-och-lagar/dokument/svensk-forfattningssamling/vallag-2005837_sfs-2005-837/) and the [government's explanation of that provision](https://www.riksdagen.se/sv/dokument-och-lagar/dokument/proposition/starkt-skydd-for-valhemligheten_h503286/html/). A deterministic tie-break may be a disclosed approximation for reproducible displayed estimates, but cannot be called the official rule.

Our national calculation with the authority's two-decimal 2022 percentages produces exactly S 107, SD 73, M 68, V 24, C 24, KD 19, MP 18, L 16: zero delta for every party. The [2022 decision, total-allocation appendix](https://resultat.val.se/protokoll/protokoll_Val_20220911_00_RD.pdf) explicitly computes total allocation treating the country as one constituency. Therefore a discrepancy in this fixture should trigger investigation of code, inputs, or rounding—not automatic attribution to constituencies.

A national-share allocation can honestly describe a national seat estimate under its threshold assumptions. It cannot determine constituency exceptions or all candidate/constituency constraints, and its probabilities inherit uncertainty omitted by the opinion model. `P(in Riksdag)` from this approximation effectively means national-threshold eligibility, not full legal eligibility. A 349-dot chart requires an integer allocation; posterior mean seats generally are fractional. Keep point allocation and sample mean seats distinct.

## Existing site's historical table

Requests to `http://pollofpolls.se`, its HTTPS root and a candidate table path failed in this environment; indexed method/blog pages were discoverable, but this audit did not obtain a verifiable historical table download or a reuse license. No contact was made and no permission is presumed. The proposed `pollofpolls_daily_2014.csv` is not an acquired fixture. Keep the comparison conditional on an actual source URL/export, dated snapshot, and clear reuse terms; it must not be the statistical launch gate.

## Decisions left to the owner and model work

1. Which survey types, missing-data cases, and method eras enter v1? Specify rejection reasons and counts, not silent defaults.
2. How should approximate periods, unknown publication dates, revisions, and historical “as-of” views behave?
3. Should historical seat charts use election-era rules or a clearly labelled current-rule hypothetical? What public wording distinguishes a national estimate from legal constituency allocation?
4. Which authoritative result fixtures and independent validation vintages are required before release? Add 2002/2006 if the agreed historical horizon requires them.
5. Is old-site comparison worth obtaining an export and clarifying reuse? It is a secondary diagnostic, not an accuracy benchmark.

Recommended implementation requirements are raw snapshot provenance, snapshot-defined active rows, explicit null-safe import identity, unrounded source values, strict date/share validation, explicit survey/era eligibility, publication-aware backtests, and the checked 2022 national fixture. These recommendations leave product/model policy unresolved for the decision tickets.
