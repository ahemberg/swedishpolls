# Individual party coverage and historical OTHER membership

Research for [Audit individual party coverage and historical OTHER membership](https://github.com/ahemberg/swedishpolls/issues/11), investigated 2026-09-07. Evidence and recommendations below do not decide the owner's party roster.

## Finding

The pinned source can separately represent the eight main party columns and FI. FI has substantial cross-pollster historical coverage in 2014–2018, but no numeric observation after August 2022. The source cannot identify any other named party inside OTHER. Missing FI is not evidence of zero support, and a permanent zero FI line is unsupported by this dataset.

## Evidence and method

All CSV counts below are calculated from [Polls.csv](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/Data/Polls.csv), commit `f0390c05854d87bbf21db9d31c6431ffa0f07f7e`, SHA-256 `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`, the same snapshot as the [earlier provenance audit](https://github.com/ahemberg/swedishpolls/blob/4822c79/docs/research/data-audit.md). There are 2,650 rows. Publication year is the four-digit prefix of `PublYearMonth`; dates in the institute table are known `PublDate` extrema. Counts are source coverage, not independent samples, effective sample size, a fitted estimate, or final eligible-poll counts. Tracking overlaps can inflate counts. House names are upstream canonical identities, not guaranteed independent methods.

The [source README](https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/README.md) defines `NA` as missing and describes historical data limitations, normalized Ipsos shares, and the Demoskop/Inizio merger. It does not say an absent FI value is zero or establish why each individual value is missing.

## What the schema distinguishes

| Source condition | Interpretation |
|---|---|
| Named column absent | This CSV cannot separately represent the party at all; it does not establish whether an institute measured it. |
| Named column present, cell `NA` | Value unreported in this snapshot; do not assume zero, nonexistence, or known suppression threshold. |
| Numeric cell equal to zero | A recorded numeric zero at source precision; preserve it, but do not infer exact population support is zero. |
| `100 − sum(eight)` | A derived remainder including FI and all other unrepresented parties, subject to input completeness, denominator validity and rounding. |

The actual party columns are `M,L,C,KD,S,V,MP,SD,FI`. There is **no explicit OTHER column**, no named columns for further parties, and no party breakdown hidden in `Uncertain` (which describes uncertain voters).

| Party | Numeric rows | Missing rows | Recorded zeros | First–last publication year with numeric value |
|---|---:|---:|---:|---|
| M | 2650 | 0 | 0 | 1944–2026 |
| L | 2650 | 0 | 0 | 1944–2026 |
| C | 2650 | 0 | 0 | 1944–2026 |
| KD | 2612 | 38 | 46 | 1967–2026 |
| S | 2650 | 0 | 0 | 1944–2026 |
| V | 2650 | 0 | 0 | 1944–2026 |
| MP | 2422 | 228 | 0 | 1987–2026 |
| SD | 1662 | 988 | 0 | 2006–2026 |
| FI | 394 | 2256 | 0 | 2006–2022 |

These are historical schema facts, not evidence that a party existed throughout every date between the endpoints. The eight-party composition is itself incomplete in some early records: the earlier audit found 33 missing-SD rows after the proposed September 2006 start that survive its initial date/sample filter.

## FI coverage by time

| Year | All rows | Numeric FI | Houses reporting FI | FI min / median / max (%) |
|---|---:|---:|---:|---|
| 2006 | 85 | 1 | 1 | 1 / 1 / 1 |
| 2007 | 62 | 0 | 0 | — |
| 2008 | 58 | 0 | 0 | — |
| 2009 | 70 | 0 | 0 | — |
| 2010 | 115 | 0 | 0 | — |
| 2011 | 89 | 0 | 0 | — |
| 2012 | 92 | 0 | 0 | — |
| 2013 | 86 | 0 | 0 | — |
| 2014 | 113 | 75 | 11 | 1.1 / 2.8 / 5.8 |
| 2015 | 86 | 81 | 8 | 1.4 / 2.1 / 3.4 |
| 2016 | 83 | 81 | 8 | 1.1 / 2.1 / 3.4 |
| 2017 | 91 | 79 | 8 | 1 / 2 / 3.4 |
| 2018 | 123 | 75 | 7 | 0.6 / 1.5 / 3.1 |
| 2019 | 72 | 0 | 0 | — |
| 2020 | 65 | 0 | 0 | — |
| 2021 | 64 | 0 | 0 | — |
| 2022 | 141 | 2 | 1 | 0.3 / 0.35 / 0.4 |
| 2023 | 66 | 0 | 0 | — |
| 2024 | 61 | 0 | 0 | — |
| 2025 | 67 | 0 | 0 | — |
| 2026 | 54 | 0 | 0 | — |

No FI values occur before 2006. The lone 2006 value is SVT VALU (1.0%), not an ordinary voting-intention poll. The 2014 maximum of 5.8% is also SVT VALU: excluding it leaves 74 FI observations from ten houses, range 1.1–4.4%, median 2.8%. Neither VALU row belongs in the agreed ordinary-poll estimate. The 2014–2018 block contains 391 FI observations including the 2014 VALU. Two YouGov FI rows lack required sample/collection fields (publications 2016-08-19 and 2017-07-16); therefore even the dense block is not wholly eligible. After excluding that VALU and these two rows, 388 remain before any further validation, method, or overlap decisions.

The only later FI observations are Sentio, published 2022-06-26 (0.4%) and 2022-08-30 (0.3%). There are no recorded FI zeros anywhere in the file. All 248 rows in 2023–2026 have missing FI, rather than 0%. This supports a current view without an individual FI series; it does not prove FI is defunct or has zero support.

## FI coverage by source house

| House | Numeric FI rows, all years | First known publication | Last known publication |
|---|---:|---|---|
| Demoskop | 54 | 2014-05-09 | 2018-08-31 |
| Inizio | 44 | 2014-11-03 | 2018-08-06 |
| Ipsos | 48 | 2014-05-30 | 2018-08-17 |
| Novus | 43 | 2014-05-29 | 2017-08-10 |
| SCB | 1 | 2014-05-27 | 2014-05-27 |
| SVT | 2 | 2006-09-17 | 2014-09-14 |
| Sentio | 47 | 2014-08-15 | 2022-08-30 |
| Sifo | 66 | 2014-05-18 | 2018-09-07 |
| Skop | 29 | 2014-06-17 | 2018-09-09 |
| United Minds | 9 | 2014-06-09 | 2014-09-12 |
| YouGov | 51 | 2014-05-14 | 2018-09-05 |

Endpoints are not promises of uninterrupted observations. Novus stops reporting FI in this file before the 2018 election; SCB has only one numeric FI row. The eight houses reporting FI in each of 2015–2017 and seven in 2018 give substantially stronger historical evidence than the two one-house observations in 2022. Annual medians are unweighted descriptive summaries, not estimates of public opinion or evidence for a particular display threshold.

## Can another party dominate OTHER?

Possibly, but this source cannot answer which one or how much. There are zero separately represented observations for every party other than these nine. No amount of inspecting the aggregate remainder can recover its constituents. Even a rising remainder cannot establish sustained support for any named party.

A primary-source cross-check demonstrates that the limitation is substantive: SCB's [2018–2022 report, printed page 57](https://www.scb.se/contentassets/f50c4aa9ab8546848fc999bc65189580/me0104_2018i22_br_me09br2201.pdf#page=57) names Alternativ för Sverige, Feministiskt initiativ, Medborgerlig samling and Piratpartiet as common constituents of its OTHER category for that period. SCB says its measurements cannot establish which of these small parties is larger; that report separately publishes an outside party only at 2.5% in an individual measurement, reached by none during the period. That is SCB's dated publication policy, **not a proposed product cutoff**, and it does not establish a current ranking.

Before proposing another current named series, obtain dated first-party institute breakdowns with party identity, share, survey question/denominator, collection dates and uncertainty/precision; then review repeated reporting across institutes. A report merely naming possible constituents or official election results can guide investigation but cannot supply a current polling series. This audit has not acquired a usable contemporary multi-institute breakdown for another party. Further sources are necessary if the owner wants such a series; none is necessary merely to retain an honestly labelled undifferentiated OTHER.

## Consequences and recommendations for the next decision

The owner resolves the roster and supported periods in [Choose individual party coverage across current and historical views](https://github.com/ahemberg/swedishpolls/issues/12).

1. Review 2014–2018 as the strongest candidate FI historical segment. Exact boundaries require the owner/model review; do not extrapolate a continuous line from the 2006 VALU or through the 2019–2021 gap and beyond the isolated 2022 readings. Source coverage endpoints are not automatically model support boundaries.
2. Preserve raw FI values and missingness independently of displayed membership. When FI is folded into OTHER, the eight-party remainder already includes FI: adding FI again double-counts it. When FI is separate and all nine shares are available, the residual is `100 − sum(eight) − FI`. With FI missing, an observed residual excluding FI cannot be computed without an explicit statistical missing-component treatment.
3. Do not silently move FI between buckets poll by poll within a period because one institute omits it. A stable display composition may require the model to use partial observations explicitly or to exclude them with a reason; the model ticket must choose. Display admission alone does not solve the likelihood/schema question.
4. If a view spans an inclusion boundary, mark the change to OTHER membership and limit the individual line to its supported segment, as agreed. For comparing changes in OTHER across eras, use a common aggregate (for example outside the fixed eight) or explain that the apparent jump includes reclassification. Never describe a membership discontinuity as voter movement. Recompute election reference aggregates with the same membership as their comparison period.
5. Keep the current roster an owner decision. Evidence supports reviewing FI historically and keeping unnamed parties aggregated until usable first-party coverage is obtained. It does not justify promoting an unnamed presumed majority of OTHER or adopting a numerical cutoff.

## Reproduce the principal counts

Run this stdlib-only check from any directory. It downloads only the pinned public CSV, verifies its hash, and fails if the key findings or claimed eligibility arithmetic do not reproduce. The report tables use the same year and house groupings.

```sh
python3 - <<'PYTHON'
import csv, hashlib, io, urllib.request
url = "https://raw.githubusercontent.com/MansMeg/SwedishPolls/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/Data/Polls.csv"
raw = urllib.request.urlopen(url).read()
assert hashlib.sha256(raw).hexdigest() == "27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608"
rows = list(csv.DictReader(io.StringIO(raw.decode())))
fi = [r for r in rows if r["FI"] != "NA"]
assert len(rows) == 2650 and len(fi) == 394
assert not any(float(r["FI"]) == 0 for r in fi)
assert len([r for r in rows if r["PublYearMonth"][:4] >= "2023"]) == 248
assert not any(r["PublYearMonth"][:4] >= "2023" for r in fi)
block = [r for r in fi if "2014" <= r["PublYearMonth"][:4] <= "2018"]
assert len(block) == 391
initial = [r for r in block if r["house"] != "SVT" and all(r[k] != "NA" for k in ("n", "collectPeriodFrom", "collectPeriodTo"))]
assert len(initial) == 388
for year in range(2006, 2027):
    cohort = [r for r in rows if r["PublYearMonth"].startswith(str(year))]
    known = [r for r in cohort if r["FI"] != "NA"]
    print(year, "rows", len(cohort), "FI", len(known), "houses", len({r["house"] for r in known}))
PYTHON
```
