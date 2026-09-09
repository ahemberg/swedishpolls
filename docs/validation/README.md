# Validation protocol v1-development-1

Registered 2026-09-08 for [issue #16](https://github.com/ahemberg/swedishpolls/issues/16).
The [final handoff](https://github.com/ahemberg/swedishpolls/issues/9#issuecomment-5575883916)
supersedes earlier experiments and ADR wording about an untouched 2022 holdout.
[protocol.json](protocol.json) freezes dates, input identities and gate constants.
This registers procedures, not passing results. No final audit has been run here.

## Publication-time development

Use the pinned source commit and SHA-256 in the manifest. Dates are calendar dates
in Sweden. The explicit cutoff list starts 2014-01-15, advances 60 days and ends
before any scoring horizon reaches 2022. Each fold trains only on eligible records
with known publication date and fieldwork end on or before its cutoff. Training
starts 2010-01-01, independently within each supported coverage period. Score every
eligible poll published in the next 35 days, once per fold, without updating the fit
from those polls. Exclude unknown publication dates and publication-before-end
records. Record exclusions, train/test row identities and counts for every fold.
This uses publication dates in today's corrected snapshot, not historical snapshots
that were never archived. Report that limitation with the results.

Tune walk variance, house scale and pooled overdispersion by marginal likelihood
using training data inside each fold. Never tune on its held-out scores. Freeze the
candidate grid and any expansions from older development evidence before running
the final audit. A boundary optimum requires a documented grid expansion and a
repeat of all affected development checks, or fails the gate. Grids, exclusions,
rosters and tie-breaking belong in the eventual frozen model artifact.

Use all common eligible observations for paired comparisons. Do not drop a failed
fit, unsupported test roster or empty fold silently. List it and block the aggregate
gate until the reason and a revised development protocol have been reviewed.
Publication of an individual FI period remains conditional on coverage validation.
Report each roster separately; never pool scores across different ilr dimensions.

## Baseline, reference and predictive scoring

The recency baseline uses the same eligible training set and roster. Keep polls
whose rounded midpoint is within the preceding 120 days, inclusive. If fewer than
three remain, use the five most recent eligible training polls, or all if fewer
than five exist. Sort by midpoint, publication date, then pinned source row number.
Use weights `n * 2^(-age_days / 30)`, with a fixed 30-day half-life. Normalize the
weighted arithmetic composition. There is no fitted house effect or trend.

Define `n_eff = sum(w)^2 / sum(w^2) * mean(n)`. The baseline's latent covariance
is the full multinomial delta covariance at the weighted mean and `n_eff`.
Its between-poll covariance is the weighted outer product of ilr residuals around
the ilr of that mean, divided by sum of weights. Predictive covariance is the sum
of those two terms and the held-out poll's full multinomial delta covariance at
the predicted composition and its sample size, with multiplier one. Do not reuse
the prototype's predictive covariance, which omitted the new poll's sampling term.

The reference uses the same daily random walk, priors, cycle effects, centering,
roster and pooled overdispersion as the midpoint candidate. Its sole likelihood
change is that a poll observes the arithmetic average of the daily latent ilr
states over its inclusive fieldwork span. Fit its hyperparameters independently
on the same training data with the same grid. Preserve cross-day covariance when
predicting window averages. This is an ilr-window reference, not an average of
inverse-transformed daily shares. It must pass small dense-system numerical
comparisons before comparative scores count as evidence.

For each held-out poll, compute the multivariate Gaussian predictive log density
of its transformed composition, including observation noise, state uncertainty,
house-effect uncertainty and their cross-covariance. Use Cholesky solves and a log
determinant, not explicit inversion. Use the same orthonormal ilr basis and zero
rule for all candidates. Higher log score is better. Score one joint observation
per poll, never one pseudo-observation per party or fieldwork day. Store individual
scores plus predictive marginal 50% and 95% composition intervals obtained from
joint draws. Coverage counts are by party and poll, including the observed poll noise.

First average paired score differences within each fold, then give folds equal
weight. The midpoint minus recency mean must be strictly positive. For midpoint
minus reference differences `d_t`, require `mean(d) >= -SE`. Set
`gamma_l = sum((d_t-mean(d))*(d_(t-l)-mean(d))) / T` over valid pairs.
Use Bartlett/Newey-West variance of the mean
`[gamma_0 + 2*sum((1-l/4)*gamma_l, l=1..3)] / T` and take its square root.
The executable gate is
[`PredictiveComparison.evaluate`](../../src/main/java/se/swedishpolls/PredictiveComparison.java),
which accepts paired per-fold means for one roster in chronological order.
Require at least eight folds. A nonfinite or negative variance fails; do not clip
it silently. This fixed three-fold lag accounts for dependence over 180 days.
Also report SE at lags 1 and 6, residual autocorrelation, overlapping fieldwork
and institute clustering. Unexplained longer dependence blocks sign-off; do not
select the lag producing a pass. Any development revision is versioned before audit.

Required predictive coverage bands are [0.90, 0.98] at 95% and [0.40, 0.60] at 50%.
Report pooled and per-party coverage, institute residuals, and fieldwork-length
bands 1–7, 8–14 and 15+ days. Explain systematic misfit and residual autocorrelation.
Failed conditional coverage invokes the named hyperparameter grid-mixture fallback,
followed by a new development freeze, rather than relaxed coverage targets.

## Numerical conventions and development tolerances

### Observation implementation, issue #18 checkpoint 1

`PollObservations.prepare(period, ingest.polls(snapshotId))` converts the normalized
records from one archived snapshot into one observation per eligible poll. Supply
one explicit coverage period per call. The returned batch keeps the period,
ordered component names, basis, original poll records, midpoint dates, zero counts
and row-numbered exclusions. Same-day polls remain separate observations. Unknown
publication dates remain usable for corrected history; development-fold callers
must apply the publication-time restrictions above before calling this method.
Election reference tables are never read. Ingest exclusions, including exit polls,
and roster exclusions carry through unchanged.

The basis rows are Helmert contrasts in the declared roster order, followed by
OTHER or RESIDUAL. Row `r`, numbered from zero, gives the first `r+1` components
weight `1/sqrt((r+1)*(r+2))`, the next component weight `-(r+1)/sqrt((r+1)*(r+2))`,
and the rest zero. For proportions `p`, the observation is `H log(p)` and its
sampling covariance is `H diag(1/p) H' / n`. This is the full delta-method
multinomial covariance: its rank-one term cancels because `H 1 = 0`. The ilr
covariance retains off-diagonal entries. No overdispersion multiplier is fitted
in this checkpoint.

`PollObservationsTest` checks both rosters against 50-digit decimal reference
calculations and the unsimplified dense `J Cov(p) J'` expression. It checks basis
orthonormality, covariance symmetry and positive definiteness, midpoint rounding,
zero replacement, small positive shares and exclusions. Numerical failures stop
conversion without jitter or clipping. An unrepresentable positive decimal is
never reclassified as an exact zero. `SnapshotIngestIT` also converts archived
pre-2022 development rows, including the 388 FI candidate observations. Candidate
conversion does not validate FI support or enable estimates. These are local
numerical checks, not frozen release tolerances or the final statistical audit.

Run the numerical checks with `./mvnw -Dtest=PollObservationsTest test`; run the
archive checks and full suite with `./mvnw clean verify` against PostgreSQL 18.

### Daily state-space implementation, issue #18 checkpoint 2

`DailyStateSpace.fit(batch, parameters)` fits one prepared batch with a fixed daily
ilr walk variance and pooled observation-covariance multiplier. It starts at the
batch's coverage-period start with mean zero and covariance `4 * I`. Each later
calendar day adds `walkVariance * I`, including days without polls. Each poll
updates the state once, on its midpoint. Same-day polls are processed separately,
ordered by archived row number after sorting by midpoint. There is no election
input. A zero walk variance is allowed as the static-state limit.

The fit returns filtered and smoothed ilr means and full covariance matrices,
plus the Gaussian innovation log likelihood and its input batch and parameters.
Filtering uses Cholesky solves and a Joseph covariance update; smoothing uses the
[Rauch–Tung–Striebel recursion](https://users.aalto.fi/~ssarkka/pub/bfs_book_2023_online.pdf).
Nonfinite values, asymmetric input covariance and failed positive-definite
factorizations stop the fit. No jitter or eigenvalue clipping is applied.
Covariance symmetry is checked to a relative `1e-12`; computed covariances are
averaged with their transpose to remove roundoff asymmetry.

These internal states run through the last midpoint. Coverage validation, public
history and last-fieldwork headline dating remain checkpoints 5 and 6. House
effects and tuning remain checkpoints 3 and 4. Candidate FI fits here do not
establish supported dates, and the fixed test parameters are not fitted values.

`DailyStateSpaceTest` checks the first update against a scalar conjugate-normal
reference, then compares every daily filtered and smoothed state and the joint
likelihood with independent dense Gaussian conditioning for both rosters. The
cases include unobserved days before and between polls, overlapping windows,
same-day polls, reversed input order, zero walk variance and numerical failures.
State comparisons use absolute `1e-11` and likelihood comparisons `1e-10` in these
small systems. These are test tolerances, not frozen development release bounds.
`SnapshotIngestIT` fits both rosters using archived pre-2022 development rows and
checks finite daily results. It does not run the reserved final statistical audit.

Run `./mvnw -Dtest=DailyStateSpaceTest test` for the numerical checks and
`./mvnw clean verify` against PostgreSQL 18 for the full suite.

### House effects and cycle transitions, issue #18 checkpoint 3

`DailyStateSpace.fit(batch, elections, parameters)` now takes the official election
dates and a `houseScale` parameter between the walk variance and the pooled
multiplier. The state is the joint vector of the daily opinion ilr coordinates and
one house effect per active effect identity in the current election cycle. A poll
observes its opinion state plus its own effect. House effects are static within a
cycle, so only the opinion block takes the daily walk.

An effect identity is the method era ingestion documented for the poll, otherwise
its institute. `demoskop_before_2019_11` and `inizio_continuation` therefore stay
separate, and Demoskop's continuation shares one identity with Inizio. Archived
Demoskop rows without a publication date carry no documented era and form a third,
institute-named identity; the fit shrinks it like any other sparse identity rather
than assuming which era produced it. Zero-mean priors are the shrinkage: an identity
with few polls stays near zero, and there is no separate sparse-institute rule.

Cycles start at the coverage-period start and at every election date inside the
fitted span. Election results remain reference outcomes and never enter the
likelihood; only their dates define the reset. At a reset the opinion mean and
covariance carry across with that day's walk, while every house effect is redrawn
from an independent zero-mean prior with covariance `houseScale^2 * I`. Coverage
periods are separate batches, so a period boundary is never bridged and restarts
the diffuse `4 * I` opinion prior as well.

Centering transforms the joint state and covariance with one linear map per cycle.
Each active institute carries total weight `1/H` split equally over the method eras
it used in that cycle, so an era shared by two institutes carries both their shares
and an institute gains no extra weight when its method changes. The reported opinion
is `x + sum_e w_e h_e` and the reported effects are `h_e - sum_f w_f h_f`. The
centered effect covariance is singular by construction, with the weighted ensemble
exactly zero in both mean and covariance. Filtering and smoothing solve in the
uncentered coordinates, which stay positive definite.

Each cycle centers on its own institute ensemble, which resolves the reported level
as support relative to the institutes active in that cycle. When the ensemble keeps
the same composition across a reset the level is continuous, and the test fixture
bounds that step at 0.01 in ilr units while both institutes swap their deviations.
When the composition changes the level can step, because the reference itself
changed. That step is a change of reference, never voter movement: checkpoint 6
treats it like the other boundaries it must not present as movement, and checkpoint
9 reports its size per cycle.

`Fit` returns the centered opinion per day and one `Cycle` per election cycle with
its effects, weights, and filtered and smoothed centered effect means and
covariances conditioned on the cycle's last day. Daily joint covariances are not
retained: the filter stores the joint state only where it changes, since the
covariance grows by a fixed diagonal on days without a poll. The predictive scoring
above also needs the opinion-to-effect cross-covariance on a scoring day, which this
shape does not expose yet; checkpoint 4 or 9 has to add that accessor rather than
recomputing it from the returned blocks.

`DailyStateSpaceTest` checks the single-institute first update against a scalar
conjugate-normal reference, then compares every daily centered state, every cycle's
centered effects and the joint likelihood with independent dense Gaussian
conditioning over the opinion path and all cycle effects, for both rosters and
across an election boundary. It also checks the era split and its weights, the
sum-to-zero centering with a singular positive semidefinite covariance, stronger
shrinkage for a single-poll identity than a repeated one, a swap of deviations at a
reset with a continuous opinion level, an empty cycle, a restarted coverage period
and rejected election orderings. `SnapshotIngestIT` fits both archived pre-2022
development rosters, checks that the cycles match the contained election dates, that
weights sum to one, that the weighted ensemble effect is zero within `1e-12` and
that Demoskop's two eras are present.

The eight-party 2010-2021 development fit ran in 8.6 seconds with a 144 MB heap
delta, and the FI candidate fit in 3.3 seconds, measured once on the development
machine with fixed unfitted parameters. These are development observations for
checkpoint 10 to measure properly, not frozen bounds. Tuning, coverage validation
and public history remain checkpoints 4 to 6.

Run `./mvnw -Dtest=DailyStateSpaceTest test` for the numerical checks and
`./mvnw clean verify` against PostgreSQL 18 for the full suite.

### Conventions for the estimator

Midpoint is `start + floor(days_between(start,end)/2)`. A half-day rounds toward
the earlier date. One-day polls stay on that day. Reject reversed/missing dates.
Validate finite shares, positive sample size, the roster, missingness and residuals
before transforming. Missing is never zero; negative OTHER is never a valid zero.

For valid exact zeros only, use multiplicative replacement: with `z` zero parts,
set each to `delta = min(0.5/n, 0.5/z)` in proportion units, and multiply positive
parts by `1-z*delta`. When z is zero, do nothing. Positive inputs retain their
ratios. Preserve original reported values. Apply the delta covariance to the
transformed composition; record replacement counts. Compare 0.25/n and 1/n on
older folds as zero-rule sensitivity. This is a declared modeling convention to
validate, not proof that a reported zero is a structural absence.

At each new coverage period, initialize ilr state with mean zero and covariance
`4 * I`, independent of the first poll and of election results. Check prior-predictive
compositions, and compare `I` and `16 * I` on development folds. New cycles reset
house effects to zero-mean Gaussian priors with the fitted house scale. Carry the
opinion state and covariance across a cycle within a coverage period; never bridge
a coverage boundary. Center the joint mean and covariance with the same linear
transformation. Each active institute receives total weight `1/H`; split that weight
equally among its eligible method eras in the cycle. Retain distinct era effects
without giving an institute additional total weight when its method changes.

Check basis orthonormality and covariance symmetry. The full centered joint
covariance must be positive semidefinite: the sum-to-zero constraint makes it
singular. Use unconstrained coordinates for state solves; those covariances and
predictive observation covariances must be positive definite. Check finite
likelihoods and draws, composition closure and range, valid grid optima, and exact
349-seat totals. Cholesky failure on a required positive-definite covariance blocks
the run. No silent jitter or clipping.
Dense small-system comparisons and known-state synthetic simulations must exercise
both rosters, sparse institutes, cycle boundaries, overlapping windows and zeros.
Use 10,000 final-day joint draws with stored seed, transform before arithmetic
averaging, and retain joint draws for threshold/seat/coalition quantities.

The manifest deliberately has no resolved drift/support/reproduction tolerance.
Issue #18's development report must record, for each proposed bound, its units,
maximum observed error, data/case count, seeds, implementation/runtime digests and
rationale. Measure cross-architecture seeded reruns and dense-reference residuals
for reproduction bounds. Measure new-snapshot changes and deletion/correction
perturbations for drift bounds, conditioning on changed polls. Determine supported
coverage/gap boundaries from eligible counts, gaps and fit stability. Determine
historical interval and near-4%/175-seat probability precision by repeated seeds.
Freeze the actual numbers, grids and support boundaries in a reviewed manifest
with the evidence hash before any final audit or automatic publication. Until then
both remain blocked. Never enlarge tolerances after seeing audit results. A failed
gate requires a recorded owner waiver or a new method with an explicit disclosure
that the old audit is now development evidence.

Recompute equal-weight versus poll-count centering and leave-one-institute-out
headline probabilities. Publish differences. A movement over 10 percentage points
requires disclosure next to the number; it does not itself block release.

## Reserved 2022 comparison and prior exposure

The manifest pins the exact CSV bytes, deterministic candidate-row selection,
count and digest of ordered `line:row_sha256` identities. Hash UTF-8 rows without
line terminators; join identities with LF and no trailing LF. These are candidate
inputs, not a claim that every selected row is eligible. The production ingestion
step must archive a versioned eligibility manifest for this exact set before fitting.
No later corrected snapshot can silently replace it.

The reserved calculation is one frozen model fit using only eligible publications
and fieldwork ending by 2022-09-10, with the eight parties plus OTHER, compared with
the final official national 2022 Riksdag party vote shares. Report errors in percentage
points and marginal interval inclusion, plus national approximate seats separately
from official constituency allocations. Results are references, never model inputs.
The manifest pins the already-exposed `docs/research/audit-polls.py` fixture by
commit and SHA-256 and copies its eight official two-decimal percentages. Use those
values, not later vote-count-derived percentages. Define outcome OTHER as exactly
100 minus those eight values in decimal arithmetic, including FI. State the
rounding limitation when reporting errors and interval inclusion: individual shares
have 0.01 percentage-point resolution and the residual accumulates their rounding.
The existing seat allocation check is not an untouched statistical validation.

Prototype commit `dae51911aff744624d1bc972f07c78d46b31b424` used the same CSV,
publication-aware evaluation cutoffs through 2026, full-history smoothing and
since-2022 house residuals. Thus 2022 polls and later polls have already influenced
method selection. The reserved calculation is unused as an election-level model
comparison according to the experiment report; neither its polling inputs nor its
public election outcome are blinded. Do not call all 2022 data untouched or present
this single retrospective comparison as calibration evidence. An August 31 comparison
is a pre-election diagnostic only. Archive the once-only result and every failure.

## Prospective 2026 registration

Registered on 2026-09-08 before the election result. Use cutoff 2026-09-12 and
reference [election date 2026-09-13](https://www.val.se/servicelankar/servicelankar/pressrum/nyheter--pressmeddelanden/pressmeddelande-nya/2026-03-13-viktiga-datum-for-valen-2026). Capture the first complete source snapshot after
the cutoff and retain only eligible polls published with fieldwork ending by the
cutoff. Freeze its input/eligibility hashes and model/run/environment before opening
final official national vote shares. Archive the official result bytes, source URL,
retrieval date and hash when available. No outcome is fetched by this build.

Repeat the 2022 reporting procedure, with per-party error and interval inclusion,
threshold and national-seat comparison. Do not infer forecast calibration from one
election: the estimand remains voting intention on the last fieldwork date. If no
validated frozen model exists by the cutoff, record a missed prospective evaluation;
never fit retrospectively and relabel it prospective. The commit timestamp, manifest
and archived run establish the registration and freeze history.
