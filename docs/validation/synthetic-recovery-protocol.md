# Bounded synthetic recovery protocol

Status: accepted by the owner on 2026-09-16, amended on 2026-09-19. Version:
`synthetic-recovery-v2`. Decision tickets: [#184](https://github.com/ahemberg/swedishpolls/issues/184)
for the protocol, [#216](https://github.com/ahemberg/swedishpolls/issues/216) for this
amendment.

## What v2 changes

v2 differs from v1 in operational clauses only. No scientific setting moved. The
repetition count, the predictive draw count, the 180-point grid, the recovery bands,
the schedule, the generating model, the fixed covariance, the master seed, the stream
rules, the roster and the two conventions are identical to the text accepted in #184.
Four clauses move, all about how the run is carried on a host:

- Four workers, one per physical core, parallel across datasets within a stage only.
- Sustained-throughput preflight measured at the registered worker count after thermal
  steady state, replacing the cold single-worker burst.
- Stage-by-stage budgeting against the budget remaining when each stage starts,
  replacing the all-or-nothing total.
- An evidence destination on the external volume, which retires the storage constraint
  without relaxing the 2x safety margin.

This amends the protocol accepted in #184; it does not supersede it. The
`synthetic-recovery-v1` evidence in `docs/validation/synthetic-recovery-v1/` stands
exactly as retained, including the `stopped_at_preflight_infeasible` result and every
digest in it. That evidence pins this document by the sha256
`897063ff08c05f15ac2b598125424ec18163396bf66f3c6d08399e2cd142b065`, which is the v1
text as committed in `5fe83e57d39449a284728aa73ba7fa57b86868df` and is retrievable
there.

Registration validation digests the working-tree file at this path, so the v1
registration no longer passes its protocol-identity check. That is the pin working, not
breaking: the v1 registration names a document that has since moved, and a frozen
registration whose document changed underneath it is exactly what a digest is for.
Re-validating v1 is not something a closed run needs. Its retained evidence carries its
own digests and stands on them.

A document pinned by digest that changes needs a new identity, which is why the version
string moved rather than the text being quietly edited in place. The experiment was not
redesigned after it failed to fit the host. It was rescheduled.

## Purpose and authority

Determine whether predictive poll intervals recover their nominal coverage when
synthetic observations follow each method's assumed Gaussian model. The owner
accepted the scope, generating assumptions, two parameter stages, scenario,
coverage ranges, simultaneous-confidence approach, repetition count and retention
policy during the #184 interview. On 2026-09-16, the owner confirmed the complete
protocol, including the exact scheduling, seed, preflight and completion rules
and the unchanged-regression-test exception below. On 2026-09-19, after the
registered host stopped the v1 run at preflight, the owner recorded the operational
amendment above.

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

The literal `synthetic-recovery-v1` token stays in the stream name under v2. A stream
name is a generating input: changing it changes every draw in the experiment, which
is a scientific change this amendment does not make. The token names the stream rule,
not the revision of this document.

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

A stage that a stage budget deferred, or that the watchdog stopped short, is
incomplete. Record its completed repetition count and report no coverage at all for
it. `N` stays frozen at 10,000, and a short stage never reports coverage over the
subset it happened to complete: a subset chosen by when the clock ran out is not
the planned design, and averaging it would answer a question nobody registered.

The complete experiment demonstrates recovery only if both methods pass both
stages. A complete failed or inconclusive first stage is a valid stopping result;
the dependent second stage remains not run. Interrupted or missing evidence is
incomplete, never a valid completed statistical result. Neither a successful
experiment nor an endpoint selection changes publication permission.

## Registered host and workers

The registered host is `alex-lenovo`: x86_64, Intel Core i5-10300H, 4 physical cores,
15.5 GiB RAM. The run uses four workers, one per physical core. The v1 capacity record
reads 8 cores, which counts logical processors; the worker count follows physical cores.

Workers parallelise datasets within one stage. Conventions and stages stay sequential.
That ordering is what makes the controls-before-estimation gate meaningful, and a gate
running concurrently with the thing it gates is not a gate. Every dataset derives its
streams from `SHA-256(stream-name|master-seed)` and no dataset reads another's state,
so which worker takes which dataset, and in what order they finish, cannot reach any
result. Retain evidence per dataset as it completes, and reduce a stage only once it
has completed.

`pinas` was considered and rejected as the host. A Pi 5 is roughly 0.4x the throughput
of `alex-lenovo` on this workload, and it serves the live application and the 03:00
nightly refresh.

## Evidence destination and storage

Write formal evidence to the external ext4-over-LUKS volume mounted at
`/media/alex/wd 4tb backup`, which has 2.9 TB free against the 23.81 GiB the v1
preflight required. Verify space for at least twice the projected retained output plus
1 GiB for logs and temporary files before formal generation. The destination retires
the constraint that stopped v1; it does not relax the rule.

The destination refuses to overwrite evidence, as in v1.

## Sustained-throughput preflight

Freeze the implementation, schedule, covariance, seeds, host, the four-worker
execution configuration and the exact commands before preflight.

Preflight measures sustained throughput at the registered worker count. Warm up until
the host reaches thermal steady state, then time a measured window with all four
workers busy, and project from that window. The v1 preflight timed one dataset at a
time on a cold machine, which samples the best conditions an H-series laptop part will
ever see; sustained all-core clocks on that part are materially lower, so a projection
from the cold burst understates the run. Record the steady-state criterion, the
measurements establishing it and the worker count alongside the timings.

Preflight datasets use a stream prefix whose phase is `preflight` and indices from 0
upward, as many as steady state and the measured window require. They never enter the
formal collection or any coverage summary. Measure generation, the known-parameter
prediction path, all 180 training-grid evaluations, estimated-parameter prediction,
evidence writing including draw hashing, and reproduction, in the intended evidence
format rather than a cheaper surrogate. The preflight may execute the estimated path
before the formal controls because it measures cost only. Do not inspect coverage or
use any statistical outcome from these datasets. Retain timings, resource measurements
and numerical errors; a numerical failure stops preflight.

For each convention and stage, let `t` be the slowest sustained wall-clock seconds per
dataset over the measured window: split the window into consecutive sub-windows of at
least 20 completed datasets and take the largest elapsed-time-per-dataset among them.
v1 charged the slowest measured dataset, and v2 charges the slowest sustained rate. The
window mean is not the charge: averaging the fast start into the slow remainder is a
weaker margin than v1 had, and the amendment does not weaken a margin. Charge generation
to the known stage; charge each stage's evidence work and reproduction to that stage.
Project that stage as `10000 × t × 1.25`. The 25% margin is unchanged. Do not lower repetitions, shorten windows, omit grid points or change
precision to make a projection fit.

## The cap and stage budgeting

The 24-hour wall-clock cap is unchanged. It starts at preflight launch and covers
preflight, formal execution and reproduction; ordinary build and software checks occur
beforehand.

v1 charged the whole projection against the whole cap before anything ran, so a total
over the cap stopped the experiment with nothing measured. v2 budgets per stage. Each
stage must project within the budget remaining when that stage starts. The four stages
start in the fixed order: `midpoint` known, `ilr_window` known, `midpoint` estimated,
`ilr_window` estimated.

Re-project each estimated stage from the controls' measured throughput over 10,000 real
repetitions, rather than reusing the preflight estimate. Ten thousand completed
repetitions at the registered worker count measure the host far better than a preflight
window can, and by the time an estimated stage is due that measurement exists.

Timing may inform what runs. Coverage may never. The prohibition on interim stopping is
about outcomes, not about measuring how fast the machine is going: no coverage figure,
per-dataset fraction, cell verdict or endpoint frequency may reach the decision to
start, continue or stop a stage. Repetitions are never adapted, `N` is never lowered,
and no extra repetitions are added.

### `stage_budget_deferred`

A stage that does not project within its remaining budget is not started, and the run
stops with the reason `stage_budget_deferred`. It is a stopping reason in its own right,
distinct from an interruption, a numerical failure and an infeasible preflight.

Preflight infeasibility keeps its own reason, `stopped_at_preflight_infeasible`, and now
means one of two things: the storage rule is not satisfied, or the first stage does not
project within its own budget, which is the cap less the elapsed preflight. A stage the preflight does clear, and a later stage the
elapsed run no longer affords, are different outcomes and are recorded as such.

The overall verdict stays `incomplete` when it fires. Overall recovery requires all four
stages, and a deferral at the estimated boundary leaves 36 of the 72 cells unmeasured.
Account every deferred stage as not run, with the deferral reason, the budget remaining
and the projection that exceeded it.

The report states both control verdicts at the top, as complete results in their own
right. A control that ran all 10,000 repetitions and reported all 18 of its cells is a
complete result whether or not the stages that depend on it ever start.

### The watchdog under parallelism

The watchdog checks the deadline while work is in flight. At the cap it stops scheduling
new repetitions and lets the in-flight ones finish. One repetition of overrun against a
24-hour cap is cheaper than the risk of a half-written evidence file, and a partially
written dataset is not evidence. Record how many repetitions completed and how far past
the cap the last one finished.

A stage the watchdog stops short is incomplete under the coverage-assessment rule above.
Resuming an incomplete experiment needs a separately recorded owner decision and must
preserve the original partial output.

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
endpoints, missing repetitions, numerical failures, stage deferral, the watchdog's
in-flight drain and output-collision behavior at the operator boundary. Software tests
are not recovery evidence.

Verify, as a software check on a handful of datasets rather than a formal run, that
retained evidence does not depend on worker count or completion order: the same datasets
at one worker and at four produce identical evidence apart from timings and the order
records were written in. Parallel execution is only safe to register if this holds.

The existing model-validation profile includes archived development-poll refits
in `DevelopmentTuningIT` and `DevelopmentDiagnosticsIT`. The owner approved
retaining those unchanged software regression checks
before preflight, including the model-validation profile. Do not set any
evidence-rebuilding `*.full` flags, rerun the once-only audit, or treat test fits
as new statistical evidence.

Record code commit, dependency/toolchain and container identities, host
architecture, worker count, commands and numerical-check tolerances in a
machine-readable registration. Protect original real-data evidence and the shipped
freeze with before/after hashes. Commit the registration before any preflight fit.
After preflight, retain its feasibility record and commit the final execution identity
before formal generation; scientific settings and N are unchanged.

## Evidence and completion

Retain on the evidence volume: the registration, generated latent states and house
effects, observation vectors and transformed shares, fixed covariance, every
seed/stream identity, all search outcomes, selected points, predictive
means/covariances, interval summaries, coverage indicators, per-dataset fractions,
confidence calculations, statuses, commands, exit codes, timings and environment
identities.

Hash each predictive draw array in the existing big-endian binary64, draw-major,
component-order encoding. Retain the hash, seed, dimensions and generating
prediction, rather than the array bytes. The full 40,000 method-stage repetitions
would otherwise generate approximately 288 GB of raw predictive draws. The 2.9 TB
destination makes that affordable, so the storage reason recorded in #184 no longer
applies. The choice stands on the stronger reason: regeneration proves the computation
reproduces, and archival only proves that bytes were stored.

### The committed record

Commit about 58 KB under `docs/validation/synthetic-recovery-v2/`:

| Path | Contents |
| --- | --- |
| `report.md` | The reviewable report, both control verdicts first |
| `registration.json` | The frozen registration, with its checksum sidecar |
| `results.json` | All 72 cells, each with its status, coverage, standard error and interval |
| rollup digests | One per stage and convention |

Each rollup digest is taken over a full per-file manifest that lives on the drive beside
the evidence, so the committed digest reaches every retained file without the repository
carrying any of them.

No `plan.json`: the plan is frozen into the registration, and a second copy is a second
thing to keep in step. No preflight datasets; v1 committed 4.7 MiB of them and they are
timing artifacts.

### Reproduction and completion

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
coverage margins and resource limits are owner decisions recorded in #184, with the
operational clauses amended in #216.
