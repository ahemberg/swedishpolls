# Validation protocol v1-development-1

Registered 2026-09-08 for [issue #16](https://github.com/ahemberg/swedishpolls/issues/16).
The [final handoff](https://github.com/ahemberg/swedishpolls/issues/9#issuecomment-5575883916)
supersedes earlier experiments and ADR wording about an untouched 2022 holdout.
[protocol.json](protocol.json) freezes dates, input identities and gate constants.
This registers procedures, not passing results.

The [final audit](#release-audit-issue-20) has now been run once, under the separate
release freeze [release-protocol.json](release-protocol.json). Its verdict is blocked:
the model is not releasable at `v1-development-1`.

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

`estimate(period, polls, elections, parameters, rules, draws)` applies the same
development window and supported-date trim as checkpoint 5, fits once and reports the
drawn composition of every retained day in percent. The series runs from the period's first
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

`history(periods, polls, elections, coverage, draws, publication)` assembles the published record from the
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

#### The published point estimate is the drawn mean, issue #76

The daily point estimate of a component is the arithmetic mean of its transformed joint
draws. The transform of the mean state, which is what this checkpoint first published,
is a different number and is now an internal diagnostic.

Two numbers cannot both be the point estimate, and the manifest had already chosen:
`uncertainty_rules.transform` in [protocol.json](protocol.json) registers that the
reported mean is the arithmetic mean of transformed draws and never the transform of the
mean state. This aligns a completed checkpoint with the manifest rather than overturning
it. The drawn mean is also the only additive option: the seat approximation, the preset
coalitions and the custom coalitions all read the draw rows, and the sum of drawn means
is the drawn mean of the sum. The state mean has no such property, so a coalition figure
would not match the parts displayed beside it. Finally the drawn mean is the posterior
mean of the published quantity and lies inside the published interval by construction,
since both come from the same draws.

The two series differ by at most **0.028 percentage points** over the 4,278 archived
development days and nine components, which
[uncertainty.json](uncertainty.json) records as `maxStateMeanShiftPoints`.

`internalStateMean` returns the transform of the mean smoothed state of the same days.
It is deterministic and costs no draws, which is worth keeping as a diagnostic, and it
is shaped like `internalFiltered`: a `Composition` per day with no interval. `Day` has
no state-mean field, so the published record cannot carry it by accident.

A published day is read once. `EstimateHistory` asks `JointUncertainty` for the day's
summary, so a day's mean, lower and upper come from one pass over one set of draws
rather than from two runs of the estimator, and the mean the history publishes is the
same number the uncertainty report publishes.

#### Publication resolution: one decimal

`publication.decimals` in [protocol.json](protocol.json) is **1**, and `report` quotes
every published mean and interval endpoint at that resolution. The series itself keeps
every digit it drew, because `change` differences two days and must not difference two
rounded numbers.

The evidence is the repeated-seed study in [uncertainty.json](uncertainty.json), over
the registered seed and its seven successors at 10,000 draws per day. The largest
movement of a mean across seeds is **0.041 percentage points** and of an interval
endpoint **0.115**, both maxima over 4,278 days and nine components. Both exceed the
0.01 the reports used to quote, so a second decimal is draw noise presented as an
estimate. At one decimal the point estimate this ticket publishes is stable: 0.041 sits
inside half a 0.1 tick.

The alternative was to raise the draw count instead. Monte Carlo error falls with the
square root of the draws, so holding a 95% endpoint inside half a tick would need
`(0.115 / 0.05)² ≈ 5.3` times as many, about 53,000 per day, and 5.3 times the runtime
below. `final_draws` was frozen for #16 and this ticket changes no registered constant,
so the raise is not taken here. The residual is recorded rather than hidden: at one
decimal a 95% endpoint can still move by one tick across seeds, which is the
`interval_endpoint_precision` proposed tolerance that checkpoint 10 owns.

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

[history.json](history.json) stores the run of 2026-09-10 as a summary: each segment's
edges and day counts rather than every daily composition, with every composition in
roster order and each component carrying the mean and the intervals read off the same
draws. The eight-party period is one
unbroken segment of 4,278 days from 2010-01-04 to 2021-09-20, since its largest internal
gap of 43 days sits inside the registered 45. It opens at S 35.1, M 26.6 and closes at
S 25.6, M 22.1, SD 20.0. `fi_candidate_2014_2018` publishes nothing and FI is
`no_validated_coverage_period`.

`EstimateHistoryTest` checks the retained days between polls and the stop at the last
midpoint, an over-long gap cut into two separately fitted segments with nothing
published between them and a discontinuous level across the cut,
the headline's two dates, changes inside one segment and suppressed across a gap and
across a reference change, filtered states differing from smoothed and absent from the
report, the published day matching the drawn summary component for component while the
state-mean diagnostic matches the transform of the mean state, the report quoting every
number at the registered resolution while the series keeps its digits, an unvalidated
period leaving its party unavailable rather than zero, the summary shape, and the
rejected period with no recorded evidence.
`EstimateHistoryIT` builds the history of the archived development rows from the
committed coverage evidence, checks that the drawn mean and the state mean are two
different series over those days, and that every published mean lies inside its own
published intervals.

Run `./mvnw -Dtest=EstimateHistoryTest test` for the rules and `./mvnw clean verify`
against PostgreSQL 18 for the suite. The daily series stopped being nearly free when it
started drawing: the integration test costs about **26 seconds** inside a full
`./mvnw clean verify` on the development machine and about **43 seconds** run on its own
from a cold JVM, against the about 10 seconds recorded before it drew. Two thirds of the
added time is the 10,000 draws on each of the 4,278 days; the rest is the state-mean
diagnostic the test now compares against, which refits without drawing.
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
supported by the draws. #76 settled which one the daily history publishes: the drawn
mean, with the state mean kept as an internal diagnostic.

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
implementation digest `3139c01…` on Java 25.0.4 and EJML 0.46.1.

#### Results, in [uncertainty.json](uncertainty.json)

- **Seeded reproduction is exact.** A rerun at the registered seed returned all 90,000
  retained values with a maximum absolute difference of 0. That is the proposed bound:
  zero, on this implementation and this runtime. The cross-architecture evidence is
  recorded separately below.
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

### Comparable remainder and changes, issue #18 checkpoint 8

`ComparableRemainder` summarizes the support outside the fixed eight parties S, M, SD,
V, C, KD, L and MP. It registers no new constant: it draws at the `seed`, `final_draws`
and `interval_levels` checkpoint 7 already froze, and it adds `remainder_rules` prose to
[protocol.json](protocol.json).

**The remainder is summed inside each draw.** Every draw of a day is transformed to
percent, its remainder members are added within that draw, and only the resulting column
of sums is averaged and cut into quantiles. Adding the members' own interval endpoints
instead would ignore how they covary and would report an interval nothing was drawn
from. The run measures the difference and records it as `maxEndpointSumErrorPoints`, so
the two are visibly not the same number rather than assumed to differ.

FI and the residual are combined **only where the roster keeps FI separate**. Where FI
is not individually represented, OTHER already contains it, so the remainder is OTHER
itself and the two agree to the last bit: the archived run reports
`maxEndpointSumErrorPoints` of exactly 0 for `eight_party_2010`, which is a one-member
sum. The FI-separate case is a unit check, since no validated period represents FI yet.

The remainder reads the same fitted runs and the same per-day draw streams as the
component summaries, through the same `EstimateHistory.fitted` split, so the two are one
set of draws rather than two runs of the estimator. Its segments and its boundaries are
therefore the daily history's own, and `change` suppresses a span across a separate fit,
an unsupported gap or a changed centering reference with the reason checkpoint 6
registered. `changes(estimated)` reports the change over each run a segment is cut into
by its boundaries and the one-day change across each boundary, which needs no horizon
constant to demonstrate what may span a break and what may not.

#### Grouping an election reference

An official result arrives in ten components: the eight parties, FI and the residual.
`group(period, election, segments)` reads it in the period's own components before it is
displayed against that period, so the same result shows OTHER where FI is not separate
and FI beside RESIDUAL where it is. The comparable remainder combines FI and the
residual in every period, so the aggregate means one thing across a roster change and
two periods' remainders can be compared at all. Election results stay references: they
are never observations, and grouping one does not extend the estimated history to its
date.

#### Results, in [remainder.json](remainder.json)

- The headline remainder of 2021-09-20 is **1.57 [1.38, 1.78]** at 95% and
  [1.50, 1.63] at 50%, over 4,278 estimated days in one segment.
- The four official results are grouped into the eight-party components and each closes
  to 100. Their comparable remainders are 1.43 (2010), 4.09 (2014), 1.53 (2018) and 1.55
  (2022); 2014 is the FI year. The 2022 result is outside the development history and is
  marked `insideSupportedHistory: false` rather than dropped.
- The segment carries three centering-reference changes, one at each election cycle
  reset. The remainder change is available over each of the four runs between them, at
  -0.28, +2.38, -1.25 and -0.90 points, and is suppressed across every reset.

The coverage gate carries in whole, so the tuning boundary decision still blocks every
number here from being a release value.

`ComparableRemainderTest` checks that FI and the residual are combined only where FI is
separate, that the one-member remainder equals OTHER's own summary to 1e-12, that the
summed-draw interval is neither the sum of the members' endpoints nor as wide as it,
that the levels nest and stay inside the composition, that the segments and boundaries
are the daily history's own, each suppression reason, a change across a centering
reference inside one segment, one election grouped two ways into the same aggregate,
inadmissible references, an election outside the estimated history, and a report that
publishes only validated periods and carries the gate. `ComparableRemainderIT` draws the
archived development rows at the registered seed and count in about 38 seconds;
`-Dremainder.full=true` rewrites `remainder.json`.

Run `./mvnw -Dtest=ComparableRemainderTest test` for the unit checks and
`./mvnw clean verify` for the suite. These are development observations for checkpoint
10 to measure properly, not frozen bounds.

### Development diagnostics and sensitivity, issue #18 checkpoint 9

`DevelopmentDiagnostics` scores the frozen publication-time folds of
[protocol.json](protocol.json) and reports what the scores are worth: predictive
coverage with the observed poll noise, residual autocorrelation, misfit by party,
institute and fieldwork length, overlapping-poll dependence, and the two registered
sensitivity reruns. It registers the `diagnostics` block and its `diagnostics_rules`
prose, and reuses the `seed`, `score_horizon_days`, `predictive_coverage95` and
`predictive_coverage50` that #16 froze.

A fold trains on the eligible polls published on or before its cutoff, which is the
same rule checkpoint 4 tuned on, and scores every eligible poll published in the next
35 days. The candidate reads the parameters [tuning.json](tuning.json) resolved for
that fold and roster, so the scored model is the tuned one rather than a fresh fit.
Three candidates predict the same polls:

- the **midpoint candidate**, the registered estimator, where a poll observes the
  latent state of its own fieldwork midpoint;
- the **recency baseline**, a weighted arithmetic average of the eligible polls of the
  preceding 120 days at a 30-day half-life, with no house effect and no trend, whose
  predictive covariance is its own sampling variance at `n_eff`, the weighted spread of
  the polls it averaged, and the held-out poll's own sampling variance at multiplier
  one;
- the **ilr-window reference**, which observes the arithmetic average of the daily
  latent states over the whole fieldwork span, tuned independently on the same training
  data and the same frozen grid.

#### One filter, two conventions

`WindowFilter` carries a window exactly, by holding the running sum of the days it
covers in the joint state beside the opinion and the house effects. A poll's
observation is that sum divided by its own length, so the reference is an average of
daily ilr states and never an average of inverse-transformed daily shares. The midpoint
candidate is the same filter with every window collapsed to one day, which is what
makes the comparison a comparison of conventions rather than of implementations.

That claim is checked rather than asserted. `WindowFilterTest` runs the collapsed
filter against `DailyStateSpace.logLikelihood` on both rosters, with overlapping
windows and a cycle boundary, and agrees to 1e-9; and it runs both conventions against
an independent dense Gaussian system, built from the random walk's own `min(s,t)`
covariance and inverted whole, for the training likelihood and for every held-out
predictive mean and covariance.

#### What a prediction conditions on

A held-out poll is never filtered. Its running sum stays in the state until the last
training observation has been, so a poll whose fieldwork closed before the cutoff is
still predicted from the whole training set rather than from the part of it that
happens to precede the poll. The dense reference covers that case directly.

House effects reset at an election, but a poll measured before one can be published
after it. A cycle's effects therefore stay in the state until every poll measured in
that cycle has resolved, so such a poll reads its own house rather than a fresh prior.
The first dense comparison failed on exactly this before the effects were kept alive.

Coverage is read off joint draws of each poll's predictive distribution, transformed
one draw at a time, because a party's share is not a coordinate of the Gaussian. Each
scored poll draws 4,000 times from its own stream, seeded by the leading eight bytes of
`SHA-256("<periodId>|<cutoff>|<candidate>|<row>|<seed>")`, so a poll's interval does not
depend on how many polls the fold scored before it.

#### Results, in [diagnostics.json](diagnostics.json)

The full run scored 75 folds, 48 on the eight-party roster and 27 on the candidate FI
roster, over 370 and 200 held-out polls. It listed 21 unscored folds rather than
dropping them: 2 the tuning run never resolved, and 19 where the FI candidate period,
which ends 2018-09-07, can compose no held-out poll at all.

**The midpoint candidate beats the recency baseline comfortably and loses narrowly to
the window reference.** On the eight-party roster the mean paired log score against the
baseline is **+1.160** per poll, and against the reference **-0.024**, with a paired
standard error of **0.012** at the frozen lag of three. That is about two standard
errors the wrong way, so the registered comparison fails: the protocol asks for a
reference difference no worse than `-SE`. The same statistic at lags 1 and 6 is 0.013
and 0.013, so the failure is not an artifact of the truncation lag, and no lag produces
a pass. On the FI roster the candidate is ahead of the reference by +0.018 against an
SE of 0.014 and passes. Averaging over the fieldwork window is therefore a real, small
improvement on the eight-party data, and it is checkpoint 10 and the owner's to decide
whether the estimand changes with it: the two conventions do not estimate the same
quantity on a poll that ran for three weeks.

**Predictive coverage is inside both registered bands.** Pooled over every party and
poll, the candidate covers **92.7%** at the 95% level and **51.8%** at the 50% level on
the eight-party roster, and 91.3% and 48.2% on the FI roster; the registered bands are
[0.90, 0.98] and [0.40, 0.60]. The recency baseline is closer to nominal (93.9% and
49.8%) while scoring a full nat per poll worse, which is what a wide, badly located
predictive distribution looks like.

**The misfit is concentrated in the small components and the short windows.** By party,
KD covers 86.5% at the 95% level with a standardized residual root mean square of 1.84,
and OTHER covers 84.9% at 1.47, while S covers 98.4% at 0.77. The model is too narrow
where the composition is smallest and too wide on the largest party. By fieldwork
length, coverage is 91.9% for 1-7 day windows, 92.3% for 8-14 and 94.8% for 15+, with
residual root mean squares of 1.29, 1.15 and 1.01: the shortest windows are the worst
calibrated. Their mean log score is also the lowest, but that number confounds the
window with the sample size, whose median rises from 1,424 to 1,844 to 2,000 across the
same three bands, so the coverage and the residual spread are the calibration evidence
and the score is not. By institute, Skop is the outlier at 82.8% coverage, a residual
root mean square of 1.69 and a mean log score of -0.24 against Novus's 7.78.

**Residuals of one fold are strongly dependent, and most of that is structural.** The
whitened residual autocorrelation is 0.41, 0.41 and 0.43 at lags 1 to 3 on the
eight-party roster and 0.39 to 0.41 on the FI roster. Every poll of a fold is predicted
from one training state, and every poll of an institute from one house effect, so their
residuals share those errors by construction; a per-poll predictive distribution is
correctly calibrated marginally, as the coverage above shows, and still gives dependent
residuals. The pair measurements separate what that explains from what it does not:
pairs with overlapping fieldwork average 1.09 against 0.84 for disjoint pairs, and
same-institute pairs average 2.55. Overlap adds dependence beyond the shared state, and
institute clustering dominates both. This is why the gate averages within a fold before
weighting folds equally, and why its standard error is the Bartlett one; a per-poll
standard error over 370 dependent polls would be far too small. What no registered
constant yet says is how much of this blocks sign-off, which is checkpoint 10's to
freeze.

**Both sensitivity reruns move the headline by under half a point and the history by up
to one.** Poll-count centering moves the 2021-09-20 headline by at most **0.28 points**,
on SD, and no day of the 4,278 by more than 0.33. Leaving one institute out moves the
headline by at most **0.44 points**, on SD when Sentio's 119 polls are dropped. The
headline is one day, though, and an institute that stopped publishing years ago cannot
move it: United Minds, whose last poll is 2014-09-11, and YouGov, whose last is
2018-09-01, both move it by under 0.001 points. The largest movement on any day both
runs estimate is reported beside it, and it is where the evidence is: dropping Ipsos
moves the composition by **1.02 points** on 2010-11-16, dropping YouGov by 0.96 on
2016-01-16, and every institute lands between 0.53 and 1.02. Nothing approaches the
10-point movement that would need a disclosure beside the number, and neither rerun
blocks release on its own. Dropping Ipsos or Demoskop also shortens the supported
window, to 4,274 and 4,231 compared days, which is itself part of what leaving an
institute out costs.

#### The gate

`report` blocks on 26 reasons. The coverage gate carries in whole, so the tuning
boundary decision is still the first of them. The new ones are the failed eight-party
reference comparison, the ilr-window reference resolving on a grid boundary in 18 and
17 folds of the two rosters, which needs the same documented expansion as the
candidate's, and every unscored fold. Nothing here is a release value, and no number
was measured on the reserved comparison: the last scoring horizon ends 2021-11-09, and
`report` refuses a protocol whose horizon reaches 2022 at all.

`DevelopmentDiagnosticsTest` checks the registered rules and the inadmissible ones, the
held-out filter against a late publication, an unknown publication date and an
ineligible row, the paired standard error against `PredictiveComparison`'s own at the
frozen lag, the baseline's window, half-life, fallback and widened prediction, a fold
scoring all three candidates on one held-out set with its recorded identities, the
coverage and misfit grouping by party, institute and fieldwork band, and that
poll-count centering moves the reference without moving the fit.
`DevelopmentDiagnosticsIT` scores the first eight folds of the archived development
rows in about 110 seconds; `-Ddiagnostics.full=true` scores all 48 and rewrites
`diagnostics.json` in about 6 minutes, most of it tuning the reference over 80 grid
points per fold.

Run `./mvnw -Dtest=DevelopmentDiagnosticsTest test` for the rules and `./mvnw clean
verify` for the suite. These are development observations for checkpoint 10 to measure
properly, not frozen bounds.

### Development gates and resources, issue #18 checkpoint 10

`DevelopmentGates` reads the frozen protocol and the committed coverage, uncertainty
and diagnostic evidence into one fail-closed result. `requirePassed` throws while that
result is blocked, which gives the later publication worker one check before it can
expose an estimate. The report preserves every upstream reason rather than replacing a
failed model with a different estimator.

The development-derived bounds are now frozen in [protocol.json](protocol.json):

| Check | Frozen maximum | Largest observed | Evidence |
| --- | ---: | ---: | --- |
| Same-runtime seeded reproduction | 0 points | 0 points | 90,000 retained values at seed 20260908 |
| Cross-architecture reproduction | 0.00000000000002 points | 0.000000000000017763568394002505 points | 90,000 retained values at seed 20260908 |
| Historical interval endpoint precision | 0.12 points | 0.11533204035550426 points | 77,004 component, level and day comparisons over eight seeds |
| Coverage-boundary stability | 0.5 points | 0.4252180277614137 points | 155,469 component-day comparisons over the registered 7, 14 and 30 day shifts |
| Probability Monte Carlo standard error | 0.005 | 0.005 | Worst case at 10,000 draws |
| 4% threshold probability precision | 0.03 | 0.0167 | 80,000 final-day draws over eight seeds |
| Tido 175-seat probability precision | 0.03 | 0.0152 | 80,000 national allocations over eight seeds |
| Changed-snapshot drift | 0.44 points | 0.43730250968707196 points | 770,004 component-day comparisons over 20 source perturbations |

The same-seed rerun on amd64 remains bit-for-bit exact. The matching arm64 run used
the same compiled classes, archived input, parameters and seed. Floating-point paths
differed in 42,697 of 90,000 retained final-day values, with a maximum difference of
`1.7763568394002505e-14` percentage points. [cross-architecture.json](cross-architecture.json)
records both Java 25.0.4 runtimes and draw digests. The frozen `2e-14` bound therefore
describes numerical reproduction across the two architectures. The same-runtime gate
remains exactly zero and cannot be weakened by that separate bound.

Subgroup coverage uses the registered 95% and 50% bands when a group has at least 100
cases. Absolute mean standardized residuals are limited to 0.5 and root-mean-square
residuals to [0.5, 1.5]. Residual autocorrelation is limited to 0.45 after inspection of
the reported overlap and same-institute dependence. Several party and institute groups
fail these frozen rules, so they appear as named blocking reasons.

Eight repeated final-day seeds exercise the inclusive 4% threshold and the 175-seat
line. Each draw allocates all 349 national seats with the registered modified
Sainte-Laguë rule, first divisor 1.2 and deterministic tie order. The MP threshold
probability ranges from 0.4113 to 0.4280; the Tido coalition's majority probability
ranges from 0.5662 to 0.5814. Both ranges pass the frozen 0.03 precision bound.
[probability-precision.json](probability-precision.json) records the fitted period,
date, components, input and implementation digests, allocation rule and every seed.

The drift study selects the last eligible development poll from each of the ten
institutes. Removing a row measures a deletion and, in reverse, a newly added row. A
second case moves 0.1 percentage points from OTHER to M in that row. Every case refits
the corrected history and compares every component on every retained common day. The
largest revision is the addition or deletion of Demoskop row 411, 0.43730 points on S
on 2021-09-04. The largest fixed correction moves M by 0.03992 points. The source bytes
retain SHA-256 `27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608`.

The production-shaped resource run computes corrected history, joint component
uncertainty, repeated-seed threshold and majority probabilities and the comparable
remainder once, using only pre-2022 election references and excluding database setup
and evidence parsing. On amd64 with Java 25.0.4 it took 124,234 ms for 1,038 observations,
4,278 days and 10,000 final draws per seed. The sum of peak-used JVM heap pools was
593,599,976 bytes. This misses the registered ten-second optimization target; that
target is not an unconditional release gate.

[development-gates.json](development-gates.json) contains the complete result and
evidence hashes. Conditional predictive coverage is inside both registered bands, so
the hyperparameter grid-mixture fallback is not required. The result is still blocked
by the carried tuning failures, the eight-party loss to the ilr-window reference,
unscored FI folds and subgroup misfit. No fallback, implicit estimator change or waiver
clears those failures. The resource run does not query the reserved 2022 outcome.

Run `./mvnw -Dtest=DevelopmentGatesTest,DevelopmentGatesIT test` for the executable
stop and evidence checks. `-Dgates.full=true` repeats the drift and resource study and
rewrites the report. `ReproductionProbe` emits the retained-draw digest or binary values
for the same native and emulated architecture comparison recorded above.

### National seats and preset coalitions, issue #19

`NationalSeats` allocates 349 seats from the final-day joint draws under the era rule
stored in `national_allocation_rule`, `Coalitions` groups the ten approved presets from
the same draws, and `SeatOutcomes` records the evidence. The full explanation of the
approximation, its omitted constituency rules and the deterministic tie order is in
[seats and coalitions](../seats-and-coalitions.md).

[seats.json](seats.json) records the development-day result for `eight_party_2010` on
2021-09-20, over 10,000 draws at seed 20260908. Point seats allocate the posterior mean
support once; mean seats average the 10,000 drawn allocations. The two are visibly
different quantities: MP averages 3.96% support, so it holds no point seats, while
reaching 4% in 41% of the draws and averaging 6.2 seats.

| Quantity | Largest spread over eight seeds | Frozen maximum |
| --- | ---: | ---: |
| 4% threshold probability (MP) | 0.0167 | 0.03 |
| 175-seat probability (tido) | 0.0152 | 0.03 |

Those are the same two spreads [probability-precision.json](probability-precision.json)
recorded for the development gates, reproduced here by the published allocator rather
than by a second implementation of it.

Sensitivity reruns the headline probabilities under poll-count centering and under each
institute left out of the fit, and reports the differences in percentage points. Three
leave-one-out cases move a headline probability more than ten points and carry an adjacent
disclosure: `majority:opposition` moves 16.8 points without Sifo, `threshold:MP` moves
13.7 without Skop and `majority:tido` moves 12.9 without Demoskop. The poll-count
centering rerun moves at most 7.3 points and needs none. A disclosure is not a block.

Each stored official result is allocated under its own era rule beside its official seats.
2018 and 2022 reproduce the official allocation exactly; 2010 differs by at most three
seats and 2014 by at most two, which is the constituency machinery the national
approximation omits. That comparison reads a published outcome and no poll, fit or model
output, so it does not consume the reserved once-only 2022 statistical audit.

Run `./mvnw -Dtest=NationalSeatsTest,CoalitionsTest,SeatOutcomesTest test` for the
allocation, catalogue and evidence checks, and the `NationalSeatsIT` commands in
[seats and coalitions](../seats-and-coalitions.md) for the stored rules and the recorded
result. `-Dseats.full=true` recomputes every draw, seed and sensitivity rerun and rewrites
the report.

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

The manifest freezes the development tolerances above and the report records their
units, maximum observed error, case count, seeds, evidence hashes and rationale.
Cross-architecture reruns, changed-snapshot perturbations and repeated-seed interval,
threshold and majority probabilities provide the evidence. Never enlarge tolerances
after seeing audit results. A failed gate requires a recorded owner waiver or a new
method with an explicit disclosure that the old audit is now development evidence.

Recompute equal-weight versus poll-count centering and leave-one-institute-out
headline probabilities. Publish differences. A movement over 10 percentage points
requires disclosure next to the number; it does not itself block release.

## Publication-time settings, issue #113

[Issue #21](https://github.com/ahemberg/swedishpolls/issues/21) introduced three numbers
into the publication path that no decision had registered.
[Issue #113](https://github.com/ahemberg/swedishpolls/issues/113) resolved them. ADR 0007
makes this evidence the shipped freeze's source of truth, so what follows is where those
three numbers now stand.

**Movement against the previous publication is not a check.** The worker used to block a
candidate whose headline share moved more than 10 points against the publication before it.
Two publications are separated by weeks of new fieldwork, and a share can legitimately move
as far as the polls do, so a bound on that distance is a claim about opinion rather than
about the estimator. The check and its `maxDriftPoints` setting are removed. The registered
`snapshot_drift_points` of 0.44 in [protocol.json](protocol.json) is a different quantity —
how far the estimate moves when the same snapshot is perturbed — and is unchanged. The
publication-time checks that remain are the composition sum, the seat total and exact
seeded reproduction of the retained draws.

**`shrunk` means the model applies shrinkage toward zero.** The published per-effect flag
used to be set when the posterior standard deviation exceeded half the house scale, which
read as a verdict on whether an institute had enough polls to be believed. No evidence
established that threshold or that reading. The flag now follows from the registered prior:
house effects are fitted under a zero-centred prior with a strictly positive house scale, so
for every effect this model produces it is true. It says the prior applies. How much the
polls say is the interval beside the number, and a sparse institute is not reliably the one
with the wider interval. Retained publications keep the values they were published with.

**A publication draws one seed.** `JointUncertainty.Rules` used to refuse a repeat count
below two, so the publication path carried a fabricated count of two that no publication
read. One seed is now admissible in the rules, and `precisionSeeds` — the repeated-seed
study itself — is what refuses fewer than two. The registered `precision_repeats` of 8
stands and no required precision study can run on a single seed.

**Registration covers every shipped setting.** Statistical parameters, publication
thresholds, seeds, draw counts and rounding precision need a registered source or a written
derivation; ordinary implementation constants, such as array indices, do not and never reach
the shipped resource. [publication-settings.json](publication-settings.json) lists every
field of `src/main/resources/publication/model-freeze.json`, nested fields included, against
the evidence it is copied from or the derivation it follows from. `ModelFreezeTest` fails on
a shipped field that is in neither, on a registered field that stops being shipped, and on a
shipped value that disagrees with its registered one. A running application reads only the
shipped resource; the evidence stays in this repository.

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

## Release audit, issue #20

[release-protocol.json](release-protocol.json) is the release freeze, registered
`v1-release-1` on 2026-09-10. It names the development protocol and the four evidence
files by SHA-256 rather than editing them, so `v1-development-1` and everything measured
against it keep the inputs they were measured with. It also freezes what this ticket
added: the composition sum bound of `1e-9` points, the 349-seat allocation total, the
minimum of eight scored folds per roster, the ten-point sensitivity disclosure line, the
runtime target as reported rather than blocking, and the approved unavailable-estimate
fallback for the individual FI roster. Two later amendments are recorded in its own
`amendments` list, both from
[#240](https://github.com/ahemberg/swedishpolls/issues/240): the institute-scope and
fieldwork-length-scope subgroup gates joined `reported_not_blocking`, and the paired
predictive comparison was registered as deferred. `ReleaseAudit.frozen` refuses a registration whose
development protocol or evidence has moved since the freeze, so the audit cannot run
against inputs the freeze never saw.

`ReleaseAudit` then reads that freeze, the once-only reserved comparison and
[development-gates.json](development-gates.json) into one verdict, and `requireReleasable`
throws while that verdict is blocked. Nothing here retunes, relaxes or substitutes an
estimator: a failed gate is carried with its reason.

### The reserved 2022 comparison

`select` applies the pinned candidate rule to the source bytes and reproduces the
manifest's 1,192 rows and their `b539aef8…` identity digest before anything is fitted.
Row identities are read off the physical CSV lines, not the parsed fields, so the check
verifies the file rather than this parser's view of it. Of those candidates the
eligibility rules retain 1,168 observations, which the eight-party period fits as one
run, with an innovation log likelihood of 6,809.31.

The series ends at the last observation midpoint, **2022-09-07**, because no day is
projected past the evidence. The election was 2022-09-11, so the four days between them
are movement the estimator never saw, and they are part of every error below.

| Component | Estimate | Official | Error (points) | In 50% | In 95% |
| --- | ---: | ---: | ---: | :---: | :---: |
| S | 28.40 | 30.33 | -1.93 | no | no |
| M | 17.34 | 19.10 | -1.76 | no | no |
| SD | 20.92 | 20.54 | +0.38 | no | yes |
| V | 7.64 | 6.75 | +0.89 | no | no |
| C | 7.13 | 6.71 | +0.42 | no | no |
| KD | 6.08 | 5.34 | +0.74 | no | no |
| L | 5.30 | 4.61 | +0.69 | no | no |
| MP | 5.69 | 5.08 | +0.61 | no | no |
| OTHER | 1.52 | 1.54 | -0.02 | yes | yes |

The mean absolute error is 0.83 points and the largest is 1.93 on S. One of nine official
shares falls inside its marginal 50% interval and two of nine inside the 95% interval.
The pattern is one-directional: both large parties are estimated low and all seven
smaller components except OTHER high. Nine compositional errors sum to zero by
construction, so they are not nine independent misses, and this single comparison does
not separate estimator bias from the four days of movement it never saw. Official shares
are the published
two-decimal percentages, so each carries 0.01 percentage-point resolution and OTHER,
defined as exactly 100 minus the other eight, accumulates their rounding.

The national approximation allocates all 349 seats from the drawn mean composition under
the 2022 rule, beside the official constituency allocation and never merged with it:

| Component | Approximate | Official | Difference |
| --- | ---: | ---: | ---: |
| S | 101 | 107 | -6 |
| M | 61 | 68 | -7 |
| SD | 74 | 73 | +1 |
| V | 27 | 24 | +3 |
| C | 25 | 24 | +1 |
| KD | 22 | 19 | +3 |
| L | 19 | 16 | +3 |
| MP | 20 | 18 | +2 |

**None of this is a gate.** The registration records no pass or fail for the reserved
comparison, and adding one after seeing it would be tuning on the outcome. These are
reference numbers from one retrospective pre-election diagnostic computed on today's
corrected snapshot, whose polling inputs and public outcome were both already exposed to
method selection. Any fit ending 2022-08-31 is pre-election evidence on the same terms.
Neither is election-forecast calibration; the estimand stays voting intention on the last
fieldwork date, and the prospective 2026 registration below remains the forward
evaluation.

The audit ran once. Rerunning it at the frozen seed reproduced all 90,000 retained
final-day draws exactly, which is the archived-input seeded reproduction gate and not a
second audit.

### The verdict, in [release-audit.json](release-audit.json)

The report records 37 gates, six of them blocking. Five of the six pass: the frozen
evidence digests, the frozen parameters and seed the reserved fit ran at, the composition
summing to 100 within 1e-13 points from a finite fit, the 349-seat total, and the exact
seeded reproduction. The sixth, `development_gates`, fails and carries the 49 reasons
[#18](https://github.com/ahemberg/swedishpolls/issues/18) left standing. Eight of those 49
are the institute-scope and fieldwork-length-scope subgroup findings that
[#240](https://github.com/ahemberg/swedishpolls/issues/240) reclassified as reported, so
they no longer reach the verdict: it is **blocked** on 42 reasons rather than 50, and
`requireReleasable` throws.

The remaining 31 gates are reported: they restate, per gate, what the aggregate flattens
into a list of strings.

- **Predictive comparison.** The midpoint candidate beats the recency baseline on both
  rosters, by +1.160 and +0.764 per poll. Against the ilr-window reference it passes on
  the FI roster (+0.018 against an SE of 0.014) and fails on the eight-party roster
  (-0.024 against an SE of 0.012), which the protocol asks to be no worse than `-SE`. That
  failure is registered as deferred in `deferred_decisions`, pending the observation-model
  fix: both compared methods carry the same misfit. A deferral changes no enforcement and
  clears no failure, so the comparison still stands as failed and still blocks through
  `development_gates`.
- **Coverage.** Pooled predictive coverage is inside both registered bands on both
  rosters: 92.7% and 51.8% on the eight-party roster, 91.3% and 48.3% on the FI roster.
  The grid-mixture fallback is therefore not invoked.
- **Folds.** Both rosters clear the eight-fold minimum with 48 and 27 scored folds, and
  the FI roster carries 21 unscored folds rather than dropping them.
- **Systematic misfit by party, institute and fieldwork length.** Itemized per subgroup
  against the frozen limits. On the
  eight-party roster S, KD and OTHER fail by party and Sifo and Skop by institute, while
  all three fieldwork-length bands pass. On the FI roster S, KD, FI and RESIDUAL fail by
  party, Inizio, Sifo and Skop by institute, and the 1-7 day band fails. Naming a scope's
  gate in the registration's `reported_not_blocking` list keeps that scope's findings out
  of the verdict, and the institute and fieldwork-length gates are named there because the
  site publishes no house-calibrated and no fieldwork-conditional quantity; see
  [ADR 0013](../adr/0013-report-institute-and-fieldwork-subgroup-gates.md). The party
  gates are not named, so party findings still block. The misfit is
  concentrated in the small components and the short windows, which is what
  [checkpoint 9](#development-diagnostics-and-sensitivity-issue-18-checkpoint-9)
  measured; the frozen limits reject it rather than explain it away.
- **Residual autocorrelation and overlap dependence.** Reported per roster with the pair
  measurements that say how much of it is structural. All three registered lags are inside
  the frozen 0.45 limit on both rosters, at 0.409 to 0.432 on the eight-party roster and
  0.393 to 0.415 on the FI roster. Beside them, overlapping-fieldwork pairs average 1.09
  and 1.15 against 0.84 and 0.83 for disjoint pairs, and same-institute pairs 2.55 and
  2.59. Overlap adds dependence beyond the shared training state and institute clustering
  dominates both, which is the registered explanation the gate carries rather than one this
  code infers.
- **Tolerances.** All eight frozen tolerances pass, each with its own evidence file and
  digest.
- **Sensitivity.** Poll-count centering moves the headline by at most 0.28 points and
  leaving out an institute by at most 0.44, against a ten-point disclosure line. Neither
  rerun needs adjacent disclosure, and neither blocks release on its own.
- **Resources.** The production-shaped run took 124,234 ms against the ten-second target.
  The gate is built blocking and the registration's `reported_not_blocking` list
  downgrades it, matching the protocol's own wording that the recompute target is not an
  unconditional release gate. Removing it from that list blocks the release.
- **Individual FI.** Its support dates, 2014-04-09 to 2018-09-07, match the registered
  roster boundaries exactly and it fits as one separate run over 388 observations, so the
  separate-fit behaviour is confirmed. Thirty-four frozen gate reasons name the roster,
  so it does not meet the gates and the approved `individual_fi_estimate_unavailable`
  fallback applies: FI estimates stay unavailable while its source observations are
  retained and served. The fallback waives no other gate.

There is no waiver. `frozen.waivers` is empty, an unregistered gate name in it is refused,
and only an explicit owner waiver of a named gate can clear a blocking failure. Nothing
in this run cleared one, so v1 is not releasable at `v1-development-1`.

Run `./mvnw -Dtest=ReleaseAuditTest test` for the manifest, waiver and verdict rules and
`./mvnw -Dit.test=ReleaseAuditIT verify` for the archived audit. `-Daudit.full=true`
refits the reserved comparison and rewrites the report; it must reproduce the archived
numbers, and it is a reproduction rather than a new audit.

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
