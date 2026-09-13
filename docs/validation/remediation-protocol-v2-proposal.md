# Development protocol v2 proposal

Proposal `v2-development-1-proposal-1`, prepared 2026-09-13 for
[issue #150](https://github.com/ahemberg/swedishpolls/issues/150), checkpoint 1.
Status: **awaiting owner review, not approved and not executable registration**.
Approval must name this proposal's commit and record the owner's rationale on #150.
Silence is not approval. Publication remains blocked by `v1-release-1`.

## Evidence and failure inventory

The [diagnosis](remediation-diagnosis.md) analyzes archived development results.
The [inventory](remediation-inventory.json) preserves every original blocking reason,
its zero-based release/development index, evidence group, affected roster and cutoffs,
and dependent checks. Its hashes pin the original files at source commit
`a4b957814db30755b0c5e2914af0e45f6ec4cb61`.

There are 50 release reasons: one aggregate wrapper and 49 inherited reasons.
The latter consist of six propagated tuning reasons, one eight-party comparison
failure, two reference-boundary summaries, 21 unscored FI folds, and 19 failed
subgroup predicates across 13 subgroups. The six tuning reasons repeat two upstream
summaries three times. The first two unscored FI reasons repeat the missing-training
cause. The inventory's 19 evidence groups are an organizational count, not a count
of independent statistical defects. Coverage and RMS failures within one subgroup
share observations, and party/institute/window groups overlap.

The evidence supports a bounded grid investigation and explicit coverage-period fold
eligibility. It does not establish that either will cure the eight-party comparison
loss or subgroup misfit. Both remain acceptance requirements. Pooled 95%/50% coverage
passes on both rosters, so the named grid-mixture fallback is not triggered.

Sources are the approved [handoff](https://github.com/ahemberg/swedishpolls/issues/9#issuecomment-5575883916),
[#18](https://github.com/ahemberg/swedishpolls/issues/18),
[#20](https://github.com/ahemberg/swedishpolls/issues/20), and the frozen
[development](protocol.json) and [release](release-protocol.json) protocols.
The handoff and [validation history](README.md#reserved-2022-comparison-and-prior-exposure)
supersede ADR 0001's claim of an untouched 2022 audit. Its older claim that the window
reference is statistically indistinguishable also does not describe the failed
frozen eight-party comparison. This proposal does not change the midpoint likelihood.

## Smallest proposed change

Approve one development-only expansion of the lower grid endpoints, and a complete
fold disposition manifest for the fixed FI coverage period. Keep the midpoint
candidate, independently tuned ilr-window reference, recency baseline, priors,
equal-institute centering, method eras, zero replacement and full covariances.
No per-party or per-institute noise parameters and no window-estimator substitution
are proposed. They would require a separate method proposal supported by the results.

Use the same expanded Cartesian grid for candidate and reference:

| Axis | Frozen v1 values | Proposed values |
| --- | --- | --- |
| Daily ilr walk variance | 0.00001, 0.00003, 0.0001, 0.0003, 0.001 | **0.000003**, 0.00001, 0.00003, 0.0001, 0.0003, 0.001 |
| House scale | 0.02, 0.05, 0.1, 0.2 | **0.01**, 0.02, 0.05, 0.1, 0.2 |
| Pooled observation covariance multiplier | 1, 1.5, 2, 3 | **0.5, 0.75**, 1, 1.5, 2, 3 |

This is 180 points instead of 80. All observed endpoints are lower endpoints;
there is no evidence for increasing an upper bound. The walk extension follows the
existing approximate factor-three spacing. Halving the house lower bound tests the
single early sparse FI optimum. The multiplier extension brackets the old endpoint
with two positive points without an unbounded search. These are proposed diagnostic
ranges, not estimates inferred from unrun fits.

A multiplier below one permits underdispersion relative to the nominal multinomial
covariance. Although `DailyStateSpace` and `DevelopmentTuning.Grid` already accept
positive multipliers, this changes the interpretation of the handoff's inflated
observation noise and the glossary's overdispersion. Owner approval must explicitly
accept this interpretation for development. It is not a tolerance relaxation or
permission to publish narrower intervals. If the owner requires multiplier >= 1,
reject this proposal and request a separately justified constrained-boundary protocol;
do not silently turn the lower endpoint into a passing optimum.

Keep training-only marginal ML and the ascending walk/house/multiplier first-maximum
tie break. Record likelihood and numerical status for every point, both methods and
every active fold. Any failed required fit or any selected endpoint of the expanded
grid blocks completion. Do not skip a failed point, clip a covariance, add jitter,
expand again, or select another seed/lag after seeing scores. A further range or
method change needs a new version and owner review.

## Frozen inputs and folds

Inherit all of `protocol.json` by its inventory SHA-256 except the three explicit grid
extensions and FI fold dispositions below. Inherit `release-protocol.json` gate
constants and disclosure rules; this does not register a new release protocol.
Use `src/test/resources/polls/audit.csv` with SHA-256
`27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`, originally
pinned from source commit `f0390c05854d87bbf21db9d31c6431ffa0f07f7e`.
The inventory also pins the SQL migrations defining source eligibility, rosters,
method metadata and election-cycle dates. Use only pre-2022 development observations;
2022 outcomes, the reserved fit and later polls are not tuning or scoring inputs.
Election dates define cycle resets, never election-share observations.

Keep the exact 48 cutoff/score-through pairs in `protocol.json#/development_folds`,
2014-01-15 through 2021-10-05, with 35-day scoring horizons and training from
2010-01-01 within each roster. Publication date and collection end must both be at
or before the cutoff for training. Score eligible publications strictly after the
cutoff through its frozen score-through date using only the training fit. Preserve
unknown-publication, publication-before-end and incomplete-roster exclusions with
physical row identity and reason. Missing FI is never zero or an observed residual.

| Roster/folds | Proposed disposition | Reason and retained evidence |
| --- | --- | --- |
| Eight-party, all 48 cutoffs | Active; rerun every fold | All have archived paired scores; retain identical eligible row identities |
| FI, 2014-01-15 | Outside estimable period, not scored | No eligible training observation; scoring window also precedes support |
| FI, 2014-03-16 | No training at cutoff, not scored | Held-out horizon enters support, but a future observation cannot train the fit |
| FI, 2014-05-15 through 2018-08-22, all 27 registered cutoffs | Active; rerun every fold | Includes the sparse initial fit and final horizon crossing the support end; score all eligible in-period rows and retain excluded out-of-period rows |
| FI, 2018-10-21 through 2021-10-05, all 19 registered cutoffs | Outside estimable period, not scored | No held-out composition after the fixed FI support end; retain the original repeated historical tuning rows as evidence, not 19 new independent fits |

The inventory enumerates all 21 inactive cutoffs. The fixed FI source-support interval
is 2014-04-09 through 2018-09-07, based on coverage evidence rather than predictive
scores. It is candidate support, not approval to publish individual FI estimates.
Retain every one of the 96 roster/cutoff entries in the revised manifest, including
21 reviewed inactive entries. The proposed aggregate checks 48 eight-party and 27 FI
active folds and still requires at least eight scored folds per roster. An unexpected
empty active fold, changed row set, unsupported test composition without an exclusion,
or numerical failure blocks. Do not restrict to successful/better-scoring folds.
This explicitly revises the old unconditional unscored-fold blocker; it does not
retroactively clear the 21 failures in v1 or waive FI's other gates.

Freeze the training/scoring row manifests and exclusions before fitting; compare
active-fold digests with `diagnostics.json#/folds`. Explain any mismatch and stop
before scoring. Keep corrected-snapshot publication timing limitations in the report.

## Diagnostics and unchanged gates

Recompute paired means within folds, then weight folds equally and keep rosters
separate. Require midpoint minus recency > 0 and midpoint minus reference >= -SE
using the existing Bartlett/Newey-West lag-three formula. Report lag-one and lag-six
SEs alongside it without selecting a favorable lag. Keep the minimum eight-fold rule
and rejection of nonfinite or negative HAC variance.

Keep predictive coverage bands [0.90, 0.98] at 95% and [0.40, 0.60] at 50%, including
poll noise, both pooled and in the existing eligible subgroup checks. Keep the
minimum 100 subgroup cases, absolute mean standardized residual <= 0.5, RMS in
[0.5, 1.5], and absolute residual autocorrelation <= 0.45 at registered lags 1, 2, 3.
Report every subgroup, including those below the case threshold, with its count.
Sparse groups are not demonstrated passes. Retain overlapping/disjoint and
same-institute dependence measurements and their explanation. A plausible hypothesis
alone does not clear a subgroup failure.

The revised development run must retain per-poll joint scores for all three methods,
row identity, fold, institute/method era, inclusive fieldwork length, sample size,
zero replacements, observed composition, predictive intervals and standardized
residuals. Reconcile them to archived-style fold/subgroup totals. This fills the
archive's missing cross-classification without adding a new estimator. Report paired
loss by the frozen 1-7, 8-14 and 15+ day bands and by institute, within each roster;
report party residual/coverage by those groups as descriptive evidence. Do not assign
one joint ilr score to individual parties or pool dimensions.

Compare old-grid and expanded-grid parameters, training likelihoods, paired fold
scores, and all candidate/reference subgroup summaries on the identical active rows.
The v1 archive retains subgroup summaries only for the midpoint candidate, so
reference subgroup comparisons are new diagnostics, not old-grid reproduction.
The old archive is the baseline, not a second tuning objective. If a changed optimum
improves training likelihood but worsens held-out performance, retain that result.
If subgroup failure or the eight-party reference loss remains, stop and return to
owner review with these diagnostics. Do not switch to the reference automatically;
its similar pooled coverage does not establish subgroup adequacy, and the v1 archive
does not retain reference subgroup results. A grid mixture is considered
only through its named conditional-coverage fallback and a separate frozen proposal
if revised pooled coverage fails. It is not an automatic cure for present misfit.

## Checks invalidated for a revised method

Every selected parameter can change when the Cartesian search expands, even for an
old interior optimum. Repeat all active tuning/comparison folds, not just endpoint
folds. The old results remain valid records of v1, but cannot certify v2:

| Evidence/check | Required new evidence |
| --- | --- |
| Tuning and diagnostics | Both ML grids, finite fits, all active fold scores, HAC comparisons, pooled and subgroup coverage/residuals, dependence |
| Coverage and history | Observation/institute counts, maximum 45-day gap, 7/14/30-day boundary shifts with 60-day burn-in and <= 0.5-point stability; separate fits, unavailable FI, dated histories and change suppression |
| Uncertainty and comparable remainder | All-day joint summaries, composition closure/range, remainder summed within draws, same-seed exact reproduction, repeated-seed endpoint precision |
| Sensitivity | Equal versus poll-count centering and every leave-one-institute-out rerun, including headline threshold/majority probabilities and required adjacent disclosures |
| Seats and probability precision | Exact 349-seat totals, fixed era rules/ties, threshold and majority eight-seed spreads and coherent joint outcomes |
| Snapshot drift | Registered addition/deletion/0.1-point correction perturbations on the pinned development rows |
| Cross-architecture and resources | Retained-draw comparison on amd64/arm64, runtime and peak memory for the revised full pipeline |
| Development aggregate and future release freeze | New evidence digests and every inherited failure, parameters, implementation/environment digest, no waiver and no reuse of the old release verdict as a v2 pass |

Keep all eight numerical tolerances unchanged: exact seeded reproduction; cross-
architecture error <= 2e-14 points; interval endpoint spread <= 0.12 points; boundary
shift <= 0.5 points; probability Monte Carlo SE <= 0.005; threshold and majority
probability spreads each <= 0.03; snapshot perturbation drift <= 0.44 points.
Composition sum error remains <= 1e-9 points and sensitivity disclosure remains
above ten percentage points. The ten-second optimization target remains reported,
not blocking. Keep the handoff's separate 30-minute host pipeline requirement.

Existing dense-system numerics for both likelihoods, observation transforms, zeros,
cycle resets, and all software checks must still pass. Retain v1 input-only counts and
fixed allocation-rule references as provenance, but recompute derived fitted values.
Do not overwrite any original validation JSON or the production model freeze.
New implementation will need an explicit separate output directory and protocol input;
the existing `*.full=true` report writers target v1 files and must not be used for v2.

## Reproduction and evidence retention

Keep master seed 20260908; precision seeds are 20260908 through 20260915 inclusive.
Keep 4,000 predictive draws per poll, 10,000 joint draws per estimated day, interval
levels 0.5/0.95, the registered SHA-256 day/poll stream derivations, Java Random
Gaussian generator, and linear-interpolation quantiles. Store all retained draws
needed for exact reproduction and compare the same values on both architectures.
Do not change published rounding or draw counts to pass precision checks.

Before revised validation, checkpoint 2 must record owner approval/rejection and its
rationale. If approved, the subsequent implementation first commits a registration
`v2-development-1` containing this proposal's inherited hashes, explicit grids, all
96 fold dispositions and row manifests, exact implementation commit/digest, JDK,
Maven, Node/npm, linear-algebra versions, container/platform digests, commands and
output paths. Use the repository's pinned toolchain/dependencies and capture their
actual versions; do not silently upgrade them. Freeze this registration before any
new fit, and verify its hashes at runner startup. A mismatch stops the run.

Retain command lines, exit codes, logs, wall time, input and output hashes, all
point likelihoods/failures, fitted parameters, row exclusions, scores and draws,
aggregate diagnostics, and passed/failed checks together under the new version.
Archive both successful and unsuccessful runs. Reproduction runs must read the
frozen inputs and compare outputs without replacing the first results.

For this checkpoint, only JSON analysis and source inspection were needed. The
following read-only check verifies inventory completeness and all frozen inputs;
it does not fit a model or read a new election outcome:

```bash
python3 - <<'PY'
import hashlib
import json
from pathlib import Path
root = Path('docs/validation')
inventory = json.loads((root / 'remediation-inventory.json').read_text())
audit = json.loads((root / 'release-audit.json').read_text())
dev = json.loads((root / 'development-gates.json').read_text())
reasons = [entry['reason'] for entry in inventory['reasons']]
assert reasons == audit['verdict']['blockingReasons']
assert reasons[1:] == ['development_gates: ' + r for r in dev['gate']['reasons']]
assert len(reasons) == 50
for path, expected in inventory['frozen_files'].items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == expected, path
for entry in inventory['reasons'][1:]:
    assert entry['evidence_group'] in inventory['evidence_groups']
assert audit['verdict']['status'] == 'blocked'
assert audit['verdict']['waivers'] == []
print('50 reasons mapped; frozen files unchanged; release remains blocked')
PY
```

## Audit exposure, prospective timing and handoff

Any approved grid/parameter or fold-rule change after audit exposure is a revised
method. The old 2022 audit then becomes development evidence for that method. Keep
its original files, interpretation and blocked verdict permanently. A reused 2022
result is retrospective evidence, never an untouched holdout, a new once-only audit,
or election-forecast calibration. This checkpoint has not rerun that audit.
A later release decision must address this lost audit status explicitly; passing
revised development gates alone does not create new independent release evidence.

Preserve `protocol.json#/prospective2026` and its 2026-09-08 registration unchanged.
Its cutoff was 2026-09-12, before this proposal. The archived release is still blocked,
and this branch has no validated model frozen by that cutoff. Record the prospective
evaluation as missed for this proposed method. If a separate qualifying freeze is
claimed, require its timestamped input/eligibility and model/run/environment evidence
from before outcome access; do not infer one from the registration alone. A later fit
cannot be relabeled prospective. No 2026 outcome was fetched in this work.

Checkpoint 2 records the owner's decision and finalizes the implementation handoff.
Proposed subsequent scope is a separate versioned validation runner/output path,
reviewed grids and fold eligibility, row-level diagnostic retention, and the complete
revalidation listed above. Approval of that work is not approval of its results or
of publication. Any remaining failure returns to owner review; a release protocol
and release evidence must be approved separately without rerunning the once-only audit.

[#113](https://github.com/ahemberg/swedishpolls/issues/113) retains the decisions on
publication-to-publication drift, the published `shrunk` flag and the inert precision
repeat placeholder. The 0.44-point snapshot perturbation bound here is a different
quantity from publication-time drift. This proposal does not decide those constants,
waive launch requirements, or unblock #28.
