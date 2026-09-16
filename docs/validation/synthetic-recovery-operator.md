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

## Not built yet

Dataset generation, the training-grid search, coverage aggregation, confidence
calculation, registration verification, preflight, the execution cap and reporting are
later slices of this same command.
