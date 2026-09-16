# Bounded synthetic recovery protocol

Status: accepted by the owner on 2026-09-16. Version: `synthetic-recovery-v1`.
Decision ticket: [#184](https://github.com/ahemberg/swedishpolls/issues/184).

## Purpose and authority

Determine whether predictive poll intervals recover their nominal coverage when
synthetic observations follow each method's assumed Gaussian model. The owner
accepted the scope, generating assumptions, two parameter stages, scenario,
coverage ranges, simultaneous-confidence approach, repetition count and retention
policy during the #184 interview. On 2026-09-16, the owner confirmed the complete
protocol, including the exact scheduling, seed, preflight and completion rules
and the unchanged-regression-test exception below.

This follows the [#160 outcome review and investigation](https://github.com/ahemberg/swedishpolls/pull/183).
It does not validate real-poll calibration or authorize publication. Existing
release gates, FI unavailability, the original evidence, the shipped freeze, the
old 2022 audit and the missed 2026 cutoff remain unchanged. No revised real-data
experiment is authorized. The restriction on new real-data fits permits only
the unchanged software regression checks described below.

Implementation and execution are subsequent work. This decision settles the
protocol; it does not itself execute a fit or authorize a model change.

## Exact generating model

Use components in this order: S, M, SD, V, C, KD, L, MP, OTHER. There are eight ilr
coordinates. Use the existing Helmert basis and inverse transformation.

- Initial state: independent coordinates with mean zero and variance 4.
- Each later day: add independent mean-zero Gaussian innovations with variance
  `q = 0.0001` per coordinate. There is no innovation on the first day.
- Five institute effects: independent mean-zero Gaussian vectors with covariance
  `s² I`, where `s = 0.05`. An institute retains its effect throughout the cycle.
- Observation noise: independent mean-zero Gaussian vectors with covariance
  `m R`, where `m = 1.5`.
- All initial-state, innovation, institute-effect and observation-noise draws are
  mutually independent. An institute's effect is shared across its polls.

Construct the fixed full covariance `R = H diag(1/p) H' / 1000`, using reference
percentages S=30, M=25, SD=15, V=8, C=6, KD=4, L=4, MP=6, OTHER=2. Divide by 100 to
obtain `p`. This reference determines covariance only. It is not the initial-state
mean, and `R` never depends on generated shares or latent states. Preserve the
matrix in the registration.

Generate two independent collections of 10,000 datasets:

1. `midpoint`: an observation's mean is the latent state on its stored midpoint
   plus the institute effect.
2. `ilr_window`: its mean is the arithmetic average of latent ilr states across
   its inclusive fieldwork window, plus the institute effect.

Add observation noise in ilr coordinates, then inverse-transform to a composition.
Do not average shares in the window generator. Do not round or apply source zero
replacement. Fit each collection with its matching convention only.

The uncentered priors match the fitted model. Do not impose a zero-sum constraint
on generated institute effects. Centering is an output transformation and does not
change the observation distribution.

## Fixed schedule

Use a synthetic coverage period beginning `2016-01-01`, day offset zero. Training
days are offsets 0 through 179, with cutoff `2016-06-28`. The 35-day scoring horizon
is offsets 180 through 214, ending `2016-08-02`. This interval has no cycle reset.
The filter and generator both start on day zero, before the first poll.

Name institutes `I0` through `I4`. For week index `k = 0..27`, each institute `i`
has one poll ending at offset `20 + 7k`. Assign fieldwork length from `[1, 10, 21]`
at index `(i + k) mod 3`. A length-L window begins at `end - L + 1`; no window is
truncated. The midpoint uses the existing floor-of-half-elapsed-days convention.
Publication date equals fieldwork end, and nominal sample size is 1,000.

Order polls by week then institute, assigning row identity `5k + i + 1`.
This gives 115 training polls and 25 scoring polls per dataset. The final five
weekly endpoints are offsets 181, 188, 195, 202 and 209. Held-out fieldwork may
overlap training dates; membership follows publication date and fieldwork end,
never whether the midpoint falls after cutoff. The known- and estimated-parameter
stages use these same generated observations, row identities and covariance.

No cycle changes, new institutes, method-era changes, missing values, rounding,
survey-design errors or abrupt non-Gaussian movements are introduced.

## Parameter stages and ordering

First run all 10,000 known-parameter repetitions for both conventions, supplying
the true `q`, `s` and `m`. Only after both controls demonstrate recovery may either
estimated-parameter stage start. A failed, inconclusive or incomplete control
leaves both estimated stages explicitly not run.

For each estimated-stage dataset, maximize training-only marginal likelihood over
the complete 180-point grid:

| Parameter | Values |
| --- | --- |
| Walk variance | 0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001 |
| House scale | 0.01, 0.02, 0.05, 0.1, 0.2 |
| Covariance multiplier | 0.5, 0.75, 1, 1.5, 2, 3 |

Use ascending walk, house, multiplier order and the existing first-maximum tie
break. Retain every attempted point's likelihood and numerical status. Include
all numerically valid selected fits, including endpoints, and report endpoint
frequencies. This synthetic endpoint policy does not waive a real-data release
gate. Never expand the grid or select parameters from scoring outcomes.

A numerical failure at a required fit or grid point prevents recovery from being
declared. Preserve the failed repetition and its identity; never remove it from
the planned denominator or replace it with another seed. Stop the execution and
report incomplete numerical evidence, including the earliest failing operation.

## Randomness and predictive intervals

Use master seed `20260916` and Java's `Random` generator, as selected by the existing
predictive scorer. Derive stream seeds using the existing SHA-256 convention:
UTF-8 encode `stream + "|" + masterSeed`, then interpret the first eight digest
bytes as a big-endian signed long. All decimal indices use plain base-ten digits
without padding.

Formal dataset indices are 0 through 9999. Stream names begin
`synthetic-recovery-v1|formal|CONVENTION|INDEX`. Append `|initial`, `|walk`,
`|houses` or `|noise` for independent generating streams. Draw initial coordinates
in component-basis order, daily innovations in day/coordinate order, institute
effects in institute/coordinate order, and observation noise in row/coordinate
order. Register the lower Cholesky factor of `m R` and multiply it by the noise
stream's standard normal vector for each poll. This includes the generating
multiplier exactly once.

Predictive stream names append `|predictive|ROW` to the dataset prefix. Reuse that
stream between known- and estimated-parameter stages, giving the stages the same
underlying normal draws without making their predictive distributions identical.
Use 4,000 joint predictive draws per scored poll, transformed one draw at a time.
Retain arithmetic share means, sample variances and linearly interpolated empirical
quantiles at 0.025, 0.975, 0.25 and 0.75. Count endpoint equality as covered.

The primary estimand is the coverage of this finite-draw interval procedure,
averaged across the specified prior-generated datasets and fixed scoring schedule.
It is not conditional calibration at every latent composition or every institute.

## Coverage assessment

For each dataset, method, parameter stage, component and interval level, calculate
the fraction of its 25 scoring polls covered. Each complete dataset contributes
one number in [0,1]. Average those numbers equally across the fixed `N = 10000`
datasets. Let `s²` be their sample variance with denominator `N - 1`; Monte Carlo
standard error is `sqrt(s²/N)`. Never treat the 25 correlated polls as independent
repetitions.

There are 72 primary results: 9 components × 2 interval levels × 2 conventions ×
2 parameter stages. Use `alpha = 0.05` and the normal critical value
`Phi^-1(1 - alpha / (2 × 72)) = 3.391763140587952`. Each confidence interval is the
estimated coverage plus or minus this critical value times its Monte Carlo
standard error, intersected with [0,1]. Keep the divisor 72 even if the second
stage is not run. This is approximate large-sample simultaneous 95% confidence,
not an exact finite-sample guarantee.

The fixed sample count bounds the untruncated interval half-width at about
1.696 percentage points even for maximally variable dataset fractions. Report
the actual standard errors and interval widths. Do not increase N after inspecting
them. An apparent zero-variance cell that passes the recovery range requires
verification of the coverage counter and retained draws before any recovery claim.

| Predictive interval | Acceptable coverage |
| --- | --- |
| 95% | [0.93, 0.97] |
| 50% | [0.47, 0.53] |

For a fully evaluated cell:

- **Recovery demonstrated**: its entire confidence interval is inside the
  acceptable range, including endpoints.
- **Recovery failed**: its confidence interval is wholly below or wholly above
  the acceptable range.
- **Inconclusive**: neither condition holds.

A method/stage demonstrates recovery only if all 18 cells demonstrate recovery.
Any failed cell means that method/stage fails; otherwise any inconclusive cell
makes it inconclusive. Missing or numerically failed required results make the
stage incomplete, with any established statistical failures retained alongside.

The complete experiment demonstrates recovery only if both methods pass both
stages. A complete failed or inconclusive first stage is a valid stopping result;
the dependent second stage remains not run. Interrupted or missing evidence is
incomplete, never a valid completed statistical result. Neither a successful
experiment nor an endpoint selection changes publication permission.

## Timing-only preflight and resource limit

Freeze the implementation, schedule, covariance, seeds, host, single-worker
execution configuration and exact commands before preflight. On that host, use
one warm-up dataset followed by three timed datasets per convention. Their stream
prefix replaces `formal` with `preflight`; their indices are 0 through 3. They
never enter the formal collection or coverage summaries.

Measure generation, the known-parameter prediction path, all 180 training-grid
evaluations, estimated-parameter prediction, evidence writing and reproduction.
The preflight
may execute the estimated path before formal controls because it measures cost
only. Do not inspect coverage or use statistical outcomes from these datasets.
Retain timings, resource measurements and numerical errors; a numerical failure
stops preflight. Time the intended evidence format, including draw hashing, rather
than a cheaper surrogate.

Let T be the sum of the slowest measured known-stage and estimated-stage times
for each convention. Charge generation to the known stage; charge each stage's
evidence work and reproduction to that stage. Project formal duration as
`10000 × T × 1.25`, then add elapsed preflight time. If this exceeds 24 hours on
the registered host, stop and
return for a decision. Do not lower repetitions, shorten windows, omit grid points
or change precision. Verify space for at least twice the projected retained output
plus 1 GiB for logs and temporary files before formal generation.

The 24-hour wall-clock cap includes preflight, formal execution and reproduction,
starting at preflight launch; ordinary build and software checks occur beforehand.
A watchdog stops scheduling work at the cap and preserves completed records and
the interruption reason. No coverage-based interim stopping or extra repetitions
are allowed. Resuming an incomplete experiment needs a separately recorded owner
decision and must preserve the original partial output.

## Implementation and independent checks

Reuse `PollObservations.Observation`/`Batch`, `WindowFilter` and the existing grid
order. Supply generated ilr observations and fixed covariance directly; do not
round-trip them through `PollObservations.prepare`. Reuse predictive summaries
through the smallest internal extraction needed. Add no public API, alternative
estimator, dependency or generic experiment framework.

Use independently specified small Gaussian fixtures to compare conditional means
and covariance against dense conditioning for both conventions. Retain existing
transform, covariance and scoring tests. Verify that synthetic training excludes
scoring observations, that fixed covariance survives the entire path, and that
known/tuned stages share identical inputs. Exercise blocked controls, selected
endpoints, missing repetitions, numerical failures and output-collision behavior
at the operator boundary. Software tests are not recovery evidence.

The existing model-validation profile includes archived development-poll refits
in `DevelopmentTuningIT` and `DevelopmentDiagnosticsIT`. The owner approved
retaining those unchanged software regression checks
before preflight, including the model-validation profile. Do not set any
evidence-rebuilding `*.full` flags, rerun the once-only audit, or treat test fits
as new statistical evidence.

Record code commit, dependency/toolchain and container identities, host
architecture, commands and numerical-check tolerances in a machine-readable
registration. Protect original real-data evidence and the shipped freeze with
before/after hashes. Commit the registration before any preflight fit. After
preflight, retain its feasibility record and commit the final execution identity
before formal generation; scientific settings and N are unchanged.

## Evidence and completion

Write to a new registered directory that refuses to overwrite evidence. Retain
the registration, generated latent states and house effects, observation vectors
and transformed shares, fixed covariance, every seed/stream identity, all search
outcomes, selected points, predictive means/covariances, interval summaries,
coverage indicators, per-dataset fractions, confidence calculations, statuses,
commands, exit codes, timings and environment identities.

Hash each predictive draw array in the existing big-endian binary64, draw-major,
component-order encoding. Retain the hash, seed, dimensions and generating
prediction, rather than the array bytes. The full 40,000 method-stage repetitions
would otherwise generate approximately 288 GB of raw predictive draws.

Reproduction uses the pinned implementation/environment and retained predictions
to regenerate every completed predictive array and compare its hash without
replacement. It also recomputes intervals, coverage indicators and aggregate
results from retained inputs. Budget this work in preflight. Cross-architecture
reproduction is outside this synthetic protocol and confers no release exemption.

Completion requires a reviewable report, all planned or explicitly not-run stages
accounted for, retained failure evidence, successful reproduction of completed
outputs and unchanged protected real-data evidence. Report known-parameter and
estimated-parameter results separately. A failed recovery check supports further
investigation of implementation, finite-sample calibration or parameter fitting;
it does not identify a cause by itself. A pass supports only this scenario and
does not authorize a subsequent experiment automatically.

## Statistical references

The [Bonferroni inequality](https://itl.nist.gov/div898/handbook/prc/section4/prc473.htm)
motivates the fixed multiplicity adjustment. Marginal intervals here use a normal
approximation, so the combined confidence claim is also approximate.
[Morris, White and Crowther](https://doi.org/10.1002/sim.8086) discuss preplanning
simulation designs and reporting Monte Carlo uncertainty. The specific scenario,
coverage margins and resource limits are owner decisions recorded in #184.
