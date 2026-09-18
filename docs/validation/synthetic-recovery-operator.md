# Synthetic recovery: operator workflow

The workflow that executes the accepted
[`synthetic-recovery-v1` protocol](synthetic-recovery-protocol.md). It is built in
slices; this page describes what exists today. Running it is a software check. It is
not recovery evidence, and it runs no formal experiment.

## Scoring one supplied dataset

```
./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='score <dataset.json> <evidence.json>'
```

Exit codes are `0` for a scored dataset and `2` for a refused one. The command refuses
an evidence destination that already exists, and writes the refusal reason instead
when the destination is free.

The dataset document supplies the observations and the fixed covariance directly;
nothing is prepared from a source snapshot, and nothing reconstructs the covariance
from the generated shares.

| Field | Meaning |
| --- | --- |
| `version` | `synthetic-recovery-v1` |
| `phase` | `formal`, `preflight` or `software_check` |
| `convention` | `midpoint` or `ilr_window` |
| `masterSeed`, `datasetIndex`, `draws` | Stream identity and predictive draws per poll |
| `periodStart`, `cutoff`, `scoreThrough` | The synthetic calendar |
| `parameters` | `walkVariance`, `houseScale`, `covarianceMultiplier` |
| `observationCovariance` | The registered fixed 8×8 matrix, in ilr coordinates |
| `observations[]` | `rowNumber`, `institute`, `membership`, `fieldworkFrom`, `fieldworkTo`, `sampleSize`, `ilr` |

The `formal` phase accepts only the registered master seed `20260916`, 4,000 draws and
dataset indices 0 through 9999. Components are fixed at S, M, SD, V, C, KD, L, MP and
OTHER, so an observation carries eight ilr coordinates. Membership follows the
publication date, which equals the fieldwork end, never the midpoint: a scoring window
may overlap training dates, but a row published by the cutoff is training.

The retained evidence carries the registered covariance, and, for every scored poll,
its predictive mean and covariance, stream name, derived seed, dimensions, per-component
interval summaries with their coverage indicators, and the SHA-256 of the predictive
draw array in the registered big-endian binary64, draw-major encoding. The arrays
themselves are not retained.

`coverageFractions` holds this dataset's own contribution to each cell: one fraction per
component and interval level over its scoring polls, which are correlated and are never
averaged as independent repetitions.

`inputsSha256` covers the fixed covariance and every row's identity, membership,
fieldwork dates and ilr coordinates. The known and estimated stages of one dataset
observe the same rows, so their evidence carries the same hash.

## Tuning one supplied dataset

```
./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='tune <dataset.json> <evidence.json>'
```

The same command with the parameter point left out. It searches the registered grid on
the training observations, scores the dataset at the point it selects and writes the
search beside the scoring evidence. Exit codes are `0` for a tuned dataset, `2` for a
refused one and `3` for a search a numerical failure stopped. The document takes the
fields above without `parameters`: supplying a point beside a search is refused, because
the evidence would not say which point produced it.

The `search` block holds the frozen grid, all 180 attempts in order with their
likelihood and status, the selected index and point, its training log-likelihood and
the grid endpoints it sits on. A point whose fit fails or returns a nonfinite
likelihood stops the search there. That point is retained, nothing after it is
attempted, the grid is never expanded and no scoring evidence follows.

A stopped run writes a `numerical_failure` document instead: the convention, dataset
index, the earliest failing operation (`search`, `score` or `retain`) and the whole
`search` block as it stood. There `selectedIndex` is `-1` and `selected`,
`logLikelihood` and the failing attempt's likelihood are `null`, so the retained
evidence stays parseable rather than carrying a bare `NaN`.

### The frozen grid

| Axis | Values |
| --- | --- |
| `walkVariance` | 0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001 |
| `houseScale` | 0.01, 0.02, 0.05, 0.1, 0.2 |
| `covarianceMultiplier` | 0.5, 0.75, 1, 1.5, 2, 3 |

The 180 points are evaluated in ascending walk variance, then house scale, then
multiplier. The objective is the training marginal likelihood under the dataset's own
convention; the scoring observations are not supplied to it, so they cannot move a
selection. An exact maximum tie keeps the first point of that order. A multiplier below
one scales the fixed covariance down; it is not an ordinary overdispersion factor.

## Running the controls and the estimated stages

```
./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=se.swedishpolls.estimation.SyntheticRecovery \
  -Dspring-boot.run.arguments='control <plan.json> <evidence-directory>'
```

Exit codes are `0` for a completed stage, `2` for a refused plan and `3` for a run a
numerical failure stopped. A completed stage may report failed or inconclusive recovery;
that is a result, not an error.

The plan carries `version`, `phase`, `masterSeed`, `datasets` and `draws`. The `formal`
phase accepts only master seed `20260916`, 4,000 draws and 10,000 datasets per
convention: the command refuses a plan that would shrink the registered run. The
destination has to be free, and nothing is written into a directory that exists.

The command generates the registered scenario itself. Both conventions run in the order
`midpoint`, `ilr_window`, each at the generating truth `q = 0.0001`, `s = 0.05` and
`m = 1.5`. Every dataset is generated, scored and retained before any of it is reduced.

Only when both controls demonstrate recovery does the command repeat each convention
with the parameters tuned. The estimated stage regenerates each dataset from the same
streams, so it observes the identical rows, covariance and row identities, and each
scored poll draws from the same predictive stream and seed as its known-parameter
counterpart. What differs is the parameter point, and therefore the predictive
distribution.

| Path | Contents |
| --- | --- |
| `control.json` | The run report: calendar, fixed covariance and noise factor, confidence rule, recovery bands, the 18 cells of each method and stage, and the state of every stage |
| `datasets/<convention>/<index>.json` | One known-parameter dataset: the scoring evidence above, plus the `generation` block holding the priors, stream names, institute effects, daily latent states and all 140 observations with their ilr coordinates and shares |
| `estimated/<convention>/<index>.json` | The same dataset tuned: the scoring evidence at the selected point, its `search` block and the same `generation` block |

### The generated scenario

The coverage period starts `2016-01-01` at day zero, trains through offset 179 and
scores through offset 214. For each of weeks 0 to 27 every institute `I0` to `I4`
publishes one poll ending at offset `20 + 7k`, over an untruncated window of
`[1, 10, 21][(i + k) mod 3]` days, with row identity `5k + i + 1`. That is 115 training
and 25 scoring polls, the last five weekly endpoints being offsets 181, 188, 195, 202
and 209. Membership follows the publication date, so a held-out window may reach back
into training dates.

Each dataset draws its initial state, daily innovations, institute effects and
observation noise from four streams named after its dataset prefix. Noise is the lower
Cholesky factor of `1.5 R` against a standard normal vector, added in ilr coordinates and
inverse-transformed without rounding or zero replacement. `R` is built once from the
registered reference percentages and never from a generated share or latent state.

### The verdict of a cell

Each dataset contributes one coverage fraction per component and interval level, over its
25 scoring polls. The fractions are weighted equally; their sample variance uses the
denominator `N - 1`, and the interval is the mean plus or minus `3.391763140587952`
standard errors, intersected with `[0,1]`. The divisor stays at 72 primary cells whether
or not the estimated stages run, and the claim is approximate large-sample simultaneous
95% confidence.

A cell demonstrates recovery only when its whole interval lies inside `[0.93, 0.97]` or
`[0.47, 0.53]`, endpoints included; it fails when the interval lies wholly outside, and
is inconclusive otherwise. All 18 cells of a method must demonstrate recovery for the
stage to. A zero-variance cell that would otherwise demonstrate recovery is checked
against its coverage counter and the retained draw hashes of the polls behind it, and
stays incomplete until both check out; the reproduction slice re-derives those draws.

Both controls have to demonstrate recovery before either estimated stage becomes
eligible. Until then the report carries both estimated stages as `not_run` with the
verdict that blocked them, `estimatedStageVerdict` as `not_run`, and no `estimated/`
directory is written at all.

Each estimated method also reports `endpointSelections` and `endpointFrequencies`, the
number of datasets whose selected point sat on a grid end and how often each axis end
was selected. Every numerically valid endpoint selection stays in the assessment. That
is a rule of this synthetic scenario and waives no real-data endpoint gate.

`recovery` combines all four method/stage verdicts under the same precedence a single
stage uses: incomplete, then failed, then inconclusive, then demonstrated. A numerical
failure in either stage stops the run at that repetition, preserves its earliest failing
operation with the attempts the search had made, and leaves the experiment incomplete.

A completed failed or inconclusive verdict is a valid stopping result and `experiment`
says which stage it stopped after. Demonstrated recovery is not completion: `experiment`
stays `incomplete` until the registration, preflight and reproduction slices exist.

## The registered lifecycle

A run that is evidence rather than a software check goes through five more commands, in
order. Each one re-reads the registration and refuses an identity that has moved.

```
register  <plan.json> <registration.json>
preflight <registration.json> <preflight-directory>
commit    <registration.json> <preflight.json> <execution.json>
experiment <execution.json> <evidence-directory> [resumption.json]
reproduce <execution.json> <evidence-directory> <reproduction.json>
report    <execution.json> <evidence-directory> <reproduction.json> <report.json>
report    <preflight.json> <report.json>
```

Exit codes are `0` for success, `1` for a completed check whose outcome did not confirm
what it checked, `2` for a refused operation, `3` for a numerical failure, `4` for a run
the watchdog stopped and `5` for a preflight that does not fit the host.

### The registration

`register` validates a plan and freezes it, writing `<registration.json>` and a
`.sha256` sidecar beside it. The plan pins the protocol document, the implementation
commit, every dependency, the toolchain, the container image, the host and its single
worker, the exact commands, the numerical tolerances, the schedule, the fixed covariance
and its noise factor, the stream rules, the run size, the resource limits, both evidence
destinations and the protected locations with their digests.

Validation is the same at every later boundary, so a registration that froze is one the
rest of the workflow accepts. A formal registration has to sit at
`docs/validation/synthetic-recovery-v1/registration.json` before preflight, and its
execution identity at `docs/validation/synthetic-recovery-v1/execution.json` before any
formal dataset is generated.

### Preflight

`preflight` runs one warm-up and three timed datasets per convention, indices 0 to 3, in
streams whose phase is `preflight`. It measures generation, the known-parameter
prediction path, all 180 training-grid evaluations, estimated-parameter prediction, the
evidence writing and hashing and full reproduction, in the intended evidence format. The
estimated path runs here for its cost alone: no coverage from these datasets is inspected
or enters a formal result. A numerical failure stops preflight with the failing operation
preserved.

`T` is the sum of each convention's slowest measured known and estimated stage time.
Generation is charged to the known stage; each stage carries its own evidence work and
reproduction. The projection is `datasets x T x 1.25` plus the elapsed preflight, and it
has to fit 24 hours. Free disk has to be at least twice the projected retained output
plus 1 GiB. An infeasible projection is retained with its reasons and stops the run: no
repetition is lowered, no window shortened, no grid point omitted and no precision
changed. `report <preflight.json> <report.json>` publishes that stop with every stage
accounted as not run.

### The execution identity and the cap

`commit` writes the final execution identity from a feasible preflight record, with its
own checksum sidecar. It carries the registration and preflight digests, the
implementation commit, the host, the single worker, the architecture and the 24-hour
clock. That clock starts at preflight launch and covers formal work and reproduction, so
each command inherits the same deadline rather than restarting it.

`experiment` takes the single-worker lock in its destination, verifies the protected
real-data evidence and the shipped freeze, and runs both stages. The watchdog checks the
deadline between repetitions: at the cap it stops scheduling, writes `interruption.json`
with the stage and repetition it stopped at, and leaves every completed record where it
is. Repetitions are never adapted and no interim coverage stops the run.

An interrupted run is never continued on its own. A resumption needs a decision recorded
outside this workflow, naming the execution identity, the interrupted run and the digest
of its partial output, and it writes a destination of its own so that output stays as the
watchdog left it.

### Reproduction

`reproduce` regenerates every completed predictive array from its retained prediction,
stream and seed, and compares the hash without replacing it. It recomputes each
component's interval summaries, both coverage indicators, the per-dataset fractions and
the cells of every stage, against the numerical tolerances the registration fixed. A
missing or changed hash, a changed summary, a missing dataset or a changed published cell
is reported as a finding; nothing is written into the run being read. A stage the
protocol left explicitly not run has no predictive output and is reported as such.
Cross-architecture reproduction is outside this protocol.

### The report

`report` accounts for every planned or explicitly not-run stage and distinguishes a
completed failed or inconclusive control from preflight infeasibility, from incomplete
numerical evidence and from an interrupted run. It carries the evidence location, the run
identities, the reproduction outcome and the before and after hashes of the protected
real-data evidence and the shipped freeze. Completion needs every planned stage complete,
demonstrated recovery, reproduced predictive output and unchanged protected evidence; it
is still not permission to publish.

| Path | Contents |
| --- | --- |
| `<preflight>/preflight.json` | Timings of all eight preflight datasets, the projection, the disk requirement and the feasibility verdict |
| `<preflight>/preflight/<stage>/<convention>/<index>.json` | The preflight datasets, which no summary reads |
| `<evidence>/worker.lock` | The host, process and claim time of the one permitted worker |
| `<evidence>/report.json` | The run report, its identities and the protected-evidence hashes |
| `<evidence>/interruption.json` | The stage and repetition the watchdog stopped at |

## The registered run

The protocol has been executed once, on `alex-lenovo` at implementation commit
`7794b6c`. Preflight measured the run at 41.8 hours against the registered 24-hour cap
and 23.8 GiB required against 11.0 GiB free, so it stopped there and no formal dataset
was generated. The registration, the repository checks, the whole preflight evidence
tree and the report are in
[`synthetic-recovery-v1/`](synthetic-recovery-v1/report.md). The protocol has no
statistical result until the owner records one of the decisions that report names.
