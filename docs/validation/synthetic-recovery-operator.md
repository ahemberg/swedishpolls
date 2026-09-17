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

## Running both known-parameter controls

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

| Path | Contents |
| --- | --- |
| `control.json` | The stage report: calendar, fixed covariance and noise factor, confidence rule, recovery bands, the 18 cells of each method and the state of every stage |
| `datasets/<convention>/<index>.json` | One dataset: the scoring evidence above, plus the `generation` block holding the priors, stream names, institute effects, daily latent states and all 140 observations with their ilr coordinates and shares |

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
verdict that blocked them.

## Not built yet

The training-grid search and the estimated-parameter stages, registration verification,
preflight, the execution cap, reproduction and the published report are later slices of
this same command.
