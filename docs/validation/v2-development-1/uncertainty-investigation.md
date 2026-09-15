# Shared uncertainty investigation

## Finding

The retained evidence supports competing explanations, not a confirmed software
defect or an adequate replacement model. The strongest new finding is that KD's
large residuals cluster in one scoring horizon across several institutes. S's
intervals overcover more broadly, and OTHER has misses in both directions.
Treating all three as one common variance problem would hide those differences.

The owner approved this investigation's scope in the
[outcome review](outcome-review.md#investigation-priority-accepted-by-the-owner).
No new real-data fit or model change was performed. The original development
verdict and publication block remain in force.

## Rechecked evidence

[inspect_uncertainty.py](inspect_uncertainty.py) independently reads all 740
retained eight-party midpoint and window predictive draw files, covering 370
polls and both methods. It checks their hashes, composition closure and ranges,
then recomputes means, sample variances, equal-tail intervals and standardized
share residuals for all 6,660 method-poll-component records. It also reconciles
the S, KD and OTHER aggregate metrics with the retained diagnostics.

All checks passed. The largest absolute summary difference was
`1.5631940186722204e-13`. The script uses accurate summation rather than the
production loop's sequential summation, so tiny numerical differences are
expected. Its `1e-10` arithmetic-check tolerance is not a replacement for any
registered statistical or reproduction limit.

All 370 eight-party scoring polls have zero replaced zeros. Direct scoring-time
zero replacement therefore cannot explain these failures. This does not rule out
an indirect effect from training observations.

## Where uncertainty fails

Counts below use the registered 95% predictive intervals. A miss above the interval
means observed support exceeded its upper endpoint. Each party has 370 cases.

| Component | Midpoint misses below / above | Window misses below / above |
| --- | ---: | ---: |
| S | 6 / 0 | 7 / 0 |
| KD | 10 / 40 | 9 / 41 |
| OTHER | 31 / 25 | 32 / 26 |

The 2018-08-22 fold contains 36 polls. KD misses above its midpoint interval in
30 of them, across seven institutes; the window method misses in 31. That fold
accounts for 68.92% of KD's total squared standardized midpoint residuals and
69.03% for the window method. These are descriptive contributions, not independent
observations establishing a cause. The fold remains part of every registered gate.

Both methods selected the same interior parameters in that fold: walk variance
`0.0001`, house scale `0.1`, covariance multiplier `1.5`. Another lower-grid
expansion has no direct support from this failure. For a concrete retained example,
source row 623 reports KD at 6.6%, versus midpoint predictive mean 3.604% and 95%
interval 2.881-4.401%. Its standardized residual is 7.772. No election outcome
is needed to observe this mismatch.

OTHER's largest concentration is the 2014-05-15 fold: six misses among nine polls
across five institutes, accounting for 22.64% of its midpoint squared residuals.
Its misses also occur in other folds and in both directions. S exceeds the upper
coverage limits in both methods, including roughly 64-65% coverage for the nominal
50% interval, while KD and OTHER under-cover at 95%.

This pattern supports investigating temporal changes as one cause of KD's failure.
It does not establish that every shared failure is a temporal problem. Broadening
every interval would not be an evidenced remedy for S's overcoverage.

## Shared numerical path

Both methods pass through the same predictive scorer and evidence aggregation.
Code inspection found no missing state-house covariance terms or omitted
observed-poll noise in the reviewed path:

- [WindowFilter.cross and system](../../../src/main/java/se/swedishpolls/estimation/WindowFilter.java)
  include the state variance, house variance, both cross terms and scaled sampling
  covariance. An unseen institute contributes its house prior. Held-out polls do
  not update the training state.
- [DevelopmentDiagnostics.score](../../../src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java)
  draws jointly in ilr coordinates, transforms each draw to shares, then computes
  share intervals and residuals. It does not treat an ilr coordinate as a party share.
- [PollObservations.deltaCovariance](../../../src/main/java/se/swedishpolls/estimation/PollObservations.java)
  uses the correct simplification of full multinomial delta covariance. Percentage
  inputs become proportions before covariance construction and return to percentages
  after inverse transformation.

The existing dense-system tests independently compare Gaussian conditional means
and covariances for both conventions and rosters, including unknown institutes and
overlapping windows. The focused command below passed 20 tests without failures,
errors or skips. These checks test the specified algebra, not real-poll calibration.

## Competing explanations and discriminating checks

| Explanation | Evidence and limit | Prediction to test |
| --- | --- | --- |
| Shared temporal model responds poorly to abrupt party-specific movement | KD's largest failure clusters in one horizon across seven institutes. Both methods share the random-walk structure and selected parameters. This does not establish whether state dynamics, parameter selection or another shared assumption caused it. | Under a frozen synthetic design, both methods should remain calibrated when data follow their assumed dynamics; an isolated abrupt component movement should reproduce clustered directional misses if that limitation is responsible. |
| One pooled noise scale does not represent survey/reporting differences | S overcovers while KD and OTHER under-cover. Nominal sample sizes do not identify effective sample sizes. The pattern alone does not establish party-specific design effects. | With dynamics held fixed, introducing known party- or institute-dependent observation errors should reproduce persistent subgroup differences; measure whether subgroup differences persist when only dynamics change. Compensation would not establish the correct noise model. |
| Covariance computed from held-out shares affects calibration | Both methods use the scored poll's shares to construct its nominal observation covariance. This is a shared plug-in approximation, not a held-out state update or demonstrated tuning leakage. | With a known fixed composition, compare calibration using covariance at the known composition with covariance at sampled shares, holding everything else fixed. |
| Remainder reporting or source handling contributes to OTHER misfit | OTHER is computed as 100 minus the eight reported shares, so it inherits their rounding and normalization errors. The archive does not identify the original reporting-error distribution. | A fixed synthetic rounding/normalization perturbation should change OTHER's calibration more than large-party calibration if this mechanism matters. It cannot establish the size of the real source error. |

The code paths for the last three explanations are
[PollObservations.prepare](../../../src/main/java/se/swedishpolls/estimation/PollObservations.java),
[PollCsv](../../../src/main/java/se/swedishpolls/source/PollCsv.java) and
[Roster.compose](../../../src/main/java/se/swedishpolls/source/Roster.java).
The parser retains denominator/effective-sample-size caveats, while the covariance
calculation uses nominal sample size. No separate reporting-error covariance is
present. Hyperparameter uncertainty also remains conditional on the selected point;
this review has not isolated its contribution.

### Small synthetic check of observed-share covariance

The reproduction script also enumerates all binomial counts at nominal sample
size 1,000 for known fixed shares. It uses observed-share delta variance in a
binary logit interval, with half-count replacement at the two endpoints. Probability
masses sum to one within `1e-10`; there is no random seed or fitted state.

| Known share | Nominal 95% interval coverage |
| --- | ---: |
| 30% | 95.086% |
| 4% | 95.696% |
| 2% | 94.668% |

This reduced check does not reproduce the severe KD/OTHER undercoverage by itself.
It is not the production nine-component share-residual calculation and omits fitted
state uncertainty, house effects, weighted surveys and timing. It therefore limits
the simplest version of this hypothesis without ruling out its interaction with
the full model.

## Proposed next experiment

Prepare a bounded synthetic calibration protocol before considering another
real-data fit. Start with recovery under the shared model's own assumptions, then
vary one mechanism at a time: temporal movement, observation-error structure,
observed-share covariance or remainder reporting. Keep all mechanisms and summaries
specified before execution, retain every result, and include S, KD and OTHER rather
than selecting whichever party improves.

The first decision is whether to commission that protocol. It must define the
generating compositions, sample sizes, institutes, timing, seeds, repetitions and
acceptance criteria. Existing release gates stay unchanged. A synthetic failure
under the assumed model would prompt checks of implementation, finite-sample
calibration and plug-in parameter uncertainty. Failures only under deliberate
misspecification would identify limitations to test in a separately registered
development experiment. Synthetic success would not authorize publication
or validate a real-data correction.

The investigation stops here with these competing explanations. It has not selected
a new covariance model, widened intervals, waived a gate or authorized another fit.

## Reproduction

Run from the repository root:

```bash
python3 docs/validation/v2-development-1/inspect_uncertainty.py
./mvnw -Dtest=PollObservationsTest,WindowFilterTest,DevelopmentDiagnosticsTest,PredictiveComparisonTest test
```

Inputs are the retained [run-2 diagnostics](evidence/run-2/diagnostics.json) and the
draw files whose identities it records. The registered implementation is `539b1f3`;
this review inspected the shared source at base commit
`f010b836fcae7e989813fe185cd2a7b880d1cda3`.
The old 2022 audit, missed 2026 cutoff, FI unavailability and separate release
decision remain as recorded in [ADR 0009](../../adr/0009-development-remediation-protocol.md).
