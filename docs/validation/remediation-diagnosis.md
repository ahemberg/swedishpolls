# Archived validation remediation diagnosis

This note records a read-only diagnosis of the committed development evidence. It does not rerun
the release audit, change the validation protocol, or create revised validation evidence. The source
inputs were `docs/validation/tuning.json`, `docs/validation/diagnostics.json`,
`docs/validation/coverage.json`, `docs/validation/protocol.json`, the validation README, and the
implementations of `DevelopmentTuning` and `DevelopmentDiagnostics`.

The extraction was done with Python `json.load` over the three archived JSON reports, grouping
resolved rows by `periodId`, counting `gridBoundaries` and `referenceGridBoundaries`, and printing
the `unresolved`, `unscored`, `paired`, and `misfit` records. No model fit or validation command was
run. The compact reproduction command was:

```sh
python3 - <<'PY'
import json
from collections import Counter
tuning_report = json.load(open("docs/validation/tuning.json"))
diagnostics_report = json.load(open("docs/validation/diagnostics.json"))
for period in ("eight_party_2010", "fi_candidate_2014_2018"):
    rows = [r for r in tuning_report["resolved"] if r["periodId"] == period]
    print(period, len(rows), sum(bool(r["gridBoundaries"]) for r in rows),
          Counter(a for r in rows for a in r["gridBoundaries"]))
print(tuning_report["unresolved"])
print(diagnostics_report["unscored"])
print(diagnostics_report["paired"])
print([r for r in diagnostics_report["misfit"] if r["periodId"] == "eight_party_2010"])
PY
```

## Observations

The registered protocol has 48 cutoffs, from 2014-01-15 through 2021-10-05, with a 35-day scoring
horizon. The frozen tuning grid is 5 walk-variance values, 4 house-scale values, and 4 covariance
multipliers, or 80 points per fold and roster. The protocol requires every boundary optimum to be
expanded and every affected check repeated. It also requires unresolved or empty folds to remain
listed and to block the aggregate gate. See [README.md](README.md#publication-time-development),
[protocol.json](protocol.json#L1-L80), and the implementation of
[`tuneAll`](../../src/main/java/se/swedishpolls/estimation/DevelopmentTuning.java#L215-L247).

### Candidate tuning boundaries

The archived tuning report has 94 resolved fits and two unresolved fits. It reports 38 boundary
fits in total. The boundary counts below count resolved rows, while the lists name the exact cutoff
dates. All boundary entries are lower endpoints. There is no observed upper-end boundary.

| Roster | Resolved | Boundary fits | Boundary axes |
| --- | ---: | ---: | --- |
| `eight_party_2010` | 48 | 19 | `covarianceMultiplier:lower` in all 19 |
| `fi_candidate_2014_2018` | 46 | 19 | 18 `covarianceMultiplier:lower`; one row also has `walkVariance:lower` and `houseScale:lower` |

The 19 eight-party candidate boundary cutoffs are:

`2014-01-15`, `2014-03-16`, `2014-05-15`, `2015-05-10`, `2015-07-09`, `2015-09-07`,
`2015-11-06`, `2016-01-05`, `2016-03-05`, `2016-05-04`, `2016-07-03`, `2016-09-01`,
`2016-10-31`, `2016-12-30`, `2017-02-28`, `2017-04-29`, `2017-06-28`, `2017-08-27`,
`2017-10-26`.

The 19 FI candidate boundary cutoffs are:

`2014-05-15` (`walkVariance:lower`, `houseScale:lower`), `2014-09-12`, `2014-11-11`,
`2015-01-10`, `2015-03-11`, `2015-05-10`, `2015-07-09`, `2015-09-07`, `2015-11-06`,
`2016-01-05`, `2016-03-05`, `2016-05-04`, `2016-07-03`, `2016-09-01`, `2016-10-31`,
`2016-12-30`, `2017-02-28`, `2017-04-29`, `2017-06-28`.

The remaining 18 FI entries are covariance lower boundaries. The two-observation FI fit at
2014-05-15 is the only candidate fit at both walk and house lower endpoints. Its selected point is
`walkVariance=1e-5`, `houseScale=0.02`, `covarianceMultiplier=1.5`, with only 2 observations out
of 400 training polls. The next FI fit, at 2014-07-14, has 15 observations and is interior on all
three axes. The archived rows and gate are in [tuning.json](tuning.json#L1-L10), with the two
unresolved rows at [tuning.json](tuning.json#L1749-L1763). The README records the same counts and
the 2-observation warning at [README.md](README.md#L304-L338).

The reference fit has the same shape of boundary problem. Its `referenceGridBoundaries` contain
18 eight-party rows and 17 FI rows. Every reference boundary is also lower:

* Eight-party reference boundaries: `2014-01-15`, `2014-03-16`, `2014-05-15`, `2015-01-10`,
  `2015-03-11`, `2015-05-10`, `2015-07-09`, `2015-09-07`, `2015-11-06`, `2016-01-05`,
  `2016-03-05`, `2016-05-04`, `2016-07-03`, `2016-09-01`, `2016-10-31`, `2016-12-30`,
  `2017-02-28`, and `2017-04-29`.
* FI reference boundaries: `2014-05-15` has both `walkVariance:lower` and `houseScale:lower`;
  `2014-09-12`, `2015-03-11`, `2015-05-10`, `2015-07-09`, `2015-09-07`, `2015-11-06`,
  `2016-01-05`, `2016-03-05`, `2016-05-04`, `2016-07-03`, `2016-09-01`, `2016-10-31`,
  `2016-12-30`, `2017-02-28`, `2017-04-29`, and `2017-06-28` have
  `covarianceMultiplier:lower`.

This is evidence for a lower-end issue shared by both likelihood conventions, not evidence that the
midpoint implementation alone is selecting the wrong parameter. The existing implementation marks
both lower and upper endpoints mechanically in
[`boundary`](../../src/main/java/se/swedishpolls/estimation/DevelopmentTuning.java#L321-L352),
and the diagnostics gate explicitly carries the reference boundary count
[`into the report gate`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L1200-L1209).

### Unresolved tuning folds

Both unresolved rows are FI candidate training folds:

| Cutoff | Score through | Reason | Evidence |
| --- | --- | --- | --- |
| 2014-01-15 | 2014-02-19 | No eligible training observation in the FI period | [tuning.json](tuning.json#L1749-L1756) |
| 2014-03-16 | 2014-04-20 | No eligible training observation in the FI period | [tuning.json](tuning.json#L1757-L1763) |

The FI candidate coverage segment starts on 2014-04-09, so these cutoffs precede its first supported source
observation. This is an empty-period fold, not a failed numerical fit. `tuneAll` emits this reason
when `PollObservations.prepare` returns no observations, before it attempts the grid
[`fit`](../../src/main/java/se/swedishpolls/estimation/DevelopmentTuning.java#L229-L244). The
README documents the same cause at [README.md](README.md#L274-L283).

### All 21 unscored FI folds

The diagnostics report lists exactly 21 FI folds with no score. The first two are the unresolved
tuning folds above. The other 19 have a resolved training fit but no held-out observation that the
FI roster can compose. Their cutoffs are:

`2018-10-21`, `2018-12-20`, `2019-02-18`, `2019-04-19`, `2019-06-18`, `2019-08-17`,
`2019-10-16`, `2019-12-15`, `2020-02-13`, `2020-04-13`, `2020-06-12`, `2020-08-11`,
`2020-10-10`, `2020-12-09`, `2021-02-07`, `2021-04-08`, `2021-06-07`, `2021-08-06`,
`2021-10-05`.

The archived evidence gives the two reason classes directly at
[diagnostics.json](diagnostics.json#L2073-L2084), continues the no-held-out sequence through
[diagnostics.json](diagnostics.json#L2085-L2157), and repeats every reason in the blocked gate at
[diagnostics.json](diagnostics.json#L1-L5). The period's supported interval is 2014-04-09 through
2018-09-07, from 388 FI observations, as recorded in [coverage.json](coverage.json#L100-L145).
Therefore the 19 later folds are structurally unscorable under the registered roster. They are
unavailable observations, not fit failures and not missing rows that can be recovered by changing a
solver.

The scoring implementation confirms the distinction. It first looks up a resolved training point;
then `fold` throws `no held-out observation the period can compose` when the held-out batch is empty
[`here`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L1122-L1147)
and the underlying check is at
[`heldOut`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L541-L568).
The report retains the fold in `unscored` and adds its reason to the gate
[`here`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L1212-L1213).

## Eight-party candidate versus reference

The eight-party comparison has all 48 folds. The midpoint candidate beats the recency baseline by
`+1.159958752773079` mean paired log score. It loses to the independently tuned ilr-window reference
by `-0.023579124341236275`, with the registered lag-three paired standard error
`0.011921334731399268`. The reference loss is therefore about 1.98 registered standard errors and
fails the gate. Lag-one and lag-six standard errors are `0.01277504845935254` and
`0.012591667398069838`. These values are in [diagnostics.json](diagnostics.json#L2158-L2169).

The pooled rows contain 370 scored polls and 3,330 party-poll cases. The midpoint has mean log score
`4.920328865309745`, 95% coverage `0.927027027027027`, 50% coverage `0.5183183183183183`, and
root mean square standardized residual `1.1663830061916336`. The reference has mean log score
`4.929073277733361`, 95% coverage `0.9255255255255255`, 50% coverage `0.5219219219219219`, and
root mean square standardized residual `1.1678442982092492`. Both pooled coverage rows fall within
the registered bands, so the reference loss is a fold-weighted predictive-score result rather than
a pooled coverage failure. The pooled rows are at
[diagnostics.json](diagnostics.json#L2183-L2218).

The small pooled difference does not identify why the reference wins. The committed report stores
per-fold means and pooled subgroup summaries, but no per-poll or per-party candidate-versus-reference
score differences. The code computes `Scored` rows in memory and reduces them to
`meanLogScore` per fold [`here`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L609-L637);
the JSON retains row hashes, not those individual scores. Attribution to a specific poll, period,
institute, party, or fieldwork length inside a fold is therefore a hypothesis until a future
diagnostic preserves row-level candidate and reference scores. The fold-level direction is known:
the midpoint is lower than the reference in 32 of 48 folds and higher in 16. The largest negative
fold differences are 2015-07-09 (`-0.2254`, one scored poll), 2017-06-28 (`-0.2248`, five polls),
2015-01-10 (`-0.1974`, eight polls), and 2020-04-13 (`-0.1524`, six polls). The largest positive
differences are 2015-05-10 (`+0.4153`, nine polls), 2014-05-15 (`+0.1579`, nine polls), and
2017-08-27 (`+0.1063`, seven polls). The registered comparison is the equal-fold mean `-0.0235791`;
the poll-weighted mean would be `-0.0087444`, so these are not interchangeable summaries.

The window convention is still a plausible hypothesis because it is the sole likelihood change in
the reference, but both candidate and reference have lower covariance boundaries and nearly the same
pooled misfit. The archived evidence does not justify saying that switching to the window convention
would fix the model.

## Candidate subgroup misfit

The midpoint's pooled coverage passes the registered bands, but subgroup results do not all pass.
The strongest party-level problems are:

| Component | 95% coverage | 50% coverage | Mean standardized residual | RMS standardized residual |
| --- | ---: | ---: | ---: | ---: |
| S | 0.9838 | 0.6405 | -0.0784 | 0.7690 |
| KD | 0.8649 | 0.4189 | +0.3920 | 1.8415 |
| OTHER | 0.8486 | 0.3919 | +0.0488 | 1.4731 |

S exceeds both upper bands, at 95% coverage `0.9838` and 50% coverage `0.6405`. KD and OTHER fall
below the lower 95% band, and OTHER also falls below the lower 50% band. KD has the largest party
residual scale. The other parties' coverage is inside both registered bands, although SD and V have
positive mean residuals (`+0.2338` and `+0.1741`). Exact rows are at
[diagnostics.json](diagnostics.json#L2220-L2326).

By institute, Skop is the clearest failure: 29 polls, 95% coverage `0.8276`, 50% coverage `0.4253`,
and RMS `1.6927`. Sifo is just below the 95% lower band at `0.8977` over 63 polls, with RMS
`1.3298`. Other institutes are within the registered bands, although SCB and United Minds have only
7 and 5 scored polls. See [diagnostics.json](diagnostics.json#L2330-L2444).

The fieldwork-length bands all pass coverage. Their midpoint RMS residuals are `1.2877` for 1-7
days, `1.1464` for 8-14 days, and `1.0134` for 15+ days. The shorter-fieldwork bands are noisier,
but this does not isolate a fieldwork-window defect. The exact rows are at
[diagnostics.json](diagnostics.json#L2450-L2483).

The FI candidate has the same broad pattern with less data. Its party-level failures are S above both
upper bands (`0.9850` at 95%, `0.6300` at 50%), KD below the 95% lower band (`0.8550`, RMS
`2.0469`, absolute mean standardized residual `0.5154` also exceeds 0.5), FI just below the 95% lower band (`0.8950`), and RESIDUAL below both lower bands
(`0.8250` and `0.3450`, RMS `1.8642`). Skop is below both institute bands (`0.8182` and `0.3455`,
RMS `1.8372`), Sifo is below the 95% band (`0.8838`), Inizio is below the same band (`0.8952`
over 21 polls and 210 component cases), and the 1-7-day fieldwork band is just below
95% (`0.8986`). These are descriptive FI diagnostics only. The FI roster has 200 scored polls in
the archived breakdown and remains an unvalidated candidate coverage segment. See [diagnostics.json](diagnostics.json#L2490-L2794).

Residual dependence is material. Eight-party whitened-residual autocorrelation is `0.4086`,
`0.4116`, and `0.4316` at lags 1, 2, and 3. The same report measures mean whitened-residual cross-products per ilr coordinate, `1.0904` for
overlapping fieldwork pairs, `0.8376` for disjoint pairs, and `2.5546` for same-institute pairs.
These pair statistics can exceed one; they are not Pearson correlation coefficients.
[`dependence`](../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java#L897-L934)
computes the products without sample-variance normalization. These numbers support retaining the registered HAC treatment and investigating source dependence,
but they do not identify a single missing model term. See [diagnostics.json](diagnostics.json#L2795-L2842).

The report retains reference pooled rows but its subgroup rows are for the midpoint candidate. It is
valid to say that the reference matches the candidate closely in pooled coverage, but there is no
archived evidence for whether the reference improves KD, OTHER, Skop, or any other subgroup. A future
row-level artifact should carry, for each held-out source row and candidate, the log score, whitened
residual, predicted composition, and coverage indicators. It should also retain the
candidate-reference score difference so the eight-party loss can be localized without rerunning the
audit.

## Smallest justified next step

The direct boundary evidence justifies extending the shared covariance lower end below 1. The
candidate and reference each select `covarianceMultiplier=1` repeatedly, with no upper boundary. A
single preregistered expansion that also makes the two-observation FI endpoint explicit is:

```text
walk variance:          3e-6, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3
house scale:            0.01, 0.02, 0.05, 0.1, 0.2
covariance multiplier:  0.5, 0.75, 1, 1.5, 2, 3
```

This is 180 points per fold and roster. The new walk and house lower values are justified only as a
controlled check of the single 2-observation FI boundary. The 37 candidate covariance boundary rows
and the reference's 34 covariance boundary rows are the stronger reason for the covariance
extension. No upper expansion is supported by the archived results.

Values below 1 change the interpretation of the covariance multiplier. The protocol describes it as
pooled overdispersion, while a value below 1 makes fitted observation noise smaller than the
multinomial sampling covariance. That is allowed by the current positive-value grid validator, but
it is a modeling decision requiring owner approval and a protocol revision. It must not be described
as ordinary overdispersion until the owner records that interpretation.

The expansion should be run once as a frozen protocol revision. Stop and record a new boundary reason
if an optimum selects any newly added endpoint. Do not add points adaptively after seeing results.
Regardless of the new optimum, retain the 21 FI unscored rows as structural roster limitations,
preserve separate FI and eight-party scoring, and add row-level candidate/reference diagnostics before
claiming that a window convention explains the eight-party loss. The current release gate remains
blocked until those decisions and checks are recorded.
