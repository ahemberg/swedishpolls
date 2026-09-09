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

### Development tuning, issue #18 checkpoint 4

`DevelopmentTuning` resolves the three approved parameters inside the frozen
publication-time folds of [protocol.json](protocol.json). The protocol now also
freezes the candidate grid under `tuning_grid`, which `DevelopmentTuning.protocol`
reads, so a grid change is a protocol change. `tuning_rules` states the objective,
the tie-break, the boundary rule and the per-institute deferral in prose; those
are implemented in code, not read from the file, so changing that object alone
changes nothing. The frozen grid is walk variance `1e-5, 3e-5, 1e-4, 3e-4, 1e-3`,
house scale `0.02, 0.05, 0.1, 0.2` and pooled covariance multiplier
`1, 1.5, 2, 3`: 80 points per fold and roster.

`training(polls, fold)` is the fold's whole input rule. It keeps eligible polls
with a known publication date on or before the cutoff. Ingest eligibility already
rejects publication before fieldwork end, so the fieldwork end lies on or before
the cutoff as a consequence, and no second filter restates it. Unknown
publication dates are excluded here even though corrected history uses them.
Held-out scoring windows are read from the protocol but not scored: predictive
scores, the recency baseline and the ilr-window reference remain checkpoint 9.

The objective is the plug-in marginal likelihood: the Gaussian innovation log
likelihood of the fold's training observations under the same fit that checkpoint
3 validated. `DailyStateSpace.logLikelihood` runs that forward filter without
retaining daily states or smoothing, which is what made 80 fits per fold
affordable; it keeps every per-observation Cholesky factorization and finiteness
check, and `DailyStateSpaceTest` compares it against `fit` on the same batches.
The daily joint positive-definite check belongs to the retained states and does
not run on this search path. So the resolved point is not stored on the search
alone: every fold reruns the full `fit` at its argmax and requires the retained
likelihood to equal the search likelihood exactly. A point that wins the search
but cannot carry a fully checked fit therefore never becomes a stored parameter.

Every grid point must fit finitely. A failed or nonfinite fit stops that fold,
naming the point, the period and the cutoff, instead of being dropped from the
grid. `tuneAll` then lists the fold in `unresolved` with that reason and carries
on, so one bad fold never destroys the other folds' evidence. A fold whose
training data yields no observation in a coverage period is listed the same way:
the 2014-01-15 and 2014-03-16 cutoffs precede the candidate FI period's
2014-04-09 start. `tune` on a single named fold still throws, because there the
caller asked for that one fold. Ties are exact-equality ties and resolve to the
first point in grid order, so the smallest walk variance wins, then the smallest
house scale, then the smallest multiplier.

Each resolved fold records its training poll count, its observation count, its
excluded poll count and a count per exclusion reason. One excluded poll can carry
several reasons, so the reason counts sum to at least the poll count. Per-fold
train and test row identities belong to checkpoint 9's scoring report, which is
where held-out rows first exist.

`gridBoundaries` names every axis whose optimum sits at an end of its frozen
axis, as `walkVariance:lower` and so on. A single-valued axis is a fixed
parameter and reports both ends. The protocol requires a documented grid
expansion and a repeat of every affected development check before a boundary
optimum can stand, so these entries are open work, not results.

`Tuning.gate` makes that machine-readable rather than leaving it to this prose.
Any boundary optimum and any unresolved fold set `blocked` and add their reason,
and the gate is serialized at the top of the evidence file. A later checkpoint
reading `tuning.json` therefore sees the block alongside the parameters and
cannot mistake them for resolved release values. Clearing the gate needs an owner
decision recorded in the protocol, not a code change here.

### Resolved development parameters and the open boundary

The full run of 2026-09-09 resolved 94 fold fits, listed 2 unresolved folds and
left the gate blocked on both counts: 38 of the 94 sit on a grid boundary, and
the 2 unresolved folds need a reviewed reason. Every resolved point also passed
the full retained fit at its own parameters. All
eight-party folds resolved walk variance `1e-4`, interior on that axis, and house
scale `0.1` on 41 folds and `0.05` on 7, six of them the earliest folds. Both
values are interior. The FI candidate roster resolved house scale `0.1` on 40 of its 46
folds and walk variance `1e-4` on 39, with `3e-4` on 6 folds in 2014-2015.

**The pooled covariance multiplier sits at its grid floor of 1 in 19 of 48
eight-party folds and 18 of 46 FI folds**, all of them before 2017-10-26. This is
a boundary optimum on the `covariance_multiplier` axis and the bulk of the 38
blocked folds; the remaining one is the near-empty FI fold below. The protocol makes it a
documented grid expansion plus a repeat of every affected development check, or a
failed gate: it is not resolved here. Expanding the axis below 1 would let the
fitted observation noise fall under the multinomial sampling variance, which is
the opposite of the overdispersion the parameter was introduced to carry, so
whether 1 is a modeling floor or an expandable grid end is an owner decision. Until
it is recorded, the aggregate score gate stays blocked and these multipliers are
not release values. Later folds, from 2017-12-25 onward, resolve `1.5` on both
rosters and sit interior.

Two FI folds carry almost no training data: the 2014-05-15 cutoff has 2
observations and 2014-07-14 has 15. The 2-observation fold is the only one whose
walk variance and house scale land at grid ends, which is the near-empty fold
resolving arbitrarily rather than evidence about either parameter. It is listed
rather than dropped, and checkpoint 9 has to decide whether folds this thin
belong in the paired comparison at all.

The eight-party roster excludes no eligible training poll in any fold, so its
`trainingPolls` equals its `observations`. The FI roster's counts diverge because
its candidate period runs 2014-04-09 to 2018-09-07: later folds carry training
polls the period cannot place, and its observation count stops growing at 387.

Per-institute observation variance is not tuned. The handoff requires older-fold
evidence for it first, and this checkpoint produces none: the pooled multiplier
stays a single shared parameter.

`DevelopmentTuningTest` checks the grid rules, the training filter against late
publication, unknown publication dates and ineligible rows, the resolved point
against a directly recomputed grid maximum for both rosters, the boundary strings,
the exact tie, the empty fold and a failed fit that overflows the opinion
covariance before its poll. `DailyStateSpaceTest` checks that the likelihood-only
path returns exactly the retained fit's likelihood and rejects the same inputs.
`DevelopmentTuningIT` runs the archived pre-2022 development rows through
`tuneAll` for both rosters and reruns one fold to confirm it resolves the same
point and likelihood.

[tuning.json](tuning.json) is the stored result of the full run. It is
development evidence at protocol version `v1-development-1`: resolved parameters
per fold and roster, their marginal likelihood, training and exclusion counts,
the grid, the boundary lists, the unresolved folds and the blocked gate. No
parameter here is a frozen release value, and none has been scored against
held-out polls yet.

Run `./mvnw -Dtest=DevelopmentTuningTest test` for the tuning rules and `./mvnw
clean verify` against PostgreSQL 18 for the suite, where the three-fold subset
costs about 85 seconds. `./mvnw clean verify -Dtuning.full=true` reruns every
fold and rewrites `tuning.json`; the run that produced the committed evidence took
19m12s wall across 8 cores on the development machine, with its per-fold verifying
fit included. An earlier run without that verifying fit took 15m07s wall and 103
CPU-minutes. CPU time was not re-measured for the committed run. These are
development observations for checkpoint 10 to measure properly, not frozen bounds.

### Coverage-period validation, issue #18 checkpoint 5

`CoverageValidation` establishes what each coverage period actually supports and
measures what a separate-fit boundary does to every modeled party.
[protocol.json](protocol.json) freezes the rules under `coverage_validation` and
states them in prose under `coverage_rules`, so a rule change is a protocol change.

`development(polls, rules)` is the whole input rule: fieldwork ending on or before
`development_through`, the last development fold cutoff of 2021-10-05. The reserved
2022 comparison and the prospective 2026 window therefore stay outside this
evidence. Unlike a tuning fold this reads corrected history, so a poll with an
unknown publication date still counts. Every entry point applies the filter, so no
caller can reach past it by passing the full snapshot.

`support(period, polls, rules)` reports what the period's own roster composes
completely: the outermost collection dates, the observation and institute counts,
the excluded polls with a count per reason, and the longest run between consecutive
observation midpoints. The supported dates come from those observations, never from
the declared endpoints, and `supported(period, support)` trims the period to them.
Source observations are untouched by any of this: a poll the roster cannot place is
excluded with its reason and stays archived.

`validate` fails a period when its observations, institutes or largest internal gap
breach the registered rules, and when pulling both supported boundaries in by a
registered shift moves the estimate too far. Each variant refits the same parameters
on the shortened window, and the comparison burns in `stability_burn_in_days` at
each end: the days beside a moved boundary lost the polls the shift removed, so only
the interior says whether the boundary choice drives the estimate. Failures are
listed, never lowered rules.

`validateAll` fits each period at the point its last development fold resolved in
[tuning.json](tuning.json), and that run's gate carries into this one. So the
coverage gate is blocked here by construction: no estimate produced from these
parameters is a release value, and clearing it needs the tuning boundary decision
first. Boundary effects compare the candidate fit with the surrounding validated
fit on the candidate's first and last retained day, per fixed party and for the
comparable remainder, which sums FI and the residual so both sides describe the
same aggregate. That step is a change of modeled membership and of the fit itself.
It is never voter movement, and checkpoint 6 must present it as a break.

#### Established dates and the observed margins

The full run of 2026-09-09 is stored in [coverage.json](coverage.json). Both periods
pass every registered rule.

The eight-party period supports 2010-01-04 to 2021-10-03 within the development
window, from 1,038 eligible observations across 10 institutes. Its estimate is
insensitive to the boundary: the worst movement over 4,111 or more compared days is
0.05 points, at every registered shift.

The FI candidate segment supports **2014-04-09 to 2018-09-07**, the outermost
collection dates of the 388 eligible complete FI observations, across 10 institutes.
These are the exact dates the evidence establishes; the declared candidate period
already carried them, and the run confirms them rather than moving them. Its
estimate is stable but visibly less so than the eight-party one: 0.07 points at the
7-day shift, 0.15 at 14 days and **0.43 points at the 30-day shift**, on S. That
sits below the registered 0.5 with little room, and a shift of 30 days at each end
drops 14 months of a 53-month segment, so the margin is a real property of a short
segment rather than a numerical artifact.

Two registered constants deserve their observed values next to them. The largest
internal gap is the summer of 2016, 2016-07-01 to 2016-08-13, **43 days against a
registered maximum of 45** on both rosters. The gap rule was registered from the
FI collection dates of the pinned snapshot before the eligible batches were fitted,
and it survives by two days. A snapshot correction that widens that summer, or a
stricter rule, ends support inside the segment rather than bridging it. Neither
constant may be enlarged after the fact: that is a protocol change with a repeat of
every affected development check.

The boundary steps are large enough to matter for presentation. At 2014-04-09 the
comparable remainder steps 1.32 points and S steps -0.92; at 2018-09-05, the last
retained day, the remainder steps 0.73, S -1.14 and SD 0.79. Both fits describe the
same voters from the same polls, so the whole step is the separate fit, the changed
membership and each period's own diffuse start. Checkpoint 6 owns marking these
boundaries, and no automatic change may cross one.

Because the tuning gate is blocked, this checkpoint records the approved fallback
rather than enabling a curve: `support_validated` stays false for
`fi_candidate_2014_2018`, the individual FI estimate remains unavailable, its source
observations stay archived, and flipping it needs the tuning boundary decision plus a
recorded owner decision. Unsupported is never zero.

`CoverageValidationTest` checks the registered rules and every inadmissible rule
value, the development window against later polls, the supported dates, institute
count, largest gap and retained exclusions of a period, a thin period that fails
only the rules it breaches, a dense period bounded under every shift, and the
per-party boundary steps with a blocked tuning gate carried into the report.
`PollObservationsTest` checks that `PollObservations.shares` inverts the transform
back to the replaced composition in component order and stays closed on an extreme
state. `CoverageValidationIT` runs the archived development rows through
`validateAll` for both rosters.

Run `./mvnw -Dtest=CoverageValidationTest test` for the rules and `./mvnw clean
verify` against PostgreSQL 18 for the suite, where the integration test uses the
first registered shift and costs about 40 seconds. `./mvnw clean verify
-Dcoverage.full=true` runs every shift and rewrites `coverage.json`; that run took
1m40s wall on the development machine. These are development observations for
checkpoint 10 to measure properly, not frozen bounds.

### Daily estimate history, issue #18 checkpoint 6

`EstimateHistory` turns the validated coverage evidence into the daily series a
publication reads. It registers no new constant: the gap rule it cuts on is the
`max_internal_gap_days` of [protocol.json](protocol.json) that checkpoint 5 already
froze, read from the [coverage report](coverage.json) it is handed.

`estimate(period, polls, elections, parameters, rules)` applies the same development
window and supported-date trim as checkpoint 5, fits once and reports the smoothed
composition of every retained day in percent. The series runs from the period's first
eligible collection date through the last observation midpoint and stops there. Days
between polls carry an estimate, which is the point of a daily state space; days after
the last midpoint do not exist, because walking the state forward would be a
projection.

Support is cut, never bridged. When the run between two consecutive observation
midpoints exceeds the registered maximum, the observations are split there and **each
side is fitted separately**, over the outermost collection dates of the polls it kept
and with its own diffuse prior. Fitting once and slicing the output afterwards would
leave the daily walk propagating through the unsupported run, so the later level would
still borrow strength from the earlier polls: presentable, but bridged underneath. The
days inside the run appear in no segment, and unsupported is absent rather than zero.

`history(periods, polls, elections, coverage)` assembles the published record from the
periods whose roster is `support_validated` and whose recorded coverage evidence
passed. A period that is validated but whose evidence failed publishes nothing and
adds its own reason to the gate; a validated period with no recorded evidence at all
is an error rather than an empty curve. The coverage gate carries in whole, so while
the tuning boundary decision is open nothing here is a release value. A component that
only an unvalidated period models is listed under `unavailable` with a reason, which is
how FI leaves this checkpoint.

#### Boundaries and what may not cross them

Three kinds of date are marked, and no automatic change may cross any of them:

- `coverage_period_start`, where a separate fit begins with its own diffuse prior.
- `unsupported_gap`, where support ended and resumed.
- `centering_reference_change`, at a cycle reset whose institute ensemble differs from
  the previous cycle's. Checkpoint 3 left this one here: the level is centered on the
  institutes active in a cycle, so when that ensemble changes the reported level can
  step against a changed reference. On the archived rows **every** election reset is
  one, 2010-09-19, 2014-09-14 and 2018-09-09, so this is the boundary that bites in
  practice rather than a theoretical case.

`change(history, periodId, date, days)` returns a per-component difference only when
both days are estimated **by that period** and no boundary of it lies in
`(date - days, date]`. Otherwise it returns the reason:
`date_outside_supported_history`, `comparison_date_outside_supported_history` or
`comparison_date_across_boundary`. The period is an argument rather than a lookup
because coverage periods overlap in time: `fi_candidate_2014_2018` sits inside
`eight_party_2010`, so once a second period publishes, a date-only lookup would resolve
a change against the wrong series or suppress it on a foreign boundary. Criterion 7
owns change suppression proper; this is the enforcement of the boundaries checkpoint 5
handed here, and checkpoint 8 may take it over.

#### The headline and the internal filtered states

The headline is the last estimated day, reported as of the last fieldwork date of its
period. Those are two different dates and the record keeps both: on the archived rows
the estimate is `estimatedOn` 2021-09-20 and `asOf` 2021-10-03, a lag of 13 days,
because the poll with the latest fieldwork end has an earlier midpoint than the poll
with the latest midpoint. Closing that lag would mean walking the state forward with no
observation behind it, which the estimand forbids.

`internalFiltered` returns the filtered states of the same days. They condition only on
the polls seen up to each day, which is what a publication-time check needs and what a
published history must not show. `History` has no filtered field, so the published
record cannot carry them by accident.

#### The archived run

[history.json](history.json) stores the run of 2026-09-09 as a summary: each segment's
edges and day counts rather than every daily composition, with every composition in
roster order. The eight-party period is one
unbroken segment of 4,278 days from 2010-01-04 to 2021-09-20, since its largest internal
gap of 43 days sits inside the registered 45. It opens at S 35.09, M 26.57 and closes at
S 25.55, M 22.07, SD 20.05. `fi_candidate_2014_2018` publishes nothing and FI is
`no_validated_coverage_period`.

`EstimateHistoryTest` checks the retained days between polls and the stop at the last
midpoint, an over-long gap cut into two separately fitted segments with nothing
published between them and a discontinuous level across the cut,
the headline's two dates, changes inside one segment and suppressed across a gap and
across a reference change, filtered states differing from smoothed and absent from the
report, an unvalidated period leaving its party unavailable rather than zero, the
summary shape, and the rejected period with no recorded evidence.
`EstimateHistoryIT` builds the history of the archived development rows from the
committed coverage evidence.

Run `./mvnw -Dtest=EstimateHistoryTest test` for the rules and `./mvnw clean verify`
against PostgreSQL 18 for the suite, where the integration test costs about 10 seconds.
`./mvnw clean verify -Dhistory.full=true` rewrites `history.json`. These are development
observations for checkpoint 10 to measure properly, not frozen bounds.

### Reproducible joint uncertainty, issue #18 checkpoint 7

`JointUncertainty` turns the fitted daily states into draws. It registers the
`uncertainty` block and its `uncertainty_rules` prose in [protocol.json](protocol.json)
and reuses the `seed` and `final_draws` that were frozen for #16.

`estimate(period, polls, elections, parameters, coverage, rules)` draws over the same
separately fitted runs the daily history publishes, because it asks `EstimateHistory`
for them rather than splitting the observations again. Each day's centered smoothed
covariance is factorized, `final_draws` standard normal vectors are drawn, and each
resulting ilr state is transformed on its own.

**Support is transformed before it is averaged.** The reported `mean` is the arithmetic
mean of the transformed draws. `stateMean` on the same record is the transform of the
mean state, which is exactly the checkpoint 6 series, and the two are kept side by side
so the difference is visible rather than implied: on the archived rows the largest gap
between them is **0.028 percentage points**, over 4,278 days and nine components. The
transform is nonlinear, so these are two different numbers and only the first is
supported by the draws. Which one a publication prints is #21's to settle; this
checkpoint states that the draw mean is the one the draws justify.

Intervals are marginal quantiles of one component's own draws, at the registered 50%
and 95%, read by linear interpolation between the order statistics at `h = (n-1)p`. No
interval endpoint is ever summed with another; the comparable remainder needs its own
draws, which is checkpoint 8. The final day's 10,000 joint draws are retained on the
result, in percent and component order, because a threshold, seat or coalition quantity
has to be computed on the rows rather than on these summaries.

#### Seeding, and why every day has its own stream

Each day draws from a `java.util.Random` seeded by the leading eight bytes of
`SHA-256("<periodId>|<date>|<seed>")`. One shared stream would make a day's draws depend
on how many days the run computed before it and on the order threads finished, so the
same day would move when an unrelated day was added. `JointUncertaintyTest` checks that:
a run that goes on to draw a second segment returns the first segment unchanged.

The generator is deliberately the legacy `java.util.Random`, whose `nextGaussian` the
Java specification pins to a stated algorithm over `StrictMath.log` and
`StrictMath.sqrt`. Its draws are therefore the same on any conforming runtime, which is
what a cross-architecture reproduction claim will need.

#### What a rerun is given

Every run records what it would take to repeat: the seed and draw count, the period,
the roster and a digest of the orthonormal basis, a digest of the ordered archived rows
it read with their count, the fitted parameters, a SHA-256 over the compiled bytecode of
the eight estimator classes, the Java runtime and VM versions, the OS name and
architecture, and the linear-algebra version. The archived run reads 1,038 rows under
implementation digest `72bf0cb…` on Java 25.0.4 and EJML 0.46.1.

#### Results, in [uncertainty.json](uncertainty.json)

- **Seeded reproduction is exact.** A rerun at the registered seed returned all 90,000
  retained values with a maximum absolute difference of 0. That is the proposed bound:
  zero, on this implementation and this runtime. A cross-architecture rerun is still
  owed before it becomes a resolved tolerance.
- **Interval precision at 10,000 draws per day.** Across the registered seed and its
  seven successors, over all 4,278 estimated days, the largest movement of any 95%
  endpoint is **0.115 percentage points**, on S; of any 50% endpoint 0.058; of any mean
  0.041. Precision is best for the small components, down to 0.023 points on OTHER's 95%
  endpoints, since Monte Carlo error scales with the component's own spread. This bounds
  how much of a published interval is sampling noise rather than estimated uncertainty.
  It is a measurement, never something to enlarge afterwards.
- The headline of 2021-09-20 is S 25.56 [24.40, 26.74], M 22.07 [20.97, 23.17],
  SD 20.05 [19.03, 21.10] at 95%.

`Precision` reports the mean spread once per component and level; the mean does not
depend on the level, so those two rows carry the same number by construction.

Both proposed bounds are listed in the report with their units, largest observed error,
case count, seeds and rationale, which is what the tolerance registration above asks
for. The coverage gate carries in whole, so the tuning boundary decision still blocks
every number here from being a release value.

`JointUncertaintyTest` checks the registered rules and the inadmissible ones, that both
the drawn and the state composition close to 100 and that they differ, that the state
mean equals the day the daily history publishes, that 50% nests inside 95% and both
bracket the mean, exact reproduction at one seed and movement at another, per-day
streams, the retained final draws on the last segment of a split period, the spread
across four seeds, the recorded metadata and its response to a changed roster or a
dropped poll, and a covariance that is singular, asymmetric or nonfinite stopping the
run without jitter. `JointUncertaintyIT` draws the archived development rows at the
registered seed and count.

Run `./mvnw -Dtest=JointUncertaintyTest test` for the rules and `./mvnw clean verify`
against PostgreSQL 18 for the suite, where the integration test costs about 34 seconds
for its two full runs. `./mvnw clean verify -Duncertainty.full=true` runs the eight-seed
precision study and rewrites `uncertainty.json` in about 150 seconds. These are
development observations for checkpoint 10 to measure properly, not frozen bounds.

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
